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

    /**
     * Sends a notification only to the sessions {@code toSession} accepts (it is given each session id). Used for
     * per-session state such as the log threshold and resource subscriptions, so one client's settings never decide
     * what another receives.
     *
     * <p>The default is for notifiers that do not track sessions: they are treated as a single session-less
     * audience, asked as {@code toSession.test(null)}. Session-aware implementations should override this.
     */
    default void broadcast(JsonNode notification, java.util.function.Predicate<String> toSession) {
        if (toSession.test(null)) {
            broadcast(notification);
        }
    }
}
