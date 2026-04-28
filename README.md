# routing-instances-report

Collects VRF/VPLS routing instance definitions from network routers
(Juniper JunOS, Cisco IOS, MikroTik RouterOS) and publishes a live HTML
report served by nginx.

## Why

Large service-provider networks carry dozens or hundreds of VRFs and VPLS
circuits spread across multiple routers of different vendors. Keeping track of
which VRF lives on which router — and what its Route Distinguisher is — becomes
a chore. This tool polls every router once a day, merges the data into a single
indexed HTML page, and lets anyone on the NOC team open a browser and instantly
see the full picture.

## How it works

```
┌─────────────────────────────────────────────────────┐
│  Docker container                                   │
│                                                     │
│  ┌──────────────────────────┐   ┌─────────────────┐ │
│  │  routing-instances-report│   │      nginx      │ │
│  │  (Java, runs once/day)   │──▶│  serves HTML    │ │
│  │                          │   │  on port 80     │ │
│  │  Juniper  ← NETCONF/SSH  │   └─────────────────┘ │
│  │  Cisco    ← Telnet       │                       │
│  │  RouterOS ← SSH exec     │                       │
│  └──────────────────────────┘                       │
└─────────────────────────────────────────────────────┘
```

1. **Juniper JunOS** — connects via SSH, establishes a NETCONF session
   (RFC 6241, NETCONF 1.0 framing `]]>]]>`), fetches the running
   configuration and extracts `//routing-instances/instance` nodes.

2. **Cisco IOS** — connects via Telnet, logs in, enters enable mode,
   runs `show running-config`, and parses `ip vrf` / `rd` blocks.

3. **MikroTik RouterOS** — connects via SSH, runs
   `/ip route vrf export compact`, and parses the continuation-line output.

All data is merged by a composite key (padded instance name + MD5 + SHA-1,
matching the original Perl implementation) so the same VRF appearing on
multiple routers is shown as one row with all routers listed.

The resulting HTML contains two sections:
- an **ordered list** of VRFs sorted by RD numeric product (AS × ID), with
  bidirectional anchor links;
- a **table** with type, name, RD, and the list of routers that carry it.

The raw XML/config dumps saved to `/tmp/juniper-<host>.xml` and
`/tmp/cisco-<host>.conf` are useful for debugging.

## Logging

The application uses [Log4j2](https://logging.apache.org/log4j/2.x/) (via Lombok `@Log4j2`).
All output goes to **stdout** so Docker captures it with `docker logs`.

### Log levels

| Level   | What is logged |
|---------|----------------|
| `INFO`  | Startup host list · connecting to each router · parsed instance count · every row of the final table · report file path |
| `DEBUG` | NETCONF session open/close · SSH/Telnet session established · every individual `merge` (name / type / RD / router) · dump file paths (`/tmp/juniper-*.xml`, `/tmp/cisco-*.conf`) |
| `WARN`  | Minimum level for JSch SSH messages (hardcoded — avoids SSH negotiation noise regardless of `LOG_LEVEL`) |

### Controlling the log level

Set the `LOG_LEVEL` environment variable to any Log4j2 level name
(`trace`, `debug`, `info`, `warn`, `error`). Default is `info`.

#### Local one-shot run

```bash
LOG_LEVEL=debug java -jar target/routing-instances-report-1.0.jar
```

#### docker run — foreground (one-shot, useful for troubleshooting a single router)

```bash
docker run --rm \
  -e ROUTER_USER=username \
  -e ROUTER_PASS=password4username \
  -e CISCO_ENABLE=password4enable \
  -e JUNIPER_HOSTS="r560-1" \
  -e LOG_LEVEL=debug \
  --entrypoint java \
  routing-instances-report \
  -jar /usr/local/bin/routing-instances-report.jar
```

#### docker run — background container with DEBUG, then follow logs

```bash
docker run -d \
  --name routing-report \
  -p 80:80 \
  -e ROUTER_USER=username \
  -e ROUTER_PASS=password4username \
  -e CISCO_ENABLE=password4enable \
  -e JUNIPER_HOSTS="r560-1,rhoh15-1,r234-1,r201-1,r525-1,r559-1,r540-1,r418-1,rf102z-1,rdc-1" \
  -e CISCO_HOSTS="rdnepr-1" \
  -e LOG_LEVEL=debug \
  routing-instances-report

docker logs -f routing-report
```

#### docker-compose — add one line to the environment block

```yaml
environment:
  LOG_LEVEL: debug
```

## Project structure

```
routing-instances-report/
├── Dockerfile                           multi-stage build (JDK 21 → nginx + JRE 21)
├── pom.xml                              Maven build (fat JAR via maven-shade-plugin)
├── bin/
│   └── routing-instances-report.sh     daily loop wrapper (runs JAR, sleeps 24 h)
├── docker-entrypoint.d/
│   └── 40-routing-instances-report.sh  launched by nginx entrypoint in background
└── src/main/java/net/ukrhub/routing/instances/report/
    ├── RoutingInstancesReport.java      main class — reads env vars, drives collection
    ├── RoutingInstance.java             data model (@Data Lombok)
    ├── HashUtils.java                   MD5 + SHA-1 composite key (Perl-compatible)
    ├── JuniperCollector.java            NETCONF over SSH collector
    ├── CiscoCollector.java              Telnet collector
    ├── RouterOSCollector.java           SSH exec collector
    └── ReportGenerator.java             HTML report writer
```

## Environment variables

| Variable        | Required | Description |
|-----------------|----------|-------------|
| `ROUTER_USER`   | **yes**  | SSH / Telnet login for all routers |
| `ROUTER_PASS`   | **yes**  | SSH / Telnet password |
| `CISCO_ENABLE`  | if Cisco | Cisco enable (privileged) password |
| `JUNIPER_HOSTS` | no       | Comma-separated Juniper hostnames |
| `CISCO_HOSTS`   | no       | Comma-separated Cisco hostnames |
| `ROUTEROS_HOSTS` | no       | Comma-separated MikroTik hostnames |
| `REPORT_PATH`   | no       | Output HTML path (default: `/usr/share/nginx/html/index.html`) |
| `LOG_LEVEL`     | no       | Log4j2 level: `trace` `debug` `info` `warn` `error` (default: `info`) |
| `OPENCHANNEL`   | no       | Juniper SSH channel type: `subsystem-netconf` (default) or `exec` |

## Building

```bash
mvn package -DskipTests
# produces target/routing-instances-report-1.0.jar
```

## Docker

### Build image

```bash
docker build -t routing-instances-report .
```

### Run

```bash
docker run -d \
  --name routing-report \
  -p 80:80 \
  -e ROUTER_USER=username \
  -e ROUTER_PASS=password4username \
  -e CISCO_ENABLE=password4enable \
  -e JUNIPER_HOSTS="r560-1,rhoh15-1,r234-1,r201-1,r525-1,r559-1,r540-1,r418-1,rf102z-1,rdc-1" \
  -e CISCO_HOSTS="rdnepr-1" \
  routing-instances-report
```

The report is available at `http://<host>/` and refreshes automatically once
per day. The first run happens a few seconds after the container starts.

### docker-compose example

```yaml
services:
  routing-report:
    build: .
    restart: unless-stopped
    ports:
      - "80:80"
    environment:
      ROUTER_USER:   username
      ROUTER_PASS:   password4username
      CISCO_ENABLE:  password4enable
      JUNIPER_HOSTS: "r560-1,rhoh15-1,r234-1,r201-1,r525-1,r559-1,r540-1,r418-1,rf102z-1,rdc-1"
      CISCO_HOSTS:   "rdnepr-1"
      # LOG_LEVEL: debug
```

## Dependencies

| Library | Purpose |
|---------|---------|
| [com.github.mwiede/jsch](https://github.com/mwiede/jsch) | SSH transport for Juniper NETCONF and RouterOS |
| [commons-net](https://commons.apache.org/proper/commons-net/) | Telnet client for Cisco IOS |
| [Lombok](https://projectlombok.org/) | Boilerplate reduction (`@Data` on `RoutingInstance`) |

## License

See [LICENSE](LICENSE).
