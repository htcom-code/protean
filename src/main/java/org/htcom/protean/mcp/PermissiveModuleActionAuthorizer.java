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
 * Default authorization implementation — allows everything (permissive by design). Matches the
 * existing REST admin's unauthenticated posture, so it has no effect on existing behavior (purely
 * additive). If the consumer registers their own {@link ModuleActionAuthorizer} bean, this default
 * steps aside.
 */
public class PermissiveModuleActionAuthorizer implements ModuleActionAuthorizer {

    @Override
    public Decision authorize(Principal caller, ModuleAction action, String moduleId) {
        return Decision.allow();
    }
}
