/*
 * Copyright 2025 Ukrcom
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations
 * under the License.
 */
package net.ukrhub.routing.instances.report;

import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Application entry point.
 *
 * <p>Reads configuration from environment variables, runs all collectors
 * sequentially, builds the lo0 address map, and writes the HTML report.</p>
 *
 * <h2>Collection order</h2>
 * <ol>
 *   <li>{@link JuniperCollector} — fetches XML via NETCONF and writes
 *       {@code /tmp/juniper-HOST.xml}; must run before the other three
 *       Juniper collectors so they can reuse the dump.</li>
 *   <li>{@link JuniperSwitchCollector} — reads the cached dump.</li>
 *   <li>{@link JuniperL2circuitCollector} — reads the cached dump.</li>
 *   <li>{@link JuniperBridgedomainsCollector} — reads the cached dump.</li>
 *   <li>{@link CiscoCollector} — Telnet to each Cisco host.</li>
 *   <li>{@link RouterOSCollector} — SSH to each MikroTik host.</li>
 * </ol>
 *
 * <p>After all collectors finish, {@link LoAddressMapper#build} scans the
 * written dump files to build the IP→name map, which is then passed to
 * {@link ReportGenerator#generate}.</p>
 *
 * <h2>Environment variables</h2>
 * <table border="1">
 *   <caption>Environment variables</caption>
 *   <tr><th>Variable</th><th>Required</th><th>Default</th></tr>
 *   <tr><td>{@code ROUTER_USER}</td><td>yes</td><td>&#8212;</td></tr>
 *   <tr><td>{@code ROUTER_PASS}</td><td>yes</td><td>&#8212;</td></tr>
 *   <tr><td>{@code CISCO_ENABLE}</td><td>if CISCO_HOSTS set</td><td>&#8212;</td></tr>
 *   <tr><td>{@code JUNIPER_HOSTS}</td><td>no</td><td>(empty)</td></tr>
 *   <tr><td>{@code CISCO_HOSTS}</td><td>no</td><td>(empty)</td></tr>
 *   <tr><td>{@code ROUTEROS_HOSTS}</td><td>no</td><td>(empty)</td></tr>
 *   <tr><td>{@code REPORT_PATH}</td><td>no</td><td>{@code /usr/share/nginx/html/index.html}</td></tr>
 *   <tr><td>{@code LOG_LEVEL}</td><td>no</td><td>{@code info}</td></tr>
 *   <tr><td>{@code OPENCHANNEL}</td><td>no</td><td>{@code subsystem-netconf}</td></tr>
 * </table>
 */
@Log4j2
public class RoutingInstancesReport {

    private RoutingInstancesReport() {
    }

    /**
     * Application entry point.
     *
     * @param args command-line arguments (ignored; all config via env vars)
     * @throws Exception on unrecoverable startup error (missing required env var)
     */
    public static void main(String[] args) throws Exception {
        String login = require("ROUTER_USER");
        String pass = require("ROUTER_PASS");
        String ciscoEnable = env("CISCO_ENABLE", "");
        String reportPath = env("REPORT_PATH", "/usr/share/nginx/html/index.html");

        List<String> juniperHosts = parseList(env("JUNIPER_HOSTS", ""));
        List<String> ciscoHosts = parseList(env("CISCO_HOSTS", ""));
        List<String> routerosHosts = parseList(env("ROUTEROS_HOSTS", ""));

        log.info("Starting collection — Juniper: {}, Cisco: {}, RouterOS: {}",
                juniperHosts, ciscoHosts, routerosHosts);

        Map<String, RoutingInstance> instances = new TreeMap<>();
        Map<String, Map<String, String>> vrfVplsList = new LinkedHashMap<>();

        Collector juniper = new JuniperCollector(login, pass);
        Collector juniperConnections = new JuniperSwitchCollector(login, pass);
        Collector juniperL2circuit = new JuniperL2circuitCollector(login, pass);
        Collector juniperBridgedomains = new JuniperBridgedomainsCollector(login, pass);
        Collector cisco = new CiscoCollector(login, pass, ciscoEnable);
        Collector routeros = new RouterOSCollector(login, pass);

        for (var e : List.of(
                Map.entry(juniper, juniperHosts),
                Map.entry(juniperConnections, juniperHosts),
                Map.entry(juniperL2circuit, juniperHosts),
                Map.entry(juniperBridgedomains, juniperHosts),
                Map.entry(cisco, ciscoHosts),
                Map.entry(routeros, routerosHosts))) {
            String label = e.getKey().getClass().getSimpleName().replace("Collector", "");
            for (String host : e.getValue()) {
                log.info("Collecting from {}: {}", label, host);
                try {
                    e.getKey().collect(host, instances, vrfVplsList);
                } catch (Exception ex) {
                    log.error("{} {} failed: {}", label, host, ex.getMessage(), ex);
                }
            }
        }

        log.info("Collection complete: {} instances total", instances.size());

        Map<String, String> loAddresses = LoAddressMapper.build(juniperHosts);
        log.info("Built lo0 address map: {} entries", loAddresses.size());

        List<String[]> orphans = findOrphans(instances, loAddresses);
        if (!orphans.isEmpty()) {
            log.info("L2CIRCUIT/VPLS orphan check: {} unpaired entries found", orphans.size());
            orphans.forEach(o -> log.info("  orphan: type={} vcId={} router={} neighbor={} ({})", o[0], o[1], o[2], o[3], o[4]));
        } else {
            log.info("L2CIRCUIT/VPLS orphan check: all pairs OK");
        }

        try {
            ReportGenerator.generate(instances, vrfVplsList, reportPath, loAddresses, orphans);
        } catch (IOException e) {
            log.error("Failed to write report to {}: {}", reportPath, e.getMessage());
        }
    }

    /**
     * Returns the value of the required environment variable {@code name}.
     *
     * @param name variable name
     * @return     non-blank value
     * @throws IllegalStateException if the variable is absent or blank
     */
    private static String require(String name) {
        String val = System.getenv(name);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Required environment variable not set: " + name);
        }
        return val;
    }

    /**
     * Returns the value of environment variable {@code name}, or
     * {@code defaultValue} if it is absent or blank.
     *
     * @param name         variable name
     * @param defaultValue fallback value
     * @return             env value or default
     */
    private static String env(String name, String defaultValue) {
        String val = System.getenv(name);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    /**
     * Finds L2CIRCUIT and VPLS instances that lack a valid reverse peer entry.
     *
     * <p>Processes all instances whose name matches {@code DIGITS/ROUTER[...]}
     * — that is, L2CIRCUIT entries and VPLS secondary entries (the latter are
     * only added by {@link JuniperCollector} when LDP neighbors are present).
     * For each such instance every neighbor IP is extracted from the host entry
     * (all tokens after the last {@code →}), resolved via {@code loAddresses},
     * then the reverse entry {@code vcId/NEIGHBOR_ROUTER} is looked up in the
     * same set. Two mismatch categories are reported:</p>
     * <ul>
     *   <li><b>сусід невідомий</b> — neighbor IP absent from the lo0 map;</li>
     *   <li><b>немає зворотного запису</b> — neighbor router known but no
     *       L2CIRCUIT or VPLS secondary entry with that router exists for the
     *       same VC-ID/VPLS-ID.</li>
     * </ul>
     *
     * @param instances    all collected routing instances
     * @param loAddresses  lo0 IP → router name map
     * @return             list of {@code [type, vcId, localRouter, neighborInfo, note]} arrays
     */
    private static List<String[]> findOrphans(
            Map<String, RoutingInstance> instances,
            Map<String, String> loAddresses) {

        Pattern namePattern = Pattern.compile("^(\\d+)/(.+)$");
        Pattern reSuffix = Pattern.compile("-re\\d+$", Pattern.CASE_INSENSITIVE);

        // vcId → set of clean router names that have any entry for this vcId
        Map<String, Set<String>> vcidRouterSet = new HashMap<>();
        // instance name → [type, ip1, ip2, …] (neighbor IPs from last " → " segment)
        Map<String, String[]> entryMap = new LinkedHashMap<>();

        instances.forEach((key, ri) -> {
            String t = ri.getType();
            if (!t.equals("L2CIRCUIT") && !t.startsWith("VPLS")) return;
            Matcher m = namePattern.matcher(ri.getName());
            if (!m.matches()) return;

            String vcId = m.group(1);
            String localRouter = stripVplsSuffix(m.group(2), reSuffix);
            vcidRouterSet.computeIfAbsent(vcId, k -> new HashSet<>()).add(localRouter);

            List<String> ips = new ArrayList<>();
            for (String host : ri.getHosts()) {
                int arrow = host.lastIndexOf(" → ");
                if (arrow >= 0) {
                    for (String ip : host.substring(arrow + 3).split(",\\s*")) {
                        if (!ip.isBlank()) ips.add(ip.trim());
                    }
                }
            }
            if (!ips.isEmpty()) {
                String[] entry = new String[ips.size() + 1];
                entry[0] = t;
                for (int i = 0; i < ips.size(); i++) entry[i + 1] = ips.get(i);
                entryMap.put(ri.getName(), entry);
            }
        });

        List<String[]> orphans = new ArrayList<>();

        entryMap.forEach((name, entry) -> {
            Matcher m = namePattern.matcher(name);
            if (!m.matches()) {
                return;
            }
            String vcId = m.group(1);
            String localRouter = stripVplsSuffix(m.group(2), reSuffix);
            String type = entry[0];

            for (int i = 1; i < entry.length; i++) {
                String ip = entry[i];
                String neighborRouter = loAddresses.get(ip);
                if (neighborRouter == null) {
                    orphans.add(new String[]{type, vcId, localRouter, ip, "сусід невідомий"});
                } else {
                    Set<String> existing = vcidRouterSet.get(vcId);
                    if (existing == null || !existing.contains(neighborRouter)) {
                        orphans.add(new String[]{type, vcId, localRouter, neighborRouter, "немає зворотного запису"});
                    }
                }
            }
        });

        return orphans;
    }

    /**
     * Strips a trailing VPLS instance name in parentheses and the JunOS
     * routing-engine suffix ({@code -re0}/{@code -re1}) from a raw router
     * name token, then upper-cases the result.
     *
     * @param raw      raw string from the instance name after the first {@code /}
     * @param reSuffix compiled pattern for {@code -re\d+}
     * @return         clean upper-cased router base name
     */
    private static String stripVplsSuffix(String raw, Pattern reSuffix) {
        String s = raw.replaceAll("\\s*\\([^)]*\\)$", "").trim();
        return reSuffix.matcher(s).replaceAll("").toUpperCase();
    }

    /**
     * Splits a comma-separated list of hostnames, trimming whitespace and
     * ignoring empty tokens.
     *
     * @param csv comma-separated string (may be blank)
     * @return    mutable list of non-blank tokens; empty if {@code csv} is blank
     */
    private static List<String> parseList(String csv) {
        List<String> result = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return result;
        }
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                result.add(t);
            }
        }
        return result;
    }
}
