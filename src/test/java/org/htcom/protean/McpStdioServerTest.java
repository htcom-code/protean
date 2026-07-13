/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.htcom.protean.mcp.McpDispatcher;
import org.htcom.protean.mcp.transport.McpStdioServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * stdio transport verification — drives {@link McpStdioServer#serve} directly (transport-independent
 * principle). Confirms it reads newline-delimited JSON-RPC and responds one line at a time. Does not touch
 * the real System.in/exit.
 */
@SpringBootTest(properties = "protean.mcp.enabled=true")
class McpStdioServerTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-stdio-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired McpDispatcher dispatcher;
    @Autowired ObjectMapper mapper;

    @Test
    void serves_newline_delimited_jsonrpc() throws Exception {
        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        new McpStdioServer(dispatcher, mapper)
                .serve(new BufferedReader(new StringReader(input)), new PrintStream(buf, true, StandardCharsets.UTF_8));

        String[] lines = buf.toString(StandardCharsets.UTF_8).split("\\R");
        assertTrue(lines.length >= 2, "2 response lines for 2 requests");
        assertTrue(lines[0].contains("\"protocolVersion\":\"2025-11-25\""), lines[0]);
        assertTrue(lines[1].contains("protean.list_modules"), lines[1]);
    }

    @Test
    void malformed_line_yields_parse_error_and_continues() throws Exception {
        String input = "not json\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}\n";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        new McpStdioServer(dispatcher, mapper)
                .serve(new BufferedReader(new StringReader(input)), new PrintStream(buf, true, StandardCharsets.UTF_8));

        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("-32700"), "a broken line yields a parse error");
        assertTrue(out.contains("\"id\":9"), "the next line is processed normally (loop continues)");
    }
}
