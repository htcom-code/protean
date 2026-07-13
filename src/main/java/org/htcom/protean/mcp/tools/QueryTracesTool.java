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
import org.htcom.protean.runtime.RequestTrace;
import org.htcom.protean.runtime.TraceQuery;
import org.htcom.protean.runtime.TraceStore;

import java.util.List;

/**
 * {@code protean.query_traces} — recent request execution traces, newest-first, with the same filters as
 * {@code GET /platform/traces}: {@code moduleId}, {@code errorsOnly}, {@code status}, {@code minLatencyMs},
 * {@code since} (epoch-millis lower bound), {@code beforeSeq} (cursor into the past), and {@code limit}.
 * Every set filter combines with AND. Read-only.
 */
public class QueryTracesTool implements McpTool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final ObjectMapper mapper;
    private final TraceStore store;

    public QueryTracesTool(ObjectMapper mapper, TraceStore store) {
        this.mapper = mapper;
        this.store = store;
    }

    @Override
    public String name() {
        return "protean.query_traces";
    }

    @Override
    public String description() {
        return "Queries recent request traces (newest-first) for incident triage. Optional filters: "
                + "moduleId, errorsOnly, status, minLatencyMs, since (epoch ms), beforeSeq (page into the past), "
                + "and limit. All filters combine with AND.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode p = schema.putObject("properties");
        p.putObject("moduleId").put("type", "string")
                .put("description", "Only traces attributed to this module. All if omitted");
        p.putObject("errorsOnly").put("type", "boolean")
                .put("description", "Only failed requests (thrown exception or status >= 500). Default false");
        p.putObject("status").put("type", "integer")
                .put("description", "Only traces with this exact HTTP status code");
        p.putObject("minLatencyMs").put("type", "integer")
                .put("description", "Only traces at least this many ms (slow-request hunting)");
        p.putObject("since").put("type", "integer")
                .put("description", "Only traces recorded at/after this epoch-millis lower bound");
        p.putObject("beforeSeq").put("type", "integer")
                .put("description", "Only traces older than this seq (cursor for paging into the past)");
        p.putObject("limit").put("type", "integer")
                .put("description", "Maximum number to return (default " + DEFAULT_LIMIT + ", max " + MAX_LIMIT + ")");
        return schema;
    }

    @Override
    public ObjectNode outputSchema() {
        return ModuleToolSchemas.traceList(mapper);
    }

    @Override
    public String title() {
        return "Query Traces";
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
        int limit = clampLimit(args.path("limit"));
        String moduleId = text(args, "moduleId");
        boolean errorsOnly = args.path("errorsOnly").asBoolean(false);
        Integer status = integer(args, "status");
        Long minLatencyMs = longVal(args, "minLatencyMs");
        Long since = longVal(args, "since");
        Long beforeSeq = longVal(args, "beforeSeq");

        List<RequestTrace> traces = store.recent(
                new TraceQuery(limit, moduleId, errorsOnly, status, minLatencyMs, since, beforeSeq));

        // structuredContent must be an object (arrays not allowed) → wrap under the traces key.
        ObjectNode structured = mapper.createObjectNode();
        structured.set("traces", mapper.valueToTree(traces));
        return McpToolResult.ok(traces.size() + " trace(s)", structured);
    }

    private static String text(JsonNode args, String field) {
        String v = args.path(field).asText(null);
        return (v == null || v.isBlank()) ? null : v;
    }

    private static Integer integer(JsonNode args, String field) {
        JsonNode n = args.path(field);
        return n.isNumber() ? n.asInt() : null;
    }

    private static Long longVal(JsonNode args, String field) {
        JsonNode n = args.path(field);
        return n.isNumber() ? n.asLong() : null;
    }

    private static int clampLimit(JsonNode limitNode) {
        if (limitNode == null || !limitNode.isNumber()) {
            return DEFAULT_LIMIT;
        }
        int limit = limitNode.asInt(DEFAULT_LIMIT);
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
