/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.isolation;

import java.util.List;

/**
 * SPI for supplying the worker runtime artifact — separates "what/how to launch the worker JVM" from the isolation
 * strategy.
 *
 * <p>The swappable axis of the library's deployment model:
 * <ul>
 *   <li><b>Embed</b>({@link EmbeddedWorkerRuntime}, default): worker = host artifact (host classpath /
 *       host bootJar explode). Since the worker compiles sources at runtime, classpath parity is free.</li>
 *   <li><b>Sidecar</b>({@link SidecarWorkerRuntime}, opt-in): worker = a dedicated slim jar/image published by
 *       Protean. Favorable for isolation/surface minimization, but shared types must be injected separately via
 *       shared-api.</li>
 * </ul>
 *
 * <p>The common {@code --spring.*} arguments (profile/isolation.mode/server.port/datasource) are appended afterward
 * by the isolation strategy. This SPI is responsible only for the "JVM/container launch" part that comes before them.
 */
public interface WorkerRuntimeProvider {

    /** process track: the prefix of the worker JVM launch command {@code [javaBin, -cp, classpath, mainClass]}. */
    List<String> processLaunchPrefix();

    /** container track: the image to use + docker run mount args + the in-container command prefix. */
    ContainerLaunchSpec containerLaunchSpec();

    /**
     * Container-track launch spec.
     *
     * @param image         docker image
     * @param mountArgs     mount args to add to docker run (e.g. {@code ["-v", "/abs:/app:ro"]}). Empty list if none.
     * @param commandPrefix in-container command prefix (e.g.
     *                      {@code ["java","-cp","/app/BOOT-INF/classes:/app/BOOT-INF/lib/*", mainClass]}).
     */
    record ContainerLaunchSpec(String image, List<String> mountArgs, List<String> commandPrefix) {
    }
}
