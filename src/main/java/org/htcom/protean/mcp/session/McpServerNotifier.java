/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.session;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Server-to-client notification channel. Used when the server actively pushes a notification outside of a call —
 * e.g. {@code notifications/tools/list_changed} invokes this.
 *
 * <p>The implementation ({@link McpSessionRegistry}) delivers over the connected session's persistent SSE stream,
 * assigning each message a session-scoped eventId and recording it in the replay buffer. This bean does not exist
 * when the session surface is disabled (it is not a no-op target — consumers inject it optionally via
 * {@code ObjectProvider}).
 */
public interface McpServerNotifier {

    /** Broadcasts a JSON-RPC notification to all connected sessions. */
    void broadcast(JsonNode notification);

    /** Sends a notification to a single session. Ignored if the session does not exist. */
    void notifySession(String sessionId, JsonNode notification);
}
