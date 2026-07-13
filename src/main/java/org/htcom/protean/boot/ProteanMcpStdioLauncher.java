/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.boot;

import org.htcom.protean.ProteanApplication;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * MCP stdio server entry point — the main that local agents (Claude Desktop/Code, etc.) spawn.
 * Boots with MCP enabled and the stdio transport activated.
 *
 * <p><b>stdout is reserved for JSON-RPC</b>, so the banner and console logging are turned off (an
 * empty {@code logging.pattern.console=} makes the console appender output vanish) — so logs do not
 * pollute the protocol stream. The web server is left running: stdio is the control channel, and
 * deployed modules are still served over HTTP.
 *
 * <p>For the same reason as {@link ProteanWorkerLauncher}, it is not annotated with
 * {@code @SpringBootApplication} and boots {@link ProteanApplication} as the source (keeping a single
 * {@code @SpringBootConfiguration}).
 */
public final class ProteanMcpStdioLauncher {

    public static final String MAIN_CLASS = "org.htcom.protean.boot.ProteanMcpStdioLauncher";

    private ProteanMcpStdioLauncher() {
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(ProteanApplication.class)
                .bannerMode(Banner.Mode.OFF)
                .properties(
                        "protean.mcp.enabled=true",
                        "protean.mcp.stdio=true",
                        "logging.pattern.console=")
                .run(args);
    }
}
