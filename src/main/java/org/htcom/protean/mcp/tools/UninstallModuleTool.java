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

/** {@code protean.uninstall_module} — uninstalls a module. */
public class UninstallModuleTool implements McpTool {

    private final ObjectMapper mapper;
    private final ModulePlatform platform;

    public UninstallModuleTool(ObjectMapper mapper, ModulePlatform platform) {
        this.mapper = mapper;
        this.platform = platform;
    }

    @Override
    public String name() {
        return "protean.uninstall_module";
    }

    @Override
    public String description() {
        return "Uninstalls a module (removes its endpoints and context).";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("id").put("type", "string");
        schema.putArray("required").add("id");
        return schema;
    }

    @Override
    public String title() {
        return "Uninstall Module";
    }

    @Override
    public McpToolAnnotations annotations() {
        // Removes the module (destructive); idempotent since re-invoking after removal yields the same result.
        return McpToolAnnotations.builder()
                .readOnly(false).destructive(true).idempotent(true).openWorld(false).build();
    }

    @Override
    public ModuleActionAuthorizer.ModuleAction action() {
        return ModuleActionAuthorizer.ModuleAction.DELETE;
    }

    @Override
    public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
        if (!arguments.hasNonNull("id")) {
            throw McpException.invalidParams("uninstall_module: id is required");
        }
        String id = arguments.get("id").asText();
        if (platform.find(id).isEmpty()) {
            return McpToolResult.error(ErrorCode.MODULE_NOT_FOUND, ErrorCode.MODULE_NOT_FOUND.format(id))
                    .with("moduleId", id);
        }
        platform.uninstall(id);
        return McpToolResult.ok("Module " + id + " uninstalled");
    }
}
