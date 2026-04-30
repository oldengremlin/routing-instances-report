package net.ukrhub.routing.instances.report;

import lombok.extern.log4j.Log4j2;
import org.w3c.dom.*;
import javax.xml.xpath.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Collects routing instances from Juniper routers via NETCONF over SSH. Uses
 * NETCONF 1.0 framing (]]>]]> delimiter).
 */
@Log4j2
public class JuniperCollector extends AbstractJuniperCollector {

    public JuniperCollector(String login, String pass) {
        super(login, pass);
    }

    @Override
    public void collect(String hostname, Map<String, RoutingInstance> instances,
                        Map<String, Map<String, String>> vrfVplsList) throws Exception {
        String xmlResponse = fetchNetconf(hostname);

        Path dumpFile = Path.of("/tmp/juniper-" + hostname + ".xml");
        Files.writeString(dumpFile, xmlResponse, StandardCharsets.UTF_8);
        log.debug("Configuration saved to {}", dumpFile);

        var doc = parseXml(xmlResponse);
        XPath xp = XPathFactory.newInstance().newXPath();
        NodeList riNodes = (NodeList) xp.evaluate(
                "//routing-instances/instance[not(ancestor::dynamic-profiles)]", doc, XPathConstants.NODESET);

        for (int i = 0; i < riNodes.getLength(); i++) {
            Node ri = riNodes.item(i);

            String name = xp.evaluate("name/text()", ri).trim();
            String type = xp.evaluate("instance-type/text()", ri).trim();
            String rd = xp.evaluate("route-distinguisher/rd-type/text()", ri).trim();
            String inactive = (ri instanceof Element e) ? e.getAttribute("inactive") : "";

            String siteId = "";
            if ("vpls".equals(type)) {
                if (!rd.isEmpty()) {
                    siteId = xp.evaluate("protocols/vpls/site/site-identifier/text()", ri).trim();
                }
                String routingIface = xp.evaluate("routing-interface/text()", ri).trim();
                type = routingIface.isEmpty() ? "vpls/l2" : "vpls/l3";
            }

            String hostEntry = hostname.toUpperCase()
                    + (!siteId.isEmpty() ? ":" + siteId : "")
                    + ("inactive".equals(inactive) ? "(-)" : "");

            RoutingInstance.merge(instances, vrfVplsList, name, type, rd, hostEntry);
        }

        log.info("Parsed {} routing instances from {}", riNodes.getLength(), hostname);
    }
}
