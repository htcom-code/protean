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
import org.htcom.protean.mcp.ModuleInputNormalizer;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.web.ModuleStatus;

/**
 * {@code protean.deploy_module} — deploys a module. Input is either files[] (source-file array) or
 * manifest (module.yaml text). It runs through all validation gates synchronously; gate rejections
 * and compile failures are thrown as exceptions that the dispatcher maps to a tool result with
 * {@code isError} (plus diagnostics). If the approval gate is enabled, it reports PENDING_APPROVAL.
 */
public class DeployModuleTool implements McpTool {

    private final ObjectMapper mapper;
    private final ModulePlatform platform;
    private final ModuleInputNormalizer normalizer;

    public DeployModuleTool(ObjectMapper mapper, ModulePlatform platform, ModuleInputNormalizer normalizer) {
        this.mapper = mapper;
        this.platform = platform;
        this.normalizer = normalizer;
    }

    @Override
    public String name() {
        return "protean.deploy_module";
    }

    @Override
    public String description() {
        return "Deploys a module. Input is either files[] (recommended: id/version/controller + array of source and test files) "
                + "or manifest (module.yaml text). Must pass the gates (test, review, validation) to become ACTIVE.";
    }

    @Override
    public ObjectNode inputSchema() {
        // files[] XOR manifest, with conditional required expressed via oneOf. Same contract as update_module, so use the shared builder.
        // ModuleInputNormalizer is the single source of truth for runtime validation; this schema is the client-facing contract.
        return ModuleToolSchemas.moduleInput(mapper);
    }

    @Override
    public String title() {
        return "Deploy Module";
    }

    @Override
    public McpToolAnnotations annotations() {
        // Creates a new module (non-destructive), but non-idempotent due to gates and side effects; closed domain.
        return McpToolAnnotations.builder()
                .readOnly(false).destructive(false).idempotent(false).openWorld(false).build();
    }

    @Override
    public ObjectNode outputSchema() {
        return ModuleToolSchemas.moduleStatus(mapper);
    }

    @Override
    public ModuleActionAuthorizer.ModuleAction action() {
        return ModuleActionAuthorizer.ModuleAction.DEPLOY;
    }

    @Override
    public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
        ModuleDescriptor descriptor = normalizer.normalize(arguments);
        int[] step = {0};
        platform.install(descriptor, msg -> ctx.progress().report(++step[0], 0, msg));
        ModuleDescriptor saved = platform.find(descriptor.id())
                .orElseThrow(() -> new IllegalStateException("module not found in store immediately after deploy: " + descriptor.id()));
        JsonNode structured = mapper.valueToTree(ModuleStatus.from(saved, platform.effectiveMode(saved)));
        if (saved.desiredState() == ModuleDescriptor.DesiredState.PENDING_APPROVAL) {
            return McpToolResult.ok("Module " + saved.id()
                    + " passed automatic gates → awaiting approval (PENDING_APPROVAL). Approve via protean.approve_module.", structured);
        }
        return McpToolResult.ok("Module " + saved.id() + " deployed (ACTIVE)", structured);
    }
}
