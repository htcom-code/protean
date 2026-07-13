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
import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpException;
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolAnnotations;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.htcom.protean.module.SharedLibStore;

/** {@code protean.get_shared_lib} — metadata for a single stored lib by name (isError if not stored). */
public class GetSharedLibTool implements McpTool {

    private final ObjectMapper mapper;
    private final SharedLibStore store;

    public GetSharedLibTool(ObjectMapper mapper, SharedLibStore store) {
        this.mapper = mapper;
        this.store = store;
    }

    @Override
    public String name() {
        return "protean.get_shared_lib";
    }

    @Override
    public String description() {
        return "Retrieves the metadata (version, sha256, size, signer) of a single stored shared lib by name.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode p = schema.putObject("properties");
        p.putObject("name").put("type", "string").put("description", "Stored lib name");
        schema.putArray("required").add("name");
        return schema;
    }

    @Override
    public String title() {
        return "Get Shared Lib";
    }

    @Override
    public McpToolAnnotations annotations() {
        return McpToolAnnotations.readOnly();
    }

    @Override
    public ObjectNode outputSchema() {
        return SharedLibToolSchemas.storedLib(mapper);
    }

    @Override
    public ModuleActionAuthorizer.ModuleAction action() {
        return ModuleActionAuthorizer.ModuleAction.READ;
    }

    @Override
    public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
        if (!arguments.hasNonNull("name")) {
            throw McpException.invalidParams("get_shared_lib: name required");
        }
        String name = arguments.get("name").asText();
        return store.get(name)
                .map(lib -> McpToolResult.ok("Shared lib " + name, mapper.valueToTree(lib)))
                .orElseGet(() -> McpToolResult.error(ErrorCode.SHARED_LIB_NOT_FOUND,
                        ErrorCode.SHARED_LIB_NOT_FOUND.format(name)).with("name", name));
    }
}
