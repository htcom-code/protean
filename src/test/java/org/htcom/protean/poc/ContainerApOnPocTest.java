/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.poc;

import org.htcom.protean.isolation.ContainerWorkerIsolation;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PoC combination 6 of 6 — <b>container × auto-provision on</b>: the strongest isolation the platform offers crossed
 * with per-tenant provisioning, and therefore the combination where a credential leak costs the most.
 *
 * <p>Everything the other five establish separately meets here: an OS-isolated runtime, a provisioned database whose
 * password is what separates one tenant's data from another's, and metadata that keeps whatever it was given readable
 * for the container's whole life. The database host also has to be reachable from inside a container, which is why the
 * provisioning credentials point at the host gateway rather than at localhost.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ContainerApOnPocTest extends AbstractPocSuite {

    private static final String SCOPE = "poctenantc";

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired ContainerWorkerIsolation containers;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path moduleStore = Files.createTempDirectory("protean-poc-container-apon-store");
        Path sharedLibStore = Files.createTempDirectory("protean-poc-container-apon-libs");
        registry.add("protean.module-store.dir", moduleStore::toString);
        registry.add("protean.module.shared-lib-store-dir", sharedLibStore::toString);
        registry.add("protean.isolation.mode", () -> "container");
        registry.add("protean.worker.db.auto-provision", () -> "true");
        registry.add("protean.worker.db.dialect", () -> "postgresql");
        registry.add("protean.worker.db.scopes", () -> SCOPE);
        registry.add("protean.worker.db.admin-url", pg::getJdbcUrl);
        registry.add("protean.worker.db.admin-username", pg::getUsername);
        registry.add("protean.worker.db.admin-password", pg::getPassword);
        registry.add("protean.worker.admin-auth.enabled", () -> "true");
    }

    @BeforeEach
    void requiresBootJar() {
        assumeTrue(bootJarExists(), "no bootJar ('gradle bootJar') — container combination unverified");
    }

    @Override
    protected String combination() {
        return "container × ap-on";
    }

    @Override
    protected String expectedMode() {
        return "container";
    }

    @Override
    protected String scope() {
        return SCOPE;
    }

    @Override
    protected void verifySecrets() {
        install(library("v1"));
        install(consumer(scope()));

        String metadata = containers.inspectArgsAndEnv(CONSUMER);
        assertFalse(metadata.isBlank() || "-1".equals(metadata),
                "no container metadata readable — the criterion would pass without checking anything");
        assertTrue(metadata.contains("--spring.config.import=") || metadata.contains("secret=")
                        || metadata.contains("password="),
                "neither a secrets handover nor a credential is present — no secret was in play, so this criterion "
                        + "verified nothing: " + metadata);
        assertFalse(metadata.contains("secret=") || metadata.contains("password="),
                "a credential is in the container's recorded metadata — under auto-provision that includes the scope's "
                        + "database password, and docker inspect exposes it for the container's whole life: " + metadata);
    }
}
