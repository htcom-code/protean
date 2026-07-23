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
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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
@Profile("!worker")
@ConditionalOnProperty(name = "protean.module-store.backend", havingValue = "jdbc")
public class JdbcModuleStore implements ModuleStore {

    /** Size of the startup self-check payload — larger than any bounded VARCHAR (e.g. MySQL's 65,535-byte limit). */
    private static final int PROBE_PAYLOAD_CHARS = 128 * 1024;
    /** module_id used only by the startup self-check rows (rolled back + defensively deleted). */
    private static final String PROBE_MARKER = "__protean_dialect_probe__";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ProteanProperties props;
    private final List<ModuleStoreDialect> customDialects;

    public JdbcModuleStore(JdbcTemplate jdbc, ObjectMapper mapper, ProteanProperties props,
                           List<ModuleStoreDialect> customDialects) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.props = props;
        this.customDialects = customDialects;
    }

    @PostConstruct
    void initSchema() {
        ModuleStoreDialect dialect = resolveDialect();
        for (String ddl : dialect.preTableDdl()) {
            jdbc.execute(ddl);
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS module ("
                + " id VARCHAR(255) PRIMARY KEY,"
                + " version VARCHAR(255),"
                + " desired_state VARCHAR(32),"
                + " descriptor_json " + dialect.jsonTextColumnType()
                + ")");
        jdbc.execute("CREATE TABLE IF NOT EXISTS module_version ("
                + " seq " + dialect.autoIncrementColumnDefinition() + ","
                + " module_id VARCHAR(255),"
                + " version VARCHAR(255),"
                + " saved_at BIGINT,"
                + " desired_state VARCHAR(32),"
                + " descriptor_json " + dialect.jsonTextColumnType()
                + ")");
        for (String ddl : dialect.postTableDdl()) {
            jdbc.execute(ddl);
        }
        verifyDialect(dialect);
    }

    /** Resolves the active dialect from the {@code module-store.dialect} override, else the detected DB product name. */
    private ModuleStoreDialect resolveDialect() {
        String productName;
        try (Connection c = dataSource().getConnection()) {
            productName = c.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to read the database product name for module-store dialect"
                    + " detection", e);
        }
        return ModuleStoreDialects.resolve(ModuleStoreDialects.registry(customDialects),
                props.getModuleStore().getDialect(), productName);
    }

    /**
     * Startup self-check that the resolved dialect actually works, run against the real {@code module_version} table
     * inside a rolled-back transaction (with a defensive delete for non-transactional engines): a large value must
     * round-trip without truncation (so {@code descriptor_json} is a real large-text type, not a bounded VARCHAR), and
     * {@code seq} must auto-generate distinct, increasing keys — regardless of the mechanism (AUTO_INCREMENT, BIGSERIAL,
     * or a trigger/sequence supplied via {@code postTableDdl}).
     */
    private void verifyDialect(ModuleStoreDialect dialect) {
        String payload = "x".repeat(PROBE_PAYLOAD_CHARS);
        try (Connection c = dataSource().getConnection()) {
            boolean prevAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO module_version"
                        + "(module_id, version, saved_at, desired_state, descriptor_json) VALUES (?, ?, ?, ?, ?)")) {
                    for (int i = 0; i < 2; i++) {
                        ins.setString(1, PROBE_MARKER);
                        ins.setString(2, "probe");
                        ins.setLong(3, 0L);
                        ins.setString(4, "ACTIVE");
                        ins.setString(5, payload);
                        ins.executeUpdate();
                    }
                }
                List<Long> seqs = new ArrayList<>();
                try (PreparedStatement sel = c.prepareStatement("SELECT seq, descriptor_json FROM module_version"
                        + " WHERE module_id = ? ORDER BY seq")) {
                    sel.setString(1, PROBE_MARKER);
                    try (ResultSet rs = sel.executeQuery()) {
                        while (rs.next()) {
                            seqs.add(rs.getLong(1));
                            String back = rs.getString(2);
                            int len = back == null ? 0 : back.length();
                            if (len != payload.length()) {
                                throw new IllegalStateException("module-store dialect '" + dialect.id()
                                        + "': descriptor_json (" + dialect.jsonTextColumnType() + ") truncated a "
                                        + payload.length() + "-char value to " + len
                                        + " — use a large-text type (CLOB/TEXT/LONGTEXT).");
                            }
                        }
                    }
                }
                if (seqs.size() != 2 || seqs.get(0).equals(seqs.get(1))) {
                    throw new IllegalStateException("module-store dialect '" + dialect.id() + "': seq ("
                            + dialect.autoIncrementColumnDefinition()
                            + ") did not auto-generate distinct keys (got " + seqs + ").");
                }
            } catch (SQLException e) {
                throw new IllegalStateException("module-store dialect '" + dialect.id() + "' failed its startup"
                        + " self-check (descriptor_json=" + dialect.jsonTextColumnType() + ", seq="
                        + dialect.autoIncrementColumnDefinition() + "): the descriptor column must hold large text and"
                        + " seq must auto-increment.", e);
            } finally {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // best-effort; the defensive delete below covers non-transactional engines
                }
                try {
                    c.setAutoCommit(prevAutoCommit);
                } catch (SQLException ignored) {
                    // restoring auto-commit is best-effort
                }
            }
            // Non-transactional engines (e.g. MyISAM) ignore rollback, so remove any surviving probe rows explicitly.
            try (PreparedStatement del = c.prepareStatement("DELETE FROM module_version WHERE module_id = ?")) {
                del.setString(1, PROBE_MARKER);
                del.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("module-store dialect self-check could not run", e);
        }
    }

    private DataSource dataSource() {
        DataSource ds = jdbc.getDataSource();
        if (ds == null) {
            throw new IllegalStateException("JdbcTemplate has no DataSource for the module-store backend");
        }
        return ds;
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
