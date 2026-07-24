/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.isolation;

import java.util.ArrayList;
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

    /**
     * Assembles a worker JVM launch command with heap/JVM options in the correct position (after the java binary,
     * before {@code -cp}/main class): an optional cgroup-relative heap default for the container track, then the
     * operator's {@code protean.worker.jvm-args}, then the caller's {@code tail} ({@code [-cp, <cp>, <mainClass>]}).
     *
     * @param javaBin           the java executable (or {@code "java"} inside a container)
     * @param containerHeapDefault when true, inserts {@code -XX:MaxRAMPercentage=75.0} — only for the container track,
     *                             which has a cgroup memory bound (a percentage on an unbounded process would size
     *                             heap against the whole host)
     * @param jvmArgs           operator-supplied extra JVM args ({@code protean.worker.jvm-args}); may be empty/null
     * @param tail              the classpath + main-class portion
     */
    static List<String> javaCommand(String javaBin, boolean containerHeapDefault, List<String> jvmArgs, List<String> tail) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        if (containerHeapDefault) {
            cmd.add("-XX:MaxRAMPercentage=75.0");
        }
        if (jvmArgs != null) {
            cmd.addAll(jvmArgs);
        }
        cmd.addAll(tail);
        return List.copyOf(cmd);
    }
}
