/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * On startup, redeploys the store's ACTIVE modules (recovery after a hard reboot).
 * A no-op when the store is empty.
 *
 * <p>Runs <b>before</b> other {@link ApplicationRunner}s (such as a consumer app's bootstrap deployment)
 * so that persisted state is recovered before app-specific runners execute (avoids the duplicate-deploy
 * race that occurs when ordering is left unspecified).
 */
@Component
@Profile("!worker")
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class ModuleReconciler implements ApplicationRunner {

    private final ModulePlatform platform;

    public ModuleReconciler(ModulePlatform platform) {
        this.platform = platform;
    }

    @Override
    public void run(ApplicationArguments args) {
        platform.reconcile();
    }
}
