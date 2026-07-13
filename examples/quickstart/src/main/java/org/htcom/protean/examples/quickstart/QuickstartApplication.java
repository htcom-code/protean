/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.examples.quickstart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/**
 * Example application that consumes the Protean library.
 *
 * <p>A single app runs three scenarios selected purely by <b>configuration (Spring profiles)</b>:
 * <ul>
 *   <li>default (no profile) — deploys the data-access module <b>in-process</b> ({@link DataAccessDeployer})</li>
 *   <li>{@code worker} — deploys the compute module to a <b>separate JVM worker</b> ({@link WorkerDeployer},
 *       {@code protean.isolation.mode=worker})</li>
 *   <li>{@code mcp} — enables the MCP adapter ({@code protean.mcp.enabled=true}) so an agent can
 *       deploy modules (no automatic deployment)</li>
 * </ul>
 *
 * <p>Protean features are wired automatically by {@code ProteanAutoConfiguration}, so this app only
 * needs to scan its own package. See the README for run instructions and curl examples.
 */
@SpringBootApplication
public class QuickstartApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuickstartApplication.class, args);
    }

    /**
     * A host shared bean for the RPC-bridge scenario. A worker/container module that declares
     * {@code SharedGreeting} in its {@code bridgedInterfaces} gets a proxy that forwards to this bean,
     * so {@code greet(...)} runs in the main JVM — the returned main pid proves it (it differs from the
     * calling module's worker pid).
     *
     * <p>{@code @Profile("!worker")} is essential here: this example's package sits under
     * {@code org.htcom.protean}, so the worker JVM's {@code ProteanApplication} component-scan would
     * otherwise re-create this bean locally in the worker (masking the bridge — greet would run in the
     * worker, not main). Excluding it from the {@code worker} profile leaves the worker without a local
     * bean, so the bridge proxy forwards to main. A real consumer app (package outside
     * {@code org.htcom.protean}) is not scanned by the worker and would not need this.
     */
    @Bean
    @Profile("!worker")
    SharedGreeting sharedGreeting() {
        return name -> "hello " + name + " (from main pid=" + ProcessHandle.current().pid() + ")";
    }
}
