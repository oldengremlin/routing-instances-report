package net.ukrhub.routing.instances.report;

import lombok.extern.log4j.Log4j2;
import org.w3c.dom.*;
import javax.xml.xpath.*;
import java.util.*;

/**
 * Collects bridge-domains/domain entries from Juniper routers.
 * Reads the XML dump written by JuniperCollector if available, otherwise
 * fetches via NETCONF independently.
 */
@Log4j2
public class JuniperBridgedomainsCollector extends AbstractJuniperCollector {

    public JuniperBridgedomainsCollector(String login, String pass) {
        super(login, pass);
    }

    @Override
    public void collect(String hostname, Map<String, RoutingInstance> instances,
                        Map<String, Map<String, String>> vrfVplsList) throws Exception {
        var doc = parseXml(readOrFetch(hostname));
        XPath xp = XPathFactory.newInstance().newXPath();
        String routerName = extractRouterName(doc, xp, hostname);

        NodeList domains = (NodeList) xp.evaluate(
                "//bridge-domains/domain[not(ancestor::dynamic-profiles)]",
                doc, XPathConstants.NODESET);

        for (int i = 0; i < domains.getLength(); i++) {
            Node domain = domains.item(i);
            String name = xp.evaluate("name/text()", domain).trim();
            String vlanId = xp.evaluate("vlan-id/text()", domain).trim();

            NodeList ifaceNodes = (NodeList) xp.evaluate(
                    "interface/name/text()", domain, XPathConstants.NODESET);
            List<String> ifaces = new ArrayList<>();
            for (int j = 0; j < ifaceNodes.getLength(); j++) {
                ifaces.add(ifaceNodes.item(j).getNodeValue().trim());
            }

            String hostEntry = routerName
                    + (vlanId.isEmpty() ? "" : ", " + vlanId)
                    + "<br>" + String.join(", ", ifaces);
            RoutingInstance.merge(instances, vrfVplsList, name, "bridge-domains", "", hostEntry);
        }

        log.info("Parsed {} bridge-domains from {}", domains.getLength(), hostname);
    }
}
