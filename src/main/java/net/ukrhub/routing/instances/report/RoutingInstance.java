package net.ukrhub.routing.instances.report;

import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data model for a single routing service instance (VRF, VPLS, L2CIRCUIT,
 * SWITCH, BRIDGE, …).
 *
 * <p>One {@code RoutingInstance} may be present on several routers; each
 * router contributes one formatted string to {@link #hosts}. Lombok
 * {@code @Data} generates getters, setters, {@code equals}, {@code hashCode},
 * and {@code toString}; {@code @Accessors(chain = true)} enables fluent
 * setter chaining.</p>
 */
@Data
@Accessors(chain = true)
@Log4j2
public class RoutingInstance {

    /** Display name (instance name as it appears in router config). */
    private String name;

    /** Uppercase type label shown in the report (e.g. {@code VRF}, {@code VPLS/L3}, {@code L2CIRCUIT}). */
    private String type;

    /**
     * Formatted RD string: {@code " [RD:AS:ID      ]"} for instances with an RD,
     * or 17 spaces for instances without one (SWITCH, L2CIRCUIT, BRIDGE).
     */
    private String rd;

    /**
     * Sanitized anchor name derived from the RD string (colons replaced with
     * underscores, whitespace and brackets removed). For instances without an RD
     * this falls back to the sanitized instance name. Used for HTML anchor targets
     * and the bidirectional links between the index lists and the main table.
     */
    private String hrefname;

    /** One entry per router that hosts this instance, in collection order. */
    private final List<String> hosts = new ArrayList<>();

    /**
     * Inserts or updates a routing instance in the shared result maps.
     *
     * <p>If an instance with the same (name, type) key already exists (i.e. the
     * same service appears on another router), {@code hostEntry} is appended to
     * its {@link #hosts} list. Otherwise a new {@code RoutingInstance} is created.
     * Instances that carry an RD are also registered in {@code vrfVplsList} for
     * the RD index.</p>
     *
     * @param instances    shared map keyed by {@link HashUtils#computeKey}
     * @param vrfVplsList  shared ordered map of RD-string → {name, href}
     * @param name         instance name (unpadded)
     * @param type         instance type (lowercase; stored uppercased)
     * @param rd           route distinguisher string, or {@code ""} if absent
     * @param hostEntry    formatted router description string to append to {@link #hosts}
     */
    static void merge(Map<String, RoutingInstance> instances,
                      Map<String, Map<String, String>> vrfVplsList,
                      String name, String type, String rd, String hostEntry) {
        String padded = String.format("%-50s", name);
        String key = HashUtils.computeKey(padded, type);

        RoutingInstance ri = instances.computeIfAbsent(key, k -> new RoutingInstance());
        String rdStr = rd.isEmpty() ? " ".repeat(17) : String.format(" [RD:%-11s]", rd);
        String hrefname = rdStr.replaceAll("[\\[\\]\\s+]", "").replace(":", "_");
        if (hrefname.isEmpty()) {
            hrefname = name.replaceAll("[^A-Za-z0-9_-]", "_");
        }
        ri
                .setName(name)
                .setType(type.toUpperCase())
                .setRd(rdStr)
                .setHrefname(hrefname);

        ri.getHosts().add(hostEntry);
        log.debug("Merge: [{}] {} {} @ {}", ri.getType(), name, ri.getRd().strip(), hostEntry);

        if (ri.getRd().contains("RD:")) {
            vrfVplsList.computeIfAbsent(ri.getRd(), k -> new LinkedHashMap<>())
                    .putIfAbsent("name", ri.getName());
            vrfVplsList.get(ri.getRd()).put("href", ri.getHrefname());
        }
    }
}
