package net.ukrhub.routing.instances.report;

import lombok.extern.log4j.Log4j2;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.xml.xpath.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Collects connections/interface-switch entries from Juniper routers.
 * Reads the XML dump written by JuniperCollector if available, otherwise
 * fetches via NETCONF independently.
 */
@Log4j2
public class JuniperConnectionsCollector extends AbstractJuniperCollector {

    private static final Pattern RE_SUFFIX = Pattern.compile("-re\\d+$", Pattern.CASE_INSENSITIVE);

    public JuniperConnectionsCollector(String login, String pass) {
        super(login, pass);
    }

    @Override
    public void collect(String hostname, Map<String, RoutingInstance> instances,
                        Map<String, Map<String, String>> vrfVplsList) throws Exception {
        Path dumpFile = Path.of("/tmp/juniper-" + hostname + ".xml");
        String xmlResponse;
        if (Files.exists(dumpFile)) {
            xmlResponse = Files.readString(dumpFile, StandardCharsets.UTF_8);
            log.debug("Using cached dump from {}", dumpFile);
        } else {
            xmlResponse = fetchNetconf(hostname);
        }

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setErrorHandler(null);
        Document doc = db.parse(new ByteArrayInputStream(xmlResponse.getBytes(StandardCharsets.UTF_8)));

        XPath xp = XPathFactory.newInstance().newXPath();

        NodeList hostNameNodes = (NodeList) xp.evaluate(
                "//system/host-name[not(ancestor::dynamic-profiles)]", doc, XPathConstants.NODESET);
        Set<String> baseNames = new TreeSet<>();
        for (int i = 0; i < hostNameNodes.getLength(); i++) {
            String hn = hostNameNodes.item(i).getTextContent().trim();
            baseNames.add(RE_SUFFIX.matcher(hn).replaceAll(""));
        }
        String routerName = (baseNames.isEmpty() ? hostname : baseNames.iterator().next()).toUpperCase();

        NodeList switches = (NodeList) xp.evaluate(
                "//protocols/connections/interface-switch[not(ancestor::dynamic-profiles)]",
                doc, XPathConstants.NODESET);

        for (int i = 0; i < switches.getLength(); i++) {
            Node sw = switches.item(i);
            String name = xp.evaluate("name/text()", sw).trim();

            NodeList ifaceNodes = (NodeList) xp.evaluate(
                    "interface/name/text()", sw, XPathConstants.NODESET);
            List<String> ifaces = new ArrayList<>();
            for (int j = 0; j < ifaceNodes.getLength(); j++) {
                ifaces.add(ifaceNodes.item(j).getNodeValue().trim());
            }

            String hostEntry = routerName + "<br>" + String.join(", ", ifaces);
            RoutingInstance.merge(instances, vrfVplsList, name, "connections", "", hostEntry);
        }

        log.info("Parsed {} connections from {}", switches.getLength(), hostname);
    }
}
