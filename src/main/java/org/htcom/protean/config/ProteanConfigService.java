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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runtime configuration surface: reads the current {@code protean.*} values and applies changes to the live
 * {@link ProteanProperties} according to each key's {@link ConfigTier}. Both the Admin REST controller and the MCP config tools call
 * this one service so the classify/validate/apply/audit logic lives in a single place.
 *
 * <p><b>Atomicity:</b> a change batch is validated in full first. If any key is unknown or has a malformed/out-of-range
 * value, the <b>entire batch is aborted</b> and nothing is applied (fail-safe). Restart-tier keys are reported
 * {@code REQUIRES_RESTART} but do not abort the applicable keys in the same batch.
 */
@Service
public class ProteanConfigService {

    private static final Logger log = LoggerFactory.getLogger(ProteanConfigService.class);

    private final ProteanProperties props;
    private final ConfigRegistry registry;
    private final ApplicationEventPublisher events;

    public ProteanConfigService(ProteanProperties props, ConfigRegistry registry, ApplicationEventPublisher events) {
        this.props = props;
        this.registry = registry;
        this.events = events;
    }

    /** Every known key with its current value and tier (declaration order). */
    public List<ConfigEntry> list() {
        List<ConfigEntry> out = new ArrayList<>();
        for (ConfigKey k : registry.all()) {
            out.add(entryOf(k));
        }
        return out;
    }

    /** A single key's current value and tier, or empty if the key is unknown. */
    public Optional<ConfigEntry> get(String key) {
        ConfigKey k = registry.get(key);
        return k == null ? Optional.empty() : Optional.of(entryOf(k));
    }

    private ConfigEntry entryOf(ConfigKey k) {
        return new ConfigEntry(k.key(), k.current(props), k.tier(), k.liveApplicable());
    }

    /** {@link #apply(Map, String)} with an anonymous actor. */
    public ApplyResult apply(Map<String, JsonNode> patch) {
        return apply(patch, "system");
    }

    /**
     * Apply a batch of changes. See the class-level atomicity note. The returned {@link ApplyResult} carries a
     * per-key outcome; {@link ApplyResult#applied()} is true only when the batch was committed (no unknown/invalid key).
     *
     * @param actor audit subject (e.g. {@code rest} or {@code mcp:<principal>}) — logged with the correlation id
     */
    public synchronized ApplyResult apply(Map<String, JsonNode> patch, String actor) {
        // Phase A — validate + classify every key without mutating anything.
        record Pending(ConfigKey key, Object typedValue) {
        }
        List<KeyOutcome> outcomes = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        boolean abort = false;

        for (Map.Entry<String, JsonNode> e : patch.entrySet()) {
            String rawKey = e.getKey();
            ConfigKey ck = registry.get(rawKey);
            if (ck == null) {
                outcomes.add(new KeyOutcome(rawKey, null, Outcome.REJECTED_UNKNOWN, "unknown config key"));
                abort = true;
                continue;
            }
            if (!ck.liveApplicable()) {
                outcomes.add(new KeyOutcome(ck.key(), ck.tier(), Outcome.REQUIRES_RESTART, restartReason(ck)));
                continue;
            }
            try {
                Object typed = ck.parse(e.getValue());
                pending.add(new Pending(ck, typed));
                // tentative — finalized in phase B
                outcomes.add(new KeyOutcome(ck.key(), ck.tier(),
                        ck.tier() == ConfigTier.LIVE ? Outcome.APPLIED_LIVE : Outcome.APPLIED_FUTURE, null));
            } catch (IllegalArgumentException ex) {
                outcomes.add(new KeyOutcome(ck.key(), ck.tier(), Outcome.REJECTED_INVALID, ex.getMessage()));
                abort = true;
            }
        }

        // Phase B — commit, or abort the whole batch on any unknown/invalid key.
        if (abort) {
            List<KeyOutcome> finalized = new ArrayList<>();
            for (KeyOutcome o : outcomes) {
                if (o.outcome() == Outcome.APPLIED_LIVE || o.outcome() == Outcome.APPLIED_FUTURE) {
                    finalized.add(new KeyOutcome(o.key(), o.tier(), Outcome.NOT_APPLIED_BATCH_ABORTED,
                            "batch aborted due to another invalid/unknown key"));
                } else {
                    finalized.add(o);
                }
            }
            log.warn("config apply aborted by actor='{}' (no changes): {}", actor, finalized);
            return new ApplyResult(false, finalized);
        }

        Set<String> changed = new LinkedHashSet<>();
        for (Pending p : pending) {
            p.key().apply(props, p.typedValue());
            changed.add(p.key().key());
        }
        if (!changed.isEmpty()) {
            // Audit: actor + changed keys, correlated via the request's traceId in the MDC log pattern.
            log.info("config applied by actor='{}' ({} key(s)): {}", actor, changed.size(), changed);
            events.publishEvent(new ConfigChangedEvent(this, changed));
        }
        return new ApplyResult(true, outcomes);
    }

    private static String restartReason(ConfigKey key) {
        if (key.liveReadPending()) {
            return "requires restart: tier " + key.tier() + " but its runtime live-read is not yet wired "
                    + "(the consumer captures the value at construction) — set it in the config file and restart";
        }
        return key.tier() == ConfigTier.RESTART_CONDITIONAL
                ? "requires restart: drives a @ConditionalOnProperty bean-graph decision fixed at context startup"
                : "requires restart: an initialization artifact was built from this value and cannot be rebound live";
    }

    /**
     * A key's current value and tier. {@code liveApplicable} is true when a change can actually take effect at
     * runtime now (tier-applicable and its live-read is wired); false for restart tiers and not-yet-wired keys.
     */
    public record ConfigEntry(String key, Object value, ConfigTier tier, boolean liveApplicable) {
    }

    /** Per-key result of an {@link #apply} batch. */
    public record KeyOutcome(String key, ConfigTier tier, Outcome outcome, String reason) {
    }

    /** Result of an {@link #apply} batch: whether it was committed, and each key's outcome. */
    public record ApplyResult(boolean applied, List<KeyOutcome> outcomes) {
    }

    /** Outcome of a single key in an apply batch. */
    public enum Outcome {
        /** Tier 1 — applied and effective immediately. */
        APPLIED_LIVE,
        /** Tier 2 — applied; takes effect for instances created afterward (not retroactive). */
        APPLIED_FUTURE,
        /** Tier 3 — not applied; a restart is required to change this key. */
        REQUIRES_RESTART,
        /** Rejected — unknown key (fail-safe); the whole batch was aborted. */
        REJECTED_UNKNOWN,
        /** Rejected — malformed or out-of-range value; the whole batch was aborted. */
        REJECTED_INVALID,
        /** Not applied — this (otherwise valid) key was rolled back because another key aborted the batch. */
        NOT_APPLIED_BATCH_ABORTED
    }
}
