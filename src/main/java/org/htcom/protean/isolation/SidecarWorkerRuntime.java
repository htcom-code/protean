/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.isolation;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.boot.ProteanWorkerLauncher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Opt-in worker runtime = <b>Sidecar</b>. The worker = a dedicated slim artifact published by Protean (separate from
 * the host app). Favorable for isolation and attack-surface minimization (consistent with the DB-scope/container
 * hardening philosophy).
 *
 * <p><b>Shared-type constraint</b>: because the worker compiles module sources at runtime, the shared types a module
 * references must be placed on the worker classpath via a shared-api jar ({@code protean.worker.sidecar.shared-api}).
 * Unlike embed, this curation is always required — that is sidecar's cost.
 *
 * <p>Active when: {@code protean.worker.runtime=sidecar}. process needs {@code sidecar.jar} (+shared-api),
 * container needs {@code sidecar.image}. If unset, fail-fast with a clear error.
 */
@Component
@Profile("!worker")
@ConditionalOnProperty(name = "protean.worker.runtime", havingValue = "sidecar")
public class SidecarWorkerRuntime implements WorkerRuntimeProvider {

    // Read live so worker.sidecar.* changes apply to the next spawn (Tier 2, future).
    private final ProteanProperties props;

    public SidecarWorkerRuntime(ProteanProperties props) {
        this.props = props;
    }

    @Override
    public List<String> processLaunchPrefix() {
        String sidecarJar = props.getWorker().getSidecar().getJar();
        String sharedApi = props.getWorker().getSidecar().getSharedApi();
        if (sidecarJar.isBlank()) {
            throw new IllegalStateException(
                    "sidecar runtime (process): protean.worker.sidecar.jar is not set");
        }
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        String cp = sharedApi.isBlank() ? sidecarJar
                : sidecarJar + File.pathSeparator + sharedApi;
        // Process track has no memory bound, so no cgroup-relative heap default — operator sizes heap via jvm-args.
        return WorkerRuntimeProvider.javaCommand(javaBin, false, props.getWorker().getJvmArgs(),
                List.of("-cp", cp, ProteanWorkerLauncher.MAIN_CLASS));
    }

    @Override
    public ContainerLaunchSpec containerLaunchSpec() {
        String sidecarImage = props.getWorker().getSidecar().getImage();
        if (sidecarImage.isBlank()) {
            throw new IllegalStateException(
                    "sidecar runtime (container): protean.worker.sidecar.image is not set");
        }
        // The dedicated image bundles app + shared-api itself → no host mount. Assumes /app/* as the classpath.
        return new ContainerLaunchSpec(sidecarImage,
                List.of(),
                WorkerRuntimeProvider.javaCommand("java", true, props.getWorker().getJvmArgs(),
                        List.of("-cp", "/app/*", ProteanWorkerLauncher.MAIN_CLASS)));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
