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
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies cancellation, _meta pass-through, and authorization-failure text by driving the dispatcher
 * directly.
 */
class McpCancelMetaTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ModuleActionAuthorizer allowAll =
            (caller, action, moduleId) -> ModuleActionAuthorizer.Decision.allow();

    private ObjectNode callReq(Object id, String tool, ObjectNode args, ObjectNode meta) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", String.valueOf(id));
        req.put("method", "tools/call");
        ObjectNode p = req.putObject("params");
        p.put("name", tool);
        p.set("arguments", args == null ? mapper.createObjectNode() : args);
        if (meta != null) {
            p.set("_meta", meta);
        }
        return req;
    }

    private ObjectNode inputObj() {
        ObjectNode s = mapper.createObjectNode();
        s.put("type", "object");
        return s;
    }

    @Test
    void cancelled_notification_aborts_inflight_tool() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        McpTool blocking = new McpTool() {
            @Override public String name() { return "test.block"; }
            @Override public String description() { return "Long-running tool for verifying cooperative cancellation"; }
            @Override public ObjectNode inputSchema() { return inputObj(); }
            @Override public McpToolResult call(JsonNode args, McpCallContext ctx) {
                started.countDown();
                for (int i = 0; i < 10_000; i++) {
                    ctx.progress().report(i, 0, "step"); // aborts via throwIfCancelled when cancelled
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted");
                    }
                }
                return McpToolResult.ok("completed (not cancelled)");
            }
        };
        McpDispatcher d = new McpDispatcher(mapper, List.of(blocking), allowAll, null, null, null, null);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            McpCallContext ctx = new McpCallContext(null, McpCallContext.NOOP, "sess1");
            Future<JsonNode> f = ex.submit(() -> d.dispatch(callReq("req1", "test.block", null, null), ctx));
            assertTrue(started.await(2, TimeUnit.SECONDS), "the tool must have started");
            Thread.sleep(30);

            // Cancel notification via a separate request - same session and requestId.
            ObjectNode cancel = mapper.createObjectNode();
            cancel.put("jsonrpc", "2.0");
            cancel.put("method", "notifications/cancelled");
            ObjectNode cp = cancel.putObject("params");
            cp.put("requestId", "req1");
            cp.put("reason", "user aborted");
            d.dispatch(cancel, ctx);

            JsonNode resp = f.get(3, TimeUnit.SECONDS);
            assertTrue(resp.path("result").path("isError").asBoolean(false),
                    "a cancelled request yields an isError result: " + resp);
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void request_meta_flows_to_tool_and_result_meta_serialized() {
        // The tool reads ctx.meta() and echoes it back as result _meta -> confirms pass-through both ways.
        McpTool echoMeta = new McpTool() {
            @Override public String name() { return "test.meta"; }
            @Override public String description() { return "_meta echo"; }
            @Override public ObjectNode inputSchema() { return inputObj(); }
            @Override public McpToolResult call(JsonNode args, McpCallContext ctx) {
                return McpToolResult.ok("ok").withMeta(ctx.meta());
            }
        };
        McpDispatcher d = new McpDispatcher(mapper, List.of(echoMeta), allowAll, null, null, null, null);
        ObjectNode meta = mapper.createObjectNode();
        meta.put("trace", "abc-123");
        JsonNode resp = d.dispatch(callReq("r2", "test.meta", null, meta), McpCallContext.anonymous());
        JsonNode result = resp.path("result");
        assertFalse(result.path("isError").asBoolean(false));
        assertEquals("abc-123", result.path("_meta").path("trace").asText(), "request _meta flows to result _meta: " + result);
    }

    @Test
    void authorization_denied_has_clear_error_text() {
        ModuleActionAuthorizer deny =
                (caller, action, moduleId) -> ModuleActionAuthorizer.Decision.deny("forbidden by policy");
        McpTool anyTool = new McpTool() {
            @Override public String name() { return "test.guarded"; }
            @Override public String description() { return "For authorization checks"; }
            @Override public ObjectNode inputSchema() { return inputObj(); }
            @Override public McpToolResult call(JsonNode args, McpCallContext ctx) { return McpToolResult.ok("x"); }
        };
        McpDispatcher d = new McpDispatcher(mapper, List.of(anyTool), deny, null, null, null, null);
        JsonNode result = d.dispatch(callReq("r3", "test.guarded", null, null), McpCallContext.anonymous()).path("result");
        assertTrue(result.path("isError").asBoolean(false));
        // RFC 9457 - [CODE]-prefixed text (for lossy harnesses) plus problem-detail in structuredContent.
        String text = result.path("content").get(0).path("text").asText();
        assertTrue(text.contains("[PERMISSION_DENIED]"), "code-prefixed text: " + text);
        JsonNode problem = result.path("structuredContent");
        assertEquals("PERMISSION_DENIED", problem.path("code").asText(), "discriminator key (code)");
        assertEquals("test.guarded", problem.path("tool").asText(), "tool name carried as structured correction data");
        assertEquals("CUSTOM", problem.path("action").asText(), "action is structured too");
    }
}
