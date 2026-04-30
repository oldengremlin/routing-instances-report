package net.ukrhub.routing.instances.report;

import java.util.Map;

/**
 * Common contract for all router collectors.
 *
 * <p>Each implementation connects to one router, retrieves its configuration,
 * and populates the shared {@code instances} and {@code vrfVplsList} maps via
 * {@link RoutingInstance#merge}.</p>
 */
public interface Collector {

    /**
     * Collects routing service definitions from the specified router and merges
     * them into the shared result maps.
     *
     * @param hostname     router hostname or IP address to connect to
     * @param instances    shared map keyed by {@link HashUtils#computeKey}; updated in place
     * @param vrfVplsList  shared ordered map of RD-string → {name, href} used to build the RD index
     * @throws Exception   on any transport or parse error (caller logs and continues)
     */
    void collect(String hostname, Map<String, RoutingInstance> instances,
                 Map<String, Map<String, String>> vrfVplsList) throws Exception;
}
