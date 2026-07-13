/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.transport;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OAuth 2.0 Protected Resource Metadata (RFC 9728) — the MCP authorization discovery surface.
 *
 * <p>An <b>opt-in</b> endpoint ({@code /.well-known/oauth-protected-resource}) registered only when
 * {@code protean.mcp.authorization.resource} is set. It provides the entry point through which an MCP client
 * (agent) discovers "which Authorization Server should I obtain this MCP server's token from".
 *
 * <p><b>Pure delegation is unchanged</b>: protean does not validate tokens, nor does it emit
 * 401/{@code WWW-Authenticate} (that is the job of the consumer's Spring Security Resource Server — confirmed
 * experimentally). This controller provides <b>only the metadata surface</b>, and all of its values are what the
 * consumer declared via {@code protean.mcp.authorization.*}. When unconfigured, this bean does not exist and
 * behavior is identical to today (non-breaking, additive).
 *
 * <p>Consumer note: this path must be accessible unauthenticated, so Security must {@code permitAll} on
 * {@code /.well-known/**}. To include a {@code resource_metadata} pointer in a 401 response, add it in the
 * consumer's {@code AuthenticationEntryPoint}.
 */
@RestController
@Profile("!worker")
@ConditionalOnProperty(name = "protean.mcp.authorization.resource")
public class McpProtectedResourceMetadataController {

    private final ProteanProperties.Authorization authz;

    public McpProtectedResourceMetadataController(ProteanProperties properties) {
        this.authz = properties.getMcp().getAuthorization();
    }

    @GetMapping("/.well-known/oauth-protected-resource")
    public Map<String, Object> metadata() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource", authz.getResource());
        putIfNotEmpty(body, "authorization_servers", authz.getAuthorizationServers());
        putIfNotEmpty(body, "scopes_supported", authz.getScopesSupported());
        putIfNotEmpty(body, "bearer_methods_supported", authz.getBearerMethodsSupported());
        return body;
    }

    private static void putIfNotEmpty(Map<String, Object> body, String key, List<String> value) {
        if (value != null && !value.isEmpty()) {
            body.put(key, value);
        }
    }
}
