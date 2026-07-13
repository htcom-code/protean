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
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.web.ModuleStatus;

import java.util.Optional;

/** {@code protean.get_module} — status of a single module. Returns a tool result with isError if not found. */
public class GetModuleTool implements McpTool {

    private final ObjectMapper mapper;
    private final ModulePlatform platform;

    public GetModuleTool(ObjectMapper mapper, ModulePlatform platform) {
        this.mapper = mapper;
        this.platform = platform;
    }

    @Override
    public String name() {
        return "protean.get_module";
    }

    @Override
    public String description() {
        return "Retrieves the status of a single module by id.";
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
        return "Get Module";
    }

    @Override
    public McpToolAnnotations annotations() {
        return McpToolAnnotations.readOnly();
    }

    @Override
    public ObjectNode outputSchema() {
        return ModuleToolSchemas.moduleStatus(mapper);
    }

    @Override
    public ModuleActionAuthorizer.ModuleAction action() {
        return ModuleActionAuthorizer.ModuleAction.READ;
    }

    @Override
    public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
        if (!arguments.hasNonNull("id")) {
            throw McpException.invalidParams("get_module: id required");
        }
        String id = arguments.get("id").asText();
        Optional<ModuleDescriptor> found = platform.find(id);
        if (found.isEmpty()) {
            return McpToolResult.error(ErrorCode.MODULE_NOT_FOUND, ErrorCode.MODULE_NOT_FOUND.format(id))
                    .with("moduleId", id);
        }
        ModuleDescriptor d = found.get();
        JsonNode structured = mapper.valueToTree(
                ModuleStatus.from(d, platform.effectiveMode(d), platform.boundGeneration(id),
                        platform.boundLibraryGenerations(id), platform.libraryGeneration(id)));
        return McpToolResult.ok("Module " + id, structured);
    }
}
