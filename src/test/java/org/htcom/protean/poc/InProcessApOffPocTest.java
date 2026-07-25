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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

/**
 * PoC combination 1 of 6 — <b>in-process × auto-provision off</b>: the baseline every other combination is compared
 * against, and the only one with no separate runtime and no provisioned database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class InProcessApOffPocTest extends AbstractPocSuite {

    /**
     * A run must start from nothing. Reusing a fixed directory let a previous run's modules be reconciled back in and
     * its uploaded jar coordinates survive — and since a jar's entry timestamps differ per build, re-pushing the same
     * version was correctly rejected as a different-bytes conflict (409). Fresh directories per run remove the whole
     * class of bleed instead of relying on teardown having succeeded.
     */
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path moduleStore = Files.createTempDirectory("protean-poc-inprocess-apoff-store");
        Path sharedLibStore = Files.createTempDirectory("protean-poc-inprocess-apoff-libs");
        registry.add("protean.module-store.dir", moduleStore::toString);
        registry.add("protean.isolation.mode", () -> "in-process");
        registry.add("protean.worker.db.auto-provision", () -> "false");
        // The put-jar surface needs somewhere to keep uploaded generations (S1/S5 of the documented scenario).
        registry.add("protean.module.shared-lib-store-dir", sharedLibStore::toString);
    }

    @Override
    protected String combination() {
        return "in-process × ap-off";
    }

    @Override
    protected String expectedMode() {
        return "in-process";
    }

    @Override
    protected Set<PocCriterion> notApplicable() {
        // Nothing is spawned, so there is no argument list and no container metadata a credential could sit in. This is
        // settled rather than unverified — which is why it is N/A and not SKIPPED.
        return EnumSet.of(PocCriterion.SECRETS);
    }

    @Override
    protected String notApplicableReason(PocCriterion criterion) {
        return criterion == PocCriterion.SECRETS
                ? "no spawned runtime — no argv or container metadata to leak into"
                : super.notApplicableReason(criterion);
    }
}
