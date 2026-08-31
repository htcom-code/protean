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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A blank required argument must be rejected as an argument error, not resolved as a missing entity.
 *
 * <p>Before this, the tools checked only {@code hasNonNull}, so {@code {"id": ""}} passed the check and the
 * empty id reached the store lookup — the caller got {@code MODULE_NOT_FOUND} ("module not found: ") and went
 * hunting for a module it had never actually named. Absent, null and blank are one case and answer alike.
 *
 * <p>Every tool listed here is covered because the guard used to be per-tool and drifted: two tools had it,
 * the rest did not.
 */
@SpringBootTest(properties = {"protean.mcp.enabled=true", "protean.trace.enabled=false"})
class McpToolArgumentValidationTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-argcheck-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired McpDispatcher dispatcher;
    @Autowired ObjectMapper mapper;

    /** JSON-RPC "Invalid params" — what an argument error surfaces as. */
    private static final int INVALID_PARAMS = -32602;

    @ParameterizedTest(name = "{0} rejects a blank {1}")
    @CsvSource({
            "protean.get_module,            id",
            "protean.uninstall_module,      id",
            "protean.module_versions,       id",
            "protean.get_module_source,     id",
            "protean.rollback_module,       id",
            "protean.approve_module,        id",
            "protean.reject_module,         id",
            "protean.reload_module_resources, id",
            "protean.patch_module,          id",
            "protean.get_shared_lib,        name",
            "protean.remove_shared_lib,     name",
            "protean.deploy_shared_lib,     name",
    })
    void blank_required_argument_is_an_argument_error(String tool, String field) {
        ObjectNode args = mapper.createObjectNode();
        args.put(field, "");
        // Fill the tool's other required arguments so the blank one under test is the only thing wrong.
        for (String other : new String[]{"id", "name", "version", "approver", "bytesBase64"}) {
            if (!other.equals(field)) {
                args.put(other, other.equals("bytesBase64") ? "AAAA" : "placeholder");
            }
        }
        JsonNode resp = callTool(tool, args);

        assertEquals(INVALID_PARAMS, resp.path("error").path("code").asInt(),
                tool + " must reject a blank " + field + " as an argument error, got: " + resp);
        String message = resp.path("error").path("message").asText();
        assertTrue(message.contains(field),
                "the message must name the offending argument, got: " + message);
    }

    @Test
    void a_blank_id_is_not_reported_as_a_missing_module() {
        ObjectNode args = mapper.createObjectNode();
        args.put("id", "");
        JsonNode resp = callTool("protean.module_versions", args);

        // The whole point: never MODULE_NOT_FOUND for an argument the caller never supplied.
        assertFalse(resp.path("result").path("content").toString().contains("MODULE_NOT_FOUND"),
                "a blank id must not be answered as a missing module: " + resp);
    }

    @Test
    void query_traces_reports_that_capture_is_off() {
        // With protean.trace.enabled=false the list is empty for a reason the caller has to be able to see:
        // "capture is off" and "nothing matched" are different answers and must not share one shape.
        JsonNode result = callTool("protean.query_traces", mapper.createObjectNode()).path("result");
        assertFalse(result.path("isError").asBoolean(false), result.toString());
        JsonNode structured = result.path("structuredContent");
        assertTrue(structured.has("enabled"), "query_traces must always report enabled: " + structured);
        assertFalse(structured.path("enabled").asBoolean(true), "capture is off in this context");
        assertTrue(structured.path("traces").isArray(), structured.toString());
    }

    private JsonNode callTool(String tool, ObjectNode args) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", tool);
        params.set("arguments", args);
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/call");
        req.set("params", params);
        return dispatcher.dispatch(req, McpCallContext.anonymous());
    }
}
