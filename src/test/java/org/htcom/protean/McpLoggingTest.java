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
import org.htcom.protean.mcp.McpException;
import org.htcom.protean.mcp.McpLogLevel;
import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.htcom.protean.mcp.session.McpServerNotifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * logging capability, logging/setLevel and notifications/message.
 * Captures emission through a notifier stub to verify threshold filtering (driving the dispatcher directly).
 */
class McpLoggingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ModuleActionAuthorizer allowAll =
            (caller, action, moduleId) -> ModuleActionAuthorizer.Decision.allow();

    /** Notifier stub that records broadcasts. */
    static final class CapturingNotifier implements McpServerNotifier {
        final List<JsonNode> sent = new ArrayList<>();
        @Override public void broadcast(JsonNode notification) { sent.add(notification); }
        @Override public void notifySession(String sessionId, JsonNode notification) { sent.add(notification); }
    }

    private JsonNode dispatch(McpDispatcher d, String method, ObjectNode params) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        return d.dispatch(req, McpCallContext.anonymous());
    }

    @Test
    void initialize_advertises_logging_capability() {
        McpDispatcher d = new McpDispatcher(mapper, List.of(), allowAll, null, null, null, null);
        JsonNode caps = dispatch(d, "initialize", null).path("result").path("capabilities");
        assertTrue(caps.has("logging"), "logging capability advertised: " + caps);
    }

    @Test
    void set_level_filters_emitted_logs() {
        CapturingNotifier notifier = new CapturingNotifier();
        McpDispatcher d = new McpDispatcher(mapper, List.of(), allowAll, null, null, notifier, null);

        // Default threshold INFO: DEBUG is suppressed, WARNING is emitted.
        d.emitLog(McpLogLevel.DEBUG, "test", mapper.createObjectNode().put("m", "debug"));
        d.emitLog(McpLogLevel.WARNING, "test", mapper.createObjectNode().put("m", "warn"));
        assertEquals(1, notifier.sent.size(), "at INFO threshold DEBUG is suppressed, WARNING is emitted");
        JsonNode msg = notifier.sent.get(0);
        assertEquals("notifications/message", msg.path("method").asText());
        assertEquals("warning", msg.path("params").path("level").asText());
        assertEquals("test", msg.path("params").path("logger").asText());
        assertEquals("warn", msg.path("params").path("data").path("m").asText());

        // Lowering the threshold to debug emits DEBUG as well.
        ObjectNode p = mapper.createObjectNode();
        p.put("level", "debug");
        dispatch(d, "logging/setLevel", p);
        assertEquals(McpLogLevel.DEBUG, d.logLevel());
        d.emitLog(McpLogLevel.DEBUG, "test", mapper.createObjectNode());
        assertEquals(2, notifier.sent.size(), "lowering the threshold to debug emits DEBUG");
    }

    @Test
    void set_level_unknown_is_invalid_params() {
        McpDispatcher d = new McpDispatcher(mapper, List.of(), allowAll, null, null, null, null);
        ObjectNode p = mapper.createObjectNode();
        p.put("level", "verbose");
        JsonNode resp = dispatch(d, "logging/setLevel", p);
        assertEquals(McpException.INVALID_PARAMS, resp.path("error").path("code").asInt());
    }

    @Test
    void emit_log_without_notifier_is_noop() {
        // Session surface off (notifier=null): no emission channel, so it is silently ignored (no exception).
        McpDispatcher d = new McpDispatcher(mapper, List.of(), allowAll, null, null, null, null);
        d.emitLog(McpLogLevel.ERROR, "test", null); // passes as long as no exception is thrown
    }
}
