package net.ukrcom.routingreport;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

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

    public static void generate(Map<String, RoutingInstance> instances,
                                 Map<String, Map<String, String>> vrfVplsList,
                                 String outputPath) throws IOException {
        String vrfList = buildVrfList(vrfVplsList);
        String vrfInfo = buildVrfInfo(instances);
        String postBr  = buildPostBr(instances.size());

        String html = HTML_TEMPLATE
                .replace("    <!--VRFVPLSLIST-->",  vrfList  + "    <!--VRFVPLSLIST-->")
                .replace("\t    <!--VRFVPLSINFO-->", vrfInfo  + "\t    <!--VRFVPLSINFO-->")
                .replace("    <!--VRFVPPOSTBR-->",  postBr   + "    <!--VRFVPPOSTBR-->");

        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(outputPath), StandardCharsets.UTF_8))) {
            pw.print(html);
        }
    }

    private static String buildVrfList(Map<String, Map<String, String>> vrfVplsList) {
        StringBuilder sb = new StringBuilder("    <p><h1>Список VRF/VPLS упорядкований за RD</h1><ol>\n");

        List<Map.Entry<String, Map<String, String>>> entries = new ArrayList<>(vrfVplsList.entrySet());
        Pattern rdPat = Pattern.compile("RD:(\\d+):(\\d+)");
        entries.sort((a, b) -> {
            Matcher ma = rdPat.matcher(a.getKey());
            Matcher mb = rdPat.matcher(b.getKey());
            long va = ma.find() ? Long.parseLong(ma.group(1)) * Long.parseLong(ma.group(2)) : 0;
            long vb = mb.find() ? Long.parseLong(mb.group(1)) * Long.parseLong(mb.group(2)) : 0;
            return Long.compare(va, vb);
        });

        for (Map.Entry<String, Map<String, String>> e : entries) {
            String rdl  = e.getKey().replaceAll("\\s", "");
            String name = e.getValue().get("name");
            String href = e.getValue().get("href");
            sb.append(String.format(
                    "    <li><a href=\"#%s\">%s</a> - <a name=\"%s\" />%s<br /></li>\n",
                    href, rdl, name, name));
        }
        sb.append("    </ol></p>\n");
        return sb.toString();
    }

    private static String buildVrfInfo(Map<String, RoutingInstance> instances) {
        StringBuilder sb = new StringBuilder();
        String sp = "\t    ";
        int num = 0;

        for (RoutingInstance ri : instances.values()) {
            num++;
            System.out.printf("[%-4s] %-50s %s %s%n",
                    ri.type, ri.name, ri.rd, String.join(", ", ri.hosts));

            sb.append(String.format(
                    sp + "<tr>" +
                    "<td style=\"vertical-align: top; text-align: right;\">%d</td>" +
                    "<td style=\"vertical-align: top;\">%s</td>" +
                    "<td style=\"vertical-align: top;\"><a name=\"%s\" />%s</td>" +
                    "<td style=\"vertical-align: top;\"><a href=\"#%s\">%s</a></td>" +
                    "<td style=\"vertical-align: top;\">%s</td>" +
                    "</tr>\n",
                    num,
                    ri.type,
                    ri.hrefname, ri.name,
                    ri.name, ri.rd,
                    String.join(", ", ri.hosts)));
        }
        return sb.toString();
    }

    private static String buildPostBr(int count) {
        return "    " + "<br />".repeat(count) + "\n";
    }
}
