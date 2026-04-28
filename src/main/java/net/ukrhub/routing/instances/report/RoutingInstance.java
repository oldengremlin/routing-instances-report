package net.ukrhub.routing.instances.report;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class RoutingInstance {
    private String name;
    private String type;
    private String rd;
    private String hrefname;
    private final List<String> hosts = new ArrayList<>();
}
