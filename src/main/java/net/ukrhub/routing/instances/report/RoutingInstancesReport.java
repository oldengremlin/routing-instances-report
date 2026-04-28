package net.ukrhub.routing.instances.report;

import lombok.extern.log4j.Log4j2;

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
@Log4j2
public class RoutingInstancesReport {

    public static void main(String[] args) throws Exception {
        String login       = require("ROUTER_USER");
        String pass        = require("ROUTER_PASS");
        String ciscoEnable = env("CISCO_ENABLE",  "");
        String reportPath  = env("REPORT_PATH",   "/usr/share/nginx/html/index.html");

        List<String> juniperHosts  = parseList(env("JUNIPER_HOSTS",  ""));
        List<String> ciscoHosts    = parseList(env("CISCO_HOSTS",    ""));
        List<String> routerosHosts = parseList(env("ROUTEROS_HOSTS", ""));

        log.info("Starting collection — Juniper: {}, Cisco: {}, RouterOS: {}",
                juniperHosts, ciscoHosts, routerosHosts);

        Map<String, RoutingInstance>     instances   = new TreeMap<>();
        Map<String, Map<String, String>> vrfVplsList = new LinkedHashMap<>();

        JuniperCollector  juniper  = new JuniperCollector(login, pass);
        CiscoCollector    cisco    = new CiscoCollector(login, pass, ciscoEnable);
        RouterOSCollector routeros = new RouterOSCollector(login, pass);

        for (String host : juniperHosts) {
            log.info("Collecting from Juniper: {}", host);
            try { juniper.collect(host, instances, vrfVplsList); }
            catch (Exception e) { log.error("Juniper {} failed: {}", host, e.getMessage(), e); }
        }

        for (String host : ciscoHosts) {
            log.info("Collecting from Cisco: {}", host);
            try { cisco.collect(host, instances, vrfVplsList); }
            catch (Exception e) { log.error("Cisco {} failed: {}", host, e.getMessage(), e); }
        }

        for (String host : routerosHosts) {
            log.info("Collecting from RouterOS: {}", host);
            try { routeros.collect(host, instances, vrfVplsList); }
            catch (Exception e) { log.error("RouterOS {} failed: {}", host, e.getMessage(), e); }
        }

        log.info("Collection complete: {} instances total", instances.size());
        ReportGenerator.generate(instances, vrfVplsList, reportPath);
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
