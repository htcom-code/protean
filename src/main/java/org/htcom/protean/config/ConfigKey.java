/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.htcom.protean.autoconfigure.ProteanProperties;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * One entry in the config-key whitelist: a single {@code protean.*} key, its {@link ConfigTier}, and how to
 * read/parse/apply it against the live {@link ProteanProperties}. Built by {@link ConfigRegistry}.
 *
 * <p>{@code parse} validates and converts a JSON value to the typed value (throwing
 * {@link IllegalArgumentException} on a bad value); {@code setter} applies the typed value. Both are
 * {@code null} for restart tiers (never applied live).
 */
public final class ConfigKey {

    private final String key;
    private final ConfigTier tier;
    private final Function<ProteanProperties, Object> getter;
    private final Function<JsonNode, Object> parse;
    private final BiConsumer<ProteanProperties, Object> setter;
    /**
     * True for a key whose tier is theoretically live/future-applicable, but whose consumer still captures the
     * value at construction (runtime live-read not yet wired). Such a key is reported as requiring a restart so the
     * response never over-promises a change that would silently no-op.
     */
    private boolean liveReadPending;

    ConfigKey(String key, ConfigTier tier, Function<ProteanProperties, Object> getter,
              Function<JsonNode, Object> parse, BiConsumer<ProteanProperties, Object> setter) {
        this.key = key;
        this.tier = tier;
        this.getter = getter;
        this.parse = parse;
        this.setter = setter;
    }

    /** Marks this key as tier-applicable but not yet wired for live reads (see {@link #liveReadPending()}). */
    ConfigKey markLiveReadPending() {
        this.liveReadPending = true;
        return this;
    }

    /** Whether this key's live-read is not yet wired (so a change requires a restart despite its tier). */
    public boolean liveReadPending() {
        return liveReadPending;
    }

    /** Whether a change can actually take effect at runtime now (tier-applicable and live-read wired). */
    public boolean liveApplicable() {
        return tier.applicable() && !liveReadPending;
    }

    /** Canonical relative key (e.g. {@code trace.capacity}). */
    public String key() {
        return key;
    }

    public ConfigTier tier() {
        return tier;
    }

    /** Current typed value read from the live properties (for list/get display). */
    public Object current(ProteanProperties props) {
        return getter.apply(props);
    }

    /**
     * Validate + convert a JSON value to its typed form. Only meaningful for applicable (LIVE/FUTURE) keys.
     * @throws IllegalArgumentException if the value is malformed or out of range
     */
    public Object parse(JsonNode value) {
        if (parse == null) {
            throw new IllegalStateException("key is not live-applicable: " + key);
        }
        return parse.apply(value);
    }

    /** Apply a previously-parsed typed value to the live properties. Only for applicable keys. */
    public void apply(ProteanProperties props, Object typedValue) {
        if (setter == null) {
            throw new IllegalStateException("key is not live-applicable: " + key);
        }
        setter.accept(props, typedValue);
    }
}
