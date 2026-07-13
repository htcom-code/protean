/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp;

import org.htcom.protean.error.ErrorCode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON-RPC 2.0 protocol-level error (transport/dispatch stage). A tool-execution failure is expressed not with
 * this but with {@link McpToolResult#error} (tool result {@code isError:true}) — MCP distinguishes the two.
 *
 * <p>When a stable {@link ErrorCode} is carried (recommended), the envelope's JSON-RPC numeric code is derived
 * from it, and the boundary ({@code McpDispatcher.error}) assembles an RFC 9457 problem-detail shape into
 * {@code error.data}. Attach structured remediation data with {@link #with}.
 */
public class McpException extends RuntimeException {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private final int code;
    /** Stable code (drives the RFC 9457 shape). When null, a legacy path with only a raw numeric code (no error.data emitted). */
    private final transient ErrorCode errorCode;
    private final transient Map<String, Object> extensions = new LinkedHashMap<>();

    public McpException(int code, String message) {
        super(message);
        this.code = code;
        this.errorCode = null;
    }

    /** Stable-code based — the JSON-RPC numeric code is derived from {@link ErrorCode#jsonRpcCode()} (INTERNAL if absent). */
    public McpException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.code = errorCode.jsonRpcCode() != null ? errorCode.jsonRpcCode() : INTERNAL_ERROR;
    }

    public int code() {
        return code;
    }

    /** Stable code (may be null). The boundary uses this to assemble the problem-detail shape. */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** Attach structured remediation data (an extension member), fluent. Example: {@code .with("gate", "signature")}. */
    public McpException with(String key, Object value) {
        extensions.put(key, value);
        return this;
    }

    /** The carried extension members (read-only). */
    public Map<String, Object> extensions() {
        return Collections.unmodifiableMap(extensions);
    }

    public static McpException methodNotFound(String method) {
        return new McpException(ErrorCode.METHOD_NOT_FOUND, ErrorCode.METHOD_NOT_FOUND.format(method));
    }

    /** Unknown tool ({@code tools/call}) — UNKNOWN_TOOL (JSON-RPC -32602). */
    public static McpException unknownTool(String name) {
        return new McpException(ErrorCode.UNKNOWN_TOOL, ErrorCode.UNKNOWN_TOOL.format(name));
    }

    /** General argument-validation failure — INVALID_ARGUMENT (JSON-RPC -32602). detail is the per-occurrence English message. */
    public static McpException invalidParams(String message) {
        return new McpException(ErrorCode.INVALID_ARGUMENT, message);
    }
}
