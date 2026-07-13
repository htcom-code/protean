/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp;

import java.security.Principal;

/**
 * Authorization SPI — the common choke point for MCP tool calls. protean does not implement
 * authentication; it delegates that to the consumer's Spring Security, and the consumer supplies only
 * the <b>policy</b> for "who can do what" through this bean. (Symmetric with the
 * {@code IsolationStrategy} / {@code CodeRule} SPIs.)
 *
 * <p>The default implementation ({@link PermissiveModuleActionAuthorizer}) allows everything, matching
 * the existing REST admin posture. If the consumer implements this interface as a bean, the default is
 * replaced (@ConditionalOnMissingBean).
 *
 * <p>Side effect: the currently metadata-only {@code ModuleDescriptor.TrustTier} becomes, in this
 * authorizer, the first place where it can actually drive a decision (e.g. UNTRUSTED rejects remote
 * deployment).
 */
public interface ModuleActionAuthorizer {

    Decision authorize(Principal caller, ModuleAction action, String moduleId);

    /** Classification of the action a tool performs. The axis on which the authorizer branches policy per action. */
    enum ModuleAction { READ, DEPLOY, UPDATE, DELETE, APPROVE, DEBUG, CUSTOM }

    /** Authorization decision. {@code reason} is the denial reason (null when allowed). */
    record Decision(boolean allowed, String reason) {
        public static Decision allow() {
            return new Decision(true, null);
        }

        public static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }
}
