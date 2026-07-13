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
 * {@code worker-demo} profile — automatically deploys the compute module to a <b>separate JVM worker</b>.
 *
 * <p>The module's {@code isolationMode} is null, so it follows the global {@code protean.isolation.mode=worker}
 * (application-worker-demo.yml). The main process spawns the worker JVM and routes to it via {@code ReverseProxy},
 * so the call site from a consumer's perspective ({@code GET /compute/square?n=...}) is unchanged.
 *
 * <p><b>Note:</b> the example profile is named {@code worker-demo}, not {@code worker} —
 * {@code worker} is a <b>reserved Spring profile</b> that marks a worker JVM spawned by Protean
 * (the main orchestration beans are {@code @Profile("!worker")}), so it must not be enabled in the main app.
 * Isolation mode is turned on via the {@code protean.isolation.mode} property, not via a profile.
 */
@Component
@Profile("worker-demo")
class WorkerDeployer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkerDeployer.class);

    private final ModulePlatform platform;

    WorkerDeployer(ModulePlatform platform) {
        this.platform = platform;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            platform.install(SampleModules.computeModule());
            log.info("[quickstart] Compute module deployed to worker → GET /compute/square?n=7");
        } catch (IllegalStateException alreadyPresent) {
            // Already restored via reconcile from the durable store on restart — handle idempotently.
            log.info("[quickstart] Compute module already deployed (restored from store) — skipping");
        } catch (Exception e) {
            log.error("[quickstart] Failed to deploy compute module to worker", e);
        }
    }
}
