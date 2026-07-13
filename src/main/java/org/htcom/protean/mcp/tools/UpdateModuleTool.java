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
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolAnnotations;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.htcom.protean.mcp.ModuleInputNormalizer;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.web.ModuleStatus;

/**
 * {@code protean.update_module} — canary hot-swap update. Input shape is identical to deploy (files[]/manifest).
 * Atomic swap → gate validation → automatic rollback on failure (delegated to ModulePlatform).
 */
public class UpdateModuleTool implements McpTool {

    private final ObjectMapper mapper;
    private final ModulePlatform platform;
    private final ModuleInputNormalizer normalizer;

    public UpdateModuleTool(ObjectMapper mapper, ModulePlatform platform, ModuleInputNormalizer normalizer) {
        this.mapper = mapper;
        this.platform = platform;
        this.normalizer = normalizer;
    }

    @Override
    public String name() {
        return "protean.update_module";
    }

    @Override
    public String description() {
        return "Canary-updates an existing module to a new version (input is identical to deploy: files[] or manifest).";
    }

    @Override
    public ObjectNode inputSchema() {
        // Same input contract as deploy_module (shared normalizer) → reuse the common schema.
        return ModuleToolSchemas.moduleInput(mapper);
    }

    @Override
    public String title() {
        return "Update Module";
    }

    @Override
    public McpToolAnnotations annotations() {
        // Full-replacement update — non-destructive since it can be reverted via version history, non-idempotent since each call creates a new version.
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
        ModuleDescriptor descriptor = normalizer.normalize(arguments);
        if (platform.find(descriptor.id()).isEmpty()) {
            return McpToolResult.error(ErrorCode.MODULE_NOT_FOUND,
                    ErrorCode.MODULE_NOT_FOUND.format(descriptor.id())).with("moduleId", descriptor.id());
        }
        int[] step = {0};
        platform.update(descriptor, msg -> ctx.progress().report(++step[0], 0, msg));
        ModuleDescriptor saved = platform.find(descriptor.id())
                .orElseThrow(() -> new IllegalStateException("Module not found in store immediately after update: " + descriptor.id()));
        JsonNode structured = mapper.valueToTree(ModuleStatus.from(saved, platform.effectiveMode(saved)));
        return McpToolResult.ok("Module " + saved.id() + " updated (v" + saved.version() + ")", structured);
    }
}
