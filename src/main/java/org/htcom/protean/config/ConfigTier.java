/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.config;

/**
 * Runtime-mutability classification of a {@code protean.*} configuration key. Decides how a change
 * request is handled by {@link ProteanConfigService}.
 */
public enum ConfigTier {

    /** Consulted at operation time — a change takes effect immediately (live). */
    LIVE,

    /**
     * Safe to change, but applies only to instances created afterward (new deploys/workers/containers);
     * already-running instances are not retroactively affected.
     */
    FUTURE,

    /**
     * Requires a restart: the key drives a {@code @ConditionalOnProperty} bean-graph decision that Spring
     * fixes at context refresh and never re-evaluates at runtime.
     */
    RESTART_CONDITIONAL,

    /**
     * Requires a restart: an initialization artifact (ClassLoader, daemon thread, store path, a secret
     * already handed to a running worker) was built from the value and cannot be rebound live.
     */
    RESTART_ARTIFACT;

    /** Whether a change to a key of this tier can be applied to the live {@code ProteanProperties}. */
    public boolean applicable() {
        return this == LIVE || this == FUTURE;
    }
}
