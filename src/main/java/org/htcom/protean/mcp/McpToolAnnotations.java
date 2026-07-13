/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * MCP tool behavior hints (ToolAnnotations; spec 2025-11-25, Tool). These hints are untrusted but useful for a
 * client's UX/gating decisions. Built-in protean tools populate them, and this is a library primitive that
 * <b>consumer tools can also expose via {@link McpTool#annotations()}</b>.
 *
 * <p>All fields are optional — a {@code null} field is omitted from serialization (advertise only what is
 * implemented, honestly; unset != false). Hint meanings:
 * <ul>
 *   <li>{@code readOnlyHint} — does not modify its environment. When true, destructive/idempotent are meaningless.
 *   <li>{@code destructiveHint} — may make destructive/irreversible changes (meaningful only when readOnly=false; the spec default convention is true).
 *   <li>{@code idempotentHint} — repeated calls with the same arguments have no additional effect.
 *   <li>{@code openWorldHint} — interacts with an external/open world (the web, etc.). When false, a closed domain.
 * </ul>
 */
public record McpToolAnnotations(
        String title,
        Boolean readOnlyHint,
        Boolean destructiveHint,
        Boolean idempotentHint,
        Boolean openWorldHint) {

    public static Builder builder() {
        return new Builder();
    }

    /** Read-only convention: readOnly=true, idempotent=true, openWorld=false. */
    public static McpToolAnnotations readOnly() {
        return builder().readOnly(true).idempotent(true).openWorld(false).build();
    }

    /** An object holding only the set hints ({@code null} if all are unset — omitted from serialization). */
    public ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode n = mapper.createObjectNode();
        if (title != null) {
            n.put("title", title);
        }
        if (readOnlyHint != null) {
            n.put("readOnlyHint", readOnlyHint);
        }
        if (destructiveHint != null) {
            n.put("destructiveHint", destructiveHint);
        }
        if (idempotentHint != null) {
            n.put("idempotentHint", idempotentHint);
        }
        if (openWorldHint != null) {
            n.put("openWorldHint", openWorldHint);
        }
        return n.isEmpty() ? null : n;
    }

    public static final class Builder {
        private String title;
        private Boolean readOnlyHint;
        private Boolean destructiveHint;
        private Boolean idempotentHint;
        private Boolean openWorldHint;

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder readOnly(boolean v) {
            this.readOnlyHint = v;
            return this;
        }

        public Builder destructive(boolean v) {
            this.destructiveHint = v;
            return this;
        }

        public Builder idempotent(boolean v) {
            this.idempotentHint = v;
            return this;
        }

        public Builder openWorld(boolean v) {
            this.openWorldHint = v;
            return this;
        }

        public McpToolAnnotations build() {
            return new McpToolAnnotations(title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint);
        }
    }
}
