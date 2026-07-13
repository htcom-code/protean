/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpDispatcher;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * MCP stdio transport — reads newline-delimited JSON-RPC from stdin, processes it through {@link McpDispatcher},
 * and writes responses to stdout one line at a time. For the scenario where a local agent spawns this process
 * (registered only when {@code protean.mcp.stdio=true}; entry point {@link org.htcom.protean.boot.ProteanMcpStdioLauncher}).
 *
 * <p>stdio is a <b>local trust boundary</b> — the spawning party is the authorizing party, so caller=null. stdout
 * is reserved for JSON-RPC, so the launcher disables console logging ({@code logging.pattern.console=}).
 */
public class McpStdioServer implements ApplicationRunner {

    private final McpDispatcher dispatcher;
    private final ObjectMapper mapper;

    public McpStdioServer(McpDispatcher dispatcher, ObjectMapper mapper) {
        this.dispatcher = dispatcher;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        serve(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
        System.exit(0); // EOF = client disconnected -> terminate the process
    }

    /** Testable core loop. Processes one line = one message until EOF. */
    public void serve(BufferedReader in, PrintStream out) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode request;
            try {
                request = mapper.readTree(line);
            } catch (Exception e) {
                out.println(parseError());
                out.flush();
                continue;
            }
            // Responses to server->client requests (result/error with no method) have no correlation channel over stdio, so ignore them (not an error).
            if (!request.hasNonNull("method") && (request.has("result") || request.has("error"))) {
                continue;
            }
            McpCallContext ctx = new McpCallContext(null, progressSink(request, out));
            JsonNode response = dispatcher.dispatch(request, ctx);
            if (response != null) { // notifications have no response
                out.println(mapper.writeValueAsString(response));
                out.flush();
            }
        }
    }

    /** A sink that streams progress notifications to stdout for a tools/call that supplied a progressToken; otherwise a no-op. */
    private McpCallContext.ProgressSink progressSink(JsonNode request, PrintStream out) {
        JsonNode token = request.path("params").path("_meta").get("progressToken");
        if (token == null || token.isNull() || !"tools/call".equals(request.path("method").asText())) {
            return McpCallContext.NOOP;
        }
        return (current, total, message) -> {
            ObjectNode notif = mapper.createObjectNode();
            notif.put("jsonrpc", "2.0");
            notif.put("method", "notifications/progress");
            ObjectNode params = notif.putObject("params");
            params.set("progressToken", token);
            params.put("progress", current);
            if (total > 0) {
                params.put("total", total);
            }
            if (message != null) {
                params.put("message", message);
            }
            out.println(notif);
            out.flush();
        };
    }

    private String parseError() {
        ObjectNode err = mapper.createObjectNode();
        err.put("jsonrpc", "2.0");
        err.putNull("id");
        ObjectNode e = err.putObject("error");
        e.put("code", -32700);
        e.put("message", "Parse error");
        return err.toString();
    }
}
