/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.boot;

import org.htcom.protean.ProteanApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Worker-JVM-only entry point (in preparation for the library form).
 *
 * <p>This is the main that an external worker (process/container track) re-executes. It indirects
 * through this class so the isolation strategy does not hardcode the
 * {@code "org.htcom.protean.ProteanApplication"} string — if ProteanApplication disappears with the
 * shift to library auto-config, <b>only this one place</b> needs to change.
 *
 * <p><b>Why it is not annotated with @SpringBootApplication</b>: if a second
 * {@code @SpringBootConfiguration} exists, the single-configuration auto-detection of a
 * {@code @SpringBootTest} without {@code classes=} breaks with "multiple @SpringBootConfiguration".
 * Instead, it boots the existing single configuration ({@link ProteanApplication}) as the source and
 * forces the {@code worker} profile → host beans ({@code @Profile("!worker")}) are excluded and only
 * worker beans come up, minimally (minimal context). Shared beans are accessed via the RPC bridge.
 */
public final class ProteanWorkerLauncher {

    /** The FQCN of the main class the isolation strategy specifies when re-executing the worker JVM. */
    public static final String MAIN_CLASS = "org.htcom.protean.boot.ProteanWorkerLauncher";

    private ProteanWorkerLauncher() {
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(ProteanApplication.class)
                .profiles("worker")
                .run(args);
    }
}
