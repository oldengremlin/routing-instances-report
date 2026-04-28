package net.ukrhub.routing.instances.report;

import org.apache.commons.net.telnet.TelnetClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Collects VRF definitions from Cisco IOS routers via Telnet.
 * Parses "show running-config" for "ip vrf NAME / rd X:Y" blocks.
 */
public class CiscoCollector {

    private static final int TIMEOUT_MS = 60_000;

    private final String login;
    private final String pass;
    private final String enablePass;

    public CiscoCollector(String login, String pass, String enablePass) {
        this.login      = login;
        this.pass       = pass;
        this.enablePass = enablePass;
    }

    public void collect(String hostname, Map<String, RoutingInstance> instances,
                        Map<String, Map<String, String>> vrfVplsList) throws Exception {
        TelnetClient telnet = new TelnetClient();
        telnet.setConnectTimeout(TIMEOUT_MS);
        telnet.connect(hostname, 23);
        telnet.setSoTimeout(TIMEOUT_MS);

        InputStream in  = telnet.getInputStream();
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
        out.println("terminal length 0");
        readUntil(in, "#");
        out.println("show running-config");
        String runningConfig = readUntil(in, "#");
        out.println("exit");
        telnet.disconnect();

        Files.writeString(Path.of("/tmp/cisco-" + hostname + ".conf"),
                runningConfig, StandardCharsets.UTF_8);

        parseConfig(hostname, runningConfig.split("\r?\n"), instances, vrfVplsList);
    }

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
                    if (sb.toString().contains(target)) return sb.toString();
                }
            } else {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        throw new IOException("Timeout waiting for '" + target + "'");
    }

    private void parseConfig(String hostname, String[] lines,
                              Map<String, RoutingInstance> instances,
                              Map<String, Map<String, String>> vrfVplsList) {
        Pattern vrfPat = Pattern.compile("^ip\\s+vrf\\s+(.+)");
        Pattern rdPat  = Pattern.compile("^\\s+rd\\s+(.+)");

        String instance = "";
        String rd       = "";

        for (String s : lines) {
            Matcher mVrf = vrfPat.matcher(s);
            Matcher mRd  = rdPat.matcher(s);

            if (mVrf.matches()) {
                instance = mVrf.group(1).trim();
                rd       = "";
            } else if (mRd.matches() && !instance.isEmpty()) {
                rd = mRd.group(1).trim();
            }

            if (!instance.isEmpty() && !rd.isEmpty()) {
                JuniperCollector.merge(instances, vrfVplsList,
                        instance, "vrf", rd, hostname.toUpperCase());
                instance = "";
                rd       = "";
            }
        }
    }
}
