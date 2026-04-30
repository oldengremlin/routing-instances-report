package net.ukrhub.routing.instances.report;

import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.util.*;

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
 *   <tr><th>Variable</th><th>Required</th><th>Default</th></tr>
 *   <tr><td>{@code ROUTER_USER}</td><td>yes</td><td>—</td></tr>
 *   <tr><td>{@code ROUTER_PASS}</td><td>yes</td><td>—</td></tr>
 *   <tr><td>{@code CISCO_ENABLE}</td><td>if CISCO_HOSTS set</td><td>—</td></tr>
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

        try {
            ReportGenerator.generate(instances, vrfVplsList, reportPath, loAddresses);
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
