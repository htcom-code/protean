/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import org.htcom.protean.compiler.UsedSharedLib;
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
 * Reverse index: shared-lib jar ({@link UsedSharedLib} = name + sha256) → the ACTIVE modules that use it. The
 * forward direction (module → jars) is produced and stored by the compiler (see
 * {@link ModuleDescriptor#usedSharedLibs()}); this class
 * derives the reverse so the question a jar change actually asks — "jar X changed → <b>who</b> uses it?" — is
 * answered in O(1) instead of scanning every module.
 *
 * <p><b>Derived, not stored.</b> The store's {@code usedSharedLibs} is the source of truth. The index is built
 * once from the ACTIVE modules at startup and kept current incrementally through the module-change listener
 * (install/approve/update/remove); it is rebuildable from the store at any time, so it needs no durability.
 *
 * <p><b>Scope = ACTIVE modules</b> (bound and served) — exactly the set a generation change would need to
 * re-bind or isolate. A module that leaves ACTIVE (uninstalled, or reverted to PENDING/INACTIVE) is dropped.
 *
 * <p><b>Consumer boundary.</b> This is the reverse index only. The generation-change hook and the
 * Plan A (rebind) / Plan B (sticky on the prior generation) execution live in {@link SharedLibInvalidator},
 * which computes its target set by calling {@link #modulesUsing}.
 */
@Component
@Profile("!worker")
public class SharedLibUsageIndex {

    private static final Logger log = LoggerFactory.getLogger(SharedLibUsageIndex.class);

    private final ModuleStore store;

    /** moduleId → the shared-lib jars it uses. Kept so an update can diff against the previous set. */
    private final Map<String, Set<UsedSharedLib>> forward = new HashMap<>();
    /** shared-lib jar → the modules that use it (the reverse index proper). */
    private final Map<UsedSharedLib, Set<String>> reverse = new HashMap<>();
    private final Object lock = new Object();

    public SharedLibUsageIndex(ModuleStore store, ModulePlatform platform) {
        this.store = store;
        // Stay current: every successful install/approve/update/remove re-reads that module from the store.
        platform.addChangeListener(this::refresh);
    }

    /** Builds the initial index from the ACTIVE modules already in the store (survives across a restart). */
    @PostConstruct
    void build() {
        int indexed = 0;
        for (ModuleDescriptor d : store.listActive()) {
            if (put(d.id(), d.usedSharedLibs())) {
                indexed++;
            }
        }
        if (indexed > 0) {
            log.info("shared-lib usage index built: {} module(s) → {} jar(s)", indexed, reverse.size());
        }
    }

    /**
     * Re-reads a module after a change and updates its index entry. Present + ACTIVE → (re)indexed with its
     * current usage; anything else (removed, or no longer ACTIVE) → dropped. Never throws (listener contract).
     */
    void refresh(String moduleId) {
        ModuleDescriptor d = store.load(moduleId).orElse(null);
        if (d != null && d.desiredState() == ModuleDescriptor.DesiredState.ACTIVE) {
            put(moduleId, d.usedSharedLibs());
        } else {
            drop(moduleId);
        }
    }

    /** The ACTIVE modules that use exactly this jar (name + sha256). Empty if none. */
    public Set<String> modulesUsing(UsedSharedLib jar) {
        synchronized (lock) {
            Set<String> mods = reverse.get(jar);
            return mods == null ? Set.of() : Set.copyOf(mods);
        }
    }

    /** The ACTIVE modules that use any version of a jar with this file name (across content hashes). Empty if none. */
    public Set<String> modulesUsing(String jarName) {
        synchronized (lock) {
            Set<String> mods = new HashSet<>();
            reverse.forEach((jar, users) -> {
                if (jar.name().equals(jarName)) {
                    mods.addAll(users);
                }
            });
            return Set.copyOf(mods);
        }
    }

    /** An immutable snapshot of the reverse index (jar → modules) — for observability. */
    public Map<UsedSharedLib, Set<String>> snapshot() {
        synchronized (lock) {
            Map<UsedSharedLib, Set<String>> copy = new HashMap<>();
            reverse.forEach((jar, users) -> copy.put(jar, Set.copyOf(users)));
            return Map.copyOf(copy);
        }
    }

    /** Sets a module's usage to {@code jars}, diffing against its previous set. Returns true if it now has any. */
    private boolean put(String moduleId, List<UsedSharedLib> jars) {
        synchronized (lock) {
            Set<UsedSharedLib> next = Set.copyOf(jars);
            Set<UsedSharedLib> prev = forward.getOrDefault(moduleId, Set.of());
            for (UsedSharedLib gone : prev) {
                if (!next.contains(gone)) {
                    removeReverse(gone, moduleId);
                }
            }
            for (UsedSharedLib jar : next) {
                reverse.computeIfAbsent(jar, k -> new HashSet<>()).add(moduleId);
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
            Set<UsedSharedLib> prev = forward.remove(moduleId);
            if (prev != null) {
                for (UsedSharedLib jar : prev) {
                    removeReverse(jar, moduleId);
                }
            }
        }
    }

    /** Removes one (jar → module) edge, cleaning up the jar key when its module set becomes empty. Caller holds lock. */
    private void removeReverse(UsedSharedLib jar, String moduleId) {
        Set<String> users = reverse.get(jar);
        if (users != null) {
            users.remove(moduleId);
            if (users.isEmpty()) {
                reverse.remove(jar);
            }
        }
    }
}
