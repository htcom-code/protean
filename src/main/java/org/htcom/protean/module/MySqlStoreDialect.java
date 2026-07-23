/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

/**
 * Built-in {@link ModuleStoreDialect} for MySQL. {@code LONGTEXT} (4 GB) is used rather than {@code TEXT} (64 KB) since
 * a descriptor carrying full module source can exceed 64 KB. Non-final so consumers may subclass (e.g. to use a
 * {@code JSON} column instead).
 */
public class MySqlStoreDialect implements ModuleStoreDialect {

    @Override
    public String id() {
        return "mysql";
    }

    @Override
    public String jsonTextColumnType() {
        return "LONGTEXT";
    }

    @Override
    public String autoIncrementColumnDefinition() {
        return "BIGINT AUTO_INCREMENT PRIMARY KEY";
    }
}
