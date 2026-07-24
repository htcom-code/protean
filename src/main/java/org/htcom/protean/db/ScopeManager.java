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

import java.util.ArrayList;
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

    /**
     * Effective state of a scope: the persisted record's state when present, else {@link ScopeRecord.State#ACTIVE} for a
     * seed-only scope, else empty when the name is unknown. A seed scope is deployable until an admin action persists a
     * non-ACTIVE state.
     */
    public Optional<ScopeRecord.State> stateOf(String name) {
        Optional<ScopeRecord> rec = store.load(name);
        if (rec.isPresent()) {
            return Optional.of(rec.get().state());
        }
        return seedNames().contains(name) ? Optional.of(ScopeRecord.State.ACTIVE) : Optional.empty();
    }

    /** A module may deploy to a scope only when it is known and its effective state is ACTIVE (not CLOSED/DETACHED). */
    public boolean isDeployable(String name) {
        return name != null && stateOf(name).orElse(null) == ScopeRecord.State.ACTIVE;
    }

    /** Record (or refresh) a scope as ACTIVE in the durable registry — called when a scope is first provisioned. */
    public void markActive(String name, String dialectId) {
        store.save(new ScopeRecord(name, ScopeRecord.State.ACTIVE, dialectId));
    }

    /**
     * Admin: create/reopen a scope as ACTIVE in the allowlist. Idempotent — for an existing scope this reopens it
     * (CLOSED/DETACHED → ACTIVE); the DATABASE is (re)provisioned lazily on the next deploy. {@code dialectId} records
     * the vendor the scope is provisioned under.
     */
    public void create(String name, String dialectId) {
        store.save(new ScopeRecord(name, ScopeRecord.State.ACTIVE, dialectId));
    }

    /** Admin: close a scope — removed from the deployable allowlist; running modules keep serving. Reversible via {@link #open}. */
    public void close(String name, String dialectId) {
        store.save(new ScopeRecord(name, ScopeRecord.State.CLOSED, dialectId));
    }

    /** Admin: reopen a CLOSED scope back to ACTIVE. */
    public void open(String name, String dialectId) {
        store.save(new ScopeRecord(name, ScopeRecord.State.ACTIVE, dialectId));
    }

    /** Admin: mark a scope DETACHED (its login was dropped; DB/data retained). Reversible via re-{@link #create}. */
    public void markDetached(String name, String dialectId) {
        store.save(new ScopeRecord(name, ScopeRecord.State.DETACHED, dialectId));
    }

    /** Admin: forget a scope's registry record entirely (after an irreversible destroy). */
    public void remove(String name) {
        store.remove(name);
    }

    public Optional<ScopeRecord> get(String name) {
        return store.load(name);
    }

    public List<ScopeRecord> list() {
        return store.list();
    }

    /**
     * Merged admin view: every known scope (registry ∪ seed) as a {@link ScopeRecord}. A seed scope with no persisted
     * record is synthesized as ACTIVE under {@code defaultDialectId} (not yet provisioned, but deployable).
     */
    public List<ScopeRecord> listAll(String defaultDialectId) {
        List<ScopeRecord> out = new ArrayList<>();
        for (String name : knownScopeNames()) {
            out.add(store.load(name)
                    .orElseGet(() -> new ScopeRecord(name, ScopeRecord.State.ACTIVE, defaultDialectId)));
        }
        return out;
    }
}
