/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.error;

import java.util.Locale;

/**
 * Stable error code catalog (SSOT) — the machine contract for the "kind" of error. The RFC 9457
 * {@code type}/{@code title} and the protocol codes (JSON-RPC/HTTP) are derived from here.
 *
 * <p>The MCP surface starts with a minimal set of thirteen kinds, extended by HTTP/Admin domain
 * errors ({@code STATE_CONFLICT}). Each code carries a stable name (the branch key),
 * an English {@code title} (identical regardless of the occurrence), a {@code detail} template
 * (the per-occurrence message, in English), and the JSON-RPC/HTTP code used when this code surfaces
 * as a protocol error ({@code null} if not applicable).
 *
 * <p>No i18n is kept here — messages are English-only, and consumers map their own locale wording
 * from the stable name.
 */
public enum ErrorCode {

    MALFORMED_REQUEST(-32600, 400, "Malformed JSON-RPC request", "malformed request: {0}"),
    INVALID_ARGUMENT(-32602, 400, "Invalid argument", "{0}"),
    UNKNOWN_TOOL(-32602, null, "Unknown tool", "unknown tool: {0}"),
    METHOD_NOT_FOUND(-32601, null, "Method not found", "method not found: {0}"),
    MODULE_NOT_FOUND(null, 404, "Module not found", "module not found: {0}"),
    SHARED_LIB_NOT_FOUND(null, 404, "Shared lib not found", "shared lib not found: {0}"),
    GATE_FAILED(null, 422, "Promotion gate failed", "promotion gate {0} failed: {1}"),
    COMPILATION_FAILED(null, 422, "Compilation failed", "compilation failed: {0}"),
    PERMISSION_DENIED(null, 403, "Permission denied", "permission denied: {0}"),
    DEBUG_DISABLED(null, 403, "Debug surface disabled", "debug surface disabled ({0})"),
    OUTPUT_SCHEMA_VIOLATION(null, 500, "Output schema violation", "output schema violation: {0}"),
    UNKNOWN_SESSION(null, 404, "Unknown MCP session", "unknown MCP session: {0}"),
    UNSUPPORTED_PROTOCOL_VERSION(-32600, 400, "Unsupported MCP protocol version",
            "unsupported MCP-Protocol-Version: {0}"),
    STATE_CONFLICT(null, 409, "State conflict", "{0}"),
    INTERNAL_ERROR(-32603, 500, "Internal error", "{0}");

    /** {@code type} URN prefix (convention: URN, domain-independent). */
    static final String TYPE_PREFIX = "urn:protean:error:";

    private final Integer jsonRpcCode;
    private final Integer httpStatus;
    private final String title;
    private final String detailTemplate;

    ErrorCode(Integer jsonRpcCode, Integer httpStatus, String title, String detailTemplate) {
        this.jsonRpcCode = jsonRpcCode;
        this.httpStatus = httpStatus;
        this.title = title;
        this.detailTemplate = detailTemplate;
    }

    /** RFC 9457 {@code type} — stable URN {@code urn:protean:error:<code-kebab>}. */
    public String type() {
        return TYPE_PREFIX + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** RFC 9457 {@code title} — fixed English summary per kind. */
    public String title() {
        return title;
    }

    /** Numeric code used when surfacing as a JSON-RPC error, {@code null} if not applicable. */
    public Integer jsonRpcCode() {
        return jsonRpcCode;
    }

    /** Status used when surfacing as HTTP problem+json, {@code null} if not applicable. */
    public Integer httpStatus() {
        return httpStatus;
    }

    /** Assembles the per-occurrence {@code detail} (English) via {@code {n}} substitution. */
    public String format(Object... args) {
        String s = detailTemplate;
        for (int i = 0; i < args.length; i++) {
            s = s.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return s;
    }

    /** Guards against lossy transports — prefixes detail with the stable code ({@code [CODE] ...}) so the branch key survives as text even if data is truncated. */
    public String prefixed(String detail) {
        return "[" + name() + "] " + detail;
    }
}
