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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Generates the HTML report from the collected routing instance data.
 *
 * <p>The report consists of three index sections followed by the main table:</p>
 * <ol>
 *   <li><b>VRF/VPLS list ordered by RD</b> — instances that carry a Route
 *       Distinguisher, sorted by the numeric product AS×ID, with
 *       bidirectional anchor links to the main table.</li>
 *   <li><b>VC-ID/VPLS-ID list</b> — instances whose name starts with a
 *       numeric prefix followed by {@code /} (L2CIRCUIT and secondary VPLS
 *       rows), grouped by that prefix, with a count of endpoints per ID and
 *       bidirectional links to the first matching table row.</li>
 *   <li><b>Main table</b> — all collected instances in deduplication-key
 *       order, with type, name, RD, and router host entries.</li>
 * </ol>
 *
 * <p>IP addresses in host entries are resolved to router names via the
 * {@code loAddresses} map built by {@link LoAddressMapper}: a bare address
 * {@code A.B.C.D} becomes {@code ROUTERNAME/A.B.C.D}.</p>
 */
@Log4j2
public class ReportGenerator {

    private ReportGenerator() {
    }

    private static final String HTML_TEMPLATE = """
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<meta content="text/html; charset=UTF-8" http-equiv="Content-Type">
<title>AS12593 VRF/VPLS</title>
</head>
<body>
    <!--VRFVPLSLIST-->
    <h1>Опис VRF/VPLS</h1>
    <table border="1" cellpadding="2" cellspacing="0">
\t<tbody>
\t    <tr><th style="vertical-align: top;">№<sup>п</sup>/<sub>п</sub></th><th style="vertical-align: top;">Тип</th><th style="vertical-align: top;">Найменування</th><th style="vertical-align: top;">RD</th><th style="vertical-align: top;">Маршрутизатор</th></tr>
\t    <!--VRFVPLSINFO-->
\t</tbody>
    </table>
    <!--ORPHANTABLE-->
    <!--DOWNSTATETABLE-->
    <!--VRFVPPOSTBR-->
</body>
</html>
""";

    private static String h(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** Matches IPv4 and IPv6 addresses inside host entry strings. */
    private static final Pattern IP_PATTERN = Pattern.compile(
            "(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}"
            + "|(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)(?:\\.(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)){3}");

    /** Matches instance names of the form {@code DIGITS/...} (L2CIRCUIT, secondary VPLS). */
    private static final Pattern VCID_PAT = Pattern.compile("^(\\d+)/");

    /**
     * Builds and writes the HTML report.
     *
     * @param instances    all collected routing instances (sorted by deduplication key)
     * @param vrfVplsList  RD → {name, href} map for the RD index
     * @param outputPath   destination file path (typically the nginx document root)
     * @param loAddresses  lo0 IP → router name map for address resolution
     * @param orphans         L2CIRCUIT/VPLS unpaired entries from {@code findOrphans}; may be empty
     * @param downConnections L2CIRCUIT/VPLS down connections from
     *                        {@link JuniperDownStateCollector}; may be empty
     * @throws IOException if the output file cannot be written
     */
    public static void generate(Map<String, RoutingInstance> instances,
                                Map<String, Map<String, String>> vrfVplsList,
                                String outputPath,
                                Map<String, String> loAddresses,
                                List<String[]> orphans,
                                List<String[]> downConnections) throws IOException {
        String html = HTML_TEMPLATE
                .replace("    <!--VRFVPLSLIST-->", buildVrfList(vrfVplsList) + buildVcidList(instances) + "    <!--VRFVPLSLIST-->")
                .replace("\t    <!--VRFVPLSINFO-->", buildVrfInfo(instances, loAddresses) + "\t    <!--VRFVPLSINFO-->")
                .replace("    <!--ORPHANTABLE-->", buildOrphanTable(orphans))
                .replace("    <!--DOWNSTATETABLE-->", buildDownStateTable(downConnections))
                .replace("    <!--VRFVPPOSTBR-->", buildPostBr(instances.size()) + "    <!--VRFVPPOSTBR-->");

        log.info("Writing report to {} ({} entries)", outputPath, instances.size());
        if (!orphans.isEmpty()) {
            log.info("L2CIRCUIT/VPLS без пар: {} записів", orphans.size());
            orphans.forEach(o -> log.info("  без пар: {} | {} | {} | {} | {}", o[0], o[1], o[2], o[3], o[4]));
        }
        if (!downConnections.isEmpty()) {
            log.info("L2CIRCUIT/VPLS неактивний стан: {} з'єднань", downConnections.size());
            downConnections.forEach(r -> log.info("  down: {} | {} | {} | {} | {} | {}", r[0], r[1], r[2], r[3], r[4], r[5]));
        }
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(outputPath), StandardCharsets.UTF_8))) {
            pw.print(html);
        }
        log.info("Report written: {}", outputPath);
    }

    /**
     * Builds the ordered RD index list (section 1).
     *
     * <p>Entries are sorted by the numeric product of AS × ID extracted from the
     * RD string. Each item links to the corresponding table row anchor, and the
     * table row carries a reverse link back here.</p>
     *
     * @param vrfVplsList  RD → {name, href} map
     * @return             HTML fragment
     */
    private static String buildVrfList(Map<String, Map<String, String>> vrfVplsList) {
        StringBuilder sb = new StringBuilder("    <p><h1>Список VRF/VPLS упорядкований за RD</h1><ol>\n");

        Pattern rdPat = Pattern.compile("RD:(\\d+):(\\d+)");
        vrfVplsList.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> {
                    Matcher m = rdPat.matcher(e.getKey());
                    return m.find() ? Long.parseLong(m.group(1)) * Long.parseLong(m.group(2)) : 0L;
                }))
                .forEach(e -> {
                    String rdl = e.getKey().replaceAll("\\s", "");
                    String name = e.getValue().get("name");
                    String href = e.getValue().get("href");
                    sb.append(String.format(
                            "    <li><a href=\"#%s\">%s</a> - <a name=\"%s\" />%s<br /></li>\n",
                            href, h(rdl), name, h(name)));
                });

        sb.append("    </ol></p>\n");
        return sb.toString();
    }

    /**
     * Builds the VC-ID/VPLS-ID index list (section 2).
     *
     * <p>Groups all instances whose name starts with {@code DIGITS/} by the
     * numeric prefix, counts entries per ID, and links to the first table row
     * with that prefix. Returns an empty string if no such instances exist.</p>
     *
     * <p>Instances are iterated in {@link java.util.TreeMap} order (i.e. the
     * same order as the main table) so that "first" is consistent with what
     * the reader sees on screen.</p>
     *
     * @param instances  all collected routing instances
     * @return           HTML fragment, or {@code ""} if no VC-ID entries exist
     */
    private static String buildVcidList(Map<String, RoutingInstance> instances) {
        Map<Integer, String> firstHref = new LinkedHashMap<>();
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        instances.values().forEach(ri -> {
            Matcher m = VCID_PAT.matcher(ri.getName());
            if (m.find()) {
                int vcid = Integer.parseInt(m.group(1));
                firstHref.putIfAbsent(vcid, ri.getHrefname());
                counts.merge(vcid, 1, Integer::sum);
            }
        });
        if (counts.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("    <p><h1>Список VC-ID/VPLS-ID</h1><ol>\n");
        counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    int vcid = e.getKey();
                    sb.append(String.format(
                            "    <li><a name=\"vcid-%d\" /><a href=\"#%s\">%d</a> — %d</li>\n",
                            vcid, firstHref.get(vcid), vcid, e.getValue()));
                });
        sb.append("    </ol></p>\n");
        return sb.toString();
    }

    /**
     * Builds the main table body rows (section 3).
     *
     * <p>For each instance, host entries are IP-resolved, sorted by type-specific
     * rules, and joined with {@code <br>}. Instances whose name matches
     * {@link #VCID_PAT} receive a superscript {@code ↑} back-link to their
     * entry in the VC-ID index list.</p>
     *
     * @param instances    all collected routing instances
     * @param loAddresses  lo0 IP → router name map
     * @return             HTML fragment containing all {@code <tr>} rows
     */
    private static String buildVrfInfo(Map<String, RoutingInstance> instances,
                                       Map<String, String> loAddresses) {
        StringBuilder sb = new StringBuilder();
        String sp = "\t    ";
        int[] num = {0};

        instances.values().forEach(ri -> {
            num[0]++;
            List<String> resolvedHosts = sortedHosts(ri).stream()
                    .map(hostEntry -> resolveIps(hostEntry, loAddresses))
                    .map(s -> h(s))
                    .collect(Collectors.toList());
            String hostsSep = "<br>";
            log.info("[{}] {} {} {}",
                    String.format("%-4s", ri.getType()),
                    String.format("%-50s", ri.getName()),
                    ri.getRd(),
                    String.join(", ", resolvedHosts));

            String hostsHtml = String.join(hostsSep, resolvedHosts);

            Matcher vcm = VCID_PAT.matcher(ri.getName());
            String vcidBack = vcm.find()
                              ? " <sup><a href=\"#vcid-" + vcm.group(1) + "\">↑</a></sup>"
                              : "";

            sb.append(String.format(
                    sp + "<tr>"
                    + "<td style=\"vertical-align: top; text-align: right;\">%d</td>"
                    + "<td style=\"vertical-align: top;\">%s</td>"
                    + "<td style=\"vertical-align: top;\"><a name=\"%s\" />%s%s</td>"
                    + "<td style=\"vertical-align: top;\"><a href=\"#%s\">%s</a></td>"
                    + "<td style=\"vertical-align: top;\">%s</td>"
                    + "</tr>\n",
                    num[0],
                    h(ri.getType()),
                    ri.getHrefname(), h(ri.getName()), vcidBack,
                    ri.getName(), h(ri.getRd()),
                    hostsHtml));
        });
        return sb.toString();
    }

    /**
     * Replaces bare IPv4 and IPv6 addresses in {@code s} with
     * {@code ROUTERNAME/ADDRESS} using the {@code loAddresses} lookup.
     * Addresses not present in the map are left unchanged.
     *
     * @param s            host entry string
     * @param loAddresses  lo0 IP → router name map
     * @return             string with recognised addresses substituted
     */
    private static String resolveIps(String s, Map<String, String> loAddresses) {
        if (loAddresses.isEmpty()) {
            return s;
        }
        return IP_PATTERN.matcher(s).replaceAll(mr -> {
            String name = loAddresses.get(mr.group());
            return name != null ? Matcher.quoteReplacement(name + "/" + mr.group()) : mr.group();
        });
    }

    /**
     * Returns the host list for {@code ri} in display order.
     *
     * <p>VPLS instances are sorted by the numeric site-ID after the colon
     * (e.g. {@code ROUTER:42(-)}) to keep sites in natural order; ties are
     * broken alphabetically. All other types are sorted alphabetically.</p>
     *
     * @param ri routing instance
     * @return   sorted, mutable copy of the host list
     */
    private static List<String> sortedHosts(RoutingInstance ri) {
        if (ri.getType().startsWith("VPLS")) {
            return ri.getHosts().stream()
                    .sorted(Comparator.comparingInt((String h) -> {
                        int colon = h.indexOf(':');
                        if (colon < 0) {
                            return 0;
                        }
                        String num = h.substring(colon + 1).replaceAll("\\D.*", "");
                        return num.isEmpty() ? 0 : Integer.valueOf(num);
                    }).thenComparing(Comparator.naturalOrder()))
                    .collect(Collectors.toList());
        }
        return ri.getHosts().stream().sorted().collect(Collectors.toList());
    }

    /**
     * Builds the orphan/unpaired L2CIRCUIT and VPLS table (section 4).
     *
     * <p>Renders a table listing L2CIRCUIT and VPLS secondary entries that
     * have no valid reverse peer, as detected by
     * {@code RoutingInstancesReport.findOrphans}. Returns an empty string
     * when {@code orphans} is empty.</p>
     *
     * <p>Each row contains five columns: Type, VC-ID, Router, Neighbor info,
     * and the mismatch category note.</p>
     *
     * @param orphans list of {@code [type, vcId, localRouter, neighborInfo, note]} arrays
     * @return        HTML fragment, or {@code ""} if no orphans exist
     */
    private static String buildOrphanTable(List<String[]> orphans) {
        if (orphans.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("    <h2>L2CIRCUIT/VPLS без пар</h2>\n");
        sb.append("    <table border=\"1\" cellpadding=\"2\" cellspacing=\"0\">\n");
        sb.append("\t<tbody>\n");
        sb.append("\t    <tr>"
                + "<th>Тип</th>"
                + "<th>VC-ID</th>"
                + "<th>Маршрутизатор</th>"
                + "<th>Сусід</th>"
                + "<th>Примітка</th>"
                + "</tr>\n");
        for (String[] o : orphans) {
            sb.append(String.format(
                    "\t    <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>\n",
                    h(o[0]), h(o[1]), h(o[2]), h(o[3]), h(o[4])));
        }
        sb.append("\t</tbody>\n");
        sb.append("    </table>\n");
        return sb.toString();
    }

    /**
     * Builds the down-state L2CIRCUIT/VPLS table (section 5).
     *
     * <p>Renders a table of connections reported as down by the router's
     * operational RPC, collected by {@link JuniperDownStateCollector}.
     * Rows are sorted by type, then numerically by VC-ID/VPLS-ID, then
     * by instance name. Returns an empty string when the list is empty.</p>
     *
     * <p>Columns: Type | VC-ID/VPLS-ID | Instance | Neighbor/Site | Статус.</p>
     *
     * @param downConnections list of {@code [type, vcId, instance, neighborSite, status]} arrays
     * @return                HTML fragment, or {@code ""} if no down connections
     */
    private static String buildDownStateTable(List<String[]> downConnections) {
        if (downConnections.isEmpty()) {
            return "";
        }
        List<String[]> sorted = new ArrayList<>(downConnections);
        sorted.sort(Comparator
                .<String[], String>comparing(r -> r[0])
                .thenComparing(r -> r[1])
                .thenComparingInt(r -> {
                    try { return Integer.valueOf(r[2]); }
                    catch (NumberFormatException e) { return 0; }
                })
                .thenComparing(r -> r[3]));

        StringBuilder sb = new StringBuilder();
        sb.append("    <h2>L2CIRCUIT/VPLS неактивний стан</h2>\n");
        sb.append("    <table border=\"1\" cellpadding=\"2\" cellspacing=\"0\">\n");
        sb.append("\t<tbody>\n");
        sb.append("\t    <tr>"
                + "<th>Тип</th>"
                + "<th>Маршрутизатор</th>"
                + "<th>VC-ID/VPLS-ID</th>"
                + "<th>Instance</th>"
                + "<th>Neighbor/Site</th>"
                + "<th>Статус</th>"
                + "</tr>\n");
        for (String[] r : sorted) {
            sb.append(String.format(
                    "\t    <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>\n",
                    h(r[0]), h(r[1]), h(r[2]), h(r[3]), h(r[4]), h(r[5])));
        }
        sb.append("\t</tbody>\n");
        sb.append("    </table>\n");
        return sb.toString();
    }

    /**
     * Returns a sequence of {@code <br />} elements used to pad the page so
     * that anchor links to the last table rows scroll the target row to the
     * top of the viewport rather than the bottom.
     *
     * @param count number of instances (one {@code <br />} per instance)
     * @return      HTML fragment
     */
    private static String buildPostBr(int count) {
        return "    " + "<br />".repeat(count) + "\n";
    }
}
