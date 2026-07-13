/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.examples.oauthmcp;

import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Consumer implementation of protean's authorization SPI — maps OAuth2 scopes to MCP action permissions.
 *
 * <p>When registered, this bean replaces protean's default {@code PermissiveModuleActionAuthorizer}
 * (@ConditionalOnMissingBean). {@code caller} is the {@code JwtAuthenticationToken} populated by the
 * Resource Server, carrying {@code SCOPE_mcp.read}/{@code SCOPE_mcp.write} authorities.
 *
 * <ul>
 *   <li>{@code mcp.write} → write actions (DEPLOY/UPDATE/DELETE/APPROVE) plus read.</li>
 *   <li>{@code mcp.read} → READ only.</li>
 *   <li>DEBUG/CUSTOM → require write permission (conservative default).</li>
 *   <li>No scope / unauthenticated → deny (e.g. when delegated authentication did not populate the Principal).</li>
 * </ul>
 */
@Component
public class McpScopeAuthorizer implements ModuleActionAuthorizer {

    private static final String READ = "SCOPE_mcp.read";
    private static final String WRITE = "SCOPE_mcp.write";

    @Override
    public Decision authorize(Principal caller, ModuleAction action, String moduleId) {
        Set<String> scopes = scopesOf(caller);
        boolean canWrite = scopes.contains(WRITE);
        boolean canRead = canWrite || scopes.contains(READ);

        return switch (action) {
            case READ -> canRead ? Decision.allow()
                    : Decision.deny("READ requires the mcp.read scope");
            case DEPLOY, UPDATE, DELETE, APPROVE, DEBUG, CUSTOM -> canWrite ? Decision.allow()
                    : Decision.deny(action + " requires the mcp.write scope");
        };
    }

    private static Set<String> scopesOf(Principal caller) {
        if (caller instanceof Authentication auth) {
            return auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toSet());
        }
        return Set.of();
    }
}
