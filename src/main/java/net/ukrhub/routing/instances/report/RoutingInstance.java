package net.ukrhub.routing.instances.report;

import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Accessors(chain = true)
@Log4j2
public class RoutingInstance {

    private String name;
    private String type;
    private String rd;
    private String hrefname;
    private final List<String> hosts = new ArrayList<>();

    static void merge(Map<String, RoutingInstance> instances,
                      Map<String, Map<String, String>> vrfVplsList,
                      String name, String type, String rd, String hostEntry) {
        String padded = String.format("%-50s", name);
        String key = HashUtils.computeKey(padded, type);

        RoutingInstance ri = instances.computeIfAbsent(key, k -> new RoutingInstance());
        String rdStr = rd.isEmpty() ? " ".repeat(17) : String.format(" [RD:%-11s]", rd);
        ri
                .setName(name)
                .setType(type.toUpperCase())
                .setRd(rdStr)
                .setHrefname(rdStr.replaceAll("[\\[\\]\\s+]", "").replace(":", "_"));

        ri.getHosts().add(hostEntry);
        log.debug("Merge: [{}] {} {} @ {}", ri.getType(), name, ri.getRd().strip(), hostEntry);

        if (ri.getRd().contains("RD:")) {
            vrfVplsList.computeIfAbsent(ri.getRd(), k -> new LinkedHashMap<>())
                    .putIfAbsent("name", ri.getName());
            vrfVplsList.get(ri.getRd()).put("href", ri.getHrefname());
        }
    }
}
