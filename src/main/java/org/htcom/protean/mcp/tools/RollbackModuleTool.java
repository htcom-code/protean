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
import org.htcom.protean.mcp.McpException;
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolAnnotations;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.web.ModuleStatus;

/** {@code protean.rollback_module} — reverts to a specific version from history (canary hot-swap + gate/validation). */
public class RollbackModuleTool implements McpTool {

    private final ObjectMapper mapper;
    private final ModulePlatform platform;

    public RollbackModuleTool(ObjectMapper mapper, ModulePlatform platform) {
        this.mapper = mapper;
        this.platform = platform;
    }

    @Override
    public String name() {
        return "protean.rollback_module";
    }

    @Override
    public String description() {
        return "Rolls a module back to a specific version from history (use module_versions to identify the target).";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("id").put("type", "string");
        props.putObject("version").put("type", "string").put("description", "Target version to revert to");
        schema.putArray("required").add("id").add("version");
        return schema;
    }

    @Override
    public String title() {
        return "Rollback Module";
    }

    @Override
    public McpToolAnnotations annotations() {
        // Reverts to a previous version and records the result as a new version — non-destructive, non-idempotent since repeating changes the state.
        return McpToolAnnotations.builder()
                .readOnly(false).destructive(false).idempotent(false).openWorld(false).build();
    }

    @Override
    public ObjectNode outputSchema() {
        return ModuleToolSchemas.moduleStatus(mapper);
    }

    @Override
    public ModuleActionAuthorizer.ModuleAction action() {
        return ModuleActionAuthorizer.ModuleAction.UPDATE;
    }

    @Override
    public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
        if (!arguments.hasNonNull("id") || !arguments.hasNonNull("version")) {
            throw McpException.invalidParams("rollback_module: id and version are required");
        }
        String id = arguments.get("id").asText();
        String version = arguments.get("version").asText();
        platform.rollback(id, version);
        ModuleDescriptor saved = platform.find(id)
                .orElseThrow(() -> new IllegalStateException("Module not found in store immediately after rollback: " + id));
        JsonNode structured = mapper.valueToTree(ModuleStatus.from(saved, platform.effectiveMode(saved)));
        return McpToolResult.ok("Module " + id + " rolled back → v" + saved.version(), structured);
    }
}
