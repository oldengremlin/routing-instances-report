package net.ukrhub.routing.instances.report;

import lombok.extern.log4j.Log4j2;
import org.w3c.dom.*;
import javax.xml.xpath.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Collects routing instances from Juniper routers via NETCONF over SSH.
 *
 * <p>Fetches the full running configuration, saves it to
 * {@code /tmp/juniper-HOST.xml} for reuse by the other Juniper collectors in
 * the same run, then parses {@code //routing-instances/instance} nodes
 * (excluding those inside {@code <dynamic-profiles>}).</p>
 *
 * <p>Type mapping:</p>
 * <ul>
 *   <li>{@code vrf} → {@code VRF}</li>
 *   <li>{@code vpls} without {@code <routing-interface>} → {@code VPLS/L2}</li>
 *   <li>{@code vpls} with {@code <routing-interface>} → {@code VPLS/L3}</li>
 * </ul>
 *
 * <p>For VPLS instances an additional secondary row is emitted when LDP
 * neighbors and a VPLS-ID are present, keyed as
 * {@code vpls-id/ROUTER (instance-name)}, so related circuits appear
 * together in the sorted table.</p>
 *
 * <p>Deactivated sections carry the {@code inactive="inactive"} XML attribute
 * and are marked with {@code (-)} in the output.</p>
 */
@Log4j2
public class JuniperCollector extends AbstractJuniperCollector {

    public JuniperCollector(String login, String pass) {
        super(login, pass);
    }

    /**
     * Fetches and parses all routing instances for {@code hostname}.
     *
     * <p>Host entry format varies by type:</p>
     * <ul>
     *   <li>VRF: {@code ROUTER[(−)] [→ iface1, iface2[(−)]]}</li>
     *   <li>VPLS/L2: {@code ROUTER[:siteId][(−)] [(vpls-id)][(vlan-id)] [→ iface1, …] [→ neighbor, …]}</li>
     *   <li>VPLS/L3: {@code ROUTER[:siteId][(−)] [(vpls-id)][(vlan-id)] → irb[(−)] → iface1, … [→ neighbor, …]}</li>
     * </ul>
     */
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
            String routingIfaceStr = "";
            String routingIfaceInactive = "";
            String vplsId = "";
            String vlanId = "";
            List<String> ldpNeighbors = new ArrayList<>();
            if ("vpls".equals(type)) {
                if (!rd.isEmpty()) {
                    siteId = xp.evaluate("protocols/vpls/site/site-identifier/text()", ri).trim();
                }
                Node routingIfaceNode = (Node) xp.evaluate("routing-interface", ri, XPathConstants.NODE);
                if (routingIfaceNode != null) {
                    routingIfaceStr = routingIfaceNode.getTextContent().trim();
                    routingIfaceInactive = (routingIfaceNode instanceof Element e) ? e.getAttribute("inactive") : "";
                }
                vplsId = xp.evaluate("protocols/vpls/vpls-id/text()", ri).trim();
                vlanId = xp.evaluate("vlan-id/text()", ri).trim();
                NodeList neighborNodes = (NodeList) xp.evaluate(
                        "protocols/vpls/neighbor | protocols/vpls/mesh-group/neighbor",
                        ri, XPathConstants.NODESET);
                for (int j = 0; j < neighborNodes.getLength(); j++) {
                    String nip = xp.evaluate("name/text()", neighborNodes.item(j)).trim();
                    if (!nip.isEmpty()) ldpNeighbors.add(nip);
                }
                type = routingIfaceStr.isEmpty() ? "vpls/l2" : "vpls/l3";
            }

            NodeList ifaceNodes = (NodeList) xp.evaluate("interface", ri, XPathConstants.NODESET);
            List<String> ifaces = new ArrayList<>();
            for (int j = 0; j < ifaceNodes.getLength(); j++) {
                Node iface = ifaceNodes.item(j);
                String ifaceName = xp.evaluate("name/text()", iface).trim();
                String ifaceInactive = (iface instanceof Element e) ? e.getAttribute("inactive") : "";
                ifaces.add(ifaceName + ("inactive".equals(ifaceInactive) ? "(-)" : ""));
            }

            String idsPart = (vplsId.isEmpty() ? "" : " (" + vplsId + ")")
                    + (vlanId.isEmpty() ? "" : " (" + vlanId + ")");
            String riPart = routingIfaceStr.isEmpty() ? ""
                    : " → " + routingIfaceStr + ("inactive".equals(routingIfaceInactive) ? "(-)" : "");
            String neighborsPart = ldpNeighbors.isEmpty() ? "" : " → " + String.join(", ", ldpNeighbors);
            String hostEntry = hostname.toUpperCase()
                    + (!siteId.isEmpty() ? ":" + siteId : "")
                    + ("inactive".equals(inactive) ? "(-)" : "")
                    + idsPart
                    + riPart
                    + (!ifaces.isEmpty() ? " → " + String.join(", ", ifaces) : "")
                    + neighborsPart;

            RoutingInstance.merge(instances, vrfVplsList, name, type, rd, hostEntry);
            if (!ldpNeighbors.isEmpty() && !vplsId.isEmpty()) {
                RoutingInstance.merge(instances, vrfVplsList,
                        vplsId + "/" + hostname.toUpperCase() + " (" + name + ")", type, rd, hostEntry);
            }
        }

        log.info("Parsed {} routing instances from {}", riNodes.getLength(), hostname);
    }
}
