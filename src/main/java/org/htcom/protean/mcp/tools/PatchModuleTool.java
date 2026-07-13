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
import org.htcom.protean.module.ModulePatch;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.web.ModuleStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code protean.patch_module} — delta update. Send only the changed files, overlay them onto the current
 * descriptor, then run a canary update. Full-replace (update_module) is canonical; this is a convenience for
 * assembling the input.
 */
public class PatchModuleTool implements McpTool {

    private final ObjectMapper mapper;
    private final ModulePlatform platform;

    public PatchModuleTool(ObjectMapper mapper, ModulePlatform platform) {
        this.mapper = mapper;
        this.platform = platform;
    }

    @Override
    public String name() {
        return "protean.patch_module";
    }

    @Override
    public String description() {
        return "Updates an existing module via a delta — send only the changed files[] to overlay onto the current state, remove entries via removeFiles, then run a canary update.";
    }

    @Override
    public ObjectNode inputSchema() {
        // Delta-only contract (differs from deploy/update) — only id required, files/removeFiles optional. Use the shared builder.
        return ModuleToolSchemas.modulePatchInput(mapper);
    }

    @Override
    public String title() {
        return "Patch Module";
    }

    @Override
    public McpToolAnnotations annotations() {
        // Modifies an existing module but is non-destructive since it can be reverted via version history; non-idempotent since it creates a new version.
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
        ModuleDescriptor current = platform.find(id)
                .orElse(null);
        if (current == null) {
            return McpToolResult.error(ErrorCode.MODULE_NOT_FOUND, ErrorCode.MODULE_NOT_FOUND.format(id))
                    .with("moduleId", id);
        }
        String version = arguments.hasNonNull("version") ? arguments.get("version").asText() : null;

        List<ModulePatch.FileSpec> files = new ArrayList<>();
        if (arguments.hasNonNull("files") && arguments.get("files").isArray()) {
            for (JsonNode f : arguments.get("files")) {
                files.add(new ModulePatch.FileSpec(
                        f.path("kind").asText("source"),
                        text(f, "filename"),
                        text(f, "content"),
                        f.path("base64").asBoolean(false)));
            }
        }
        List<String> removeFiles = new ArrayList<>();
        if (arguments.hasNonNull("removeFiles") && arguments.get("removeFiles").isArray()) {
            arguments.get("removeFiles").forEach(n -> removeFiles.add(n.asText()));
        }

        ModuleDescriptor merged = ModulePatch.apply(current, version, files, removeFiles);
        int[] step = {0};
        platform.update(merged, msg -> ctx.progress().report(++step[0], 0, msg));
        ModuleDescriptor saved = platform.find(id)
                .orElseThrow(() -> new IllegalStateException("Module not found in store immediately after patch: " + id));
        JsonNode structured = mapper.valueToTree(ModuleStatus.from(saved, platform.effectiveMode(saved)));
        return McpToolResult.ok("Module " + saved.id() + " patched (v" + saved.version() + ")", structured);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            throw McpException.invalidParams("missing required field: " + field);
        }
        return node.get(field).asText();
    }
}
