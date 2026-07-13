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
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.module.ModuleVersion;

import java.util.List;

/** {@code protean.module_versions} — module version history (newest first). */
public class ModuleVersionsTool implements McpTool {

    private final ObjectMapper mapper;
    private final ModulePlatform platform;

    public ModuleVersionsTool(ObjectMapper mapper, ModulePlatform platform) {
        this.mapper = mapper;
        this.platform = platform;
    }

    @Override
    public String name() {
        return "protean.module_versions";
    }

    @Override
    public String description() {
        return "Retrieves a module's version history, newest first (for identifying rollback targets).";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("id").put("type", "string").put("description", "Module id");
        schema.putArray("required").add("id");
        return schema;
    }

    @Override
    public String title() {
        return "Module Versions";
    }

    @Override
    public McpToolAnnotations annotations() {
        return McpToolAnnotations.readOnly();
    }

    @Override
    public ObjectNode outputSchema() {
        return ModuleToolSchemas.versionList(mapper);
    }

    @Override
    public ModuleActionAuthorizer.ModuleAction action() {
        return ModuleActionAuthorizer.ModuleAction.READ;
    }

    @Override
    public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
        if (!arguments.hasNonNull("id")) {
            throw McpException.invalidParams("module_versions: id required");
        }
        String id = arguments.get("id").asText();
        if (platform.find(id).isEmpty()) {
            return McpToolResult.error(ErrorCode.MODULE_NOT_FOUND, ErrorCode.MODULE_NOT_FOUND.format(id))
                    .with("moduleId", id);
        }
        List<ModuleVersion> history = platform.history(id);
        // The MCP spec requires structuredContent to be an object (arrays not allowed) -> wrap under the versions key.
        ObjectNode structured = mapper.createObjectNode();
        structured.set("versions", mapper.valueToTree(history));
        return McpToolResult.ok(history.size() + " version(s)", structured);
    }
}
