package net.ukrhub.routing.instances.report;

import com.jcraft.jsch.*;
import lombok.extern.log4j.Log4j2;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.xml.xpath.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Collects routing instances from Juniper routers via NETCONF over SSH.
 * Uses NETCONF 1.0 framing (]]>]]> delimiter).
 */
@Log4j2
public class JuniperCollector {

    private static final String NETCONF_HELLO =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<hello xmlns=\"urn:ietf:params:xml:ns:netconf:base:1.0\">\n" +
            "  <capabilities>\n" +
            "    <capability>urn:ietf:params:netconf:base:1.0</capability>\n" +
            "  </capabilities>\n" +
            "</hello>\n]]>]]>";

    private static final String GET_CONFIG_RPC =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<rpc xmlns=\"urn:ietf:params:xml:ns:netconf:base:1.0\" message-id=\"1\">\n" +
            "  <get-config>\n" +
            "    <source><running/></source>\n" +
            "  </get-config>\n" +
            "</rpc>\n]]>]]>";

    private static final String CLOSE_SESSION_RPC =
            "<rpc xmlns=\"urn:ietf:params:xml:ns:netconf:base:1.0\" message-id=\"2\">" +
            "<close-session/></rpc>]]>]]>";

    private static final String DELIM = "]]>]]>";

    private final String login;
    private final String pass;
    private final StringBuilder leftover = new StringBuilder();

    public JuniperCollector(String login, String pass) {
        this.login = login;
        this.pass  = pass;
    }

    public void collect(String hostname, Map<String, RoutingInstance> instances,
                        Map<String, Map<String, String>> vrfVplsList) throws Exception {
        String xmlResponse = fetchNetconf(hostname);

        Path dumpFile = Path.of("/tmp/juniper-" + hostname + ".xml");
        Files.writeString(dumpFile, xmlResponse, StandardCharsets.UTF_8);
        log.debug("Configuration saved to {}", dumpFile);

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setErrorHandler(null);
        Document doc = db.parse(new ByteArrayInputStream(xmlResponse.getBytes(StandardCharsets.UTF_8)));

        XPath xp = XPathFactory.newInstance().newXPath();
        NodeList riNodes = (NodeList) xp.evaluate(
                "//routing-instances/instance", doc, XPathConstants.NODESET);

        for (int i = 0; i < riNodes.getLength(); i++) {
            Node ri = riNodes.item(i);

            String name     = xp.evaluate("name/text()", ri).trim();
            String type     = xp.evaluate("instance-type/text()", ri).trim();
            String rd       = xp.evaluate("route-distinguisher/rd-type/text()", ri).trim();
            String inactive = (ri instanceof Element e) ? e.getAttribute("inactive") : "";

            String siteId = "";
            if ("vpls".equals(type) && !rd.isEmpty()) {
                siteId = xp.evaluate("protocols/vpls/site/site-identifier/text()", ri).trim();
            }

            String hostEntry = hostname.toUpperCase()
                    + (!siteId.isEmpty()          ? ":" + siteId : "")
                    + ("inactive".equals(inactive) ? "(-)"       : "");

            merge(instances, vrfVplsList, name, type, rd, hostEntry);
        }

        log.info("Parsed {} routing instances from {}", riNodes.getLength(), hostname);
    }

    private String fetchNetconf(String hostname) throws Exception {
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

        ChannelSubsystem channel = (ChannelSubsystem) session.openChannel("subsystem");
        channel.setSubsystem("netconf");
        OutputStream out = channel.getOutputStream();
        InputStream  in  = channel.getInputStream();
        channel.connect(15_000);
        log.debug("NETCONF session established: {}", hostname);

        readUntilDelimiter(in);       // consume server hello
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
            if (n == -1) break;
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            idx = sb.indexOf(DELIM);
        }

        if (idx >= 0) {
            leftover.append(sb, idx + DELIM.length(), sb.length());
            return sb.substring(0, idx);
        }
        return sb.toString();
    }

    static void merge(Map<String, RoutingInstance> instances,
                      Map<String, Map<String, String>> vrfVplsList,
                      String name, String type, String rd, String hostEntry) {
        String padded = String.format("%-50s", name);
        String key    = HashUtils.computeKey(padded, type);

        RoutingInstance ri = instances.computeIfAbsent(key, k -> new RoutingInstance());
        ri.setName(name);
        ri.setType(type.toUpperCase());
        ri.setRd(rd.isEmpty() ? " ".repeat(17) : String.format(" [RD:%-11s]", rd));
        ri.setHrefname(ri.getRd().replaceAll("[\\[\\]\\s+]", "").replace(":", "_"));
        ri.getHosts().add(hostEntry);
        log.debug("Merge: [{}] {} {} @ {}", ri.getType(), name, ri.getRd().strip(), hostEntry);

        if (ri.getRd().contains("RD:")) {
            vrfVplsList.computeIfAbsent(ri.getRd(), k -> new LinkedHashMap<>())
                       .putIfAbsent("name", ri.getName());
            vrfVplsList.get(ri.getRd()).put("href", ri.getHrefname());
        }
    }
}
