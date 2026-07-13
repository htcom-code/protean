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
import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolAnnotations;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.mcp.ModuleActionAuthorizer;

/**
 * {@code config.get} — one {@code protean.*} config key's current value, tier, and {@code liveApplicable}.
 * Read-only; returns an error result if the key is unknown.
 */
public class ConfigGetTool implements McpTool {

    private final ObjectMapper mapper;
    private final ProteanConfigService configService;

    public ConfigGetTool(ObjectMapper mapper, ProteanConfigService configService) {
        this.mapper = mapper;
        this.configService = configService;
    }

    @Override
    public String name() {
        return "config.get";
    }

    @Override
    public String title() {
        return "Get Config";
    }

    @Override
    public String description() {
        return "Returns a single protean.* configuration key's current value, mutability tier, and liveApplicable. "
                + "Accepts the key with or without the 'protean.' prefix. Read-only. "
                + "Call config.list to discover the available keys.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode p = schema.putObject("properties");
        p.putObject("key").put("type", "string")
                .put("description", "config key, e.g. trace.capacity (the 'protean.' prefix is optional)");
        schema.putArray("required").add("key");
        return schema;
    }

    @Override
    public ObjectNode outputSchema() {
        return ConfigToolSchemas.configGet(mapper);
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
        String key = arguments == null ? null : arguments.path("key").asText(null);
        if (key == null || key.isBlank()) {
            return McpToolResult.error(ErrorCode.INVALID_ARGUMENT, "'key' is required");
        }
        return configService.get(key)
                .map(entry -> McpToolResult.ok(entry.key() + " = " + entry.value(), mapper.valueToTree(entry)))
                .orElseGet(() -> McpToolResult.error(ErrorCode.INVALID_ARGUMENT, "unknown config key: " + key));
    }
}
