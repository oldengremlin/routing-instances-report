package net.ukrcom.routingreport;

import com.jcraft.jsch.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/**
 * Collects VRF definitions from MikroTik RouterOS via SSH.
 * Runs "/ip route vrf export compact" and parses the continuation-line output.
 */
public class RouterOSCollector {

    private final String login;
    private final String pass;

    public RouterOSCollector(String login, String pass) {
        this.login = login;
        this.pass  = pass;
    }

    public void collect(String hostname, Map<String, RoutingInstance> instances,
                        Map<String, Map<String, String>> vrfVplsList) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession(login, hostname, 22);
        session.setPassword(pass);
        Properties cfg = new Properties();
        cfg.put("StrictHostKeyChecking", "no");
        cfg.put("PreferredAuthentications", "password,keyboard-interactive");
        session.setConfig(cfg);
        session.connect(30_000);

        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand("/ip route vrf export compact");
        InputStream in = channel.getInputStream();
        channel.connect();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        channel.disconnect();
        session.disconnect();

        String[] lines = baos.toString(StandardCharsets.UTF_8).split("\r?\n");
        parseConfig(hostname, lines, instances, vrfVplsList);
    }

    private void parseConfig(String hostname, String[] lines,
                              Map<String, RoutingInstance> instances,
                              Map<String, Map<String, String>> vrfVplsList) {
        Pattern sectionPat = Pattern.compile(".*/.*");
        Pattern vrfPat     = Pattern.compile(
                "/ip route vrf add .+ route-distinguisher=([^ ]+) routing-mark=(.+)");
        final String type = "vrf";

        String csect = "";
        String cstr  = "";

        for (String rawLine : lines) {
            String s = rawLine.trim();
            if (s.startsWith("#") || s.isEmpty()) continue;

            if (sectionPat.matcher(s).matches() && !s.endsWith("\\")) {
                // New section header
                csect = s + " ";
                cstr  = csect;
            } else if (s.endsWith("\\")) {
                cstr += s.replaceAll("\\\\\\s*$", "");
            } else {
                cstr += s;
                // Complete logical line — try to match a VRF definition
                Matcher m = vrfPat.matcher(cstr);
                if (m.find()) {
                    String rd       = m.group(1).trim();
                    String instance = m.group(2).trim();
                    addInstance(instances, vrfVplsList, instance, type, rd, hostname.toUpperCase());
                }
                cstr = csect;
            }
        }
    }

    private void addInstance(Map<String, RoutingInstance> instances,
                              Map<String, Map<String, String>> vrfVplsList,
                              String name, String type, String rd, String hostEntry) {
        String padded = String.format("%-50s", name);
        String key    = HashUtils.computeKey(padded, type);

        RoutingInstance ri = instances.computeIfAbsent(key, k -> new RoutingInstance());
        ri.name     = name;
        ri.type     = type.toUpperCase();
        ri.rd       = String.format(" [RD:%-11s]", rd);
        ri.hrefname = ri.rd.replaceAll("[\\[\\]\\s+]", "").replace(":", "_");
        ri.hosts.add(hostEntry);

        vrfVplsList.computeIfAbsent(ri.rd, k -> new LinkedHashMap<>())
                   .putIfAbsent("name", ri.name);
        vrfVplsList.get(ri.rd).put("href", ri.hrefname);
    }
}
