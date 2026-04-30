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

            NodeList ifaceNodes = (NodeList) xp.evaluate("interface", domain, XPathConstants.NODESET);
            List<String> ifaces = new ArrayList<>();
            for (int j = 0; j < ifaceNodes.getLength(); j++) {
                Node iface = ifaceNodes.item(j);
                String ifaceName = xp.evaluate("name/text()", iface).trim();
                String ifaceInactive = (iface instanceof Element e) ? e.getAttribute("inactive") : "";
                ifaces.add(ifaceName + ("inactive".equals(ifaceInactive) ? "(-)" : ""));
            }

            String routingIface = xp.evaluate("routing-interface/text()", domain).trim();
            String type = routingIface.isEmpty() ? "bridge/l2" : "bridge/l3";

            String hostEntry = routerName
                    + (vlanId.isEmpty() ? "" : ", " + vlanId)
                    + " → " + String.join(", ", ifaces);
            RoutingInstance.merge(instances, vrfVplsList, name, type, "", hostEntry);
        }

        log.info("Parsed {} bridge-domains from {}", domains.getLength(), hostname);
    }
}
