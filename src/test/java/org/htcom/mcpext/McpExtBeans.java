/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.mcpext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpTool;
import org.htcom.protean.mcp.McpToolResult;
import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.Principal;

/**
 * Consumer beans for MCP extension-point tests — a consumer-provided custom
 * {@link ModuleActionAuthorizer} (denies DEPLOY) plus a custom {@link McpTool} (echo).
 * Placed in a package <b>outside {@code org.htcom.protean}</b> so it is not picked up by the
 * auto-config's {@code @ComponentScan("org.htcom.protean")}, and is applied only to the target
 * tests via {@code @Import} (otherwise it would leak into every context and pollute other tests).
 */
@Configuration
public class McpExtBeans {

    @Bean
    public ModuleActionAuthorizer denyDeployAuthorizer() {
        return (Principal caller, ModuleActionAuthorizer.ModuleAction action, String moduleId) ->
                action == ModuleActionAuthorizer.ModuleAction.DEPLOY
                        ? ModuleActionAuthorizer.Decision.deny("test policy: deploy denied")
                        : ModuleActionAuthorizer.Decision.allow();
    }

    @Bean
    public McpTool echoTool(ObjectMapper mapper) {
        return new McpTool() {
            @Override
            public String name() {
                return "consumer.echo";
            }

            @Override
            public String description() {
                return "Consumer custom tool (echo)";
            }

            @Override
            public ObjectNode inputSchema() {
                ObjectNode s = mapper.createObjectNode();
                s.put("type", "object");
                s.putObject("properties").putObject("text").put("type", "string");
                return s;
            }

            @Override
            public McpToolResult call(JsonNode arguments, McpCallContext ctx) {
                return McpToolResult.ok("echo: " + arguments.path("text").asText(""));
            }
        };
    }
}
