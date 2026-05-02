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

import com.jcraft.jsch.*;
import lombok.extern.log4j.Log4j2;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/**
 * Collects VRF definitions from MikroTik RouterOS devices via SSH.
 *
 * <p>Executes {@code /ip route vrf export compact} over an SSH exec channel and
 * parses the continuation-line output. RouterOS exports multi-line entries with
 * a trailing backslash ({@code \}) on all but the last line; this collector
 * reassembles them before matching.</p>
 *
 * <p>A complete VRF entry is a line matching:</p>
 * <pre>
 *   /ip route vrf add ... route-distinguisher=AS:ID ... routing-mark=NAME
 * </pre>
 * <p>and is merged with type {@code VRF}.</p>
 */
@Log4j2
public class RouterOSCollector implements Collector {

    private final String login;
    private final String pass;

    /**
     * Creates a new collector with the given SSH credentials.
     *
     * @param login SSH username
     * @param pass  SSH password
     */
    public RouterOSCollector(String login, String pass) {
        this.login = login;
        this.pass = pass;
    }

    @Override
    public void collect(String hostname, Map<String, RoutingInstance> instances,
                        Map<String, Map<String, String>> vrfVplsList) throws Exception {
        log.info("Connecting to {} via SSH", hostname);
        JSch jsch = new JSch();
        Session session = jsch.getSession(login, hostname, 22);
        session.setPassword(pass);
        Properties cfg = new Properties();
        cfg.put("StrictHostKeyChecking", "no");
        cfg.put("PreferredAuthentications", "password,keyboard-interactive");
        session.setConfig(cfg);
        session.connect(30_000);
        log.debug("SSH session established: {}", hostname);

        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand("/ip route vrf export compact");
        InputStream in = channel.getInputStream();
        channel.connect();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        channel.disconnect();
        session.disconnect();

        int before = instances.size();
        parseConfig(hostname, baos.toString(StandardCharsets.UTF_8).split("\r?\n"),
                instances, vrfVplsList);
        log.info("Parsed {} VRF definitions from {}", instances.size() - before, hostname);
    }

    /**
     * Reassembles continuation lines and extracts VRF definitions.
     *
     * <p>Lines starting with {@code #} or empty lines are skipped. A line
     * containing {@code /} and not ending with {@code \} starts a new section
     * context. Continuation lines (ending with {@code \}) are concatenated.
     * The final line of a logical entry is matched against the VRF pattern.</p>
     *
     * @param hostname     router hostname (used as the host entry label)
     * @param lines        output split by line
     * @param instances    shared instances map
     * @param vrfVplsList  shared RD index map
     */
    private void parseConfig(String hostname, String[] lines,
                             Map<String, RoutingInstance> instances,
                             Map<String, Map<String, String>> vrfVplsList) {
        Pattern vrfPat = Pattern.compile(
                "/ip route vrf add .+ route-distinguisher=([^ ]+) routing-mark=(.+)");

        String csect = "";
        String cstr = "";

        for (String rawLine : lines) {
            String s = rawLine.trim();
            if (s.startsWith("#") || s.isEmpty()) {
                continue;
            }

            if (s.contains("/") && !s.endsWith("\\")) {
                csect = s + " ";
                cstr = csect;
            } else if (s.endsWith("\\")) {
                cstr += s.replaceAll("\\\\\\s*$", "");
            } else {
                cstr += s;
                Matcher m = vrfPat.matcher(cstr);
                if (m.find()) {
                    RoutingInstance.merge(instances, vrfVplsList,
                            m.group(2).trim(), "vrf", m.group(1).trim(),
                            hostname.toUpperCase());
                }
                cstr = csect;
            }
        }
    }
}
