package net.ukrhub.routing.instances.report;

import lombok.extern.log4j.Log4j2;
import org.apache.commons.net.telnet.TelnetClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Collects VRF definitions from Cisco IOS routers via Telnet.
 *
 * <p>Connects on port 23, authenticates with username/password, enters
 * privileged mode ({@code enable}), disables pagination ({@code terminal
 * length 0}), and captures {@code show running-config}. The raw output is
 * saved to {@code /tmp/cisco-HOST.conf} for debugging.</p>
 *
 * <p>Parser looks for {@code ip vrf NAME} / {@code rd X:Y} block pairs.
 * Each complete pair is merged as type {@code VRF}.</p>
 */
@Log4j2
public class CiscoCollector implements Collector {

    private static final int TIMEOUT_MS = 60_000;

    private final String login;
    private final String pass;
    private final String enablePass;

    /**
     * @param login      Telnet username
     * @param pass       Telnet password
     * @param enablePass Cisco enable (privileged) password
     */
    public CiscoCollector(String login, String pass, String enablePass) {
        this.login = login;
        this.pass = pass;
        this.enablePass = enablePass;
    }

    @Override
    public void collect(String hostname, Map<String, RoutingInstance> instances,
                        Map<String, Map<String, String>> vrfVplsList) throws Exception {
        log.info("Connecting to {} via Telnet", hostname);
        TelnetClient telnet = new TelnetClient();
        telnet.setConnectTimeout(TIMEOUT_MS);
        telnet.connect(hostname, 23);
        telnet.setSoTimeout(TIMEOUT_MS);

        InputStream in = telnet.getInputStream();
        PrintStream out = new PrintStream(telnet.getOutputStream(), true, StandardCharsets.UTF_8);

        readUntil(in, "Username:");
        out.println(login);
        readUntil(in, "Password:");
        out.println(pass);
        readUntil(in, ">");
        out.println("enable");
        readUntil(in, "Password:");
        out.println(enablePass);
        readUntil(in, "#");
        log.debug("Telnet session established: {}", hostname);
        out.println("terminal length 0");
        readUntil(in, "#");
        out.println("show running-config");
        String runningConfig = readUntil(in, "#");
        out.println("exit");
        telnet.disconnect();

        Path dumpFile = Path.of("/tmp/cisco-" + hostname + ".conf");
        Files.writeString(dumpFile, runningConfig, StandardCharsets.UTF_8);
        log.debug("Configuration saved to {}", dumpFile);

        int before = instances.size();
        parseConfig(hostname, runningConfig.split("\r?\n"), instances, vrfVplsList);
        log.info("Parsed {} VRF definitions from {}", instances.size() - before, hostname);
    }

    /**
     * Reads from the Telnet stream until {@code target} appears in the buffer
     * or the timeout expires.
     *
     * @param in     Telnet input stream
     * @param target string to wait for (e.g. {@code "#"}, {@code "Password:"})
     * @return       everything read up to and including {@code target}
     * @throws IOException on read error or timeout
     */
    private String readUntil(InputStream in, String target) throws IOException {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            int avail = in.available();
            if (avail > 0) {
                byte[] buf = new byte[avail];
                int n = in.read(buf);
                if (n > 0) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    if (sb.toString().contains(target)) {
                        return sb.toString();
                    }
                }
            } else {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw new IOException("Timeout waiting for '" + target + "'");
    }

    /**
     * Scans config lines for {@code ip vrf} / {@code rd} pairs and merges each
     * into the shared instance map.
     *
     * @param hostname     router hostname (used as the host entry label)
     * @param lines        config split by line
     * @param instances    shared instances map
     * @param vrfVplsList  shared RD index map
     */
    private void parseConfig(String hostname, String[] lines,
                             Map<String, RoutingInstance> instances,
                             Map<String, Map<String, String>> vrfVplsList) {
        Pattern vrfPat = Pattern.compile("^ip\\s+vrf\\s+(.+)");
        Pattern rdPat = Pattern.compile("^\\s+rd\\s+(.+)");

        String instance = "";
        String rd = "";

        for (String s : lines) {
            Matcher mVrf = vrfPat.matcher(s);
            Matcher mRd = rdPat.matcher(s);

            if (mVrf.matches()) {
                instance = mVrf.group(1).trim();
                rd = "";
            } else if (mRd.matches() && !instance.isEmpty()) {
                rd = mRd.group(1).trim();
            }

            if (!instance.isEmpty() && !rd.isEmpty()) {
                RoutingInstance.merge(instances, vrfVplsList,
                        instance, "vrf", rd, hostname.toUpperCase());
                instance = "";
                rd = "";
            }
        }
    }
}
