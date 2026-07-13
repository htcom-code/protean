/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.error.ProblemDetail;
import org.htcom.protean.error.ProteanException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Unifies control-plane/Admin HTTP errors as RFC 9457 {@code application/problem+json}. Reuses the
 * same {@link ErrorCode} catalog and {@link ProblemDetail} shape as the MCP error surface, keeping
 * the surfaces consistent.
 *
 * <p><b>Scope</b>: applies only to {@code org.htcom.protean.web} controllers
 * ({@link ModuleAdminController} and {@link TraceAdminController}) — it does not touch
 * runtime-loaded consumer module endpoints (their own package) or the MCP transport controllers
 * that handle their own errors (a separate shape). Framework exceptions (validation, missing
 * parameters, etc.) are left to Spring's default handling (no broad catch-all here, so there is no
 * status-code regression).
 */
@RestControllerAdvice(basePackages = "org.htcom.protean.web")
class ProteanAdminErrorAdvice {

    private final ObjectMapper mapper;

    ProteanAdminErrorAdvice(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * A protean error carrying a stable code — shapes the code's HTTP status + extension members
     * directly. {@code GateFailedException} (GATE_FAILED+gate) and {@code CompilationException}
     * (COMPILATION_FAILED+diagnostics) are also subclasses of {@link ProteanException}, so they are
     * emitted here in structured form.
     */
    @ExceptionHandler(ProteanException.class)
    ResponseEntity<JsonNode> handleProtean(ProteanException e) {
        return problem(e.code(), e.getMessage(), e.extensions());
    }

    /** Invalid manifest/input (missing required fields, etc.) -> 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<JsonNode> handleBadRequest(IllegalArgumentException e) {
        return problem(ErrorCode.INVALID_ARGUMENT, e.getMessage(), null);
    }

    /** State conflicts such as an unsupported/unknown isolation mode -> 409. */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<JsonNode> handleConflict(IllegalStateException e) {
        return problem(ErrorCode.STATE_CONFLICT, e.getMessage(), null);
    }

    private ResponseEntity<JsonNode> problem(ErrorCode code, String detail, Map<String, Object> extensions) {
        int status = code.httpStatus() != null ? code.httpStatus() : 500;
        ProblemDetail pd = ProblemDetail.of(code).detail(detail).status(status);
        if (extensions != null) {
            extensions.forEach(pd::ext);
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd.toJson(mapper));
    }
}
