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
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>MCP dispatcher-level</b> verification of the debug tools (debug.launch, debug.evaluate) — drives the
 * tool surface (JSON schema, argument parsing, authorizer choke point, isError conversion) that the core
 * tests (DebugLaunchWorkerTest/DebugEvaluateTest) cannot cover, through a real {@code tools/call}.
 * worker mode with mcp/debug enabled.
 */
@SpringBootTest(properties = {"protean.mcp.enabled=true", "protean.mcp.debug.enabled=true"})
class McpDebugLaunchEvaluateTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-launch-eval-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");   // debug.launch spawns a dedicated worker
    }

    private static final Pattern LISTENING = Pattern.compile("address:\\s*(\\d+)");
    private static final String EVAL_TARGET = "org.htcom.mcpext.DebugEvalTarget";
    private static final int EVAL_RETURN_LINE = 47;

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

    private ObjectNode args(String sessionId) {
        ObjectNode o = mapper.createObjectNode();
        o.put("sessionId", sessionId);
        return o;
    }

    /** Drives debug.evaluate through the dispatcher - value/type structuredContent plus error isError conversion. */
    @Test
    void evaluate_over_dispatcher() throws Exception {
        Process target = spawnJdwp(EVAL_TARGET);
        try {
            int port = readListeningPort(target);
            assertTrue(port > 0);

            ObjectNode attachArgs = mapper.createObjectNode();
            attachArgs.put("host", "127.0.0.1");
            attachArgs.put("port", port);
            JsonNode attach = callTool("debug.attach", attachArgs);
            String sessionId = attach.path("structuredContent").path("sessionId").asText();
            assertTrue(sessionId.startsWith("dbg-"));

            ObjectNode bp = args(sessionId);
            bp.put("className", EVAL_TARGET);
            bp.put("line", EVAL_RETURN_LINE);
            assertFalse(callTool("debug.set_breakpoint", bp).path("isError").asBoolean(false));

            ObjectNode wait = args(sessionId);
            wait.put("timeoutMs", 8000);
            assertTrue(callTool("debug.await_stop", wait).path("structuredContent").path("stopped").asBoolean(false));

            // Full expression syntax works through the dispatcher (operators, getters)
            ObjectNode ev = args(sessionId);
            ev.put("expr", "user.getAge() * 2");
            JsonNode r = callTool("debug.evaluate", ev);
            assertFalse(r.path("isError").asBoolean(false), "evaluate succeeds");
            assertEquals("58", r.path("structuredContent").path("value").asText());
            assertEquals("int", r.path("structuredContent").path("type").asText());

            // Ternary + string
            ObjectNode ev2 = args(sessionId);
            ev2.put("expr", "user.getAge() > 18 ? \"adult\" : \"minor\"");
            assertEquals("adult", callTool("debug.evaluate", ev2)
                    .path("structuredContent").path("value").asText());

            // Error expression -> isError conversion (the exception does not leak into the result)
            ObjectNode bad = args(sessionId);
            bad.put("expr", "nope.bad()");
            assertTrue(callTool("debug.evaluate", bad).path("isError").asBoolean(false),
                    "an invalid expression must be converted to isError");

            callTool("debug.continue", args(sessionId));
            callTool("debug.terminate", args(sessionId));
        } finally {
            target.destroyForcibly();
            target.waitFor();
        }
    }

    /** Drives debug.launch through the dispatcher - spawns a dedicated worker, attaches, returns sessionId/ports/paths, and cleans up on terminate. */
    @Test
    void launch_over_dispatcher() {
        String fqcn = "runtime.ml.LController";
        String src = """
                package runtime.ml;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class LController {
                    @GetMapping("/mcp-dbg-launch/ping")
                    public String ping() { return "launched"; }
                }
                """;
        ObjectNode launchArgs = mapper.createObjectNode();
        launchArgs.put("id", "mcp-launch-mod");
        launchArgs.put("version", "1.0.0");
        launchArgs.put("controller", fqcn);
        ArrayNode files = launchArgs.putArray("files");
        ObjectNode f = files.addObject();
        f.put("kind", "source");
        f.put("filename", "LController.java");
        f.put("content", src);

        JsonNode launch = callTool("debug.launch", launchArgs);
        assertFalse(launch.path("isError").asBoolean(false), "launch succeeds: " + launch);
        JsonNode sc = launch.path("structuredContent");
        String sessionId = sc.path("sessionId").asText();
        try {
            assertTrue(sessionId.startsWith("dbg-"), "sessionId issued");
            assertTrue(sc.path("jdwpPort").asInt() > 0, "JDWP port");
            assertTrue(sc.path("workerPort").asInt() > 0, "worker port");
            assertTrue(sc.path("paths").toString().contains("/mcp-dbg-launch/ping"), "route registered: " + sc);
        } finally {
            // Session shutdown -> kill the worker and restore routes (through the dispatcher)
            assertFalse(callTool("debug.terminate", args(sessionId)).path("isError").asBoolean(false),
                    "terminate succeeds");
        }
    }

    private Process spawnJdwp(String target) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0",
                "-cp", System.getProperty("java.class.path"),
                target);
        pb.redirectErrorStream(true);
        return pb.start();
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
