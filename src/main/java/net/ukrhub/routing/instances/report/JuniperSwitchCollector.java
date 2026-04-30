package net.ukrhub.routing.instances.report;

import lombok.extern.log4j.Log4j2;
import org.w3c.dom.*;
import javax.xml.xpath.*;
import java.util.*;

/**
 * Collects connections/interface-switch entries from Juniper routers.
 * Reads the XML dump written by JuniperCollector if available, otherwise
 * fetches via NETCONF independently.
 */
@Log4j2
public class JuniperSwitchCollector extends AbstractJuniperCollector {

    public JuniperSwitchCollector(String login, String pass) {
        super(login, pass);
    }

    @Override
    public void collect(String hostname, Map<String, RoutingInstance> instances,
                        Map<String, Map<String, String>> vrfVplsList) throws Exception {
        var doc = parseXml(readOrFetch(hostname));
        XPath xp = XPathFactory.newInstance().newXPath();
        String routerName = extractRouterName(doc, xp, hostname);

        NodeList switches = (NodeList) xp.evaluate(
                "//protocols/connections/interface-switch[not(ancestor::dynamic-profiles)]",
                doc, XPathConstants.NODESET);

        for (int i = 0; i < switches.getLength(); i++) {
            Node sw = switches.item(i);
            String name = xp.evaluate("name/text()", sw).trim();
            String inactive = (sw instanceof Element e) ? e.getAttribute("inactive") : "";

            NodeList ifaceNodes = (NodeList) xp.evaluate(
                    "interface/name/text()", sw, XPathConstants.NODESET);
            List<String> ifaces = new ArrayList<>();
            for (int j = 0; j < ifaceNodes.getLength(); j++) {
                ifaces.add(ifaceNodes.item(j).getNodeValue().trim());
            }

            String hostEntry = routerName
                    + ("inactive".equals(inactive) ? "(-)" : "")
                    + " → " + String.join(", ", ifaces);
            RoutingInstance.merge(instances, vrfVplsList, name, "switch", "", hostEntry);
        }

        log.info("Parsed {} switches from {}", switches.getLength(), hostname);
    }
}
