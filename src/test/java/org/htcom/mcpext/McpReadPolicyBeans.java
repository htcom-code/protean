/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.mcpext;

import org.htcom.protean.mcp.ModuleActionAuthorizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A consumer {@link ModuleActionAuthorizer} that restricts {@code READ}: the anonymous caller is denied every read,
 * any other caller is denied reads of {@link #SECRET} only. Placed outside {@code org.htcom.protean} (like
 * {@link McpExtBeans}) so the auto-config component scan does not leak it into other test contexts.
 */
@Configuration
public class McpReadPolicyBeans {

    public static final String SECRET = "authz-secret";

    @Bean
    public ModuleActionAuthorizer readPolicyAuthorizer() {
        return (caller, action, moduleId) -> {
            if (action != ModuleActionAuthorizer.ModuleAction.READ) {
                return ModuleActionAuthorizer.Decision.allow();
            }
            if (caller == null) {
                return ModuleActionAuthorizer.Decision.deny("anonymous read");
            }
            return SECRET.equals(moduleId)
                    ? ModuleActionAuthorizer.Decision.deny("secret module")
                    : ModuleActionAuthorizer.Decision.allow();
        };
    }
}
