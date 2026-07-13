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
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.web.ModuleStatus;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * {@code protean.list_modules} — status list of ACTIVE modules. Delegates to {@link ModulePlatform#list()}.
 *
 * <p>Called with no arguments, it preserves the existing behavior (all ACTIVE modules) for backward
 * compatibility. For large module counts it supports optional filtering and paging:
 * {@code query} (partial, case-insensitive match on id/controllerFqcn), {@code mode} and
 * {@code trustTier} (exact match), {@code limit} (default 50, max 200, {@code 0} = all/unbounded),
 * and {@code cursor} (offset continuation). Results are sorted by id ascending (paging stability).
 * If more remain, {@code nextCursor} is included in the structuredContent.
 */
public class ListModulesTool implements McpTool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ObjectMapper mapper;
    private final ModulePlatform platform;

    public ListModulesTool(ObjectMapper mapper, ModulePlatform platform) {
        this.mapper = mapper;
        this.platform = platform;
    }

    @Override
    public String name() {
        return "protean.list_modules";
    }

    @Override
    public String description() {
        return "Retrieves the status list of deployed (ACTIVE) modules (id, version, status, isolation mode). "
                + "Filter with query/mode/trustTier and page with limit/cursor (all optional).";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode p = schema.putObject("properties");
        p.putObject("query").put("type", "string")
                .put("description", "Partial match on id or controllerFqcn (case-insensitive). All if omitted");
        p.putObject("mode").put("type", "string")
                .put("description", "Exact match on isolation mode (in-process|worker|container). All if omitted");
        ObjectNode trustTier = p.putObject("trustTier");
        trustTier.put("type", "string").put("description", "Exact match on trust tier. All if omitted");
        trustTier.putArray("enum").add("TRUSTED").add("UNTRUSTED");
        p.putObject("limit").put("type", "integer")
                .put("description", "Maximum number to return (default " + DEFAULT_LIMIT + ", max " + MAX_LIMIT
                        + ", 0 = all/unbounded)");
        p.putObject("cursor").put("type", "string")
                .put("description", "nextCursor from the previous response (continuation). From the start if omitted");
        return schema;
    }

    @Override
    public String title() {
        return "List Modules";
    }

    @Override
    public McpToolAnnotations annotations() {
        return McpToolAnnotations.readOnly();
    }

    @Override
    public ObjectNode outputSchema() {
        return ModuleToolSchemas.moduleStatusList(mapper);
    }

    @Override
    public ModuleActionAuthorizer.ModuleAction action() {
        return ModuleActionAuthorizer.ModuleAction.READ;
    }

    @Override
    public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
        JsonNode args = arguments == null ? mapper.missingNode() : arguments;
        String query = text(args, "query");
        String modeFilter = text(args, "mode");
        String trustFilter = text(args, "trustTier");
        int limit = clampLimit(args.path("limit"));
        int offset = decodeCursor(args.path("cursor").asText(null));

        // All ACTIVE → filter → id ascending (paging stability).
        List<ModuleStatus> matched = platform.list().stream()
                .map(d -> ModuleStatus.from(d, platform.effectiveMode(d), platform.boundGeneration(d.id()),
                        platform.boundLibraryGenerations(d.id()), platform.libraryGeneration(d.id())))
                .filter(s -> matches(s, query, modeFilter, trustFilter))
                .sorted(java.util.Comparator.comparing(ModuleStatus::id))
                .toList();

        int total = matched.size();
        int from = Math.min(offset, total);
        // limit == Integer.MAX_VALUE (limit=0) means unbounded — take everything from the offset
        // without overflowing from + limit.
        int to = limit >= total ? total : Math.min(from + limit, total);
        List<ModuleStatus> page = matched.subList(from, to);

        // Per the MCP spec, structuredContent must be an object (arrays not allowed) → wrap under the modules key.
        ObjectNode structured = mapper.createObjectNode();
        structured.set("modules", mapper.valueToTree(page));
        if (to < total) {
            structured.put("nextCursor", encodeCursor(to));
        }
        String summary = to < total
                ? page.size() + " module(s) (of " + total + ", more available)"
                : page.size() + " module(s)";
        return McpToolResult.ok(summary, structured);
    }

    private static String text(JsonNode args, String field) {
        String v = args.path(field).asText(null);
        return (v == null || v.isBlank()) ? null : v;
    }

    private static boolean matches(ModuleStatus s, String query, String mode, String trust) {
        if (query != null) {
            String q = query.toLowerCase(Locale.ROOT);
            boolean hit = s.id().toLowerCase(Locale.ROOT).contains(q)
                    || (s.controllerFqcn() != null && s.controllerFqcn().toLowerCase(Locale.ROOT).contains(q));
            if (!hit) {
                return false;
            }
        }
        if (mode != null && !mode.equals(s.mode())) {
            return false;
        }
        return trust == null || trust.equals(s.trustTier());
    }

    /**
     * Resolves the requested limit. Absent/non-numeric → default ({@value #DEFAULT_LIMIT}).
     * {@code 0} → unbounded (return everything, no paging cap), signalled as {@link Integer#MAX_VALUE}.
     * Negative → treated as absent (default). Positive → capped at {@value #MAX_LIMIT}.
     */
    private static int clampLimit(JsonNode limitNode) {
        if (limitNode == null || !limitNode.isNumber()) {
            return DEFAULT_LIMIT;
        }
        int limit = limitNode.asInt(DEFAULT_LIMIT);
        if (limit == 0) {
            return Integer.MAX_VALUE; // 0 = all (unbounded)
        }
        if (limit < 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(offset).getBytes(StandardCharsets.UTF_8));
    }

    /** Decodes the opaque cursor back to an offset. Corrupt/missing → 0 (from the start) as a safe fallback. */
    private static int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
            return Math.max(offset, 0);
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
