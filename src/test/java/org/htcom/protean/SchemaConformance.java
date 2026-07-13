/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test-only full JSON-Schema conformance check (networknt). The MCP dispatcher's runtime guard is deliberately
 * zero-dep and only verifies top-level {@code required}; this helper performs the full nested/type validation as a
 * test-time guarantee that each declared {@code outputSchema} actually matches the real tool output. The validator
 * is a {@code testImplementation} dependency and is never shipped to consumers.
 */
final class SchemaConformance {

    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private SchemaConformance() {
    }

    /** Compiles a declared schema; fails loudly if it is structurally invalid JSON Schema. */
    static JsonSchema compile(JsonNode schemaNode, String label) {
        assertTrue(schemaNode != null && schemaNode.isObject(), label + " has no outputSchema object");
        JsonSchema schema = FACTORY.getSchema(schemaNode);
        assertNotNull(schema, label + " outputSchema did not compile");
        return schema;
    }

    /** Asserts an actual tool output validates against its declared outputSchema (full nested/type check). */
    static void assertConforms(JsonNode schemaNode, JsonNode instance, String label) {
        Set<ValidationMessage> errors = compile(schemaNode, label).validate(instance);
        assertTrue(errors.isEmpty(), label + " output violates its declared outputSchema: " + errors
                + "\n  instance: " + instance);
    }
}
