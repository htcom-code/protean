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
 * (completeFromClient) matches a request sent via requestClient by request id and completes the future,
 * while unmatched responses and timeouts are handled correctly.
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

        // First request -> server request id is "srv:1" (monotonic, fresh registry).
        CompletableFuture<JsonNode> future = reg.requestClient(s.id(), "roots/list", null, 5_000);
        assertFalse(future.isDone(), "not done before the response");

        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", "srv:1");
        response.putObject("result").putArray("roots");

        assertTrue(reg.completeFromClient(response), "a matching response is consumed");
        JsonNode result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result.path("roots").isArray());
    }

    @Test
    void unmatched_response_is_not_consumed() {
        reg = new McpSessionRegistry(mapper, 0, 256, 3_600_000);
        reg.create();
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", "srv:999");   // no pending request
        response.putObject("result");
        assertFalse(reg.completeFromClient(response), "an unmatched response must be handed to the dispatcher (false)");
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
}
