/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the ErrorCode catalog + ProteanException + ProblemDetail.
 *
 * <p>This test doubles as an <b>executable mapping inventory</b>: instead of prose docs, it pins down
 * which code/shape each current MCP-surface error string is emitted as.
 */
class ErrorCatalogTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void catalog_size() {
        // 13 MCP-surface codes, the HTTP/Admin STATE_CONFLICT code, and SHARED_LIB_NOT_FOUND = 15 total.
        assertEquals(15, ErrorCode.values().length);
    }

    @Test
    void type_is_stable_urn_derived_from_name() {
        assertEquals("urn:protean:error:module-not-found", ErrorCode.MODULE_NOT_FOUND.type());
        assertEquals("urn:protean:error:unsupported-protocol-version",
                ErrorCode.UNSUPPORTED_PROTOCOL_VERSION.type());
        for (ErrorCode c : ErrorCode.values()) {
            assertTrue(c.type().startsWith(ErrorCode.TYPE_PREFIX), c + " type must start with the URN prefix");
            assertFalse(c.type().contains("_"), c + " type must be kebab-case (no underscores)");
        }
    }

    @Test
    void type_urns_are_unique() {
        Set<String> seen = new HashSet<>();
        for (ErrorCode c : ErrorCode.values()) {
            assertTrue(seen.add(c.type()), "duplicate type: " + c.type());
        }
    }

    @Test
    void every_code_has_english_title_and_at_least_one_protocol_code() {
        for (ErrorCode c : ErrorCode.values()) {
            assertNotNull(c.title(), c + " requires a title");
            assertFalse(c.title().isBlank(), c + " title is blank");
            assertTrue(c.jsonRpcCode() != null || c.httpStatus() != null,
                    c + " must have either a JSON-RPC code or an HTTP status");
        }
    }

    /** Current MCP-surface error strings -> code/detail mapping (pins the shape). */
    @Test
    void detail_templates_match_surface_errors() {
        assertEquals("module not found: m1", ErrorCode.MODULE_NOT_FOUND.format("m1"));
        assertEquals("unknown tool: protean.nope", ErrorCode.UNKNOWN_TOOL.format("protean.nope"));
        assertEquals("method not found: nonexistent/method",
                ErrorCode.METHOD_NOT_FOUND.format("nonexistent/method"));
        assertEquals("promotion gate signature failed: bad key",
                ErrorCode.GATE_FAILED.format("signature", "bad key"));
        // INVALID_ARGUMENT/INTERNAL_ERROR use a passthrough template ({0})
        assertEquals("get_module: id required", ErrorCode.INVALID_ARGUMENT.format("get_module: id required"));
        assertEquals("boom", ErrorCode.INTERNAL_ERROR.format("boom"));
    }

    @Test
    void prefixed_survives_lossy_harness() {
        assertEquals("[MODULE_NOT_FOUND] module not found: m1",
                ErrorCode.MODULE_NOT_FOUND.prefixed(ErrorCode.MODULE_NOT_FOUND.format("m1")));
    }

    @Test
    void protean_exception_carries_code_message_and_extensions() {
        ProteanException ex = new ProteanException(ErrorCode.GATE_FAILED, "signature", "bad key")
                .with("gate", "signature");
        assertEquals(ErrorCode.GATE_FAILED, ex.code());
        assertEquals("promotion gate signature failed: bad key", ex.getMessage());
        assertEquals("signature", ex.extensions().get("gate"));
    }

    @Test
    void protean_exception_preserves_cause() {
        RuntimeException cause = new IllegalStateException("worker died");
        ProteanException ex = new ProteanException(ErrorCode.INTERNAL_ERROR, cause, "deploy failed");
        assertEquals("deploy failed", ex.getMessage());
        assertEquals(cause, ex.getCause());
    }

    @Test
    void problem_detail_shape_omits_status_for_protocol_and_tool_paths() {
        // protocol and tool paths: status not set -> omitted from the shape, while type/title/code are present
        ObjectNode json = ProblemDetail.of(ErrorCode.MODULE_NOT_FOUND)
                .detail("module not found: m1")
                .instance("m1")
                .toJson(mapper);
        assertEquals("urn:protean:error:module-not-found", json.get("type").asText());
        assertEquals("Module not found", json.get("title").asText());
        assertEquals("MODULE_NOT_FOUND", json.get("code").asText());
        assertEquals("module not found: m1", json.get("detail").asText());
        assertEquals("m1", json.get("instance").asText());
        assertFalse(json.has("status"), "the protocol and tool paths do not carry status");
    }

    @Test
    void problem_detail_shape_includes_status_for_http_path() {
        // HTTP path: status set -> included in the actual problem+json status
        ObjectNode json = ProblemDetail.of(ErrorCode.UNKNOWN_SESSION)
                .detail("unknown MCP session: s9")
                .status(404)
                .toJson(mapper);
        assertEquals(404, json.get("status").asInt());
    }

    @Test
    void problem_detail_carries_extension_members_as_structured_data() {
        List<String> missing = Arrays.asList("id", "version");
        ObjectNode json = ProblemDetail.of(ErrorCode.INVALID_ARGUMENT)
                .detail("rollback_module: id and version required")
                .ext("missingFields", missing)
                .toJson(mapper);
        assertTrue(json.get("missingFields").isArray());
        assertEquals("id", json.get("missingFields").get(0).asText());
        assertEquals("version", json.get("missingFields").get(1).asText());
    }

    @Test
    void problem_detail_from_exception_absorbs_code_detail_and_extensions() {
        ProteanException ex = new ProteanException(ErrorCode.GATE_FAILED, "signature", "bad key")
                .with("gate", "signature");
        ObjectNode json = ProblemDetail.from(ex).toJson(mapper);
        assertEquals("urn:protean:error:gate-failed", json.get("type").asText());
        assertEquals("promotion gate signature failed: bad key", json.get("detail").asText());
        assertEquals("signature", json.get("gate").asText());
        assertNull(json.get("status"), "from(ex) does not auto-set status (the boundary decides it)");
    }
}
