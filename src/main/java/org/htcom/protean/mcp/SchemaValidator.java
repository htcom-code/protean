/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Optional full JSON-Schema validator for MCP tool I/O. It backs the strict-schema mode
 * ({@code protean.mcp.strict-schema=true}), which validates tool arguments against {@code inputSchema} and structured
 * results against {@code outputSchema} at the dispatch boundary — most useful for a consumer's own custom tools,
 * which the library's own tests cannot cover.
 *
 * <p>The core library stays zero-dependency: the networknt-backed implementation is compiled {@code compileOnly}, so
 * when the validator jar is absent {@link #create()} returns {@link #unavailable()} and the dispatcher falls back to
 * its built-in top-level guard. The API is deliberately validator-agnostic (a {@code List<String>} of violation
 * messages) so consumers can reuse it — e.g. to validate their custom tools in their own test suites — without
 * importing networknt types.
 */
public interface SchemaValidator {

    /** True when a real JSON-Schema validator is available (the jar is on the classpath). */
    boolean available();

    /** Validates {@code instance} against {@code schema}; returns violation messages (empty = conforms). */
    List<String> validate(JsonNode schema, JsonNode instance);

    /** Returns a networknt-backed validator, or {@link #unavailable()} if the jar is not on the classpath. */
    static SchemaValidator create() {
        try {
            return new NetworkntSchemaValidator();
        } catch (Throwable notOnClasspath) { // NoClassDefFoundError/LinkageError when networknt is absent
            return unavailable();
        }
    }

    /** A no-op validator that reports itself unavailable (strict mode then degrades to the top-level guard). */
    static SchemaValidator unavailable() {
        return new SchemaValidator() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public List<String> validate(JsonNode schema, JsonNode instance) {
                return List.of();
            }
        };
    }
}
