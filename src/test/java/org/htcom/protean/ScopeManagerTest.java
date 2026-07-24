/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.db.ScopeManager;
import org.htcom.protean.db.ScopeRecord;
import org.htcom.protean.db.ScopeStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registry-facade unit tests for {@link ScopeManager} — the scope state machine and deployability rules, with an
 * in-memory {@link ScopeStore} (no DB). Verifies seed defaulting, ACTIVE/CLOSED/DETACHED transitions, {@code isDeployable}
 * (only ACTIVE is deployable), and the merged {@code listAll} view.
 */
class ScopeManagerTest {

    /** In-memory ScopeStore (the project avoids Mockito; a hand fake keeps the test dependency-free). */
    static final class InMemoryStore implements ScopeStore {
        final Map<String, ScopeRecord> map = new LinkedHashMap<>();

        @Override
        public void save(ScopeRecord scope) {
            map.put(scope.name(), scope);
        }

        @Override
        public Optional<ScopeRecord> load(String name) {
            return Optional.ofNullable(map.get(name));
        }

        @Override
        public List<ScopeRecord> list() {
            return new ArrayList<>(map.values());
        }

        @Override
        public void remove(String name) {
            map.remove(name);
        }
    }

    private static ScopeManager manager(InMemoryStore store, String... seed) {
        ProteanProperties props = new ProteanProperties();
        props.getWorker().getDb().setScopes(List.of(seed));
        return new ScopeManager(store, props);
    }

    @Test
    void empty_seed_defaults_to_a_single_default_scope() {
        ScopeManager m = manager(new InMemoryStore());
        assertEquals(java.util.Set.of("default"), m.knownScopeNames());
        assertTrue(m.isDeployable("default"));
        assertEquals(ScopeRecord.State.ACTIVE, m.stateOf("default").orElseThrow());
        assertFalse(m.isDeployable("other"), "an unseeded scope is not deployable");
    }

    @Test
    void configured_seed_replaces_default_and_is_deployable() {
        ScopeManager m = manager(new InMemoryStore(), "alpha", "beta");
        assertTrue(m.isDeployable("alpha"));
        assertTrue(m.isDeployable("beta"));
        assertFalse(m.isKnown("default"), "with an explicit seed, 'default' is not implied");
    }

    @Test
    void create_makes_an_unseeded_scope_known_and_deployable() {
        InMemoryStore store = new InMemoryStore();
        ScopeManager m = manager(store, "alpha");
        assertFalse(m.isKnown("gamma"));
        m.create("gamma", "postgresql");
        assertTrue(m.isKnown("gamma"));
        assertTrue(m.isDeployable("gamma"));
        assertEquals("postgresql", m.get("gamma").orElseThrow().dialectId());
    }

    @Test
    void close_then_open_toggles_deployability_without_forgetting_the_scope() {
        ScopeManager m = manager(new InMemoryStore(), "alpha");
        m.close("alpha", "postgresql");
        assertTrue(m.isKnown("alpha"), "a closed scope is still known");
        assertFalse(m.isDeployable("alpha"), "a closed scope is not deployable");
        assertEquals(ScopeRecord.State.CLOSED, m.stateOf("alpha").orElseThrow());

        m.open("alpha", "postgresql");
        assertTrue(m.isDeployable("alpha"));
        assertEquals(ScopeRecord.State.ACTIVE, m.stateOf("alpha").orElseThrow());
    }

    @Test
    void detach_marks_detached_not_deployable_but_still_known() {
        ScopeManager m = manager(new InMemoryStore(), "alpha");
        m.markDetached("alpha", "postgresql");
        assertTrue(m.isKnown("alpha"));
        assertFalse(m.isDeployable("alpha"));
        assertEquals(ScopeRecord.State.DETACHED, m.stateOf("alpha").orElseThrow());
    }

    @Test
    void remove_forgets_an_unseeded_scope_entirely() {
        InMemoryStore store = new InMemoryStore();
        ScopeManager m = manager(store, "alpha");
        m.create("gamma", "postgresql");
        m.remove("gamma");
        assertFalse(m.isKnown("gamma"), "a removed, unseeded scope is gone");
    }

    @Test
    void remove_of_a_seeded_scope_falls_back_to_the_seed_active_state() {
        // A seeded scope with a persisted CLOSED record: removing the record reverts to the seed's implicit ACTIVE.
        ScopeManager m = manager(new InMemoryStore(), "alpha");
        m.close("alpha", "postgresql");
        assertFalse(m.isDeployable("alpha"));
        m.remove("alpha");
        assertTrue(m.isKnown("alpha"), "still seeded");
        assertTrue(m.isDeployable("alpha"), "reverts to the seed's implicit ACTIVE state");
    }

    @Test
    void listAll_merges_seed_and_registry_synthesizing_active_for_unpersisted_seed() {
        InMemoryStore store = new InMemoryStore();
        ScopeManager m = manager(store, "alpha", "beta");
        m.close("beta", "postgresql");
        m.create("gamma", "postgresql");

        Map<String, ScopeRecord.State> byName = new LinkedHashMap<>();
        for (ScopeRecord r : m.listAll("postgresql")) {
            byName.put(r.name(), r.state());
        }
        assertEquals(ScopeRecord.State.ACTIVE, byName.get("alpha"), "unpersisted seed → synthesized ACTIVE");
        assertEquals(ScopeRecord.State.CLOSED, byName.get("beta"));
        assertEquals(ScopeRecord.State.ACTIVE, byName.get("gamma"));
        assertEquals("postgresql", m.listAll("postgresql").stream()
                .filter(r -> r.name().equals("alpha")).findFirst().orElseThrow().dialectId());
    }
}
