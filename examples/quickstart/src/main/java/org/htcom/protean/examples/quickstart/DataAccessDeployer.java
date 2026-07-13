/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.examples.quickstart;

import org.htcom.protean.module.ModulePlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Default profile (no profile active) — automatically deploys the data-access module <b>in-process</b>.
 *
 * <p>Deployment goes through {@link ModulePlatform#install} (promotion gate 1 tests → gate 2 review,
 * then serving). Once deployed, verify with {@code GET /items/add?name=...}.
 */
@Component
@Profile("default")
class DataAccessDeployer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataAccessDeployer.class);

    private final ModulePlatform platform;

    DataAccessDeployer(ModulePlatform platform) {
        this.platform = platform;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            platform.install(SampleModules.dataAccessModule());
            log.info("[quickstart] Data-access module deployed → GET /items/add?name=widget");
        } catch (IllegalStateException alreadyPresent) {
            // Already restored via reconcile from the durable store on restart — handle idempotently.
            log.info("[quickstart] Data-access module already deployed (restored from store) — skipping");
        } catch (Exception e) {
            log.error("[quickstart] Failed to deploy data-access module", e);
        }
    }
}
