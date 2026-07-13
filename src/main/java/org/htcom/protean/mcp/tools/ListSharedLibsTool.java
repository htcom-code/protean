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
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolAnnotations;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.htcom.protean.module.SharedLibStore;

/** {@code protean.list_shared_libs} — the current shared-lib generation id and the live stored libs. */
public class ListSharedLibsTool implements McpTool {

    private final ObjectMapper mapper;
    private final SharedLibStore store;

    public ListSharedLibsTool(ObjectMapper mapper, SharedLibStore store) {
        this.mapper = mapper;
        this.store = store;
    }

    @Override
    public String name() {
        return "protean.list_shared_libs";
    }

    @Override
    public String description() {
        return "Lists the live shared-lib store: the current parent-tier generation id and every stored lib.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    @Override
    public String title() {
        return "List Shared Libs";
    }

    @Override
    public McpToolAnnotations annotations() {
        return McpToolAnnotations.readOnly();
    }

    @Override
    public ObjectNode outputSchema() {
        return SharedLibToolSchemas.sharedLibsView(mapper);
    }

    @Override
    public ModuleActionAuthorizer.ModuleAction action() {
        return ModuleActionAuthorizer.ModuleAction.READ;
    }

    @Override
    public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
        SharedLibStore.SharedLibsView view = store.view();
        return McpToolResult.ok("Shared libs: " + view.libs().size()
                + " (generation " + view.generation() + ")", mapper.valueToTree(view));
    }
}
