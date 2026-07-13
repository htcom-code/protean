/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

/**
 * One entry (metadata) in a module's version history. The actual descriptor is fetched via {@link ModuleStore#loadVersion}.
 *
 * @param seq           monotonically increasing sequence within the module (save order)
 * @param version       the descriptor's version string at that point
 * @param savedAtMillis save timestamp (epoch ms)
 * @param desiredState  the desired-state at that point
 */
public record ModuleVersion(
        long seq,
        String version,
        long savedAtMillis,
        ModuleDescriptor.DesiredState desiredState
) {}
