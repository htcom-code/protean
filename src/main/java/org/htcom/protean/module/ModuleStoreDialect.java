/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import java.util.List;

/**
 * Per-vendor DDL strategy for the JDBC {@link ModuleStore} backend ({@link JdbcModuleStore}).
 *
 * <p>The store owns the table/column names and all CRUD SQL; a dialect only supplies the two column fragments that
 * differ by database vendor — the large-text type for {@code descriptor_json} and the auto-increment definition for
 * the {@code module_version.seq} primary key. This keeps a dialect's blast radius tiny (two type tokens plus additive
 * DDL) so a consumer dialect can never desync the schema shape the store's CRUD depends on.
 *
 * <p><b>Not to be confused with {@code db.DbDialect}</b>, which is a different axis: {@code DbDialect} provisions an
 * isolated DB scope (database/schema + user) per module, whereas this shapes the module-store's own schema DDL.
 *
 * <p><b>Extension (SPI)</b>: the built-in {@code h2}/{@code mysql}/{@code postgresql} dialects are batteries-included.
 * Register a {@code ModuleStoreDialect} bean to add a vendor (e.g. Oracle) or, by returning an existing {@link #id()},
 * to override a built-in. The active dialect is chosen by {@code protean.module-store.dialect} (override) or, when
 * unset, by detecting the database product name.
 *
 * <p>The {@code descriptor_json} column stores a whole {@link ModuleDescriptor} as JSON (including full module source),
 * so {@link #jsonTextColumnType()} MUST map to an unbounded large-character type — a bounded {@code VARCHAR} would
 * truncate. This is enforced at startup by a self-check in {@link JdbcModuleStore}.
 */
public interface ModuleStoreDialect {

    /** Registry key; also the value matched against {@code protean.module-store.dialect} and the detected vendor. */
    String id();

    /**
     * SQL type for the {@code descriptor_json} column — must be an unbounded large-character type
     * (H2 {@code CLOB}, MySQL {@code LONGTEXT}, PostgreSQL {@code TEXT}). A bounded {@code VARCHAR} truncates and is
     * rejected by the startup self-check.
     */
    String jsonTextColumnType();

    /**
     * Full column definition for the auto-incrementing {@code module_version.seq} primary key
     * (e.g. {@code "BIGINT AUTO_INCREMENT PRIMARY KEY"} for H2/MySQL, {@code "BIGSERIAL PRIMARY KEY"} for PostgreSQL,
     * or a plain type when a {@link #postTableDdl() trigger} does the numbering). It is placed immediately after the
     * {@code seq} column name.
     */
    String autoIncrementColumnDefinition();

    /**
     * DDL executed <b>before</b> the {@code module}/{@code module_version} tables are created (e.g. a sequence that a
     * column default references). The table/column names ({@code module}, {@code module_version}, {@code seq},
     * {@code descriptor_json}, {@code id}, {@code version}, {@code desired_state}, {@code module_id}, {@code saved_at})
     * are a stable contract you may reference. Statements MUST be idempotent — the store re-runs schema init on every
     * boot. Empty by default.
     */
    default List<String> preTableDdl() {
        return List.of();
    }

    /**
     * DDL executed <b>after</b> the tables are created (e.g. triggers or indexes referencing them). Same stable-name
     * contract and idempotency requirement as {@link #preTableDdl()}. Empty by default.
     */
    default List<String> postTableDdl() {
        return List.of();
    }
}
