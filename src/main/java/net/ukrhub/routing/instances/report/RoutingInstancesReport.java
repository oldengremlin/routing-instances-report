package net.ukrhub.routing.instances.report;

import java.util.*;

/**
 * Entry point. All configuration comes from environment variables:
 *
 *   ROUTER_USER      – SSH/Telnet login          (required)
 *   ROUTER_PASS      – SSH/Telnet password        (required)
 *   CISCO_ENABLE     – Cisco enable password      (required when CISCO_HOSTS is set)
 *   JUNIPER_HOSTS    – comma-separated hostnames  (default: empty)
 *   CISCO_HOSTS      – comma-separated hostnames  (default: empty)
 *   ROUTEROS_HOSTS   – comma-separated hostnames  (default: empty)
 *   REPORT_PATH      – output HTML file path      (default: /usr/share/nginx/html/index.html)
 */
public class RoutingInstancesReport {

    public static void main(String[] args) throws Exception {
        String login       = require("ROUTER_USER");
        String pass        = require("ROUTER_PASS");
        String ciscoEnable = env("CISCO_ENABLE",  "");
        String reportPath  = env("REPORT_PATH",   "/usr/share/nginx/html/index.html");

        List<String> juniperHosts  = parseList(env("JUNIPER_HOSTS",  ""));
        List<String> ciscoHosts    = parseList(env("CISCO_HOSTS",    ""));
        List<String> routerosHosts = parseList(env("ROUTEROS_HOSTS", ""));

        Map<String, RoutingInstance>     instances   = new TreeMap<>();
        Map<String, Map<String, String>> vrfVplsList = new LinkedHashMap<>();
        List<String> analyzed = new ArrayList<>();

        JuniperCollector  juniper  = new JuniperCollector(login, pass);
        CiscoCollector    cisco    = new CiscoCollector(login, pass, ciscoEnable);
        RouterOSCollector routeros = new RouterOSCollector(login, pass);

        for (String host : juniperHosts) {
            analyzed.add(host);
            progress(analyzed);
            try { juniper.collect(host, instances, vrfVplsList); }
            catch (Exception e) { System.err.printf("ERROR Juniper %s: %s%n", host, e.getMessage()); }
        }

        for (String host : ciscoHosts) {
            analyzed.add(host);
            progress(analyzed);
            try { cisco.collect(host, instances, vrfVplsList); }
            catch (Exception e) { System.err.printf("ERROR Cisco %s: %s%n", host, e.getMessage()); }
        }

        for (String host : routerosHosts) {
            analyzed.add(host);
            progress(analyzed);
            try { routeros.collect(host, instances, vrfVplsList); }
            catch (Exception e) { System.err.printf("ERROR RouterOS %s: %s%n", host, e.getMessage()); }
        }

        System.out.println();
        ReportGenerator.generate(instances, vrfVplsList, reportPath);
    }

    private static void progress(List<String> analyzed) {
        System.out.printf("Analyze: %s\r", String.join(", ", analyzed));
        System.out.flush();
    }

    private static String require(String name) {
        String val = System.getenv(name);
        if (val == null || val.isBlank())
            throw new IllegalStateException("Required environment variable not set: " + name);
        return val;
    }

    private static String env(String name, String defaultValue) {
        String val = System.getenv(name);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    private static List<String> parseList(String csv) {
        List<String> result = new ArrayList<>();
        if (csv == null || csv.isBlank()) return result;
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) result.add(t);
        }
        return result;
    }
}
