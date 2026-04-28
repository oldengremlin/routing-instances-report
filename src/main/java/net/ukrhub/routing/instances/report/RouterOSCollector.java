package net.ukrhub.routing.instances.report;

import com.jcraft.jsch.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/**
 * Collects VRF definitions from MikroTik RouterOS via SSH.
 * Executes "/ip route vrf export compact" and parses continuation-line output.
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

        parseConfig(hostname, baos.toString(StandardCharsets.UTF_8).split("\r?\n"),
                instances, vrfVplsList);
    }

    private void parseConfig(String hostname, String[] lines,
                              Map<String, RoutingInstance> instances,
                              Map<String, Map<String, String>> vrfVplsList) {
        Pattern vrfPat = Pattern.compile(
                "/ip route vrf add .+ route-distinguisher=([^ ]+) routing-mark=(.+)");

        String csect = "";
        String cstr  = "";

        for (String rawLine : lines) {
            String s = rawLine.trim();
            if (s.startsWith("#") || s.isEmpty()) continue;

            if (s.contains("/") && !s.endsWith("\\")) {
                csect = s + " ";
                cstr  = csect;
            } else if (s.endsWith("\\")) {
                cstr += s.replaceAll("\\\\\\s*$", "");
            } else {
                cstr += s;
                Matcher m = vrfPat.matcher(cstr);
                if (m.find()) {
                    JuniperCollector.merge(instances, vrfVplsList,
                            m.group(2).trim(), "vrf", m.group(1).trim(),
                            hostname.toUpperCase());
                }
                cstr = csect;
            }
        }
    }
}
