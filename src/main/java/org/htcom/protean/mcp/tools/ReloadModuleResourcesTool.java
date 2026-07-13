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
import org.htcom.protean.module.ModuleResource;
import org.htcom.protean.web.ModuleStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code protean.reload_module_resources} — live-reload of resources. Replaces resources in place without
 * recompiling or rebuilding the context (for resources read on every request). Deliberately a no-op for
 * resources that are parsed once at initialization time (e.g. ORM mappings).
 */
public class ReloadModuleResourcesTool implements McpTool {

    private final ObjectMapper mapper;
    private final ModulePlatform platform;

    public ReloadModuleResourcesTool(ObjectMapper mapper, ModulePlatform platform) {
        this.mapper = mapper;
        this.platform = platform;
    }

    @Override
    public String name() {
        return "protean.reload_module_resources";
    }

    @Override
    public String description() {
        return "Replaces module resources in place without recompiling or rebuilding (live-reload). For resources read on every request — a no-op for resources parsed at initialization time, such as ORM mappings.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("id").put("type", "string");
        ObjectNode files = props.putObject("files");
        files.put("type", "array").put("description", "Resources to replace/add (filename=classpath path, content, base64?)");
        ObjectNode fp = files.putObject("items").put("type", "object").putObject("properties");
        fp.putObject("filename").put("type", "string");
        fp.putObject("content").put("type", "string");
        fp.putObject("base64").put("type", "boolean");
        props.putObject("removeFiles").put("type", "array").put("description", "Resource paths to remove");
        return schema;
    }

    @Override
    public String title() {
        return "Reload Module Resources";
    }

    @Override
    public McpToolAnnotations annotations() {
        // Replaces resources only (non-destructive). Marked non-idempotent since the outcome depends on the resource set passed in.
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
        String id = text(arguments, "id");
        if (platform.find(id).isEmpty()) {
            return McpToolResult.error(ErrorCode.MODULE_NOT_FOUND, ErrorCode.MODULE_NOT_FOUND.format(id))
                    .with("moduleId", id);
        }
        Map<String, ModuleResource> add = new LinkedHashMap<>();
        if (arguments.hasNonNull("files") && arguments.get("files").isArray()) {
            for (JsonNode f : arguments.get("files")) {
                add.put(text(f, "filename"),
                        new ModuleResource(text(f, "content"), f.path("base64").asBoolean(false)));
            }
        }
        List<String> removeFiles = new ArrayList<>();
        if (arguments.hasNonNull("removeFiles") && arguments.get("removeFiles").isArray()) {
            arguments.get("removeFiles").forEach(n -> removeFiles.add(n.asText()));
        }

        int[] step = {0};
        platform.reloadResources(id, add, removeFiles, msg -> ctx.progress().report(++step[0], 0, msg));
        ModuleDescriptor saved = platform.find(id)
                .orElseThrow(() -> new IllegalStateException("Module not found immediately after resource reload: " + id));
        JsonNode structured = mapper.valueToTree(ModuleStatus.from(saved, platform.effectiveMode(saved)));
        return McpToolResult.ok("Resources reloaded for module " + saved.id(), structured);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            throw McpException.invalidParams("missing required field: " + field);
        }
        return node.get(field).asText();
    }
}
