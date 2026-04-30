package net.ukrhub.routing.instances.report;

import lombok.extern.log4j.Log4j2;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

@Log4j2
class LoAddressMapper {

    private static final Pattern RE_SUFFIX = Pattern.compile("-re\\d+$", Pattern.CASE_INSENSITIVE);

    static Map<String, String> build(List<String> hosts) {
        Map<String, String> result = new HashMap<>();
        for (String host : hosts) {
            Path dumpFile = Path.of("/tmp/juniper-" + host + ".xml");
            if (!Files.exists(dumpFile)) {
                log.debug("No dump for lo0 extraction: {}", dumpFile);
                continue;
            }
            try {
                String xml = Files.readString(dumpFile, StandardCharsets.UTF_8);
                processXml(xml, host, result);
            } catch (Exception e) {
                log.warn("lo0 address extraction failed for {}: {}", host, e.getMessage());
            }
        }
        log.debug("Built lo0 address map: {} entries", result.size());
        return result;
    }

    private static void processXml(String xml, String fallback, Map<String, String> result) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setErrorHandler(null);
        Document doc = db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        XPath xp = XPathFactory.newInstance().newXPath();

        NodeList hnNodes = (NodeList) xp.evaluate(
                "//system/host-name[not(ancestor::dynamic-profiles)]", doc, XPathConstants.NODESET);
        Set<String> baseNames = new TreeSet<>();
        for (int i = 0; i < hnNodes.getLength(); i++) {
            String hn = hnNodes.item(i).getTextContent().trim();
            baseNames.add(RE_SUFFIX.matcher(hn).replaceAll(""));
        }
        String routerName = (baseNames.isEmpty() ? fallback : baseNames.iterator().next()).toUpperCase();

        NodeList addrNodes = (NodeList) xp.evaluate(
                "//interfaces/interface[name='lo0'][not(ancestor::dynamic-profiles)]"
                + "/unit/family/*/address/name",
                doc, XPathConstants.NODESET);

        for (int i = 0; i < addrNodes.getLength(); i++) {
            String raw = addrNodes.item(i).getTextContent().trim();
            // strip prefix length (/32, /128, etc.)
            String ip = raw.contains("/") ? raw.substring(0, raw.indexOf('/')) : raw;
            if (!ip.isEmpty()) {
                result.putIfAbsent(ip, routerName);
                log.debug("lo0 {} → {}", ip, routerName);
            }
        }
    }
}
