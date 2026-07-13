/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpDispatcher;
import org.htcom.protean.mcp.McpException;
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code tools/list} cursor pagination by driving the dispatcher directly.
 * When there are many consumer tools, paging through must return them all (library completeness).
 */
class McpPaginationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ModuleActionAuthorizer allowAll =
            (caller, action, moduleId) -> ModuleActionAuthorizer.Decision.allow();

    private McpTool fake(String name) {
        return new McpTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public ObjectNode inputSchema() {
                ObjectNode s = mapper.createObjectNode();
                s.put("type", "object");
                return s;
            }
            @Override public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
                return McpToolResult.ok("ok");
            }
        };
    }

    private JsonNode listTools(McpDispatcher d, String cursor) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/list");
        if (cursor != null) {
            req.putObject("params").put("cursor", cursor);
        }
        return d.dispatch(req, McpCallContext.anonymous()).path("result");
    }

    @Test
    void tools_list_paginates_across_pages_with_cursor() {
        McpDispatcher d = new McpDispatcher(mapper,
                List.of(fake("t.a"), fake("t.b"), fake("t.c"), fake("t.d"), fake("t.e")),
                allowAll, null, null, null, null);
        d.setPageSize(2);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode result = listTools(d, cursor);
            result.path("tools").forEach(t -> seen.add(t.path("name").asText()));
            cursor = result.path("nextCursor").asText(null);
            assertTrue(result.path("tools").size() <= 2, "page size respected");
            pages++;
        } while (cursor != null && pages < 10);

        assertEquals(3, pages, "5 / 2 = 3 pages");
        assertEquals(5, seen.size(), "all tools retrieved via cursor");
        assertTrue(seen.containsAll(List.of("t.a", "t.b", "t.c", "t.d", "t.e")));
    }

    @Test
    void single_page_has_no_next_cursor() {
        McpDispatcher d = new McpDispatcher(mapper, List.of(fake("only.one")),
                allowAll, null, null, null, null);
        JsonNode result = listTools(d, null);
        assertEquals(1, result.path("tools").size());
        assertFalse(result.has("nextCursor"), "no nextCursor when everything fits on one page");
    }

    @Test
    void malformed_cursor_is_invalid_params() {
        McpDispatcher d = new McpDispatcher(mapper, List.of(fake("t.a")), allowAll, null, null, null, null);
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/list");
        req.putObject("params").put("cursor", "!!!not-base64!!!");
        JsonNode resp = d.dispatch(req, McpCallContext.anonymous());
        assertEquals(McpException.INVALID_PARAMS, resp.path("error").path("code").asInt());
    }
}
