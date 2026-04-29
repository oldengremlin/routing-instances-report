# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

```bash
mvn package -DskipTests
# produces target/routing-instances-report-1.0.jar (fat JAR via maven-shade-plugin)
```

```bash
docker build -t routing-instances-report .
```

There are no automated tests; the Dockerfile is the primary integration target.

## Architecture

The tool is a single-shot Java 21 CLI that collects VRF/VPLS routing instance definitions from network routers and writes one HTML report. It runs inside a Docker container (nginx + JRE 21) that re-invokes it every 24 hours via a shell loop (`bin/routing-instances-report.sh`), started as a background process by nginx's entrypoint (`docker-entrypoint.d/40-routing-instances-report.sh`).

**Data flow:**

```
env vars → RoutingInstancesReport.main()
    → JuniperCollector   (SSH → NETCONF over subsystem or exec channel, parses XML)
    → CiscoCollector     (Telnet, parses show running-config text)
    → RouterOSCollector  (SSH exec, parses /ip route vrf export compact)
    → ReportGenerator    (writes indexed HTML to REPORT_PATH)
```

**Deduplication key** (`HashUtils.computeKey`): instance name padded to 50 chars + MD5 + SHA-1, matching the original Perl implementation so the same VRF present on multiple routers collapses into one row listing all routers.

**Data model** (`RoutingInstance.java`): Lombok `@Data` — holds type, name, RD, and a set of router hostnames. Also contains the static `merge()` method used by all three collectors to insert/update an instance in the shared maps.

**Collectors** write raw dumps to `/tmp/juniper-<host>.xml` and `/tmp/cisco-<host>.conf` for debugging.

## Environment variables

| Variable         | Required              | Default                                   |
|------------------|-----------------------|-------------------------------------------|
| `ROUTER_USER`    | yes                   |                                           |
| `ROUTER_PASS`    | yes                   |                                           |
| `CISCO_ENABLE`   | if CISCO_HOSTS set    |                                           |
| `JUNIPER_HOSTS`  | no                    | (empty)                                   |
| `CISCO_HOSTS`    | no                    | (empty)                                   |
| `ROUTEROS_HOSTS` | no                    | (empty)                                   |
| `REPORT_PATH`    | no                    | `/usr/share/nginx/html/index.html`        |
| `LOG_LEVEL`      | no                    | `info` (Log4j2 levels: trace/debug/info/warn/error) |
| `OPENCHANNEL`    | no                    | `subsystem-netconf` (alt: `exec`)         |

## Logging

Uses Lombok `@Log4j2`; all output goes to stdout (`docker logs`). JSch SSH-handshake noise is hard-capped at WARN regardless of `LOG_LEVEL`.
