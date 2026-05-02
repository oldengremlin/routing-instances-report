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

/**
 * Collects {@code connections/interface-switch} entries from Juniper routers.
 *
 * <p>Reads the XML dump written by {@link JuniperCollector} if available,
 * otherwise fetches via NETCONF independently. Parses
 * {@code //protocols/connections/interface-switch} nodes, excluding those
 * inside {@code <dynamic-profiles>}.</p>
 *
 * <p>Each switch is recorded with type {@code SWITCH}. A deactivated
 * {@code interface-switch} node (attribute {@code inactive="inactive"}) is
 * marked with {@code (-)} after the router name.</p>
 *
 * <p>Host entry format: {@code ROUTER[(−)] → iface1, iface2, …}</p>
 */
@Log4j2
public class JuniperSwitchCollector extends AbstractJuniperCollector {

    /**
     * Creates a new collector with the given SSH credentials.
     *
     * @param login SSH username
     * @param pass  SSH password
     */
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
