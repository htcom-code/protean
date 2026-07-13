/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP debug.* tools end-to-end. Drives attach->set_breakpoint->await_stop->frames->get_variables->
 * continue->terminate through the dispatcher to confirm the session adapter wraps DebugCore correctly.
 * Uses a real JDWP target JVM (no Docker needed).
 */
@SpringBootTest(properties = {"protean.mcp.enabled=true", "protean.mcp.debug.enabled=true"})
class McpDebugToolsTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-debug-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    private static final Pattern LISTENING = Pattern.compile("address:\\s*(\\d+)");
    private static final String TARGET = "org.htcom.mcpext.DebugLoopTarget";

    @Autowired McpDispatcher dispatcher;
    @Autowired ObjectMapper mapper;

    private JsonNode callTool(String tool, ObjectNode args) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", tool);
        params.set("arguments", args);
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/call");
        req.set("params", params);
        return dispatcher.dispatch(req, McpCallContext.anonymous()).path("result");
    }

    private JsonNode toolNode(String name) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 0);
        req.put("method", "tools/list");
        for (JsonNode t : dispatcher.dispatch(req, McpCallContext.anonymous()).path("result").path("tools")) {
            if (name.equals(t.path("name").asText())) {
                return t;
            }
        }
        return mapper.missingNode();
    }

    @Test
    void debug_tools_declare_output_schema() {
        // list_sessions wraps an array under "sessions"
        JsonNode ls = toolNode("debug.list_sessions").path("outputSchema");
        assertTrue(ls.isObject(), "list_sessions outputSchema serialized");
        assertEquals("sessions", ls.path("required").get(0).asText());
        // await_stop is a variant → only "stopped" is always present, so it is the sole required field
        JsonNode aw = toolNode("debug.await_stop").path("outputSchema");
        assertEquals(1, aw.path("required").size());
        assertEquals("stopped", aw.path("required").get(0).asText());
        // get_variables is a dynamic map → no top-level required, string values
        JsonNode gv = toolNode("debug.get_variables").path("outputSchema");
        assertFalse(gv.has("required"), "dynamic map has no required");
        assertEquals("string", gv.path("additionalProperties").path("type").asText());
        // redefine promoted to structured output
        assertEquals("redefined", toolNode("debug.redefine").path("outputSchema").path("required").get(0).asText());
        // pure-ack tools stay text-only (declaring a schema would fail every success)
        assertFalse(toolNode("debug.continue").has("outputSchema"), "continue is a pure ack");
        assertFalse(toolNode("debug.terminate").has("outputSchema"), "terminate is a pure ack");
    }

    @Test
    void debug_tools_full_flow_over_dispatcher() throws Exception {
        // Debug tools are listed in tools/list
        List<String> names = new ArrayList<>();
        ObjectNode listReq = mapper.createObjectNode();
        listReq.put("jsonrpc", "2.0");
        listReq.put("id", 0);
        listReq.put("method", "tools/list");
        dispatcher.dispatch(listReq, McpCallContext.anonymous())
                .path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
        assertTrue(names.contains("debug.attach"), "debug.* tools listed: " + names);
        assertTrue(names.contains("debug.set_breakpoint"));

        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0",
                "-cp", System.getProperty("java.class.path"),
                TARGET);
        pb.redirectErrorStream(true);
        Process target = pb.start();
        try {
            int port = readListeningPort(target);
            assertTrue(port > 0);

            // attach
            ObjectNode attachArgs = mapper.createObjectNode();
            attachArgs.put("host", "127.0.0.1");
            attachArgs.put("port", port);
            JsonNode attach = callTool("debug.attach", attachArgs);
            assertFalse(attach.path("isError").asBoolean(false), "attach succeeds");
            String sessionId = attach.path("structuredContent").path("sessionId").asText();
            assertTrue(sessionId.startsWith("dbg-"));
            SchemaConformance.assertConforms(toolNode("debug.attach").path("outputSchema"),
                    attach.path("structuredContent"), "debug.attach");

            // list_sessions - the session is exposed in the global list (for reattach/rediscovery). structuredContent is an object.
            JsonNode list = callTool("debug.list_sessions", mapper.createObjectNode());
            assertTrue(list.path("structuredContent").isObject(), "structuredContent must be an object: " + list);
            boolean found = false;
            for (JsonNode s : list.path("structuredContent").path("sessions")) {
                if (sessionId.equals(s.path("sessionId").asText())) {
                    found = true;
                }
            }
            assertTrue(found, "list_sessions must contain the current session: " + list);
            SchemaConformance.assertConforms(toolNode("debug.list_sessions").path("outputSchema"),
                    list.path("structuredContent"), "debug.list_sessions");

            // set_breakpoint (return doubled; = line 21)
            ObjectNode bpArgs = mapper.createObjectNode();
            bpArgs.put("sessionId", sessionId);
            bpArgs.put("className", TARGET);
            bpArgs.put("line", 21);
            assertFalse(callTool("debug.set_breakpoint", bpArgs).path("isError").asBoolean(false));

            // await_stop
            ObjectNode waitArgs = mapper.createObjectNode();
            waitArgs.put("sessionId", sessionId);
            waitArgs.put("timeoutMs", 8000);
            JsonNode stop = callTool("debug.await_stop", waitArgs);
            assertTrue(stop.path("structuredContent").path("stopped").asBoolean(false), "must stop");
            assertEquals(21, stop.path("structuredContent").path("line").asInt());
            SchemaConformance.assertConforms(toolNode("debug.await_stop").path("outputSchema"),
                    stop.path("structuredContent"), "debug.await_stop");

            // frames
            ObjectNode sidOnly = mapper.createObjectNode();
            sidOnly.put("sessionId", sessionId);
            JsonNode frames = callTool("debug.frames", sidOnly);
            // MCP spec: structuredContent must be an object (arrays not allowed) -> wrap under a frames key.
            assertTrue(frames.path("structuredContent").isObject(), "structuredContent must be an object: " + frames);
            assertEquals("compute",
                    frames.path("structuredContent").path("frames").get(0).path("method").asText());
            SchemaConformance.assertConforms(toolNode("debug.frames").path("outputSchema"),
                    frames.path("structuredContent"), "debug.frames");

            // get_variables frame 0
            ObjectNode varArgs = mapper.createObjectNode();
            varArgs.put("sessionId", sessionId);
            varArgs.put("frame", 0);
            JsonNode vars = callTool("debug.get_variables", varArgs);
            assertTrue(vars.path("structuredContent").has("doubled"), "doubled variable: " + vars);
            SchemaConformance.assertConforms(toolNode("debug.get_variables").path("outputSchema"),
                    vars.path("structuredContent"), "debug.get_variables");

            // continue + terminate
            assertFalse(callTool("debug.continue", sidOnly).path("isError").asBoolean(false));
            assertFalse(callTool("debug.terminate", sidOnly).path("isError").asBoolean(false));
        } finally {
            target.destroyForcibly();
            target.waitFor();
        }
    }

    private int readListeningPort(Process target) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(target.getInputStream(), StandardCharsets.UTF_8));
        String line;
        for (int i = 0; i < 50 && (line = reader.readLine()) != null; i++) {
            Matcher m = LISTENING.matcher(line);
            if (line.contains("Listening for transport") && m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return -1;
    }
}
