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
import org.htcom.protean.mcp.McpLogLevel;
import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.htcom.protean.mcp.session.McpServerNotifier;
import org.htcom.protean.mcp.session.McpSession;
import org.htcom.protean.mcp.session.McpSessionRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * One MCP session's settings never decide what another session receives: the log threshold
 * ({@code logging/setLevel}) and resource subscriptions are per session, and per-session state is dropped when the
 * session ends. A session is usable only by the principal that created it.
 */
class McpSessionIsolationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ModuleActionAuthorizer allowAll =
            (caller, action, moduleId) -> ModuleActionAuthorizer.Decision.allow();

    /** Session-aware notifier over a fixed set of sessions; records which session each notification reached. */
    static final class SessionsNotifier implements McpServerNotifier {
        final List<String> sessions;
        final List<String> delivered = new ArrayList<>();

        SessionsNotifier(String... sessions) {
            this.sessions = List.of(sessions);
        }

        @Override public void broadcast(JsonNode notification) { delivered.addAll(sessions); }
        @Override public void notifySession(String sessionId, JsonNode notification) { delivered.add(sessionId); }
        @Override public void broadcast(JsonNode notification, Predicate<String> toSession) {
            sessions.stream().filter(toSession).forEach(delivered::add);
        }
    }

    @Test
    void set_level_changes_only_the_calling_session() {
        SessionsNotifier notifier = new SessionsNotifier("A", "B");
        McpDispatcher d = dispatcher(notifier);

        setLevel(d, "A", "debug");
        assertEquals(McpLogLevel.DEBUG, d.logLevel("A"));
        assertEquals(McpLogLevel.INFO, d.logLevel("B"), "another session's threshold must not move");

        d.emitLog(McpLogLevel.DEBUG, "t", null);
        assertEquals(List.of("A"), notifier.delivered, "DEBUG reaches only the session that asked for it");
    }

    @Test
    void session_less_set_level_does_not_change_any_session() {
        SessionsNotifier notifier = new SessionsNotifier("A", "B");
        McpDispatcher d = dispatcher(notifier);

        setLevel(d, null, "emergency");
        d.emitLog(McpLogLevel.WARNING, "t", null);
        assertEquals(List.of("A", "B"), notifier.delivered, "sessions keep their own (default) threshold");
    }

    @Test
    void resource_updates_reach_only_subscribed_sessions() {
        SessionsNotifier notifier = new SessionsNotifier("A", "B");
        McpDispatcher d = dispatcher(notifier);

        ObjectNode params = mapper.createObjectNode().put("uri", "protean://modules");
        dispatch(d, "A", "resources/subscribe", params);
        d.notifyResourceUpdated("protean://modules");
        assertEquals(List.of("A"), notifier.delivered, "an unsubscribed session must not be notified");

        notifier.delivered.clear();
        dispatch(d, "A", "resources/unsubscribe", params);
        d.notifyResourceUpdated("protean://modules");
        assertEquals(List.of(), notifier.delivered);
    }

    @Test
    void session_state_is_dropped_when_the_session_ends() {
        McpSessionRegistry registry = new McpSessionRegistry(mapper, 0, 256, 3_600_000);
        try {
            McpDispatcher d = dispatcher(registry);
            McpSession s = registry.create();
            setLevel(d, s.id(), "debug");
            assertEquals(McpLogLevel.DEBUG, d.logLevel(s.id()));

            registry.terminate(s.id());
            assertEquals(McpLogLevel.INFO, d.logLevel(s.id()), "a terminated session leaves no state behind");
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void session_is_usable_only_by_its_creator() {
        McpSessionRegistry registry = new McpSessionRegistry(mapper, 0, 256, 3_600_000);
        try {
            McpSession s = registry.create("alice");
            assertNotNull(registry.get(s.id(), "alice"));
            assertNull(registry.get(s.id(), "bob"), "another principal must not see the session");
            assertNull(registry.get(s.id(), null), "an unauthenticated request must not use an owned session");

            McpSession anonymous = registry.create();
            assertNotNull(registry.get(anonymous.id(), null));
            assertNull(registry.get(anonymous.id(), "alice"));
        } finally {
            registry.shutdown();
        }
    }

    // --- helpers ---

    private McpDispatcher dispatcher(McpServerNotifier notifier) {
        return new McpDispatcher(mapper, List.of(), allowAll, null, null, notifier, null);
    }

    private void setLevel(McpDispatcher d, String sessionId, String level) {
        dispatch(d, sessionId, "logging/setLevel", mapper.createObjectNode().put("level", level));
    }

    private JsonNode dispatch(McpDispatcher d, String sessionId, String method, ObjectNode params) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", method);
        req.set("params", params);
        JsonNode resp = d.dispatch(req, new McpCallContext(null, McpCallContext.NOOP, sessionId));
        assertNull(resp.get("error"), method + " failed: " + resp);
        return resp;
    }
}
