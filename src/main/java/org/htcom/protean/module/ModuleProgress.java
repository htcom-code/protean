/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

/**
 * Deploy/update stage progress callback (the source of progress notifications). The MCP adapter implements
 * this and streams it out as {@code notifications/progress}. Existing callers use {@link #NONE}, so there is
 * no behavior change (additive).
 */
@FunctionalInterface
public interface ModuleProgress {

    void stage(String message);

    ModuleProgress NONE = message -> {
    };
}
