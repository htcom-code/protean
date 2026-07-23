/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

/** Built-in {@link ModuleStoreDialect} for H2 (the zero-config default backend). Non-final so consumers may subclass. */
public class H2StoreDialect implements ModuleStoreDialect {

    @Override
    public String id() {
        return "h2";
    }

    @Override
    public String jsonTextColumnType() {
        return "CLOB";
    }

    @Override
    public String autoIncrementColumnDefinition() {
        return "BIGINT AUTO_INCREMENT PRIMARY KEY";
    }
}
