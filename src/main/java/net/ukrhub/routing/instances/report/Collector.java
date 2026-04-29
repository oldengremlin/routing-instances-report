package net.ukrhub.routing.instances.report;

import java.util.Map;

public interface Collector {
    void collect(String hostname, Map<String, RoutingInstance> instances,
                 Map<String, Map<String, String>> vrfVplsList) throws Exception;
}
