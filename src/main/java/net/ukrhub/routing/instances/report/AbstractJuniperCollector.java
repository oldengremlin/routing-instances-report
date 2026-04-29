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
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

@Log4j2
abstract class AbstractJuniperCollector implements Collector {

    private static final String DELIM = "]]>]]>";

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

    private static final Pattern RE_SUFFIX = Pattern.compile("-re\\d+$", Pattern.CASE_INSENSITIVE);

    private final String login;
    private final String pass;
    private final StringBuilder leftover = new StringBuilder();

    AbstractJuniperCollector(String login, String pass) {
        this.login = login;
        this.pass = pass;
    }

    /** Returns cached XML dump if available, otherwise fetches via NETCONF. */
    protected String readOrFetch(String hostname) throws Exception {
        Path dumpFile = Path.of("/tmp/juniper-" + hostname + ".xml");
        if (Files.exists(dumpFile)) {
            log.debug("Using cached dump from {}", dumpFile);
            return Files.readString(dumpFile, StandardCharsets.UTF_8);
        }
        return fetchNetconf(hostname);
    }

    protected Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setErrorHandler(null);
        return db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** Extracts router base name from system/host-name, stripping -re\d+ suffix. */
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

    protected String fetchNetconf(String hostname) throws Exception {
        leftover.setLength(0);
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

        readUntilDelimiter(in);
        send(out, NETCONF_HELLO);
        send(out, GET_CONFIG_RPC);
        String response = readUntilDelimiter(in);
        send(out, CLOSE_SESSION_RPC);

        channel.disconnect();
        session.disconnect();
        log.debug("NETCONF session closed: {}", hostname);

        return response;
    }

    private void send(OutputStream out, String msg) throws IOException {
        out.write(msg.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private String readUntilDelimiter(InputStream in) throws IOException {
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
