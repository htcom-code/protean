/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Builds the {@link ModuleStoreDialect} registry and resolves the active dialect for {@link JdbcModuleStore}.
 *
 * <p>Resolution order: an explicit {@code protean.module-store.dialect} override wins; otherwise the vendor is detected
 * from the JDBC database product name. Detection is <b>strict</b> — only the exactly known vendors (h2/mysql/postgresql)
 * map; anything else fails fast so the consumer is guided to set the override or register a dialect bean, rather than
 * silently defaulting to H2 DDL and breaking later.
 */
final class ModuleStoreDialects {

    private ModuleStoreDialects() {
    }

    /** Built-in dialects keyed by {@link ModuleStoreDialect#id()}, overlaid with consumer beans (same id overrides). */
    static Map<String, ModuleStoreDialect> registry(List<ModuleStoreDialect> customDialects) {
        Map<String, ModuleStoreDialect> registry = new LinkedHashMap<>();
        for (ModuleStoreDialect builtin : List.of(new H2StoreDialect(), new MySqlStoreDialect(), new PostgresStoreDialect())) {
            registry.put(builtin.id(), builtin);
        }
        if (customDialects != null) {
            for (ModuleStoreDialect custom : customDialects) {
                registry.put(custom.id(), custom);
            }
        }
        return registry;
    }

    /** Maps a JDBC {@code getDatabaseProductName()} to a built-in dialect id, or {@code null} if not exactly known. */
    static String detect(String productName) {
        if (productName == null) {
            return null;
        }
        String p = productName.toLowerCase(Locale.ROOT);
        if (p.contains("h2")) {
            return "h2";
        }
        if (p.contains("postgresql")) {
            return "postgresql";
        }
        if (p.contains("mysql")) {
            return "mysql";
        }
        return null;
    }

    /**
     * Selects the dialect from {@code configuredId} (override) or, when blank, from the detected {@code productName}.
     * Throws an actionable {@link IllegalStateException} when no dialect matches or when the chosen dialect omits a
     * required column fragment.
     */
    static ModuleStoreDialect resolve(Map<String, ModuleStoreDialect> registry, String configuredId, String productName) {
        boolean overridden = configuredId != null && !configuredId.isBlank();
        String id;
        if (overridden) {
            id = configuredId.trim().toLowerCase(Locale.ROOT);
            if (id.equals("postgres")) {
                id = "postgresql";  // convenience alias, mirrors provisioning DbDialect
            }
        } else {
            id = detect(productName);
        }

        ModuleStoreDialect dialect = id == null ? null : registry.get(id);
        if (dialect == null) {
            String origin = overridden
                    ? "requested via protean.module-store.dialect='" + configuredId + "'"
                    : "auto-detected from database product name '" + productName + "'";
            throw new IllegalStateException("No ModuleStoreDialect for module-store backend (" + origin
                    + "). Available: " + new TreeSet<>(registry.keySet())
                    + ". Set protean.module-store.dialect to one of these, or register a ModuleStoreDialect bean for a"
                    + " custom vendor — see docs/guide/10-spi-extension.");
        }
        if (isBlank(dialect.jsonTextColumnType()) || isBlank(dialect.autoIncrementColumnDefinition())) {
            throw new IllegalStateException("ModuleStoreDialect '" + dialect.id()
                    + "' must return non-blank jsonTextColumnType() and autoIncrementColumnDefinition().");
        }
        return dialect;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
