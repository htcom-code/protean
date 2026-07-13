/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reverse index for shared-module typed sharing: library id → the ACTIVE modules that {@code use} it (its dependents).
 * The forward direction (module → the libraries it uses) is {@link ModuleDescriptor#uses() consumer-authored} in the
 * descriptor, so — unlike the shared-lib jar index (server-observed) — this reverse index is derived directly from it.
 * It answers the question a library republish asks: "library X changed → <b>who</b> must move to the new generation?"
 * in O(1) instead of scanning every module.
 *
 * <p><b>Derived, not stored.</b> The store's descriptors are the source of truth. Built once from the ACTIVE modules at
 * startup and kept current incrementally through the module-change listener (install/approve/update/remove); it is
 * rebuildable at any time, so it needs no durability. Scope = ACTIVE modules — exactly the set a generation change
 * would rebind. Mirror of {@link SharedLibUsageIndex}.
 */
@Component
@Profile("!worker")
public class SharedModuleUsageIndex {

    private static final Logger log = LoggerFactory.getLogger(SharedModuleUsageIndex.class);

    private final ModuleStore store;

    /** moduleId → the library ids it uses. Kept so an update can diff against the previous set. */
    private final Map<String, Set<String>> forward = new HashMap<>();
    /** library id → the dependent modules that use it (the reverse index proper). */
    private final Map<String, Set<String>> reverse = new HashMap<>();
    private final Object lock = new Object();

    public SharedModuleUsageIndex(ModuleStore store, ModulePlatform platform) {
        this.store = store;
        platform.addChangeListener(this::refresh);
    }

    /** Builds the initial index from the ACTIVE modules already in the store (survives across a restart). */
    @PostConstruct
    void build() {
        int indexed = 0;
        for (ModuleDescriptor d : store.listActive()) {
            if (put(d.id(), d.uses())) {
                indexed++;
            }
        }
        if (indexed > 0) {
            log.info("shared-module usage index built: {} dependent(s) → {} library(ies)", indexed, reverse.size());
        }
    }

    /**
     * Re-reads a module after a change and updates its index entry. Present + ACTIVE → (re)indexed with its current
     * {@code uses}; anything else (removed, or no longer ACTIVE) → dropped. Never throws (listener contract).
     */
    void refresh(String moduleId) {
        ModuleDescriptor d = store.load(moduleId).orElse(null);
        if (d != null && d.desiredState() == ModuleDescriptor.DesiredState.ACTIVE) {
            put(moduleId, d.uses());
        } else {
            drop(moduleId);
        }
    }

    /** The ACTIVE modules that use this library. Empty if none. */
    public Set<String> dependentsOf(String libraryId) {
        synchronized (lock) {
            Set<String> mods = reverse.get(libraryId);
            return mods == null ? Set.of() : Set.copyOf(mods);
        }
    }

    /** An immutable snapshot of the reverse index (library → dependents) — for observability. */
    public Map<String, Set<String>> snapshot() {
        synchronized (lock) {
            Map<String, Set<String>> copy = new HashMap<>();
            reverse.forEach((lib, users) -> copy.put(lib, Set.copyOf(users)));
            return Map.copyOf(copy);
        }
    }

    /** Sets a module's library usage to {@code uses}, diffing against its previous set. Returns true if it uses any. */
    private boolean put(String moduleId, List<String> uses) {
        synchronized (lock) {
            Set<String> next = Set.copyOf(uses);
            Set<String> prev = forward.getOrDefault(moduleId, Set.of());
            for (String gone : prev) {
                if (!next.contains(gone)) {
                    removeReverse(gone, moduleId);
                }
            }
            for (String lib : next) {
                reverse.computeIfAbsent(lib, k -> new HashSet<>()).add(moduleId);
            }
            if (next.isEmpty()) {
                forward.remove(moduleId);
                return false;
            }
            forward.put(moduleId, next);
            return true;
        }
    }

    /** Removes a module from the index entirely. */
    private void drop(String moduleId) {
        synchronized (lock) {
            Set<String> prev = forward.remove(moduleId);
            if (prev != null) {
                for (String lib : prev) {
                    removeReverse(lib, moduleId);
                }
            }
        }
    }

    /** Removes one (library → module) edge, cleaning up the library key when its set becomes empty. Caller holds lock. */
    private void removeReverse(String libraryId, String moduleId) {
        Set<String> users = reverse.get(libraryId);
        if (users != null) {
            users.remove(moduleId);
            if (users.isEmpty()) {
                reverse.remove(libraryId);
            }
        }
    }
}
