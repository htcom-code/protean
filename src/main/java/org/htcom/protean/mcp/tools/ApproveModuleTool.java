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

/**
 * {@code protean.approve_module} — promotes a module awaiting approval (PENDING_APPROVAL) via human
 * authorization (final validation gate + deploy → ACTIVE).
 * {@code approver} is a string used for the audit log (identity verification is the consumer's
 * Security/authorizer responsibility).
 */
public class ApproveModuleTool implements McpTool {

    private final ObjectMapper mapper;
    private final ModulePlatform platform;

    public ApproveModuleTool(ObjectMapper mapper, ModulePlatform platform) {
        this.mapper = mapper;
        this.platform = platform;
    }

    @Override
    public String name() {
        return "protean.approve_module";
    }

    @Override
    public String description() {
        return "Approves a module awaiting approval (PENDING_APPROVAL), promoting it to ACTIVE via the final validation gate and deploy.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("id").put("type", "string");
        props.putObject("approver").put("type", "string").put("description", "Approver identity (audit log)");
        schema.putArray("required").add("id").add("approver");
        return schema;
    }

    @Override
    public String title() {
        return "Approve Module";
    }

    @Override
    public McpToolAnnotations annotations() {
        // Approval is non-destructive and idempotent: re-invoking on an already-approved module yields the same result.
        return McpToolAnnotations.builder()
                .readOnly(false).destructive(false).idempotent(true).openWorld(false).build();
    }

    @Override
    public ObjectNode outputSchema() {
        return ModuleToolSchemas.moduleStatus(mapper);
    }

    @Override
    public ModuleActionAuthorizer.ModuleAction action() {
        return ModuleActionAuthorizer.ModuleAction.APPROVE;
    }

    @Override
    public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
        if (!arguments.hasNonNull("id") || !arguments.hasNonNull("approver")) {
            throw McpException.invalidParams("approve_module: id and approver required");
        }
        String id = arguments.get("id").asText();
        platform.approve(id, arguments.get("approver").asText());
        ModuleDescriptor saved = platform.find(id)
                .orElseThrow(() -> new IllegalStateException("module not found in store immediately after approval: " + id));
        JsonNode structured = mapper.valueToTree(ModuleStatus.from(saved, platform.effectiveMode(saved)));
        return McpToolResult.ok("Module " + id + " approved (ACTIVE)", structured);
    }
}
