/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.session;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;

/**
 * Server-to-client <b>request</b> channel. Unlike {@link McpServerNotifier} (one-way notifications), this is
 * bidirectional JSON-RPC that waits for a response — sampling/createMessage, roots/list, and elicitation/create
 * flow through it. The server pushes a request over the session's persistent stream, and when the client POSTs a
 * response back to the same endpoint, it is matched by request id to complete the future.
 *
 * <p>Implemented by {@link McpSessionRegistry} (which holds the stream and correlation state). This bean does not
 * exist when the session surface is disabled.
 */
public interface McpClientChannel {

    /**
     * Sends a JSON-RPC request to the session's client and returns a future for the response. If there is no stream
     * or the request times out, the future completes exceptionally. {@code params} may be null.
     */
    CompletableFuture<JsonNode> requestClient(String sessionId, String method, JsonNode params, long timeoutMillis);

    /** Client capabilities the session reported at initialize (null if none). Used to gate whether a request is allowed. */
    JsonNode clientCapabilities(String sessionId);
}
