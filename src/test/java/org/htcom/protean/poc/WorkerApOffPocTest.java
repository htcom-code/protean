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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PoC combination 3 of 6 — <b>worker × auto-provision off</b>: the first combination with a runtime of its own, so the
 * first where a credential can be somewhere an onlooker reads.
 *
 * <p>Worker admin-auth is switched on deliberately. With it off there is no secret in play and "no credential on the
 * command line" would hold trivially — a criterion that cannot fail teaches nothing. With it on, the platform has a
 * real secret to hand over, and how it hands it over is exactly what this criterion judges.
 *
 * <p>The check reads the argument list the platform assembled rather than the live OS process table. Both describe the
 * same exposure, but only the first is observable identically on a developer machine and in a CI container — a check
 * built on the second passed locally and reported nothing in CI, which is how an unverified claim once looked green.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WorkerApOffPocTest extends AbstractPocSuite {

    @Autowired WorkerProcessIsolation workers;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path moduleStore = Files.createTempDirectory("protean-poc-worker-apoff-store");
        Path sharedLibStore = Files.createTempDirectory("protean-poc-worker-apoff-libs");
        registry.add("protean.module-store.dir", moduleStore::toString);
        registry.add("protean.module.shared-lib-store-dir", sharedLibStore::toString);
        registry.add("protean.isolation.mode", () -> "worker");
        registry.add("protean.worker.db.auto-provision", () -> "false");
        // Give the platform a secret to deliver, so the Secrets criterion has something it can actually fail on.
        registry.add("protean.worker.admin-auth.enabled", () -> "true");
    }

    @Override
    protected String combination() {
        return "worker × ap-off";
    }

    @Override
    protected String expectedMode() {
        return "worker";
    }

    @Override
    protected void verifySecrets() {
        install(library("v1"));
        install(consumer());

        List<String> command = workers.launchCommand(CONSUMER);
        assertFalse(command.isEmpty(), "no launch command recorded — the criterion would pass without checking anything");
        assertTrue(command.stream().anyMatch(arg -> arg.contains("--spring.profiles.active=worker")),
                "the recorded command is not a worker launch: " + command);

        List<String> exposed = command.stream()
                .filter(arg -> arg.contains("secret=") || arg.contains("password="))
                .toList();
        assertTrue(exposed.isEmpty(),
                "a credential is on the worker's command line, where any local user can read it via ps: " + exposed);
    }
}
