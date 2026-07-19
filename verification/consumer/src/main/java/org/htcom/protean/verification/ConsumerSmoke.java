/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.htcom.protean.verification;

import org.htcom.protean.ProteanApplication;

/**
 * Release consume-test entry point. This class lives in a standalone build that depends on the published
 * {@code org.htcom:protean} coordinate (resolved from mavenLocal), not on the project source. Compiling it proves the
 * published jar's public API links against a downstream classpath; loading a core Protean type at runtime proves the
 * published POM's transitive runtime dependencies actually resolve. If either fails, the release gate fails before any
 * artifact is uploaded to the Central Portal.
 */
public final class ConsumerSmoke {

    private ConsumerSmoke() {
    }

    public static void main(String[] args) {
        // Reference a stable public type from the published jar. Loading it forces the class (and the transitive
        // dependencies its declaration touches) to be present on the resolved consumer classpath.
        Class<?> linked = ProteanApplication.class;
        System.out.println("protean consume-test OK: linked " + linked.getName());
    }
}
