/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Common root for protean errors. The error identity ({@link ErrorCode}) lives in the catalog
 * (SSOT), the per-occurrence mutable data is carried by this exception, and the wire shape
 * (RFC 9457) is assembled at a single boundary.
 *
 * <p>Throw sites do not invent string literals; they pass only the code plus arguments:
 * {@code throw new ProteanException(ErrorCode.MODULE_NOT_FOUND, id);} — the message is assembled
 * from the code template. Structured remediation data (extension members) is attached via
 * {@link #with} (e.g. {@code gate}/{@code missingFields}).
 *
 * <p>Only errors emitted at the MCP surface are raised onto this root here; a full realignment of
 * domain exceptions (layering them under this common root) is deferred.
 */
public class ProteanException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> extensions = new LinkedHashMap<>();

    public ProteanException(ErrorCode code, Object... args) {
        super(code.format(args));
        this.code = code;
    }

    public ProteanException(ErrorCode code, Throwable cause, Object... args) {
        super(code.format(args), cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    /** Attaches structured remediation data (extension member), fluent. E.g. {@code .with("gate", "signature")}. */
    public ProteanException with(String key, Object value) {
        extensions.put(key, value);
        return this;
    }

    /** The attached extension members (read-only). */
    public Map<String, Object> extensions() {
        return Collections.unmodifiableMap(extensions);
    }
}
