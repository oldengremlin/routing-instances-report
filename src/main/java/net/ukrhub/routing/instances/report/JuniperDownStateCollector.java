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

import java.io.IOException;
import lombok.extern.log4j.Log4j2;
import org.w3c.dom.*;
import javax.xml.xpath.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;
import java.util.regex.*;

/**
 * Fetches operational down-state for L2CIRCUIT and VPLS from Juniper routers.
 *
 * <p>Sends two NETCONF RPCs in a single SSH session per router:</p>
 * <ul>
 *   <li>{@code get-l2ckt-connection-information} with {@code <down/>} filter;</li>
 *   <li>{@code get-vpls-connection-information} with {@code <down/>} filter.</li>
 * </ul>
 *
 * <p>Each down connection is returned as a six-element string array
 * {@code [type, routerName, vcId, instance, neighborSite, statusDescription]}:</p>
 * <ul>
 *   <li><b>type</b> — {@code L2CIRCUIT} or {@code VPLS};</li>
 *   <li><b>routerName</b> — local router base name (canonical, from
 *       {@code //system/host-name}, falling back to upper-cased hostname);</li>
 *   <li><b>vcId</b> — VC-ID (L2CIRCUIT) or VPLS-ID;</li>
 *   <li><b>instance</b> — {@code vcId/ROUTER} for L2CIRCUIT, instance name for VPLS;</li>
 *   <li><b>neighborSite</b> — resolved neighbor name + interface for L2CIRCUIT,
 *       resolved neighbor name or numeric site-ID for VPLS;</li>
 *   <li><b>statusDescription</b> — full text from {@link ConnectionStatus}.</li>
 * </ul>
 *
 * <p>Neighbor IPs are resolved via the {@code loAddresses} map built by
 * {@link LoAddressMapper}; unresolved IPs are kept as-is.</p>
 */
@Log4j2
public class JuniperDownStateCollector extends AbstractJuniperCollector {

    private static final String L2CKT_DOWN_RPC =
            "<rpc xmlns=\"urn:ietf:params:xml:ns:netconf:base:1.0\" message-id=\"1\">"
            + "<get-l2ckt-connection-information><down/></get-l2ckt-connection-information>"
            + "</rpc>" + DELIM;

    private static final String VPLS_DOWN_RPC =
            "<rpc xmlns=\"urn:ietf:params:xml:ns:netconf:base:1.0\" message-id=\"2\">"
            + "<get-vpls-connection-information><down/></get-vpls-connection-information>"
            + "</rpc>" + DELIM;

    /** Extracts VC-ID from L2CIRCUIT connection-id, e.g. {@code xe-0/2/0.1228(vc 1228)} → {@code 1228}. */
    private static final Pattern VC_ID_PAT = Pattern.compile("\\(vc (\\d+)\\)$");

    /** Extracts the IP part from VPLS LDP neighbor connection-id, e.g. {@code 94.125.120.78(vpls-id 2999)}. */
    private static final Pattern VPLS_NEIGHBOR_PAT = Pattern.compile("^([\\d:.a-fA-F]+)\\(");

    /**
     * Creates a new collector with the given SSH credentials.
     *
     * @param login    SSH username
     * @param pass     SSH password
     * @param xmlCache shared in-memory XML cache populated by {@link JuniperCollector};
     *                 used to resolve the local router's canonical name
     */
    public JuniperDownStateCollector(String login, String pass, ConcurrentHashMap<String, String> xmlCache) {
        super(login, pass, xmlCache);
    }

    /**
     * Not used — operational state is fetched via {@link #collectDownState}.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public void collect(String hostname, Map<String, RoutingInstance> instances,
                        Map<String, Map<String, String>> vrfVplsList) {
        throw new UnsupportedOperationException("Use collectDownState() instead");
    }

    /**
     * Fetches and parses all down L2CIRCUIT and VPLS connections for
     * {@code hostname} in a single NETCONF session.
     *
     * <p>The local router name is read from the cached config dump written
     * by {@link JuniperCollector} (if available) so that L2CIRCUIT instance
     * names match the format used in the main table. Falls back to the
     * upper-cased hostname when the dump does not exist.</p>
     *
     * @param hostname    Juniper router hostname
     * @param loAddresses lo0 IP → router name map for neighbor resolution
     * @return            list of {@code [type, routerName, vcId, instance, neighborSite, status]} rows
     * @throws Exception  on SSH or XML parse error
     */
    public List<String[]> collectDownState(String hostname,
                                           Map<String, String> loAddresses) throws Exception {
        String routerName = resolveRouterName(hostname);
        List<String> responses = fetchRpcs(hostname, List.of(L2CKT_DOWN_RPC, VPLS_DOWN_RPC));

        List<String[]> results = new ArrayList<>();
        parseL2ckt(responses.get(0), routerName, loAddresses, results);
        parseVpls(responses.get(1), routerName, loAddresses, results);

        log.info("Down state from {}: {} connections down", hostname, results.size());
        return results;
    }

    /**
     * Tries to read the router's canonical name from the cached config dump.
     * Falls back to the uppercased hostname when the dump is absent.
     */
    private String resolveRouterName(String hostname) {
        String xml = xmlCache.get(hostname);
        if (xml == null) {
            Path dump = Path.of(DUMP_DIR, "juniper-" + hostname + ".xml");
            if (Files.exists(dump)) {
                try { xml = Files.readString(dump, StandardCharsets.UTF_8); }
                catch (IOException e) {
                    log.warn("Could not read dump for {}: {}", hostname, e.getMessage());
                }
            }
        }
        if (xml != null) {
            try {
                return extractRouterName(parseXml(xml), XPathFactory.newInstance().newXPath(), hostname);
            } catch (Exception e) {
                log.warn("Could not extract router name for {}: {}", hostname, e.getMessage());
            }
        }
        return hostname.toUpperCase();
    }

    /**
     * Parses {@code get-l2ckt-connection-information} reply and appends rows
     * to {@code results}.
     *
     * <p>{@code l2circuit-neighbor} elements are selected when they contain at
     * least one {@code connection} child or a {@code neighbor-display-error}
     * element. Two cases are handled:</p>
     * <ul>
     *   <li>Neighbor has {@code connection} elements — one row per connection.</li>
     *   <li>Neighbor has {@code neighbor-display-error} but no connections —
     *       a single row with {@code "?"} as the VC-ID and the error text
     *       (typically {@code "No l2circuit connections found"}) as the status.</li>
     * </ul>
     */
    private void parseL2ckt(String xml, String routerName,
                             Map<String, String> loAddresses,
                             List<String[]> results) throws Exception {
        Document doc = parseXml(xml);
        XPath xp = XPathFactory.newInstance().newXPath();

        NodeList neighbors = (NodeList) xp.evaluate(
                "//l2circuit-neighbor[connection or neighbor-display-error]", doc, XPathConstants.NODESET);

        for (int i = 0; i < neighbors.getLength(); i++) {
            Node neighbor = neighbors.item(i);
            String neighborIp = xp.evaluate("neighbor-address/text()", neighbor).trim();
            String neighborName = loAddresses.getOrDefault(neighborIp, neighborIp);
            String neighborDisplay = neighborName.equals(neighborIp)
                    ? neighborIp : neighborName + "/" + neighborIp;

            NodeList connections = (NodeList) xp.evaluate("connection", neighbor, XPathConstants.NODESET);
            if (connections.getLength() > 0) {
                for (int j = 0; j < connections.getLength(); j++) {
                    Node conn = connections.item(j);
                    String connId = xp.evaluate("connection-id/text()", conn).trim();
                    String statusCode = xp.evaluate("connection-status/text()", conn).trim();

                    Matcher m = VC_ID_PAT.matcher(connId);
                    String vcId = m.find() ? m.group(1) : connId;
                    String iface = connId.replaceAll("\\s*\\(vc \\d+\\)$", "").trim();

                    results.add(new String[]{
                            "L2CIRCUIT",
                            routerName,
                            vcId,
                            vcId + "/" + routerName,
                            neighborDisplay + ", " + iface,
                            ConnectionStatus.describe(statusCode)
                    });
                }
            } else {
                String errorText = xp.evaluate("neighbor-display-error/text()", neighbor).trim();
                results.add(new String[]{
                        "L2CIRCUIT",
                        routerName,
                        "?",
                        "?/" + routerName,
                        neighborDisplay,
                        errorText.isEmpty() ? "no connections" : errorText
                });
            }
        }
    }

    /**
     * Parses {@code get-vpls-connection-information} reply and appends rows
     * to {@code results}.
     *
     * <p>The VPLS-ID is read from {@code ldp-vpls-reference-site/vpls-id}
     * first; if absent, from {@code reference-site/vpls-id}. Two cases are
     * handled:</p>
     * <ul>
     *   <li>Instance has {@code reference-site/connection} elements — one row
     *       per connection; for BGP-VPLS the connection-id is a numeric site
     *       number, for LDP-VPLS it has the form {@code IP(vpls-id N)} and the
     *       IP is resolved via {@code loAddresses}.</li>
     *   <li>Instance has {@code instance-display-error} but no connections —
     *       a single row with {@code "-"} as the neighbor and the error text
     *       (typically {@code "No connections found."}) as the status.</li>
     * </ul>
     */
    private void parseVpls(String xml, String routerName,
                            Map<String, String> loAddresses,
                            List<String[]> results) throws Exception {
        Document doc = parseXml(xml);
        XPath xp = XPathFactory.newInstance().newXPath();

        NodeList instances = (NodeList) xp.evaluate(
                "//instance[reference-site/connection or instance-display-error]", doc, XPathConstants.NODESET);

        for (int i = 0; i < instances.getLength(); i++) {
            Node inst = instances.item(i);
            String instanceName = xp.evaluate("instance-name/text()", inst).trim();

            String vplsId = xp.evaluate("ldp-vpls-reference-site/vpls-id/text()", inst).trim();
            if (vplsId.isEmpty()) {
                vplsId = xp.evaluate("reference-site/vpls-id/text()", inst).trim();
            }

            NodeList connections = (NodeList) xp.evaluate(
                    "reference-site/connection", inst, XPathConstants.NODESET);

            if (connections.getLength() > 0) {
                for (int j = 0; j < connections.getLength(); j++) {
                    Node conn = connections.item(j);
                    String connId = xp.evaluate("connection-id/text()", conn).trim();
                    String statusCode = xp.evaluate("connection-status/text()", conn).trim();

                    String neighborSite;
                    Matcher m = VPLS_NEIGHBOR_PAT.matcher(connId);
                    if (m.find()) {
                        String ip = m.group(1);
                        String name = loAddresses.getOrDefault(ip, ip);
                        neighborSite = name.equals(ip) ? ip : name + "/" + ip;
                    } else {
                        neighborSite = connId;
                    }

                    results.add(new String[]{
                            "VPLS",
                            routerName,
                            vplsId.isEmpty() ? "?" : vplsId,
                            instanceName,
                            neighborSite,
                            ConnectionStatus.describe(statusCode)
                    });
                }
            } else {
                String errorText = xp.evaluate("instance-display-error/text()", inst).trim();
                results.add(new String[]{
                        "VPLS",
                        routerName,
                        vplsId.isEmpty() ? "?" : vplsId,
                        instanceName,
                        "-",
                        errorText.isEmpty() ? "no connections" : errorText
                });
            }
        }
    }
}
