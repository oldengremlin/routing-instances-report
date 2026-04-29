package net.ukrhub.routing.instances.report;

import lombok.extern.log4j.Log4j2;
import org.w3c.dom.*;
import javax.xml.xpath.*;
import java.util.*;

/**
 * Collects protocols/l2circuit/neighbor/interface entries from Juniper routers.
 * Reads the XML dump written by JuniperCollector if available, otherwise
 * fetches via NETCONF independently.
 */
@Log4j2
public class JuniperL2circuitCollector extends AbstractJuniperCollector {

    public JuniperL2circuitCollector(String login, String pass) {
        super(login, pass);
    }

    @Override
    public void collect(String hostname, Map<String, RoutingInstance> instances,
                        Map<String, Map<String, String>> vrfVplsList) throws Exception {
        var doc = parseXml(readOrFetch(hostname));
        XPath xp = XPathFactory.newInstance().newXPath();
        String routerName = extractRouterName(doc, xp, hostname);

        NodeList neighbors = (NodeList) xp.evaluate(
                "//protocols/l2circuit/neighbor[not(ancestor::dynamic-profiles)]",
                doc, XPathConstants.NODESET);

        int total = 0;
        for (int i = 0; i < neighbors.getLength(); i++) {
            Node neighbor = neighbors.item(i);
            String neighborIp = xp.evaluate("name/text()", neighbor).trim();

            NodeList ifaces = (NodeList) xp.evaluate("interface", neighbor, XPathConstants.NODESET);
            for (int j = 0; j < ifaces.getLength(); j++) {
                Node iface = ifaces.item(j);
                String ifaceName = xp.evaluate("name/text()", iface).trim();
                String vcId = xp.evaluate("virtual-circuit-id/text()", iface).trim();

                String hostEntry = routerName + ", " + ifaceName + " → " + neighborIp;
                RoutingInstance.merge(instances, vrfVplsList, vcId, "l2circuit", "", hostEntry);
                total++;
            }
        }

        log.info("Parsed {} l2circuits from {}", total, hostname);
    }
}
