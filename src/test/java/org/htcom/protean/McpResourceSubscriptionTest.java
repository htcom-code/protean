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
import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.htcom.protean.mcp.session.McpServerNotifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the dispatcher semantics of resources/subscribe, resources/unsubscribe and
 * notifications/resources/updated via a notifier stub (only subscribed URIs, no emission after unsubscribe).
 */
class McpResourceSubscriptionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ModuleActionAuthorizer allowAll =
            (caller, action, moduleId) -> ModuleActionAuthorizer.Decision.allow();

    static final class CapturingNotifier implements McpServerNotifier {
        final List<JsonNode> sent = new ArrayList<>();
        @Override public void broadcast(JsonNode n) { sent.add(n); }
        @Override public void notifySession(String id, JsonNode n) { sent.add(n); }
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

    private ObjectNode uri(String u) {
        ObjectNode p = mapper.createObjectNode();
        p.put("uri", u);
        return p;
    }

    @Test
    void initialize_advertises_resource_subscribe() {
        McpDispatcher d = new McpDispatcher(mapper, List.of(), allowAll, null, null, null, null);
        JsonNode resources = dispatch(d, "initialize", null).path("result").path("capabilities").path("resources");
        assertTrue(resources.path("subscribe").asBoolean(), "resources.subscribe advertised: " + resources);
    }

    @Test
    void updates_only_delivered_for_subscribed_uris() {
        CapturingNotifier notifier = new CapturingNotifier();
        McpDispatcher d = new McpDispatcher(mapper, List.of(), allowAll, null, null, notifier, null);

        // Before subscribing: notifying does not emit.
        d.notifyResourceUpdated("protean://modules");
        assertEquals(0, notifier.sent.size(), "no emission before subscribing");

        dispatch(d, "resources/subscribe", uri("protean://modules"));
        d.notifyResourceUpdated("protean://modules");
        d.notifyResourceUpdated("protean://modules/other/source"); // unsubscribed uri
        assertEquals(1, notifier.sent.size(), "only subscribed URIs are emitted");
        JsonNode notif = notifier.sent.get(0);
        assertEquals("notifications/resources/updated", notif.path("method").asText());
        assertEquals("protean://modules", notif.path("params").path("uri").asText());

        // After unsubscribing: no emission again.
        dispatch(d, "resources/unsubscribe", uri("protean://modules"));
        d.notifyResourceUpdated("protean://modules");
        assertEquals(1, notifier.sent.size(), "no emission after unsubscribing");
    }

    @Test
    void subscribe_without_uri_is_invalid_params() {
        McpDispatcher d = new McpDispatcher(mapper, List.of(), allowAll, null, null, null, null);
        JsonNode resp = dispatch(d, "resources/subscribe", mapper.createObjectNode());
        assertEquals(McpException.INVALID_PARAMS, resp.path("error").path("code").asInt());
    }
}
