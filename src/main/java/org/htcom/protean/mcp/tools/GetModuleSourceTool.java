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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code protean.get_module_source} — retrieves the source text (FQCN → content) of a deployed module.
 *
 * <p>During debugging, this lets you view the code at a break location ({@code className}) from the
 * server's stored copy even when the client has no source. The source is stored durably in
 * {@link ModuleDescriptor#sources()} (and {@link ModuleDescriptor#tests()}) from deploy time onward,
 * so this only reads — it stores nothing.
 *
 * <p>Parameters: {@code id} (required), {@code className} (FQCN; if given, only that file, otherwise the
 * whole map), {@code version} (history version; current if omitted), {@code includeTests} (merge tests
 * into the full map). Unlike the existing resource {@code protean://modules/{id}/source} (full map of the
 * current version), this supports specifying a version and a class.
 */
public class GetModuleSourceTool implements McpTool {

    private final ObjectMapper mapper;
    private final ModulePlatform platform;

    public GetModuleSourceTool(ObjectMapper mapper, ModulePlatform platform) {
        this.mapper = mapper;
        this.platform = platform;
    }

    @Override
    public String name() {
        return "protean.get_module_source";
    }

    @Override
    public String description() {
        return "Retrieves the source (FQCN → text) of a deployed module. During debugging, lets you view break-location code even when the client has no source.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("id").put("type", "string").put("description", "Module id");
        props.putObject("className").put("type", "string")
                .put("description", "FQCN. If given, only that class file; otherwise the full source map");
        props.putObject("version").put("type", "string")
                .put("description", "History version. Current deployed version if omitted");
        props.putObject("includeTests").put("type", "boolean")
                .put("description", "Also merge test sources when returning the full map (default false)");
        schema.putArray("required").add("id");
        return schema;
    }

    @Override
    public String title() {
        return "Get Module Source";
    }

    @Override
    public McpToolAnnotations annotations() {
        return McpToolAnnotations.readOnly();
    }

    @Override
    public ObjectNode outputSchema() {
        return ModuleToolSchemas.moduleSource(mapper);
    }

    @Override
    public ModuleActionAuthorizer.ModuleAction action() {
        return ModuleActionAuthorizer.ModuleAction.READ;
    }

    @Override
    public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
        if (!arguments.hasNonNull("id")) {
            throw McpException.invalidParams("get_module_source: id required");
        }
        String id = arguments.get("id").asText();
        String version = arguments.hasNonNull("version") ? arguments.get("version").asText() : null;
        String className = arguments.hasNonNull("className") ? arguments.get("className").asText() : null;
        boolean includeTests = arguments.hasNonNull("includeTests") && arguments.get("includeTests").asBoolean();

        Optional<ModuleDescriptor> found = version != null
                ? platform.findVersion(id, version)
                : platform.find(id);
        if (found.isEmpty()) {
            // For not-found cases, don't split the error code; distinguish via the moduleId/version extensions.
            String detail = version != null
                    ? "module version not found: " + id + "@" + version
                    : ErrorCode.MODULE_NOT_FOUND.format(id);
            McpToolResult err = McpToolResult.error(ErrorCode.MODULE_NOT_FOUND, detail).with("moduleId", id);
            return version != null ? err.with("version", version) : err;
        }
        ModuleDescriptor d = found.get();
        Map<String, String> sources = d.sources() == null ? Map.of() : d.sources();
        Map<String, String> tests = d.tests() == null ? Map.of() : d.tests();

        Map<String, String> files = new LinkedHashMap<>();
        if (className != null) {
            String src = sources.get(className);
            if (src == null) {
                src = tests.get(className);
            }
            if (src == null) {
                return McpToolResult.error(ErrorCode.MODULE_NOT_FOUND,
                                "class source not found in module " + id + ": " + className
                                        + " (may be an external/dependency class)")
                        .with("moduleId", id).with("resource", className);
            }
            files.put(className, src);
        } else {
            files.putAll(sources);
            if (includeTests) {
                files.putAll(tests);
            }
        }

        ObjectNode structured = mapper.createObjectNode();
        structured.put("id", id);
        structured.put("version", d.version());
        structured.set("files", mapper.valueToTree(files));
        return McpToolResult.ok(renderText(id, d.version(), files), structured);
    }

    /** Puts the raw source into the text content (so it is readable directly even when the client has no source). */
    private String renderText(String id, String version, Map<String, String> files) {
        StringBuilder sb = new StringBuilder();
        sb.append("Module ").append(id).append(" v").append(version)
                .append(" source: ").append(files.size()).append(" file(s)\n");
        for (Map.Entry<String, String> e : files.entrySet()) {
            sb.append("\n// ==== ").append(e.getKey()).append(" ====\n");
            sb.append(e.getValue());
            if (!e.getValue().endsWith("\n")) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
