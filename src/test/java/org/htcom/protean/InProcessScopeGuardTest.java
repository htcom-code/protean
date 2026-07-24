/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.db.DbScopeProvisioner;
import org.htcom.protean.db.PostgresDialect;
import org.htcom.protean.isolation.InProcessIsolation;
import org.htcom.protean.module.ModuleDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the ap=true + in-process trap: a module that declares a DB {@code scope} but is routed to in-process
 * isolation (which cannot bind to a per-scope datasource) must be rejected under auto-provision. The check runs before
 * any compile/deploy work, so a null compiler/container is never reached.
 */
class InProcessScopeGuardTest {

    /** Minimal ObjectProvider returning a fixed instance (only getObject is exercised via the getIfAvailable default). */
    private static ObjectProvider<DbScopeProvisioner> provider(DbScopeProvisioner p) {
        return new ObjectProvider<>() {
            @Override
            public DbScopeProvisioner getObject() {
                return p;
            }

            @Override
            public DbScopeProvisioner getObject(Object... args) {
                return p;
            }
        };
    }

    private static ModuleDescriptor scopedInProcessModule() {
        return ModuleDescriptor.builder()
                .id("m").version("1.0.0")
                .controllerFqcn("X").componentFqcns(List.of("X")).sources(Map.of("X", "class X {}"))
                .isolationMode("in-process").scope("tenant-x")
                .build();
    }

    @Test
    void rejects_a_scoped_module_routed_to_in_process_under_auto_provision() {
        // auto-provision on (provisioner present); admin creds are bogus — the reject fires before any DB call.
        DbScopeProvisioner prov = new DbScopeProvisioner(
                new PostgresDialect(), "jdbc:postgresql://unused/db", "u", "p");
        InProcessIsolation iso = new InProcessIsolation(null, null, provider(prov), new MockEnvironment());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> iso.deploy(scopedInProcessModule()));
        assertTrue(ex.getMessage().contains("in-process"), "message must name the in-process routing");
        assertTrue(ex.getMessage().contains("tenant-x"), "message must name the declared scope");
    }
}
