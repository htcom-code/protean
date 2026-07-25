/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.poc;

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
import java.util.EnumSet;
import java.util.Set;

/**
 * PoC combination 2 of 6 — <b>in-process × auto-provision on</b>.
 *
 * <p>The combination exists to hold a documented asymmetry honest: with provisioning enabled, a module that declares a
 * scope and lands in-process is <b>rejected</b>, while one that declares none is accepted and runs on the main
 * datasource. So the same library can be serving an in-process consumer on the host database and a worker consumer on a
 * provisioned one, and this combination is the half of that picture where no scope is ever bound. The fixtures
 * deliberately declare no scope; if a future change starts requiring one here, `route` fails on the mode assertion.
 *
 * <p>PostgreSQL is fixed as the provisioning vendor for the gate. Vendor differences are covered by the dedicated
 * dialect tests — widening the gate's axes to vendors would double the matrix without testing a different behavior.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class InProcessApOnPocTest extends AbstractPocSuite {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path moduleStore = Files.createTempDirectory("protean-poc-inprocess-apon-store");
        Path sharedLibStore = Files.createTempDirectory("protean-poc-inprocess-apon-libs");
        registry.add("protean.module-store.dir", moduleStore::toString);
        registry.add("protean.module.shared-lib-store-dir", sharedLibStore::toString);
        registry.add("protean.isolation.mode", () -> "in-process");
        registry.add("protean.worker.db.auto-provision", () -> "true");
        registry.add("protean.worker.db.dialect", () -> "postgresql");
        registry.add("protean.worker.db.scopes", () -> "poc-tenant");
        registry.add("protean.worker.db.admin-url", pg::getJdbcUrl);
        registry.add("protean.worker.db.admin-username", pg::getUsername);
        registry.add("protean.worker.db.admin-password", pg::getPassword);
    }

    @Override
    protected String combination() {
        return "in-process × ap-on";
    }

    @Override
    protected String expectedMode() {
        return "in-process";
    }

    @Override
    protected Set<PocCriterion> notApplicable() {
        return EnumSet.of(PocCriterion.SECRETS);
    }

    @Override
    protected String notApplicableReason(PocCriterion criterion) {
        return criterion == PocCriterion.SECRETS
                ? "no spawned runtime — provisioning is on but nothing here binds a scope"
                : super.notApplicableReason(criterion);
    }
}
