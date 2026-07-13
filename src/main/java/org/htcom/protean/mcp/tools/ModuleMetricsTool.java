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
import org.htcom.protean.runtime.ModuleMetricsSnapshot;
import org.htcom.protean.runtime.TraceMetrics;

import java.util.List;

/**
 * {@code protean.module_metrics} — per-module aggregated request metrics (count, error rate, latency
 * p50/p95/p99, max, last-seen). With {@code moduleId}, returns that module; without it, every tracked
 * module. Read-only.
 *
 * <p>Requires {@code protean.trace.metrics.enabled=true}; when disabled this returns an empty result with
 * {@code enabled:false} rather than an error, so an agent can detect the toggle state.
 */
public class ModuleMetricsTool implements McpTool {

    private final ObjectMapper mapper;
    private final TraceMetrics metrics;

    public ModuleMetricsTool(ObjectMapper mapper, TraceMetrics metrics) {
        this.mapper = mapper;
        this.metrics = metrics;
    }

    @Override
    public String name() {
        return "protean.module_metrics";
    }

    @Override
    public String description() {
        return "Returns per-module aggregated request metrics (count, error count/rate, latency "
                + "p50/p95/p99, max, last-seen). With moduleId, one module; without it, all tracked modules. "
                + "Requires protean.trace.metrics.enabled.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode p = schema.putObject("properties");
        p.putObject("moduleId").put("type", "string")
                .put("description", "Metrics for this module only. All tracked modules if omitted");
        return schema;
    }

    @Override
    public ObjectNode outputSchema() {
        return ModuleToolSchemas.moduleMetrics(mapper);
    }

    @Override
    public String title() {
        return "Module Metrics";
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
        JsonNode args = arguments == null ? mapper.missingNode() : arguments;
        String moduleId = text(args, "moduleId");

        ObjectNode structured = mapper.createObjectNode();
        structured.put("enabled", metrics.enabled());

        if (!metrics.enabled()) {
            structured.set("metrics", mapper.createArrayNode());
            return McpToolResult.ok("metrics disabled (set protean.trace.metrics.enabled=true)", structured);
        }

        List<ModuleMetricsSnapshot> snapshots = moduleId != null
                ? metrics.snapshot(moduleId).map(List::of).orElseGet(List::of)
                : metrics.snapshots();
        structured.set("metrics", mapper.valueToTree(snapshots));
        return McpToolResult.ok(snapshots.size() + " module metric(s)", structured);
    }

    private static String text(JsonNode args, String field) {
        String v = args.path(field).asText(null);
        return (v == null || v.isBlank()) ? null : v;
    }
}
