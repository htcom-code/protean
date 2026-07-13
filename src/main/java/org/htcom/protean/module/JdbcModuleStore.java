/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * JDBC-based ModuleStore — keeps descriptors and version history in a DB.
 * Compared to the filesystem backend, it offers better durability, queryability, and multi-instance support
 * (multiple mains sharing the same store).
 *
 * <p>Active only when {@code protean.module-store.backend=jdbc} (the default is {@link FileSystemModuleStore}).
 * Schema: {@code module} (current state) + {@code module_version} (append-only history).
 */
@Component
@ConditionalOnProperty(name = "protean.module-store.backend", havingValue = "jdbc")
public class JdbcModuleStore implements ModuleStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcModuleStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostConstruct
    void initSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS module (
                  id VARCHAR(255) PRIMARY KEY,
                  version VARCHAR(255),
                  desired_state VARCHAR(32),
                  descriptor_json CLOB
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS module_version (
                  seq BIGINT AUTO_INCREMENT PRIMARY KEY,
                  module_id VARCHAR(255),
                  version VARCHAR(255),
                  saved_at BIGINT,
                  desired_state VARCHAR(32),
                  descriptor_json CLOB
                )""");
    }

    @Override
    public void save(ModuleDescriptor descriptor) {
        String json = toJson(descriptor);
        String state = descriptor.desiredState().name();
        // 1) Append to version history (for audit/rollback)
        jdbc.update("INSERT INTO module_version(module_id, version, saved_at, desired_state, descriptor_json) "
                        + "VALUES (?, ?, ?, ?, ?)",
                descriptor.id(), descriptor.version(), System.currentTimeMillis(), state, json);
        // 2) Upsert the current state (update, then insert if absent; using this instead of MERGE for DB portability)
        int updated = jdbc.update("UPDATE module SET version = ?, desired_state = ?, descriptor_json = ? WHERE id = ?",
                descriptor.version(), state, json, descriptor.id());
        if (updated == 0) {
            jdbc.update("INSERT INTO module(id, version, desired_state, descriptor_json) VALUES (?, ?, ?, ?)",
                    descriptor.id(), descriptor.version(), state, json);
        }
    }

    @Override
    public Optional<ModuleDescriptor> load(String moduleId) {
        List<String> rows = jdbc.query("SELECT descriptor_json FROM module WHERE id = ?",
                (rs, n) -> rs.getString(1), moduleId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(fromJson(rows.get(0)));
    }

    @Override
    public List<ModuleDescriptor> listActive() {
        return jdbc.query("SELECT descriptor_json FROM module WHERE desired_state = ?",
                (rs, n) -> fromJson(rs.getString(1)),
                ModuleDescriptor.DesiredState.ACTIVE.name());
    }

    @Override
    public void remove(String moduleId) {
        jdbc.update("DELETE FROM module WHERE id = ?", moduleId);
        jdbc.update("DELETE FROM module_version WHERE module_id = ?", moduleId);
    }

    @Override
    public List<ModuleVersion> history(String moduleId) {
        return jdbc.query("SELECT seq, version, saved_at, desired_state FROM module_version "
                        + "WHERE module_id = ? ORDER BY seq DESC",
                (rs, n) -> new ModuleVersion(rs.getLong("seq"), rs.getString("version"),
                        rs.getLong("saved_at"),
                        ModuleDescriptor.DesiredState.valueOf(rs.getString("desired_state"))),
                moduleId);
    }

    @Override
    public Optional<ModuleDescriptor> loadVersion(String moduleId, String version) {
        // If the same version appears multiple times, take the one with the largest seq (= the most recent)
        List<String> rows = jdbc.query("SELECT descriptor_json FROM module_version "
                        + "WHERE module_id = ? AND version = ? ORDER BY seq DESC",
                (rs, n) -> rs.getString(1), moduleId, version);
        return rows.isEmpty() ? Optional.empty() : Optional.of(fromJson(rows.get(0)));
    }

    private String toJson(ModuleDescriptor d) {
        try {
            return mapper.writeValueAsString(d);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize descriptor: " + d.id(), e);
        }
    }

    private ModuleDescriptor fromJson(String json) {
        try {
            return mapper.readValue(json, ModuleDescriptor.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize descriptor", e);
        }
    }
}
