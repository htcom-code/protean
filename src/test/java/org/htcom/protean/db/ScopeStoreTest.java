/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip + reload coverage for the two {@link ScopeStore} implementations (previously only exercised via an
 * in-memory fake in {@code ScopeManagerTest}). Confirms save/load/list/remove and — critically — that a record
 * survives into a fresh store instance backed by the same medium (persistence, the property reconcile relies on).
 */
class ScopeStoreTest {

    private static void roundTrip(ScopeStore store, java.util.function.Supplier<ScopeStore> reopen) {
        store.save(new ScopeRecord("alpha", ScopeRecord.State.ACTIVE, "postgresql"));
        store.save(new ScopeRecord("beta", ScopeRecord.State.CLOSED, "mysql"));

        assertEquals(ScopeRecord.State.ACTIVE, store.load("alpha").orElseThrow().state());
        assertEquals("mysql", store.load("beta").orElseThrow().dialectId());
        assertEquals(2, store.list().size());
        assertTrue(store.load("ghost").isEmpty());

        // upsert (state transition) overwrites, not duplicates
        store.save(new ScopeRecord("alpha", ScopeRecord.State.DETACHED, "postgresql"));
        assertEquals(ScopeRecord.State.DETACHED, store.load("alpha").orElseThrow().state());
        assertEquals(2, store.list().size());

        // persists into a fresh instance over the same medium
        ScopeStore reopened = reopen.get();
        assertEquals(ScopeRecord.State.DETACHED, reopened.load("alpha").orElseThrow().state());

        reopened.remove("alpha");
        assertTrue(reopened.load("alpha").isEmpty());
        assertEquals(1, reopened.list().size());
    }

    @Test
    void filesystem_store_round_trips_and_persists(@TempDir Path dir) {
        ProteanProperties props = new ProteanProperties();
        props.getModuleStore().setDir(dir.toString());
        ObjectMapper mapper = new ObjectMapper();
        roundTrip(new FileSystemScopeStore(props, mapper), () -> new FileSystemScopeStore(props, mapper));
    }

    @Test
    void jdbc_store_round_trips_and_persists() {
        // Shared in-memory H2 (DB_CLOSE_DELAY=-1 keeps it alive across connections) so a "reopened" store sees the data.
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:scopestore;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        JdbcScopeStore store = new JdbcScopeStore(jdbc);
        store.initSchema();
        roundTrip(store, () -> {
            JdbcScopeStore s = new JdbcScopeStore(jdbc);
            s.initSchema();   // idempotent (CREATE TABLE IF NOT EXISTS)
            return s;
        });
    }
}
