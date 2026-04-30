package net.ukrhub.routing.instances.report;

import lombok.extern.log4j.Log4j2;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.function.*;

@Log4j2
public class ReportGenerator {

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
    <!--VRFVPPOSTBR-->
</body>
</html>
""";

    private static final Pattern IP_PATTERN = Pattern.compile(
            "(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}" +
            "|(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)(?:\\.(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)){3}");

    public static void generate(Map<String, RoutingInstance> instances,
                                Map<String, Map<String, String>> vrfVplsList,
                                String outputPath,
                                Map<String, String> loAddresses) throws IOException {
        String html = HTML_TEMPLATE
                .replace("    <!--VRFVPLSLIST-->", buildVrfList(vrfVplsList) + "    <!--VRFVPLSLIST-->")
                .replace("\t    <!--VRFVPLSINFO-->", buildVrfInfo(instances, loAddresses) + "\t    <!--VRFVPLSINFO-->")
                .replace("    <!--VRFVPPOSTBR-->", buildPostBr(instances.size()) + "    <!--VRFVPPOSTBR-->");

        log.info("Writing report to {} ({} entries)", outputPath, instances.size());
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(outputPath), StandardCharsets.UTF_8))) {
            pw.print(html);
        }
        log.info("Report written: {}", outputPath);
    }

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
                            href, rdl, name, name));
                });

        sb.append("    </ol></p>\n");
        return sb.toString();
    }

    private static String buildVrfInfo(Map<String, RoutingInstance> instances,
                                       Map<String, String> loAddresses) {
        StringBuilder sb = new StringBuilder();
        String sp = "\t    ";
        int[] num = {0};

        instances.values().forEach(ri -> {
            num[0]++;
            List<String> resolvedHosts = sortedHosts(ri).stream()
                    .map(h -> resolveIps(h, loAddresses))
                    .collect(Collectors.toList());
            String hostsSep = "<br>";
            log.info("[{}] {} {} {}",
                    String.format("%-4s", ri.getType()),
                    String.format("%-50s", ri.getName()),
                    ri.getRd(),
                    String.join(", ", resolvedHosts));

            String hostsHtml = String.join(hostsSep, resolvedHosts);

            sb.append(String.format(
                    sp + "<tr>"
                    + "<td style=\"vertical-align: top; text-align: right;\">%d</td>"
                    + "<td style=\"vertical-align: top;\">%s</td>"
                    + "<td style=\"vertical-align: top;\"><a name=\"%s\" />%s</td>"
                    + "<td style=\"vertical-align: top;\"><a href=\"#%s\">%s</a></td>"
                    + "<td style=\"vertical-align: top;\">%s</td>"
                    + "</tr>\n",
                    num[0],
                    ri.getType(),
                    ri.getHrefname(), ri.getName(),
                    ri.getName(), ri.getRd(),
                    hostsHtml));
        });
        return sb.toString();
    }

    private static String resolveIps(String s, Map<String, String> loAddresses) {
        if (loAddresses.isEmpty()) return s;
        return IP_PATTERN.matcher(s).replaceAll(mr -> {
            String name = loAddresses.get(mr.group());
            return name != null ? Matcher.quoteReplacement(name + "/" + mr.group()) : mr.group();
        });
    }

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

    private static String buildPostBr(int count) {
        return "    " + "<br />".repeat(count) + "\n";
    }
}
