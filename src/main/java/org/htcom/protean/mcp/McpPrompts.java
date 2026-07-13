/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * MCP prompts — templates that standardize module authoring. Currently a single {@code create-module}:
 * it guides the agent to generate a controller plus tests in the {@code deploy_module} input shape (files[]).
 */
public class McpPrompts {

    private static final String CREATE_MODULE = "create-module";

    private final ObjectMapper mapper;

    public McpPrompts(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode list() {
        ObjectNode res = mapper.createObjectNode();
        ObjectNode p = res.putArray("prompts").addObject();
        p.put("name", CREATE_MODULE);
        p.put("description", "Guides authoring a protean module (controller + JUnit test) in the deploy_module input shape");
        ObjectNode arg = p.putArray("arguments").addObject();
        arg.put("name", "purpose");
        arg.put("description", "Description of the functionality the module should provide");
        arg.put("required", true);
        return res;
    }

    /**
     * completion/complete — prompt-argument autocompletion. The {@code purpose} argument of {@code create-module}
     * is free text, so there are no completion candidates (honestly: an empty list). Consumer prompts with
     * enumerated arguments can extend this here.
     */
    public java.util.List<String> complete(String name, String argName, String value) {
        return java.util.List.of();
    }

    public JsonNode get(JsonNode params) {
        String name = params == null ? null : params.path("name").asText(null);
        if (!CREATE_MODULE.equals(name)) {
            throw McpException.invalidParams("unknown prompt: " + name);
        }
        String purpose = params.path("arguments").path("purpose").asText("(purpose unspecified)");
        String text = """
                Write a protean module for the following purpose: %s

                Call the protean.deploy_module tool with arguments in the files[] shape:
                - Specify id, version, controller (FQCN)
                - files: each {kind: "source"|"test", filename, content}
                - One @RestController controller + one JUnit test that verifies its behavior (enforced by the structure gate)
                - Do not use forbidden APIs (System.exit, Runtime.exec, etc.); they are rejected by the safety gate
                The FQCN is derived automatically from the file's package declaration + filename, so declare the package accurately.
                """.formatted(purpose);

        ObjectNode res = mapper.createObjectNode();
        res.put("description", "Guidance for authoring a protean module");
        ArrayNode messages = res.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        ObjectNode content = msg.putObject("content");
        content.put("type", "text");
        content.put("text", text);
        return res;
    }
}
