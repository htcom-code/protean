/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.htcom.protean.compiler.Generation;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpException;
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolAnnotations;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.module.SharedLibStore;

/**
 * {@code protean.remove_shared_lib} — removes a lib from the store, publishing a new generation without it. In-use
 * older generations keep it. Returns the resulting store view.
 */
public class RemoveSharedLibTool implements McpTool {

    private final ObjectMapper mapper;
    private final SharedLibStore store;

    public RemoveSharedLibTool(ObjectMapper mapper, SharedLibStore store) {
        this.mapper = mapper;
        this.store = store;
    }

    @Override
    public String name() {
        return "protean.remove_shared_lib";
    }

    @Override
    public String description() {
        return "Removes a lib from the shared-lib store (future generations only; in-use generations keep it).";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode p = schema.putObject("properties");
        p.putObject("name").put("type", "string").put("description", "Stored lib name to remove");
        schema.putArray("required").add("name");
        return schema;
    }

    @Override
    public String title() {
        return "Remove Shared Lib";
    }

    @Override
    public McpToolAnnotations annotations() {
        // Removes a lib (destructive); not idempotent (a second remove of the same name errors); closed domain.
        return McpToolAnnotations.builder()
                .readOnly(false).destructive(true).idempotent(false).openWorld(false).build();
    }

    @Override
    public ObjectNode outputSchema() {
        return SharedLibToolSchemas.sharedLibsView(mapper);
    }

    @Override
    public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
        if (!arguments.hasNonNull("name")) {
            throw McpException.invalidParams("remove_shared_lib: name required");
        }
        String name = arguments.get("name").asText();
        Generation gen = store.remove(name);
        return McpToolResult.ok("Shared lib " + name + " removed → generation " + gen.id(),
                mapper.valueToTree(store.view()));
    }
}
