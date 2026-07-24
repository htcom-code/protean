/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.db;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes the {@link DbScopeProvisioner} bean only when {@code protean.worker.db.auto-provision=true}.
 * Configures the provisioner using the dialect and admin credentials from configuration.
 *
 * <p><b>Dialect extension point (SPI)</b>: the built-in {@link MySqlDialect}/{@link PostgresDialect} are provided by
 * default, but if a consumer registers a {@link DbDialect} bean it joins the registry (overriding a built-in when the
 * {@link DbDialect#id()} matches). This lets you add arbitrary vendors such as Oracle/SQL Server/MariaDB without
 * forking the library source.
 */
@Configuration
@Profile("!worker")
@ConditionalOnProperty(name = "protean.worker.db.auto-provision", havingValue = "true")
public class DbProvisioningConfig {

    /**
     * @param customDialects {@link DbDialect} beans registered by the consumer (empty list if none). The built-in
     *                       dialects are not {@code @Component}s but created directly here, so they are not in this list.
     */
    @Bean
    public DbScopeProvisioner dbScopeProvisioner(ProteanProperties props, List<DbDialect> customDialects) {
        ProteanProperties.Db db = props.getWorker().getDb();

        // batteries-included built-in dialects → overridable/extendable by consumer beans (extension point).
        Map<String, DbDialect> registry = new LinkedHashMap<>();
        for (DbDialect builtin : List.of(new MySqlDialect(), new PostgresDialect())) {
            registry.put(builtin.id(), builtin);
        }
        for (DbDialect custom : customDialects) {
            registry.put(custom.id(), custom);
        }

        String requested = db.getDialect() == null ? "" : db.getDialect().toLowerCase();
        if (requested.equals("postgres")) {
            requested = "postgresql";  // keep the alias (compatible with the previous switch)
        }
        DbDialect d = registry.get(requested);
        if (d == null) {
            throw new IllegalStateException("unsupported db dialect: " + db.getDialect()
                    + " (available: " + registry.keySet() + " — register a DbDialect bean for a custom one)");
        }
        // Admin creds are read live: DbScopeProvisioner rebuilds (and validates) its admin connection when they change,
        // so an admin-credential rotation applies to the next provision without a restart. The dialect is still captured
        // here (a live dialect swap is a separate, riskier follow-up — existing scopes were created under the old
        // dialect's DDL/URL shape).
        return new DbScopeProvisioner(d,
                () -> new DbScopeProvisioner.AdminCreds(
                        props.getWorker().getDb().getAdminUrl(),
                        props.getWorker().getDb().getAdminUsername(),
                        props.getWorker().getDb().getAdminPassword()));
    }
}
