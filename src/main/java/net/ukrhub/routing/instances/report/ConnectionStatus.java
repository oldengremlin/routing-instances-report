/*
 * Copyright 2025 Ukrcom
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations
 * under the License.
 */
package net.ukrhub.routing.instances.report;

/**
 * Status codes reported in {@code connection-status} elements of Juniper
 * {@code get-l2ckt-connection-information} and
 * {@code get-vpls-connection-information} RPC replies.
 *
 * <p>Most codes are shared between L2CIRCUIT and VPLS; a few are specific
 * to one type. The {@link #describe} helper converts a raw code string
 * (as it appears in the XML) to a human-readable description.</p>
 *
 * <p>Codes marked with <b>(L2)</b> appear only in the L2CIRCUIT legend;
 * codes marked with <b>(VPLS)</b> appear only in the VPLS legend; the rest
 * are common to both.</p>
 */
enum ConnectionStatus {
    /* ----- common ----- */
    EI("encapsulation invalid"),
    EM("encapsulation mismatch"),
    MM("MTU mismatch"),
    CM("control-word mismatch"),
    VM("VLAN ID mismatch"),
    OL("no outgoing label"),
    LD("local site signaled down"),
    RD("remote site signaled down"),
    BK("backup connection"),
    ST("standby connection"),
    RS("remote site standby"),
    HS("hot-standby connection"),
    NP("interface h/w not present"),
    XX("unknown"),
    UP("operational"),
    DN("down"),
    CF("call admission control failure"),
    VC_DN("virtual circuit down"),

    /* ----- L2CIRCUIT only ----- */
    NC("interface encaps not CCC/TCC"),
    IB("TDM incompatible bitrate"),
    TM("TDM misconfiguration"),
    CB("rcvd cell-bundle size bad"),
    SP("static pseudowire"),

    /* ----- VPLS only ----- */
    WE("interface and instance encaps not same"),
    CN("circuit not provisioned"),
    OR("out of range"),
    SC("local and remote site ID collision"),
    LN("local site not designated"),
    LM("local site ID not minimum designated"),
    RN("remote site not designated"),
    RM("remote site ID not minimum designated"),
    IL("no incoming label"),
    MI("mesh-group ID not available"),
    PF("profile parse failure"),
    PB("profile busy"),
    LB("local site not best-site"),
    RB("remote site not best-site"),
    SN("static neighbor");

    private final String description;

    ConnectionStatus(String description) {
        this.description = description;
    }

    /**
     * Returns the human-readable description for {@code code}, or
     * {@code code} itself when the code is not recognised.
     *
     * <p>Lookup is case-insensitive; hyphens in the code (e.g.
     * {@code VC-Dn}) are replaced with underscores before matching.</p>
     *
     * @param code raw status code from the XML (e.g. {@code NP}, {@code OL},
     *             {@code VC-Dn})
     * @return     description string
     */
    static String describe(String code) {
        if (code == null || code.isBlank()) {
            return code;
        }
        if ("->".equals(code)) return "only outbound connection is up";
        if ("<-".equals(code)) return "only inbound connection is up";
        String normalized = code.replace("-", "_").toUpperCase();
        try {
            return ConnectionStatus.valueOf(normalized).description;
        } catch (IllegalArgumentException e) {
            return code;
        }
    }
}
