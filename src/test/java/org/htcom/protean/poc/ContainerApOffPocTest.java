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
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PoC combination 5 of 6 — <b>container × auto-provision off</b>.
 *
 * <p>The Secrets criterion changes instrument here. A worker's exposure is its command line, which the OS forgets when
 * the process ends; a container's is its recorded metadata, which {@code docker inspect} keeps for the container's whole
 * life. Same secret, longer reach — so this combination reads the metadata rather than an argument list.
 *
 * <p>Two prerequisites: a Docker daemon and the host boot jar the container mounts. Missing either leaves this
 * combination's criteria SKIPPED, which the verdict treats as unverified — never as satisfied.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ContainerApOffPocTest extends AbstractPocSuite {

    @Autowired ContainerWorkerIsolation containers;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path moduleStore = Files.createTempDirectory("protean-poc-container-apoff-store");
        Path sharedLibStore = Files.createTempDirectory("protean-poc-container-apoff-libs");
        registry.add("protean.module-store.dir", moduleStore::toString);
        registry.add("protean.module.shared-lib-store-dir", sharedLibStore::toString);
        registry.add("protean.isolation.mode", () -> "container");
        registry.add("protean.worker.db.auto-provision", () -> "false");
        // Give the platform a secret to deliver, so the criterion has something it can actually fail on.
        registry.add("protean.worker.admin-auth.enabled", () -> "true");
    }

    @BeforeEach
    void requiresBootJar() {
        // The container mounts the exploded host boot jar; without it every criterion here would fail for the wrong
        // reason. Aborting instead records SKIPPED — unverified, which is the truthful state.
        assumeTrue(bootJarExists(), "no bootJar ('gradle bootJar') — container combination unverified");
    }

    @Override
    protected String combination() {
        return "container × ap-off";
    }

    @Override
    protected String expectedMode() {
        return "container";
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
                "a credential is in the container's recorded metadata, where docker inspect exposes it for the "
                        + "container's whole life: " + metadata);
    }
}
