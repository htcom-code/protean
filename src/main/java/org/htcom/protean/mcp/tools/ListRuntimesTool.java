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
import org.htcom.protean.isolation.RuntimeInfo;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolAnnotations;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.htcom.protean.module.ModulePlatform;

import java.util.Comparator;
import java.util.List;

/**
 * {@code protean.list_runtimes} — the runtimes hosting modules: the main JVM, each worker JVM, each worker container.
 *
 * <p>{@code list_modules} already reports a {@code runtimeId} per module, so an agent can group by it to see the
 * packing. This tool adds what that grouping cannot show — a runtime hosting nothing (warm, or retiring while it
 * drains) — and each runtime's scope and uptime. Read-only; the REST twin is {@code GET /platform/runtimes}.
 */
public class ListRuntimesTool implements McpTool {

    private final ObjectMapper mapper;
    private final ModulePlatform platform;

    public ListRuntimesTool(ObjectMapper mapper, ModulePlatform platform) {
        this.mapper = mapper;
        this.platform = platform;
    }

    @Override
    public String name() {
        return "protean.list_runtimes";
    }

    @Override
    public String description() {
        return "Lists the runtimes that host modules (main JVM / worker JVMs / worker containers) with their scope, "
                + "state (LIVE|RETIRING), uptime, and hosted module ids — including runtimes hosting nothing, which "
                + "list_modules cannot reveal. Group modules by runtimeId to see how they were packed.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode p = schema.putObject("properties");
        p.putObject("mode").put("type", "string")
                .put("description", "Exact match on isolation mode (in-process|worker|container). All if omitted");
        p.putObject("scope").put("type", "string")
                .put("description", "Exact match on the DB scope a runtime is bound to. All if omitted");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public String title() {
        return "List Runtimes";
    }

    @Override
    public McpToolAnnotations annotations() {
        return McpToolAnnotations.readOnly();
    }

    @Override
    public ObjectNode outputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode runtimes = schema.putObject("properties").putObject("runtimes");
        runtimes.put("type", "array").put("description", "One entry per live runtime");
        ObjectNode item = runtimes.putObject("items");
        item.put("type", "object");
        ObjectNode p = item.putObject("properties");
        p.putObject("runtimeId").put("type", "string")
                .put("description", "Opaque host id, joins to a module's runtimeId (main | worker:<uuid> | container:<name>)");
        p.putObject("mode").put("type", "string").put("description", "in-process | worker | container");
        p.putObject("scope").put("type", "string")
                .put("description", "DB scope this runtime is bound to; null when auto-provision is off");
        p.putObject("state").put("type", "string").put("description", "LIVE | RETIRING");
        p.putObject("sinceEpochMs").put("type", "integer").put("description", "Start time (epoch ms) for uptime");
        p.putObject("moduleIds").put("type", "array").put("description", "Modules hosted here; empty when warm/retiring")
                .putObject("items").put("type", "string");
        item.putArray("required").add("runtimeId").add("mode").add("state").add("sinceEpochMs").add("moduleIds");
        schema.putArray("required").add("runtimes");
        return schema;
    }

    @Override
    public ModuleActionAuthorizer.ModuleAction action() {
        return ModuleActionAuthorizer.ModuleAction.READ;
    }

    @Override
    public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
        JsonNode args = arguments == null ? mapper.missingNode() : arguments;
        String modeFilter = ToolArgs.optional(args, "mode");
        String scopeFilter = ToolArgs.optional(args, "scope");

        List<RuntimeInfo> matched = platform.runtimes().stream()
                .filter(r -> modeFilter == null || modeFilter.equals(r.mode()))
                .filter(r -> scopeFilter == null || scopeFilter.equals(r.scope()))
                .sorted(Comparator.comparing(RuntimeInfo::mode).thenComparing(RuntimeInfo::runtimeId))
                .toList();

        // Per the MCP spec structuredContent must be an object → wrap the array under a key.
        ObjectNode structured = mapper.createObjectNode();
        structured.set("runtimes", mapper.valueToTree(matched));
        long hostingNothing = matched.stream().filter(r -> r.moduleIds().isEmpty()).count();
        String summary = hostingNothing == 0
                ? matched.size() + " runtime(s)"
                : matched.size() + " runtime(s), " + hostingNothing + " hosting no modules";
        return McpToolResult.ok(summary, structured);
    }
}
