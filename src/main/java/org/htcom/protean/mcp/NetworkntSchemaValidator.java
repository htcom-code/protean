/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * networknt-backed {@link SchemaValidator}. Loaded only via {@link SchemaValidator#create()}, which catches the
 * linkage error when the {@code json-schema-validator} jar is absent (the dependency is {@code compileOnly}, never
 * shipped). Draft 2020-12, matching the schemas the tools declare.
 */
final class NetworkntSchemaValidator implements SchemaValidator {

    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<String> validate(JsonNode schema, JsonNode instance) {
        if (schema == null || !schema.isObject()) {
            return List.of();
        }
        JsonNode value = instance == null ? NullNode.getInstance() : instance;
        Set<ValidationMessage> messages = factory.getSchema(schema).validate(value);
        List<String> violations = new ArrayList<>(messages.size());
        for (ValidationMessage m : messages) {
            violations.add(m.getMessage());
        }
        return violations;
    }
}
