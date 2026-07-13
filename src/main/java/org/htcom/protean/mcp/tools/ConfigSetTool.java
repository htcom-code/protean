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
import org.htcom.protean.config.ProteanConfigService.ApplyResult;
import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.mcp.ModuleActionAuthorizer;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code config.set} — applies a batch of {@code protean.*} config changes. The batch is atomic on validation:
 * an unknown/invalid key aborts the whole batch (nothing applied); restart-tier keys are reported without being
 * applied. A privileged mutation (not read-only) — routed through {@link ModuleActionAuthorizer} like the other
 * write tools.
 */
public class ConfigSetTool implements McpTool {

    private final ObjectMapper mapper;
    private final ProteanConfigService configService;

    public ConfigSetTool(ObjectMapper mapper, ProteanConfigService configService) {
        this.mapper = mapper;
        this.configService = configService;
    }

    @Override
    public String name() {
        return "config.set";
    }

    @Override
    public String title() {
        return "Set Config";
    }

    @Override
    public String description() {
        return "Applies a batch of protean.* config changes given as {changes: {key: value}}. Atomic on "
                + "validation: an unknown or invalid value aborts the whole batch (nothing applied). LIVE keys take "
                + "effect immediately, FUTURE keys for instances created afterward, and restart-tier keys are "
                + "reported REQUIRES_RESTART without being applied. Returns a per-key outcome. "
                + "Call config.list first to discover the available keys and which are runtime-settable (tier LIVE/FUTURE).";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode p = schema.putObject("properties");
        ObjectNode changes = p.putObject("changes");
        changes.put("type", "object");
        changes.put("description", "map of config key → new value (the 'protean.' prefix is optional). "
                + "See config.list for the available keys and their mutability tiers.");
        schema.putArray("required").add("changes");
        return schema;
    }

    @Override
    public ObjectNode outputSchema() {
        return ConfigToolSchemas.configSet(mapper);
    }

    @Override
    public ModuleActionAuthorizer.ModuleAction action() {
        return ModuleActionAuthorizer.ModuleAction.CUSTOM;
    }

    @Override
    public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
        JsonNode changesNode = arguments == null ? null : arguments.get("changes");
        if (changesNode == null || !changesNode.isObject() || changesNode.isEmpty()) {
            return McpToolResult.error(ErrorCode.INVALID_ARGUMENT, "'changes' must be a non-empty object of key->value");
        }
        Map<String, JsonNode> patch = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : changesNode.properties()) {
            patch.put(e.getKey(), e.getValue());
        }

        ApplyResult result = configService.apply(patch, actor(ctx));
        if (!result.applied()) {
            return McpToolResult.error(ErrorCode.INVALID_ARGUMENT,
                    "config change rejected (unknown/invalid key); nothing applied")
                    .with("outcomes", result.outcomes());
        }
        return McpToolResult.ok(summary(result), mapper.valueToTree(result));
    }

    private static String actor(McpCallContext ctx) {
        Principal caller = ctx == null ? null : ctx.caller();
        return "mcp:" + (caller != null ? caller.getName() : "anonymous");
    }

    private static String summary(ApplyResult result) {
        long applied = result.outcomes().stream()
                .filter(o -> o.outcome() == ProteanConfigService.Outcome.APPLIED_LIVE
                        || o.outcome() == ProteanConfigService.Outcome.APPLIED_FUTURE)
                .count();
        long restart = result.outcomes().stream()
                .filter(o -> o.outcome() == ProteanConfigService.Outcome.REQUIRES_RESTART)
                .count();
        return "applied " + applied + " key(s)" + (restart > 0 ? ", " + restart + " require restart" : "");
    }
}
