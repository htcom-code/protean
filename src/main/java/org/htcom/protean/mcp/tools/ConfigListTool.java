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
import org.htcom.protean.config.ProteanConfigService;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolAnnotations;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.mcp.ModuleActionAuthorizer;

import java.util.List;

/**
 * {@code config.list} — every known {@code protean.*} config key with its current value, tier, and whether a
 * change takes effect live now ({@code liveApplicable}). Read-only.
 */
public class ConfigListTool implements McpTool {

    private final ObjectMapper mapper;
    private final ProteanConfigService configService;

    public ConfigListTool(ObjectMapper mapper, ProteanConfigService configService) {
        this.mapper = mapper;
        this.configService = configService;
    }

    @Override
    public String name() {
        return "config.list";
    }

    @Override
    public String title() {
        return "List Config";
    }

    @Override
    public String description() {
        return "Lists every known protean.* configuration key with its current value, mutability tier "
                + "(LIVE/FUTURE/RESTART_CONDITIONAL/RESTART_ARTIFACT), and liveApplicable (whether a change takes "
                + "effect at runtime now). Read-only.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    @Override
    public ObjectNode outputSchema() {
        return ConfigToolSchemas.configList(mapper);
    }

    @Override
    public McpToolAnnotations annotations() {
        return McpToolAnnotations.readOnly();
    }

    @Override
    public ModuleActionAuthorizer.ModuleAction action() {
        return ModuleActionAuthorizer.ModuleAction.READ;
    }

    @Override
    public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
        List<ProteanConfigService.ConfigEntry> entries = configService.list();
        ObjectNode structured = mapper.createObjectNode();
        structured.set("configs", mapper.valueToTree(entries));
        return McpToolResult.ok(entries.size() + " config key(s)", structured);
    }
}
