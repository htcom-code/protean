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
import org.htcom.protean.mcp.session.McpSession;
import org.htcom.protean.mcp.session.McpSessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server-to-client request correlation in {@link McpSessionRegistry}: a client response
 * (completeFromClient) matches a request sent via requestClient by request id <b>on the session it was sent to</b>
 * and completes the future, while unmatched or other-session responses and timeouts are handled correctly.
 */
class McpClientRequestCorrelationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private McpSessionRegistry reg;

    @AfterEach
    void tearDown() {
        if (reg != null) {
            reg.shutdown();
        }
    }

    @Test
    void client_response_completes_pending_request() throws Exception {
        reg = new McpSessionRegistry(mapper, 0, 256, 3_600_000);
        McpSession s = reg.create();

        CompletableFuture<JsonNode> future = reg.requestClient(s.id(), "roots/list", null, 5_000);
        assertFalse(future.isDone(), "not done before the response");

        assertTrue(reg.completeFromClient(s.id(), rootsResponse(onlyPendingId())), "a matching response is consumed");
        JsonNode result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result.path("roots").isArray());
    }

    @Test
    void response_on_another_session_is_not_consumed() {
        reg = new McpSessionRegistry(mapper, 0, 256, 3_600_000);
        McpSession target = reg.create();
        McpSession other = reg.create();
        CompletableFuture<JsonNode> future = reg.requestClient(target.id(), "roots/list", null, 5_000);
        String id = onlyPendingId();

        assertFalse(reg.completeFromClient(other.id(), rootsResponse(id)), "another session's response is refused");
        assertFalse(future.isDone(), "the request stays pending for its own session");
        assertTrue(reg.completeFromClient(target.id(), rootsResponse(id)));
    }

    @Test
    void unmatched_response_is_not_consumed() {
        reg = new McpSessionRegistry(mapper, 0, 256, 3_600_000);
        McpSession s = reg.create();
        assertFalse(reg.completeFromClient(s.id(), rootsResponse("srv:999")),
                "an unmatched response must not be consumed (false)");
    }

    @Test
    @SuppressWarnings("removal")
    void legacy_sessionless_matching_never_completes() {
        reg = new McpSessionRegistry(mapper, 0, 256, 3_600_000);
        McpSession s = reg.create();
        CompletableFuture<JsonNode> future = reg.requestClient(s.id(), "roots/list", null, 5_000);
        assertFalse(reg.completeFromClient(rootsResponse(onlyPendingId())));
        assertFalse(future.isDone());
    }

    @Test
    void request_to_unknown_session_fails_fast() {
        reg = new McpSessionRegistry(mapper, 0, 256, 3_600_000);
        CompletableFuture<JsonNode> future = reg.requestClient("no-such", "roots/list", null, 5_000);
        assertTrue(future.isCompletedExceptionally(), "with no session, completes exceptionally right away");
    }

    @Test
    void request_times_out_without_response() {
        reg = new McpSessionRegistry(mapper, 0, 256, 3_600_000);
        McpSession s = reg.create();
        CompletableFuture<JsonNode> future = reg.requestClient(s.id(), "roots/list", null, 100);
        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof TimeoutException, "timeout cause: " + ex.getCause());
    }

    private ObjectNode rootsResponse(String id) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.putObject("result").putArray("roots");
        return response;
    }

    /** The id of the single in-flight server request. Ids are random by design, so read it from the registry. */
    private String onlyPendingId() {
        try {
            java.lang.reflect.Field f = McpSessionRegistry.class.getDeclaredField("pending");
            f.setAccessible(true);
            java.util.Map<?, ?> pending = (java.util.Map<?, ?>) f.get(reg);
            assertEquals(1, pending.size(), "exactly one request in flight");
            return (String) pending.keySet().iterator().next();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
