/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.poc;

import org.htcom.protean.isolation.WorkerProcessIsolation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PoC combination 4 of 6 — <b>worker × auto-provision on</b>: the combination the scope model is actually sold on, and
 * the first where provisioning really happens. Consumers declare a scope, so the platform creates that scope's database
 * area and hands the worker credentials for it.
 *
 * <p>That makes the Secrets criterion carry its full weight here: two different secrets are in play — the worker
 * admin-auth token and the provisioned database password — and the second is what makes a tenant's data that tenant's.
 * A leak of it is not a hardening nicety; it undoes the isolation the mode exists to provide.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class WorkerApOnPocTest extends AbstractPocSuite {

    private static final String SCOPE = "poctenant";

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired WorkerProcessIsolation workers;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path moduleStore = Files.createTempDirectory("protean-poc-worker-apon-store");
        Path sharedLibStore = Files.createTempDirectory("protean-poc-worker-apon-libs");
        registry.add("protean.module-store.dir", moduleStore::toString);
        registry.add("protean.module.shared-lib-store-dir", sharedLibStore::toString);
        registry.add("protean.isolation.mode", () -> "worker");
        registry.add("protean.worker.db.auto-provision", () -> "true");
        registry.add("protean.worker.db.dialect", () -> "postgresql");
        registry.add("protean.worker.db.scopes", () -> SCOPE);
        registry.add("protean.worker.db.admin-url", pg::getJdbcUrl);
        registry.add("protean.worker.db.admin-username", pg::getUsername);
        registry.add("protean.worker.db.admin-password", pg::getPassword);
        // A second secret alongside the provisioned password, so the criterion covers both kinds at once.
        registry.add("protean.worker.admin-auth.enabled", () -> "true");
    }

    @Override
    protected String combination() {
        return "worker × ap-on";
    }

    @Override
    protected String expectedMode() {
        return "worker";
    }

    /** Under auto-provision a worker module must bind a known scope; the library stays in-process and declares none. */
    @Override
    protected String scope() {
        return SCOPE;
    }

    @Override
    protected void verifySecrets() {
        install(library("v1"));
        install(consumer(scope()));

        List<String> command = workers.launchCommand(CONSUMER);
        assertFalse(command.isEmpty(), "no launch command recorded — the criterion would pass without checking anything");
        assertTrue(command.stream().anyMatch(arg -> arg.startsWith("--spring.config.import=")
                        || arg.contains("secret=") || arg.contains("password=")),
                "neither a secrets handover nor a credential is present — provisioning did not put a secret in play, "
                        + "so this criterion verified nothing: " + command);

        List<String> exposed = command.stream()
                .filter(arg -> arg.contains("secret=") || arg.contains("password="))
                .toList();
        assertTrue(exposed.isEmpty(),
                "a credential is on the worker's command line, where any local user can read it via ps — under "
                        + "auto-provision this includes the scope's database password, which is what keeps one tenant's "
                        + "data out of another's reach: " + exposed);
    }
}
