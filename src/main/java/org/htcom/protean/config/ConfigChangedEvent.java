/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.config;

import org.springframework.context.ApplicationEvent;

import java.util.Set;

/**
 * Published by {@link ProteanConfigService} after a batch of live/future config changes has been applied to
 * {@code ProteanProperties}. Beans that hold derived state (rather than reading the live properties per
 * operation) subscribe to re-derive on the relevant keys — e.g. {@code TraceStore} trims its ring buffer
 * when {@code trace.capacity} shrinks.
 *
 * <p>{@code changedKeys} are canonical relative keys (e.g. {@code trace.capacity}); Tier 3 (rejected) keys
 * are never included since nothing was applied for them.
 */
public class ConfigChangedEvent extends ApplicationEvent {

    private final Set<String> changedKeys;

    public ConfigChangedEvent(Object source, Set<String> changedKeys) {
        super(source);
        this.changedKeys = Set.copyOf(changedKeys);
    }

    /** Canonical relative keys that were actually applied in this batch. */
    public Set<String> changedKeys() {
        return changedKeys;
    }

    /** Whether the given canonical relative key was changed in this batch. */
    public boolean changed(String key) {
        return changedKeys.contains(key);
    }
}
