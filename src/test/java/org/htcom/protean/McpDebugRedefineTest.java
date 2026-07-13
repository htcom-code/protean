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
 * fix-and-continue. Redefines {@code DebugLoopTarget.compute} in place to {@code i*7} while running
 * (debug.redefine), then confirms at a breakpoint that {@code doubled == i*7}. The line layout must
 * stay identical to the original so that the breakpoint line (21) still matches.
 */
@SpringBootTest(properties = {"protean.mcp.enabled=true", "protean.mcp.debug.enabled=true"})
class McpDebugRedefineTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-redefine-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    private static final Pattern LISTENING = Pattern.compile("address:\\s*(\\d+)");
    private static final String TARGET = "org.htcom.mcpext.DebugLoopTarget";

    // Same line layout as the original, only the compute body is i*7 (method-body change -> redefine allowed).
    private static final String MODIFIED = """
            package org.htcom.mcpext;

            /**
             * redefine target - only the compute body is replaced with i*7.
             * Line layout is identical to DebugLoopTarget.java (breakpoint line 21 preserved).
             */
            public final class DebugLoopTarget {

                private DebugLoopTarget() {
                }

                public static void main(String[] args) throws InterruptedException {
                    for (int i = 0; i < 1_000_000; i++) {
                        compute(i);
                        Thread.sleep(5);
                    }
                }

                static int compute(int i) {
                    int doubled = i * 7;
                    return doubled;
                }
            }
            """;

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

    @Test
    void redefine_changes_running_method_body() throws Exception {
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

            ObjectNode attachArgs = mapper.createObjectNode();
            attachArgs.put("host", "127.0.0.1");
            attachArgs.put("port", port);
            String sessionId = callTool("debug.attach", attachArgs)
                    .path("structuredContent").path("sessionId").asText();

            // fix-and-continue: swap compute to i*7 in place
            ObjectNode redefineArgs = mapper.createObjectNode();
            redefineArgs.put("sessionId", sessionId);
            ArrayNode files = redefineArgs.putArray("files");
            files.addObject().put("filename", "DebugLoopTarget.java").put("content", MODIFIED);
            JsonNode redefine = callTool("debug.redefine", redefineArgs);
            assertFalse(redefine.path("isError").asBoolean(false), "redefine succeeds: " + redefine);

            // Breakpoint in the redefined code -> doubled == i*7
            ObjectNode bpArgs = mapper.createObjectNode();
            bpArgs.put("sessionId", sessionId);
            bpArgs.put("className", TARGET);
            bpArgs.put("line", 21);
            callTool("debug.set_breakpoint", bpArgs);

            ObjectNode waitArgs = mapper.createObjectNode();
            waitArgs.put("sessionId", sessionId);
            waitArgs.put("timeoutMs", 30000);
            JsonNode stop = callTool("debug.await_stop", waitArgs);
            assertTrue(stop.path("structuredContent").path("stopped").asBoolean(false), "stopped: " + stop);

            ObjectNode varArgs = mapper.createObjectNode();
            varArgs.put("sessionId", sessionId);
            varArgs.put("frame", 0);
            JsonNode vars = callTool("debug.get_variables", varArgs).path("structuredContent");
            int i = Integer.parseInt(vars.path("i").asText());
            int doubled = Integer.parseInt(vars.path("doubled").asText());
            assertEquals(i * 7, doubled, "after redefine doubled == i*7 (body swap reflected): " + vars);

            ObjectNode sid = mapper.createObjectNode();
            sid.put("sessionId", sessionId);
            callTool("debug.continue", sid);
            callTool("debug.terminate", sid);
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
