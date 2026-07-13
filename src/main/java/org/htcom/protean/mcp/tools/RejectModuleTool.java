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
import org.htcom.protean.module.ModulePlatform;

/**
 * {@code protean.reject_module} — rejects and removes a module awaiting approval. {@code approver} is for the audit log.
 */
public class RejectModuleTool implements McpTool {

    private final ObjectMapper mapper;
    private final ModulePlatform platform;

    public RejectModuleTool(ObjectMapper mapper, ModulePlatform platform) {
        this.mapper = mapper;
        this.platform = platform;
    }

    @Override
    public String name() {
        return "protean.reject_module";
    }

    @Override
    public String description() {
        return "Rejects and removes a module awaiting approval (PENDING_APPROVAL).";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("id").put("type", "string");
        props.putObject("approver").put("type", "string").put("description", "Identity of the rejector (audit log)");
        schema.putArray("required").add("id").add("approver");
        return schema;
    }

    @Override
    public String title() {
        return "Reject Module";
    }

    @Override
    public McpToolAnnotations annotations() {
        // Rejection removes the pending module (destructive) and is idempotent since re-invoking after removal yields the same result.
        return McpToolAnnotations.builder()
                .readOnly(false).destructive(true).idempotent(true).openWorld(false).build();
    }

    @Override
    public ModuleActionAuthorizer.ModuleAction action() {
        return ModuleActionAuthorizer.ModuleAction.APPROVE;
    }

    @Override
    public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
        if (!arguments.hasNonNull("id") || !arguments.hasNonNull("approver")) {
            throw McpException.invalidParams("reject_module: id and approver required");
        }
        String id = arguments.get("id").asText();
        platform.reject(id, arguments.get("approver").asText());
        return McpToolResult.ok("Module " + id + " rejected and removed");
    }
}
