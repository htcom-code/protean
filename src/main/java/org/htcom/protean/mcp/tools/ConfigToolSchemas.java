/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@code outputSchema} builders for the {@code config.*} MCP tools. Kept together (like {@link ModuleToolSchemas})
 * so the tool I/O contract lives in one place. The runtime guard validates top-level {@code required} presence;
 * full validation is opt-in via {@code protean.mcp.strict-schema}.
 */
final class ConfigToolSchemas {

    private ConfigToolSchemas() {
    }

    /** One config entry: {@code {key, value, tier, liveApplicable}}. */
    private static ObjectNode entrySchema(ObjectMapper mapper) {
        ObjectNode entry = mapper.createObjectNode();
        entry.put("type", "object");
        ObjectNode props = entry.putObject("properties");
        props.putObject("key").put("type", "string");
        props.putObject("value").put("description", "current value (type depends on the key)");
        props.putObject("tier").put("type", "string")
                .putArray("enum").add("LIVE").add("FUTURE").add("RESTART_CONDITIONAL").add("RESTART_ARTIFACT");
        props.putObject("liveApplicable").put("type", "boolean");
        entry.putArray("required").add("key").add("tier").add("liveApplicable");
        return entry;
    }

    /** {@code config.list} → {@code {configs: Entry[]}}. */
    static ObjectNode configList(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode configs = props.putObject("configs");
        configs.put("type", "array");
        configs.set("items", entrySchema(mapper));
        schema.putArray("required").add("configs");
        return schema;
    }

    /** {@code config.get} → a single {@code Entry}. */
    static ObjectNode configGet(ObjectMapper mapper) {
        return entrySchema(mapper);
    }

    /** {@code config.set} → {@code {applied: boolean, outcomes: Outcome[]}}. */
    static ObjectNode configSet(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("applied").put("type", "boolean");
        ObjectNode outcomes = props.putObject("outcomes");
        outcomes.put("type", "array");
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ip.putObject("key").put("type", "string");
        ip.putObject("tier").put("type", "string");
        ip.putObject("outcome").put("type", "string");
        ip.putObject("reason").put("type", "string");
        ArrayNode req = item.putArray("required");
        req.add("key").add("outcome");
        outcomes.set("items", item);
        schema.putArray("required").add("applied").add("outcomes");
        return schema;
    }
}
