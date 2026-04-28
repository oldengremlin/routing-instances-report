package net.ukrcom.routingreport;

import java.util.ArrayList;
import java.util.List;

public class RoutingInstance {
    public String name;
    public String type;
    public String rd;
    public String hrefname;
    public final List<String> hosts = new ArrayList<>();
}
