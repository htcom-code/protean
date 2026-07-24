/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.db.ScopeAdminService;
import org.htcom.protean.db.ScopeManager;
import org.htcom.protean.isolation.ContainerWorkerIsolation;
import org.htcom.protean.isolation.WorkerProcessIsolation;
import org.htcom.protean.module.ModuleDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Non-Docker coverage of the scope-admin guard + deploy-time scope-state rejection (the policy surface that was
 * previously only exercisable via Testcontainers). Auto-provision is on with a bogus admin URL that is never
 * connected: every assertion here rejects BEFORE any DB/worker/container work, so no Docker or live DB is needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "protean.worker.db.auto-provision=true",
        "protean.worker.db.dialect=postgresql",
        "protean.worker.db.admin-url=jdbc:postgresql://unused-host:5432/db",
        "protean.worker.db.admin-username=u",
        "protean.worker.db.admin-password=p",
        "protean.worker.db.scopes=alpha,beta,gamma"
})
class ScopeAdminServiceTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-scope-admin-svc-test");

    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired ScopeAdminService scopes;
    @Autowired ScopeManager scopeManager;
    @Autowired WorkerProcessIsolation worker;
    @Autowired ContainerWorkerIsolation container;
    @Autowired ProteanProperties props;

    private static ModuleDescriptor mod(String id, String scope, String mode) {
        String fqcn = "runtime.sa.C";
        return ModuleDescriptor.builder()
                .id(id).version("1.0.0")
                .controllerFqcn(fqcn).componentFqcns(List.of(fqcn))
                .sources(Map.of(fqcn, "package runtime.sa; public class C {}"))
                .isolationMode(mode).scope(scope)
                .build();
    }

    @Test
    void destroy_is_refused_when_allow_destroy_is_off() {
        props.getWorker().getDb().setAllowDestroy(false);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> scopes.destroy("alpha", "alpha"));
        assertTrue(ex.getMessage().contains("allow-destroy"),
                "refusal must name the allow-destroy guard: " + ex.getMessage());
    }

    @Test
    void destroy_is_refused_when_confirmation_does_not_match() {
        props.getWorker().getDb().setAllowDestroy(true);   // pass the allow-destroy gate so the confirm check is reached
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> scopes.destroy("alpha", "WRONG"));
            assertTrue(ex.getMessage().contains("confirmation"),
                    "refusal must name the confirmation mismatch: " + ex.getMessage());
        } finally {
            props.getWorker().getDb().setAllowDestroy(false);
        }
    }

    @Test
    void worker_deploy_rejects_an_unknown_scope() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> worker.deploy(mod("sa-w-ghost", "ghost", "worker")));
        assertTrue(ex.getMessage().contains("unknown scope"), ex.getMessage());
    }

    @Test
    void worker_deploy_rejects_a_closed_scope() {
        scopeManager.close("beta", "postgresql");
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> worker.deploy(mod("sa-w-beta", "beta", "worker")));
            assertTrue(ex.getMessage().contains("not ACTIVE"), ex.getMessage());
        } finally {
            scopeManager.open("beta", "postgresql");   // restore for other tests sharing the context
        }
    }

    @Test
    void container_deploy_rejects_an_unknown_scope() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> container.deploy(mod("sa-c-ghost", "ghost", "container")));
        assertTrue(ex.getMessage().contains("unknown scope"), ex.getMessage());
    }

    @Test
    void container_deploy_rejects_a_closed_scope() {
        scopeManager.close("gamma", "postgresql");
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> container.deploy(mod("sa-c-gamma", "gamma", "container")));
            assertTrue(ex.getMessage().contains("not ACTIVE"), ex.getMessage());
        } finally {
            scopeManager.open("gamma", "postgresql");
        }
    }
}
