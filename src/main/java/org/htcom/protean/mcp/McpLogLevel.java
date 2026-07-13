/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp;

/**
 * MCP logging severity (spec 2025-11-25, Logging section) — the eight syslog (RFC 5424) levels.
 * The ordinal is exactly the severity order, so it is used directly for threshold comparisons
 * (DEBUG lowest ... EMERGENCY highest).
 */
public enum McpLogLevel {
    DEBUG, INFO, NOTICE, WARNING, ERROR, CRITICAL, ALERT, EMERGENCY;

    /** Parses from the MCP wire name (lowercase). Returns {@code null} if unknown. */
    public static McpLogLevel fromWire(String name) {
        if (name == null) {
            return null;
        }
        for (McpLogLevel l : values()) {
            if (l.name().equalsIgnoreCase(name)) {
                return l;
            }
        }
        return null;
    }

    /** The MCP wire name (lowercase). */
    public String wire() {
        return name().toLowerCase();
    }

    /** Eligible for emission if this level is at or above {@code threshold} (inclusive). */
    public boolean atLeast(McpLogLevel threshold) {
        return ordinal() >= threshold.ordinal();
    }
}
