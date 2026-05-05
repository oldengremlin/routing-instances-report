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

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSubsystem;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.extern.log4j.Log4j2;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Base class for all Juniper collectors.
 *
 * <p>Provides the NETCONF 1.0 transport over SSH (both {@code subsystem-netconf}
 * and {@code exec} channel modes) and three XML helpers shared by the four
 * concrete subclasses:</p>
 * <ul>
 *   <li>{@link #readOrFetch} — returns a cached {@code /tmp/juniper-HOST.xml}
 *       dump when available, falling back to a live NETCONF fetch;</li>
 *   <li>{@link #parseXml} — parses an XML string into a DOM {@link Document};</li>
 *   <li>{@link #extractRouterName} — resolves the router's base hostname from
 *       {@code //system/host-name}, stripping the {@code -re\d+} routing-engine
 *       suffix.</li>
 * </ul>
 *
 * <p>The channel mode is controlled by the {@code OPENCHANNEL} environment
 * variable ({@code subsystem-netconf} by default, {@code exec} as alternative).
 * JSch log noise is suppressed to WARN regardless of the application log level.</p>
 */
@Log4j2
abstract class AbstractJuniperCollector implements Collector {

    protected static final String DELIM = "]]>]]>";

    /**
     * Directory where Juniper XML configuration dumps are written and read.
     * Defaults to {@code /tmp}; overridden by the {@code DUMP_DIR} environment variable.
     */
    static final String DUMP_DIR;
    static {
        String d = System.getenv("DUMP_DIR");
        DUMP_DIR = (d != null && !d.isBlank()) ? d : "/tmp";
    }

    private static final String NETCONF_HELLO
            = """
            <?xml version="1.0" encoding="UTF-8"?>
            <hello xmlns="urn:ietf:params:xml:ns:netconf:base:1.0">
              <capabilities>
                <capability>urn:ietf:params:netconf:base:1.0</capability>
              </capabilities>
            </hello>""".concat(DELIM);

    private static final String GET_CONFIG_RPC
            = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rpc xmlns="urn:ietf:params:xml:ns:netconf:base:1.0" message-id="1">
              <get-config>
                <source><running/></source>
              </get-config>
            </rpc>""".concat(DELIM);

    private static final String CLOSE_SESSION_RPC
            = """
            <rpc xmlns="urn:ietf:params:xml:ns:netconf:base:1.0" message-id="2">
            <close-session/></rpc>""".concat(DELIM);

    /** Matches the {@code -re0} / {@code -re1} routing-engine suffix in JunOS hostnames. */
    private static final Pattern RE_SUFFIX = Pattern.compile("-re\\d+$", Pattern.CASE_INSENSITIVE);

    private final String login;
    private final String pass;

    AbstractJuniperCollector(String login, String pass) {
        this.login = login;
        this.pass = pass;
    }

    /**
     * Returns the raw XML configuration for {@code hostname}.
     *
     * <p>If {@code /tmp/juniper-HOST.xml} already exists (written by
     * {@link JuniperCollector} earlier in the same run) it is read from disk.
     * Otherwise a live NETCONF session is opened via {@link #fetchNetconf}.</p>
     *
     * @param hostname router hostname
     * @return         full XML string of the running configuration
     * @throws Exception on I/O or SSH error
     */
    protected String readOrFetch(String hostname) throws Exception {
        Path dumpFile = Path.of(DUMP_DIR, "juniper-" + hostname + ".xml");
        if (Files.exists(dumpFile)) {
            log.debug("Using cached dump from {}", dumpFile);
            return Files.readString(dumpFile, StandardCharsets.UTF_8);
        }
        return fetchNetconf(hostname);
    }

    /**
     * Parses an XML string into a namespace-unaware DOM {@link Document}.
     *
     * @param xml XML text (UTF-8)
     * @return    parsed document
     * @throws Exception on XML parse error
     */
    protected Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setErrorHandler(null);
        return db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Extracts the router's canonical base name from {@code //system/host-name}.
     *
     * <p>JunOS dual-RE systems report two host-name nodes ({@code router-re0},
     * {@code router-re1}). Both are collected, the {@code -re\d+} suffix is
     * stripped, duplicates are removed, and the lexicographically first result
     * is returned in upper case. Falls back to {@code fallback} (the DNS
     * hostname) when no host-name element is found.</p>
     *
     * @param doc      parsed configuration document
     * @param xp       XPath instance to reuse
     * @param fallback hostname to use if {@code //system/host-name} is absent
     * @return         upper-cased base router name
     * @throws Exception on XPath evaluation error
     */
    protected String extractRouterName(Document doc, XPath xp, String fallback) throws Exception {
        NodeList nodes = (NodeList) xp.evaluate(
                "//system/host-name[not(ancestor::dynamic-profiles)]", doc, XPathConstants.NODESET);
        Set<String> baseNames = new TreeSet<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            String hn = nodes.item(i).getTextContent().trim();
            baseNames.add(RE_SUFFIX.matcher(hn).replaceAll(""));
        }
        return (baseNames.isEmpty() ? fallback : baseNames.iterator().next()).toUpperCase();
    }

    /**
     * Opens an SSH connection to {@code hostname}, runs a NETCONF session
     * (RFC 6241, NETCONF 1.0 framing), retrieves the full running configuration,
     * and returns it as a raw XML string.
     *
     * <p>The channel type is selected by the {@code OPENCHANNEL} environment
     * variable: {@code subsystem-netconf} (default) opens a proper NETCONF
     * subsystem; {@code exec} runs {@code xml-mode netconf need-trailer} instead,
     * which is useful when the NETCONF subsystem is not available.</p>
     *
     * @param hostname router hostname
     * @return         XML text of the {@code <rpc-reply>} containing running config
     * @throws Exception on SSH, channel, or I/O error
     */
    protected String fetchNetconf(String hostname) throws Exception {
        return fetchRpcs(hostname, List.of(GET_CONFIG_RPC)).get(0);
    }

    /**
     * Opens an SSH/NETCONF session to {@code hostname}, exchanges hellos,
     * sends each RPC in order, reads the corresponding reply for each, then
     * closes the session.
     *
     * <p>Use this when more than one operational RPC must be sent to the same
     * router in a single SSH connection (e.g. {@code get-l2ckt-connection-information}
     * and {@code get-vpls-connection-information}).</p>
     *
     * @param hostname router hostname
     * @param rpcs     list of RPC strings, each already terminated with
     *                 the NETCONF 1.0 {@code ]]>]]>} delimiter
     * @return         list of raw RPC-reply strings, one per entry in {@code rpcs}
     * @throws Exception on SSH, channel, or I/O error
     */
    protected List<String> fetchRpcs(String hostname, List<String> rpcs) throws Exception {
        StringBuilder leftover = new StringBuilder();
        log.info("Connecting to {} via NETCONF/SSH", hostname);
        JSch jsch = new JSch();
        Session session = jsch.getSession(login, hostname, 22);
        session.setPassword(pass);
        Properties cfg = new Properties();
        cfg.put("StrictHostKeyChecking", "no");
        cfg.put("PreferredAuthentications", "password,keyboard-interactive");
        session.setConfig(cfg);
        session.connect(30_000);

        String mode = System.getenv("OPENCHANNEL");
        if (mode == null || mode.isBlank()) {
            mode = "subsystem-netconf";
        }

        Channel channel;
        OutputStream out;
        InputStream in;

        if ("exec".equals(mode)) {
            ChannelExec exec = (ChannelExec) session.openChannel("exec");
            exec.setCommand("xml-mode netconf need-trailer");
            out = exec.getOutputStream();
            in = exec.getInputStream();
            exec.connect(15_000);
            channel = exec;
        } else {
            ChannelSubsystem sub = (ChannelSubsystem) session.openChannel("subsystem");
            sub.setSubsystem("netconf");
            out = sub.getOutputStream();
            in = sub.getInputStream();
            sub.connect(15_000);
            channel = sub;
        }
        log.debug("NETCONF channel ({}) established: {}", mode, hostname);

        try {
            readUntilDelimiter(in, leftover);
            send(out, NETCONF_HELLO);
            List<String> responses = new ArrayList<>();
            for (String rpc : rpcs) {
                send(out, rpc);
                responses.add(readUntilDelimiter(in, leftover));
            }
            send(out, CLOSE_SESSION_RPC);
            return responses;
        } finally {
            channel.disconnect();
            session.disconnect();
            log.debug("NETCONF session closed: {}", hostname);
        }
    }

    /**
     * Writes {@code msg} to the NETCONF output stream and flushes.
     *
     * @param out SSH channel output stream
     * @param msg NETCONF RPC or hello message (already includes {@code ]]>]]>} delimiter)
     */
    private void send(OutputStream out, String msg) throws IOException {
        out.write(msg.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Reads from {@code in} until the NETCONF 1.0 {@code ]]>]]>} delimiter is
     * encountered and returns everything before it.
     *
     * <p>Any bytes read after the delimiter are stored in {@code leftover} and
     * prepended to the next call's buffer, ensuring no data is lost between
     * consecutive RPC exchanges on the same channel. {@code leftover} is
     * allocated per-{@link #fetchRpcs} call so concurrent sessions on the
     * same collector instance do not interfere.</p>
     *
     * @param in       SSH channel input stream
     * @param leftover carry-over buffer shared across calls within one session
     * @return         text preceding the delimiter
     * @throws IOException on read error or unexpected EOF
     */
    private String readUntilDelimiter(InputStream in, StringBuilder leftover) throws IOException {
        StringBuilder sb = new StringBuilder(65536);
        sb.append(leftover);
        leftover.setLength(0);

        byte[] buf = new byte[8192];
        int idx = sb.indexOf(DELIM);
        while (idx < 0) {
            int n = in.read(buf);
            if (n == -1) {
                break;
            }
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            idx = sb.indexOf(DELIM);
        }

        if (idx >= 0) {
            leftover.append(sb, idx + DELIM.length(), sb.length());
            return sb.substring(0, idx);
        }
        return sb.toString();
    }
}
