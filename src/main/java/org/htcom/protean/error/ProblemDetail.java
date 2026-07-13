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
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assembler for the RFC 9457 problem-detail shape. To stay compliant with the MCP/JSON-RPC specs it
 * does not force the {@code application/problem+json} media type; instead it builds the <b>member
 * shape</b> as a JsonNode and places it into the standard slots (JSON-RPC {@code error.data} / tool
 * {@code structuredContent} / HTTP problem+json body).
 *
 * <p>{@code type}/{@code title}/{@code code} are derived from {@link ErrorCode}. {@code status} is
 * serialized <b>only when set</b> — it is populated for HTTP problem+json but omitted for JSON-RPC
 * and tool responses (where the protocol code lives in the envelope). Extension members carry only
 * structured remediation data, never commands (trust boundary).
 */
public final class ProblemDetail {

    /** Correlation ID MDC key — {@code CorrelationIdFilter} populates it per request. When present, it is included in the problem-detail. */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    private final ErrorCode code;
    private String detail;
    private Integer status;
    private String instance;
    private final Map<String, Object> extensions = new LinkedHashMap<>();

    private ProblemDetail(ErrorCode code) {
        this.code = code;
    }

    public static ProblemDetail of(ErrorCode code) {
        return new ProblemDetail(code);
    }

    /** Absorbs the {@link ProteanException}'s code, detail, and extension members as-is (boundary-mapping entry point). */
    public static ProblemDetail from(ProteanException ex) {
        ProblemDetail p = new ProblemDetail(ex.code()).detail(ex.getMessage());
        p.extensions.putAll(ex.extensions());
        return p;
    }

    public ProblemDetail detail(String detail) {
        this.detail = detail;
        return this;
    }

    /** Called only for HTTP problem+json — JSON-RPC and tool responses do not set it, so {@code status} is omitted. */
    public ProblemDetail status(Integer status) {
        this.status = status;
        return this;
    }

    public ProblemDetail instance(String instance) {
        this.instance = instance;
        return this;
    }

    /** Attaches structured remediation data (extension member), fluent. */
    public ProblemDetail ext(String key, Object value) {
        extensions.put(key, value);
        return this;
    }

    public ErrorCode code() {
        return code;
    }

    public String detail() {
        return detail;
    }

    /** Guards against lossy transports — a code-prefixed string for the human-facing {@code text}/{@code detail}. */
    public String prefixedDetail() {
        return code.prefixed(detail == null ? code.title() : detail);
    }

    /** RFC 9457 member shape (JsonNode). {@code status} is included only when set. */
    public ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", code.type());
        n.put("title", code.title());
        if (status != null) {
            n.put("status", status);
        }
        if (detail != null) {
            n.put("detail", detail);
        }
        if (instance != null) {
            n.put("instance", instance);
        }
        n.put("code", code.name());   // extension: a simple branch key (no URI parsing)
        for (Map.Entry<String, Object> e : extensions.entrySet()) {
            n.set(e.getKey(), mapper.valueToTree(e.getValue()));
        }
        // Correlation ID — when present in the request scope, include it in the error envelope (for cross-correlation with logs). An explicit ext takes precedence.
        String traceId = MDC.get(TRACE_ID_MDC_KEY);
        if (traceId != null && !n.has(TRACE_ID_MDC_KEY)) {
            n.put(TRACE_ID_MDC_KEY, traceId);
        }
        return n;
    }
}
