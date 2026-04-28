package net.ukrcom.routingreport;

import com.jcraft.jsch.*;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.xml.xpath.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Collects routing instances from Juniper routers via NETCONF over SSH.
 * NETCONF 1.0 framing is used (]]>]]> delimiter).
 */
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

    private final String login;
    private final String pass;

    public JuniperCollector(String login, String pass) {
        this.login = login;
        this.pass = pass;
    }

    public void collect(String hostname, Map<String, RoutingInstance> instances,
                        Map<String, Map<String, String>> vrfVplsList) throws Exception {
        String xmlResponse = fetchNetconf(hostname);

        Files.writeString(Path.of("/tmp/juniper-" + hostname + ".xml"),
                xmlResponse, StandardCharsets.UTF_8);

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

            String instance     = xp.evaluate("name/text()", ri).trim();
            String instancetype = xp.evaluate("instance-type/text()", ri).trim();
            String instancerd   = xp.evaluate("route-distinguisher/rd-type/text()", ri).trim();
            String inactive     = (ri instanceof Element) ? ((Element) ri).getAttribute("inactive") : "";

            String siteId = "";
            if ("vpls".equals(instancetype) && !instancerd.isEmpty()) {
                siteId = xp.evaluate("protocols/vpls/site/site-identifier/text()", ri).trim();
            }

            String hostEntry = hostname.toUpperCase()
                    + (!siteId.isEmpty()         ? ":" + siteId : "")
                    + ("inactive".equals(inactive) ? "(-)"       : "");

            addInstance(instances, vrfVplsList, instance, instancetype, instancerd, hostEntry);
        }
    }

    private String fetchNetconf(String hostname) throws Exception {
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

        readUntilDelimiter(in);             // consume server hello
        send(out, NETCONF_HELLO);
        send(out, GET_CONFIG_RPC);
        String response = readUntilDelimiter(in);
        send(out, CLOSE_SESSION_RPC);

        channel.disconnect();
        session.disconnect();
        return response;
    }

    private void send(OutputStream out, String msg) throws IOException {
        out.write(msg.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private String readUntilDelimiter(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(65536);
        byte[] buf = new byte[8192];
        String delim = "]]>]]>";
        while (true) {
            int n = in.read(buf);
            if (n == -1) break;
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            int idx = sb.indexOf(delim);
            if (idx >= 0) return sb.substring(0, idx);
        }
        return sb.toString();
    }

    private void addInstance(Map<String, RoutingInstance> instances,
                              Map<String, Map<String, String>> vrfVplsList,
                              String name, String type, String rd, String hostEntry) {
        String padded = String.format("%-50s", name);
        String key    = HashUtils.computeKey(padded, type);

        RoutingInstance ri = instances.computeIfAbsent(key, k -> new RoutingInstance());
        ri.name     = name;
        ri.type     = type.toUpperCase();
        ri.rd       = rd.isEmpty() ? " ".repeat(17) : String.format(" [RD:%-11s]", rd);
        ri.hrefname = ri.rd.replaceAll("[\\[\\]\\s+]", "").replace(":", "_");
        ri.hosts.add(hostEntry);

        if (ri.rd.contains("RD:")) {
            vrfVplsList.computeIfAbsent(ri.rd, k -> new LinkedHashMap<>())
                       .putIfAbsent("name", ri.name);
            vrfVplsList.get(ri.rd).put("href", ri.hrefname);
        }
    }
}
