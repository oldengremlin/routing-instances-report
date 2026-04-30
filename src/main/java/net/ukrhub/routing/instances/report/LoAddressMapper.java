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

/**
 * Builds an IP-address → router-name lookup map from Juniper XML dump files.
 *
 * <p>After {@link JuniperCollector} has written {@code /tmp/juniper-HOST.xml}
 * for every host, this class reads those files and extracts all addresses
 * configured on the {@code lo0} loopback interface (both IPv4 and IPv6,
 * across all address families). The resulting map is used by
 * {@link ReportGenerator} to replace bare neighbor IP addresses in host
 * entries with {@code ROUTERNAME/IP}, making circuit endpoints immediately
 * recognisable without consulting a separate address plan.</p>
 *
 * <p>Prefix lengths ({@code /32}, {@code /128}, etc.) are stripped before
 * the address is stored as a key. When the same address appears in multiple
 * dump files, the first one wins ({@link Map#putIfAbsent}).</p>
 */
@Log4j2
class LoAddressMapper {

    /** Matches the {@code -re0} / {@code -re1} routing-engine suffix in JunOS hostnames. */
    private static final Pattern RE_SUFFIX = Pattern.compile("-re\\d+$", Pattern.CASE_INSENSITIVE);

    private LoAddressMapper() {}

    /**
     * Reads {@code /tmp/juniper-HOST.xml} for each host in {@code hosts} and
     * returns a map of loopback IP address → upper-cased router base name.
     *
     * <p>Hosts whose dump file does not exist yet (e.g. collection failed) are
     * silently skipped. Parse errors are logged at WARN level and do not abort
     * processing of the remaining hosts.</p>
     *
     * @param hosts list of Juniper hostnames (same list passed to the collectors)
     * @return      mutable map; empty if no dump files are available
     */
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

    /**
     * Parses one XML dump, resolves the router's base name, then extracts all
     * {@code lo0} addresses and inserts them into {@code result}.
     *
     * <p>XPath used for addresses:
     * <code>//interfaces/interface[name='lo0'][not(ancestor::dynamic-profiles)]
     * /unit/family/&#42;/address/name</code>
     * — covers {@code inet}, {@code inet6}, and any other address family
     * without enumerating them explicitly.</p>
     *
     * @param xml      raw XML string of the configuration dump
     * @param fallback hostname to use when {@code //system/host-name} is absent
     * @param result   map to populate
     * @throws Exception on XML parse or XPath error
     */
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
            String ip = raw.contains("/") ? raw.substring(0, raw.indexOf('/')) : raw;
            if (!ip.isEmpty()) {
                result.putIfAbsent(ip, routerName);
                log.debug("lo0 {} → {}", ip, routerName);
            }
        }
    }
}
