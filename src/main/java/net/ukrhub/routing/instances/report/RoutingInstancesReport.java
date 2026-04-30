package net.ukrhub.routing.instances.report;

import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.util.*;

/**
 * Entry point. All configuration comes from environment variables:
 *
 * ROUTER_USER – SSH/Telnet login (required)
 *
 * ROUTER_PASS – SSH/Telnet password (required)
 *
 * CISCO_ENABLE – Cisco enable password (required when CISCO_HOSTS is set)
 *
 * JUNIPER_HOSTS – comma-separated hostnames (default: empty)
 *
 * CISCO_HOSTS – comma-separated hostnames (default: empty)
 *
 * ROUTEROS_HOSTS – comma-separated hostnames (default: empty)
 *
 * REPORT_PATH – output HTML file path (default:
 * /usr/share/nginx/html/index.html)
 */
@Log4j2
public class RoutingInstancesReport {

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
        try {
            ReportGenerator.generate(instances, vrfVplsList, reportPath);
        } catch (IOException e) {
            log.error("Failed to write report to {}: {}", reportPath, e.getMessage());
        }
    }

    private static String require(String name) {
        String val = System.getenv(name);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Required environment variable not set: " + name);
        }
        return val;
    }

    private static String env(String name, String defaultValue) {
        String val = System.getenv(name);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

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
