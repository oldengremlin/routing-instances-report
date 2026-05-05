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

import lombok.extern.log4j.Log4j2;
import org.w3c.dom.*;
import javax.xml.xpath.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects {@code bridge-domains/domain} entries from Juniper routers.
 *
 * <p>Reads the XML dump written by {@link JuniperCollector} if available,
 * otherwise fetches via NETCONF independently. Parses
 * {@code //bridge-domains/domain} nodes, excluding those inside
 * {@code <dynamic-profiles>}.</p>
 *
 * <p>Type mapping:</p>
 * <ul>
 *   <li>Domain without {@code <routing-interface>} → {@code BRIDGE/L2}</li>
 *   <li>Domain with {@code <routing-interface>} → {@code BRIDGE/L3}</li>
 * </ul>
 *
 * <p>Inactive state (attribute {@code inactive="inactive"}) is handled at
 * three levels: the whole {@code domain} node marks the router name with
 * {@code (-)}, a deactivated {@code routing-interface} marks the IRB name,
 * and per-interface inactive marks the individual interface name.</p>
 *
 * <p>Host entry format:
 * {@code ROUTER[(−)][, vlan-id] → [irb[(−)] →] iface1, iface2[(−)], …}</p>
 */
@Log4j2
public class JuniperBridgedomainsCollector extends AbstractJuniperCollector {

    /**
     * Creates a new collector with the given SSH credentials.
     *
     * @param login    SSH username
     * @param pass     SSH password
     * @param xmlCache shared in-memory XML cache populated by {@link JuniperCollector}
     */
    public JuniperBridgedomainsCollector(String login, String pass, ConcurrentHashMap<String, String> xmlCache) {
        super(login, pass, xmlCache);
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
            String inactive = (domain instanceof Element e) ? e.getAttribute("inactive") : "";

            NodeList ifaceNodes = (NodeList) xp.evaluate("interface", domain, XPathConstants.NODESET);
            List<String> ifaces = new ArrayList<>();
            for (int j = 0; j < ifaceNodes.getLength(); j++) {
                Node iface = ifaceNodes.item(j);
                String ifaceName = xp.evaluate("name/text()", iface).trim();
                String ifaceInactive = (iface instanceof Element e) ? e.getAttribute("inactive") : "";
                ifaces.add(ifaceName + ("inactive".equals(ifaceInactive) ? "(-)" : ""));
            }

            Node routingIfaceNode = (Node) xp.evaluate("routing-interface", domain, XPathConstants.NODE);
            String routingIfaceStr = "";
            String routingIfaceInactive = "";
            if (routingIfaceNode != null) {
                routingIfaceStr = routingIfaceNode.getTextContent().trim();
                routingIfaceInactive = (routingIfaceNode instanceof Element e) ? e.getAttribute("inactive") : "";
            }
            String type = routingIfaceStr.isEmpty() ? "bridge/l2" : "bridge/l3";

            String riPart = routingIfaceStr.isEmpty() ? ""
                    : routingIfaceStr + ("inactive".equals(routingIfaceInactive) ? "(-)" : "") + " → ";
            String hostEntry = routerName
                    + ("inactive".equals(inactive) ? "(-)" : "")
                    + (vlanId.isEmpty() ? "" : ", " + vlanId)
                    + " → " + riPart + String.join(", ", ifaces);
            RoutingInstance.merge(instances, vrfVplsList, name, type, "", hostEntry);
        }

        log.info("Parsed {} bridge-domains from {}", domains.getLength(), hostname);
    }
}
