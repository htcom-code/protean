/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.poc;

import org.htcom.protean.isolation.IsolationStrategy;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The criteria half of the PoC gate: one test per criterion, written once and run by every combination.
 *
 * <p>A subclass supplies a combination — an isolation mode crossed with auto-provision on or off — as Spring
 * properties, and declares which criteria do not exist for it. Everything else is shared, so a combination cannot
 * quietly verify less than its siblings: what it did or did not establish lands in {@link PocReport} either way.
 *
 * <p>Each criterion reports exactly one outcome. A body that throws records FAILED and rethrows (so the build fails
 * too); an aborted assumption records SKIPPED, which the report treats as unverified rather than satisfied.
 *
 * <p>Restart is simulated the way the existing reconcile tests do it — undeploy behind the platform's back, then
 * reconcile — because a JUnit context cannot restart its own JVM. That covers the recovery path (does the module come
 * back without a redeploy) but not a真 cold boot; the runbook remains the place a real restart is exercised.
 */
@Tag("poc")
public abstract class AbstractPocSuite {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ModulePlatform platform;
    @Autowired protected List<IsolationStrategy> strategies;

    /** Combination label for the verdict table, e.g. {@code in-process × ap-off}. */
    protected abstract String combination();

    /** The isolation mode modules of this combination land in — asserted, so a mis-wired combination cannot pass. */
    protected abstract String expectedMode();

    /** Criteria that do not exist for this combination (settled, not unverified). Default: none. */
    protected Set<PocCriterion> notApplicable() {
        return EnumSet.noneOf(PocCriterion.class);
    }

    /** Reason shown next to N/A in the report. */
    protected String notApplicableReason(PocCriterion criterion) {
        return "not applicable to this combination";
    }

    // --- criteria ---------------------------------------------------------------------------------------------

    @Test
    void route() {
        run(PocCriterion.ROUTE, () -> {
            install(library("v1"));
            install(consumer());
            assertEquals(expectedMode(), platform.effectiveMode(platform.find(CONSUMER).orElseThrow()),
                    "the combination did not place the module in the mode it declares");
            mockMvc.perform(get("/poc/label")).andExpect(status().isOk()).andExpect(content().string("c:v1"));
        });
    }

    @Test
    void propagation() {
        run(PocCriterion.PROPAGATION, () -> {
            install(library("v1"));
            install(consumer());
            install(strictConsumer());
            mockMvc.perform(get("/poc/label")).andExpect(content().string("c:v1"));
            mockMvc.perform(get("/poc/strict")).andExpect(content().string("s:v1"));

            platform.update(library("v2"));

            // The version-stable consumer moves; the one whose gate pins the old value stays behind (Plan B, sticky).
            mockMvc.perform(get("/poc/label")).andExpect(content().string("c:v2"));
            mockMvc.perform(get("/poc/strict")).andExpect(content().string("s:v1"));
        });
    }

    @Test
    void reporting() {
        run(PocCriterion.REPORTING, () -> {
            install(library("v1"));
            install(consumer());
            List<Long> before = platform.boundLibraryGenerations(CONSUMER);
            assertFalse(before.isEmpty(), "a consumer of a library must report the generation it is bound to");
            String host = platform.runtimeId(CONSUMER);
            assertTrue(host != null && !host.isBlank(), "a deployed module must report its hosting runtime");

            platform.update(library("v2"));
            mockMvc.perform(get("/poc/label")).andExpect(content().string("c:v2"));   // it really moved

            List<Long> after = platform.boundLibraryGenerations(CONSUMER);
            assertTrue(max(after) > max(before),
                    "the reported generation must follow the rebind: " + before + " → " + after);
            assertEquals(host, platform.runtimeId(CONSUMER), "the rebind must not silently re-place the module");
        });
    }

    @Test
    void secrets() {
        run(PocCriterion.SECRETS, this::verifySecrets);
    }

    /**
     * How this combination establishes that no credential is observable. Only combinations that spawn a separate
     * runtime have anything to check, and each observes it where an onlooker would actually look — a container's
     * recorded metadata, or the argument list the platform assembled for a worker. Reading the live OS process table is
     * deliberately not used: it yields a value locally and nothing in a CI container, which is how a green run once
     * hid an unverified claim.
     */
    protected void verifySecrets() {
        throw new TestAbortedException("no separate runtime in this combination — see notApplicable()");
    }

    @Test
    void reconcile() {
        run(PocCriterion.RECONCILE, () -> {
            install(library("v1"));
            install(consumer());
            mockMvc.perform(get("/poc/label")).andExpect(status().isOk());

            // Simulated restart: tear the deployments down without touching the store, then let reconcile restore them.
            for (IsolationStrategy strategy : strategies) {
                strategy.undeploy(CONSUMER);
                strategy.undeploy(LIB);
            }
            mockMvc.perform(get("/poc/label")).andExpect(status().isNotFound());
            platform.reconcile();

            mockMvc.perform(get("/poc/label")).andExpect(status().isOk()).andExpect(content().string("c:v1"));
        });
    }

    /**
     * Reproduces the documented scenario end to end: push a shared-lib jar, publish a library, deploy a consumer that
     * links <b>both</b>, then swap each of them live. This is the criterion the project keeps breaking — every defect
     * found in the last round was invisible to unit tests but visible here — so it walks the same steps as the runbook
     * (S1-S5) with the same expectations, and the values are asserted exactly rather than by shape.
     */
    @Test
    void legacyScenario() {
        run(PocCriterion.LEGACY_SCENARIO, () -> {
            // S1 — push a jar: apply(100, 20) = 80. The consumer below links it from the published generation only.
            long gen1 = pushSharedLib("acme-pricing", "1.0.0", discountJar(0));
            assertTrue(gen1 >= 1, "pushing a jar must publish a generation, got " + gen1);

            // S2/S3 — library + a consumer using BOTH tiers; the composite proves each half resolved.
            install(library("v1"));
            install(bothConsumer());
            mockMvc.perform(get("/poc/both")).andExpect(status().isOk())
                    .andExpect(content().string("quote=80 label=v1"));
            // Generation ids are a JVM-wide counter, so their absolute value depends on what ran before. Only movement
            // is meaningful — asserting a literal number here made this criterion fail for the wrong reason once.
            List<Long> linked = platform.boundLibraryGenerations(BOTH);
            assertFalse(linked.isEmpty(), "the consumer must report the library generation it linked");

            // S4 — swap the library live: the label half moves, the consumer's own version does not.
            platform.update(library("v2"));
            mockMvc.perform(get("/poc/both")).andExpect(content().string("quote=80 label=v2"));
            assertEquals("1.0.0", platform.find(BOTH).orElseThrow().version(),
                    "eager propagation must not bump the consumer's own version");
            assertTrue(max(platform.boundLibraryGenerations(BOTH)) > max(linked),
                    "the reported library generation must follow the swap: " + linked
                            + " → " + platform.boundLibraryGenerations(BOTH));

            // S5 — swap the jar live (different bytes: apply returns one less): the quote half moves, still no redeploy.
            long gen2 = pushSharedLib("acme-pricing", "2.0.0", discountJar(1));
            assertTrue(gen2 > gen1, "a different-bytes push must publish a new generation: " + gen1 + " → " + gen2);
            mockMvc.perform(get("/poc/both")).andExpect(content().string("quote=79 label=v2"));
        });
    }

    /** Pushes a jar through the put-jar surface (the transport an operator uses) and returns the new generation id. */
    protected long pushSharedLib(String name, String version, byte[] jar) throws Exception {
        var file = new org.springframework.mock.web.MockMultipartFile("file", name + ".jar",
                "application/java-archive", jar);
        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/platform/shared-libs").file(file)
                        .param("name", name).param("version", version))
                .andExpect(status().isCreated())   // the surface answers 201 for a published generation
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("generation").asLong();
    }

    /**
     * A jar carrying {@code ext.pricing.Discount}, which is not on the test classpath — so a consumer that compiles
     * against it proves the published generation is really on its parent tier. {@code less} shifts the result so a
     * second push is a genuine behavior change rather than the same bytes under a new version.
     */
    protected static byte[] discountJar(int less) throws Exception {
        Path base = Files.createTempDirectory("protean-poc-pricing");
        Path src = base.resolve("Discount.java");
        Files.writeString(src, "package ext.pricing; public class Discount { "
                + "public static int apply(int price, int pct) { return price - price * pct / 100 - " + less + "; } }");
        Path out = Files.createDirectories(base.resolve("classes"));
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.toString(), src.toString()) != 0) {
            throw new IllegalStateException("shared-lib fixture compile failed");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            String entry = "ext/pricing/Discount.class";
            jar.putNextEntry(new JarEntry(entry));
            jar.write(Files.readAllBytes(out.resolve(entry)));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    // --- harness ----------------------------------------------------------------------------------------------

    /** Runs one criterion and records exactly one outcome for it. */
    private void run(PocCriterion criterion, ThrowingRunnable body) {
        if (notApplicable().contains(criterion)) {
            PocReport.record(combination(), criterion, PocReport.Outcome.NOT_APPLICABLE,
                    notApplicableReason(criterion));
            return;
        }
        try {
            body.run();
            PocReport.record(combination(), criterion, PocReport.Outcome.PASSED, null);
        } catch (TestAbortedException e) {
            PocReport.record(combination(), criterion, PocReport.Outcome.SKIPPED, e.getMessage());
            throw e;
        } catch (Throwable t) {
            PocReport.record(combination(), criterion, PocReport.Outcome.FAILED, firstLine(t.toString()));
            if (t instanceof RuntimeException re) {
                throw re;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(t);
        }
    }

    protected interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static long max(List<Long> generations) {
        return generations.stream().mapToLong(Long::longValue).max().orElse(-1);
    }

    private static String firstLine(String text) {
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    @AfterEach
    void tearDown() {
        for (String id : List.of(STRICT, BOTH, CONSUMER, LIB)) {   // consumers before the library they use
            try {
                if (platform.find(id).isPresent()) {
                    platform.uninstall(id);
                }
            } catch (RuntimeException ignored) {
                // a failed criterion may leave a half-deployed module; the next test reinstalls from scratch
            }
        }
    }

    protected void install(ModuleDescriptor descriptor) {
        if (platform.find(descriptor.id()).isPresent()) {
            platform.update(descriptor);
        } else {
            platform.install(descriptor);
        }
    }

    // --- fixtures ---------------------------------------------------------------------------------------------

    protected static final String LIB = "poc-lib";
    protected static final String CONSUMER = "poc-consumer";
    protected static final String STRICT = "poc-consumer-strict";
    protected static final String BOTH = "poc-consumer-both";

    /** A LIBRARY publishing one exported type whose value identifies the generation. Always in-process (parent tier). */
    protected static ModuleDescriptor library(String label) {
        return ModuleDescriptor.builder()
                .id(LIB).version(label.equals("v1") ? "1.0.0" : "2.0.0")
                .kind(ModuleDescriptor.ModuleKind.LIBRARY).exports(List.of("pocgeo"))
                .isolationMode("in-process")
                .sources(Map.of("pocgeo.Label", """
                        package pocgeo;
                        public class Label { public String value() { return "%s"; } }
                        """.formatted(label)))
                .tests(Map.of("pocgeo.LabelTest", """
                        package pocgeo;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertNotNull;
                        class LabelTest { @Test void ok() { assertNotNull(new Label().value()); } }
                        """))
                .build();
    }

    /** Version-stable consumer: its gate checks the shape, so a library value change propagates to it. */
    protected static ModuleDescriptor consumer() {
        String fqcn = "runtime.poc.PocConsumer";
        return ModuleDescriptor.builder()
                .id(CONSUMER).version("1.0.0").uses(List.of(LIB))
                .controllerFqcn(fqcn).componentFqcns(List.of(fqcn))
                .sources(Map.of(fqcn, """
                        package runtime.poc;
                        import pocgeo.Label;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class PocConsumer {
                            @GetMapping("/poc/label") public String label() { return "c:" + new Label().value(); }
                        }
                        """))
                .tests(Map.of(fqcn + "Test", """
                        package runtime.poc;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertTrue;
                        class PocConsumerTest {
                            @Test void shape() { assertTrue(new PocConsumer().label().startsWith("c:")); }
                        }
                        """))
                .build();
    }

    /** Strict consumer: its gate pins the current value, so a library change must leave it on its old generation. */
    protected static ModuleDescriptor strictConsumer() {
        String fqcn = "runtime.poc.PocStrict";
        return ModuleDescriptor.builder()
                .id(STRICT).version("1.0.0").uses(List.of(LIB))
                .controllerFqcn(fqcn).componentFqcns(List.of(fqcn))
                .sources(Map.of(fqcn, """
                        package runtime.poc;
                        import pocgeo.Label;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class PocStrict {
                            @GetMapping("/poc/strict") public String label() { return "s:" + new Label().value(); }
                        }
                        """))
                .tests(Map.of(fqcn + "Test", """
                        package runtime.poc;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertEquals;
                        class PocStrictTest {
                            @Test void exact() { assertEquals("s:v1", new PocStrict().label()); }
                        }
                        """))
                .build();
    }

    /**
     * Consumer of BOTH tiers — the jar's {@code ext.pricing.Discount} and the library's exported type. Its gate checks
     * the shape, so both a jar generation swap and a library update are allowed to reach it.
     */
    protected static ModuleDescriptor bothConsumer() {
        String fqcn = "runtime.poc.PocBoth";
        return ModuleDescriptor.builder()
                .id(BOTH).version("1.0.0").uses(List.of(LIB))
                .controllerFqcn(fqcn).componentFqcns(List.of(fqcn))
                .sources(Map.of(fqcn, """
                        package runtime.poc;
                        import ext.pricing.Discount;
                        import pocgeo.Label;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class PocBoth {
                            @GetMapping("/poc/both")
                            public String quote() {
                                return "quote=" + Discount.apply(100, 20) + " label=" + new Label().value();
                            }
                        }
                        """))
                .tests(Map.of(fqcn + "Test", """
                        package runtime.poc;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertTrue;
                        class PocBothTest {
                            @Test void shape() { assertTrue(new PocBoth().quote().startsWith("quote=")); }
                        }
                        """))
                .build();
    }
}
