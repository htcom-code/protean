/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.db;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Host-side facade over the {@link ScopeStore} registry — the single point the deploy path, reconcile, and the scope
 * admin API consult for "which scopes exist / are they deployable". The authoritative set is
 * {@code ScopeStore.list()} unioned with the startup seed ({@code worker.db.scopes}, defaulting to a single
 * {@code "default"} scope), so a scope named in a persisted module descriptor is honored even if the registry entry
 * was lost (self-heal).
 *
 * <p>Provisioning/worker wiring is not owned here (that lives in the isolation strategies); this only tracks the
 * registry + allowlist.
 */
@Component
@Profile("!worker")
public class ScopeManager {

    /** The implicit scope used when {@code worker.db.scopes} is unset — still selected explicitly at deploy time. */
    public static final String DEFAULT_SCOPE = "default";

    private final ScopeStore store;
    private final ProteanProperties props;

    public ScopeManager(ScopeStore store, ProteanProperties props) {
        this.store = store;
        this.props = props;
    }

    /** Seed scope names from config; a single {@link #DEFAULT_SCOPE} when unset. */
    public Set<String> seedNames() {
        List<String> configured = props.getWorker().getDb().getScopes();
        Set<String> names = new LinkedHashSet<>();
        if (configured == null || configured.isEmpty()) {
            names.add(DEFAULT_SCOPE);
        } else {
            names.addAll(configured);
        }
        return names;
    }

    /** All known scope names = registry ∪ seed. */
    public Set<String> knownScopeNames() {
        Set<String> names = new LinkedHashSet<>(seedNames());
        store.list().forEach(r -> names.add(r.name()));
        return names;
    }

    public boolean isKnown(String name) {
        return name != null && knownScopeNames().contains(name);
    }

    /** Record (or refresh) a scope as ACTIVE in the durable registry — called when a scope is first provisioned. */
    public void markActive(String name, String dialectId) {
        store.save(new ScopeRecord(name, ScopeRecord.State.ACTIVE, dialectId));
    }

    public Optional<ScopeRecord> get(String name) {
        return store.load(name);
    }

    public List<ScopeRecord> list() {
        return store.list();
    }
}
