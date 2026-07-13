/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.examples.oauthmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Protean × OAuth2 consumer example.
 *
 * <p>A single app plays three roles (all on the <b>consumer side</b> — protean does not implement authentication):
 * <ol>
 *   <li><b>Authorization Server</b> (Spring Authorization Server) — H2 user login → issues JWT access tokens
 *       with {@code mcp.read}/{@code mcp.write} scopes. Login sessions are persisted in Redis (Spring Session).</li>
 *   <li><b>Resource Server</b> — protects {@code /platform/mcp/**} with JWT and injects the verified principal as a
 *       {@link java.security.Principal} into the protean MCP controller (the delegation point).</li>
 *   <li><b>protean MCP</b> — {@link McpScopeAuthorizer} maps that principal's scopes to MCP action permissions.</li>
 * </ol>
 *
 * <p>This example demonstrates OAuth discovery metadata <b>end to end</b> — who issues the 401/{@code WWW-Authenticate},
 * and whether the protected-resource metadata is protean's responsibility or the consumer's.
 */
@SpringBootApplication
public class OAuthMcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(OAuthMcpApplication.class, args);
    }
}
