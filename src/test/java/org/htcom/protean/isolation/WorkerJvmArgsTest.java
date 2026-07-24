/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.isolation;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Worker JVM launch args (Docker-free): the container track gets a cgroup-relative heap default
 * ({@code -XX:MaxRAMPercentage=75.0}) while the process track does not (no memory bound), and
 * {@code protean.worker.jvm-args} is threaded into both, positioned after the java binary and before {@code -cp}.
 */
class WorkerJvmArgsTest {

    private static final String HEAP = "-XX:MaxRAMPercentage=75.0";

    @Test
    void javaCommand_containerGetsHeapDefault_processDoesNot() {
        List<String> tail = List.of("-cp", "cp", "Main");

        List<String> process = WorkerRuntimeProvider.javaCommand("java", false, List.of(), tail);
        assertEquals(List.of("java", "-cp", "cp", "Main"), process, "process track: no cgroup heap default");

        List<String> container = WorkerRuntimeProvider.javaCommand("java", true, List.of(), tail);
        assertEquals("java", container.get(0));
        assertEquals(HEAP, container.get(1), "container heap default must sit right after the java binary");
        assertTrue(container.indexOf(HEAP) < container.indexOf("-cp"), "heap arg must precede -cp/main");
    }

    @Test
    void javaCommand_threadsJvmArgsAfterHeapBeforeClasspath() {
        List<String> cmd = WorkerRuntimeProvider.javaCommand(
                "java", true, List.of("-Xmx512m", "-Dfoo=bar"), List.of("-cp", "cp", "Main"));
        assertTrue(cmd.indexOf(HEAP) < cmd.indexOf("-Xmx512m"), "operator jvm-args come after the heap default");
        assertTrue(cmd.indexOf("-Xmx512m") < cmd.indexOf("-cp"), "jvm-args precede -cp/main");
        assertTrue(cmd.contains("-Dfoo=bar"));
    }

    @Test
    void embeddedProcessPrefix_hasNoHeapDefault_butHonorsJvmArgs() {
        ProteanProperties props = new ProteanProperties();
        props.getWorker().setJvmArgs(List.of("-Xmx256m"));
        List<String> cmd = new EmbeddedWorkerRuntime(props).processLaunchPrefix();
        assertFalse(cmd.contains(HEAP), "process track must not add MaxRAMPercentage (unbounded host)");
        assertTrue(cmd.contains("-Xmx256m"), "operator jvm-args must be applied to the process worker");
        assertTrue(cmd.indexOf("-Xmx256m") < cmd.indexOf("-cp"));
    }

    @Test
    void sidecarContainerCommand_hasHeapDefaultAndJvmArgs() {
        ProteanProperties props = new ProteanProperties();
        props.getWorker().getSidecar().setImage("example/worker:latest");
        props.getWorker().setJvmArgs(List.of("-Xss1m"));
        List<String> cmd = new SidecarWorkerRuntime(props).containerLaunchSpec().commandPrefix();
        assertTrue(cmd.contains(HEAP), "container track carries the cgroup heap default");
        assertTrue(cmd.contains("-Xss1m"), "operator jvm-args applied to the container worker too");
    }
}
