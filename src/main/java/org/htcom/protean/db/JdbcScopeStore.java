/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.db;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * JDBC-backed {@link ScopeStore} — a {@code module_scope} table in the application's {@code JdbcTemplate} (the same
 * DataSource as {@code JdbcModuleStore}). Active when {@code module-store.backend=jdbc}. All columns are short
 * {@code VARCHAR}, so the DDL is vendor-portable (no large-text/auto-increment types — unlike the descriptor store).
 */
@Component
@Profile("!worker")
@ConditionalOnProperty(name = "protean.module-store.backend", havingValue = "jdbc")
public class JdbcScopeStore implements ScopeStore {

    private final JdbcTemplate jdbc;

    public JdbcScopeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS module_scope ("
                + " name VARCHAR(255) PRIMARY KEY,"
                + " state VARCHAR(32),"
                + " dialect_id VARCHAR(64)"
                + ")");
    }

    @Override
    public void save(ScopeRecord scope) {
        int updated = jdbc.update("UPDATE module_scope SET state = ?, dialect_id = ? WHERE name = ?",
                scope.state().name(), scope.dialectId(), scope.name());
        if (updated == 0) {
            jdbc.update("INSERT INTO module_scope(name, state, dialect_id) VALUES (?, ?, ?)",
                    scope.name(), scope.state().name(), scope.dialectId());
        }
    }

    @Override
    public Optional<ScopeRecord> load(String name) {
        List<ScopeRecord> rows = jdbc.query("SELECT name, state, dialect_id FROM module_scope WHERE name = ?",
                (rs, n) -> map(rs.getString("name"), rs.getString("state"), rs.getString("dialect_id")), name);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<ScopeRecord> list() {
        return jdbc.query("SELECT name, state, dialect_id FROM module_scope",
                (rs, n) -> map(rs.getString("name"), rs.getString("state"), rs.getString("dialect_id")));
    }

    @Override
    public void remove(String name) {
        jdbc.update("DELETE FROM module_scope WHERE name = ?", name);
    }

    private static ScopeRecord map(String name, String state, String dialectId) {
        return new ScopeRecord(name, ScopeRecord.State.valueOf(state), dialectId);
    }
}
