/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

/**
 * Built-in {@link ModuleStoreDialect} for PostgreSQL. {@code TEXT} is the unbounded character type, and
 * {@code BIGSERIAL} provides the auto-incrementing key (PostgreSQL has neither {@code CLOB} nor {@code AUTO_INCREMENT}).
 * Non-final so consumers may subclass.
 */
public class PostgresStoreDialect implements ModuleStoreDialect {

    @Override
    public String id() {
        return "postgresql";
    }

    @Override
    public String jsonTextColumnType() {
        return "TEXT";
    }

    @Override
    public String autoIncrementColumnDefinition() {
        return "BIGSERIAL PRIMARY KEY";
    }
}
