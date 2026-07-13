/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.isolation.WorkerProcessIsolation;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.SharedLibStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Live shared-lib propagation to worker JVMs. A jar uploaded to the main's put-jar store
 * is pushed to running workers (which publish a new generation and rebind the affected modules in place, no restart),
 * and a freshly spawned worker is seeded with the current store generation rather than only the boot seed dir.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WorkerSharedLibPropagationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        registry.add("protean.isolation.mode", () -> "worker");
        // A dedicated, empty store dir (no seed dir): the worker must get the jar via live push / spawn seeding only.
        Path store = Files.createTempDirectory("protean-prop-test-store");
        registry.add("protean.module.shared-lib-store-dir", store::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired WorkerProcessIsolation isolation;
    @Autowired SharedLibStore store;

    /** Builds a jar containing {@code ext.Widget} whose {@code hello()} returns the given string. */
    static byte[] widgetJar(String helloReturn) throws Exception {
        Path base = Files.createTempDirectory("protean-widget");
        Path classes = Files.createDirectories(base.resolve("classes"));
        Path src = base.resolve("Widget.java");
        Files.writeString(src, "package ext; public class Widget { "
                + "public String hello() { return \"" + helloReturn + "\"; } }");
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), src.toString()) != 0) {
            throw new IllegalStateException("widget fixture compilation failed");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(bos)) {
            jos.putNextEntry(new JarEntry("ext/Widget.class"));
            jos.write(Files.readAllBytes(classes.resolve("ext/Widget.class")));
            jos.closeEntry();
        }
        return bos.toByteArray();
    }

    static SharedLibStore.IncomingLib lib(String version, byte[] bytes) {
        return new SharedLibStore.IncomingLib("ext-widget", version, bytes, null, null);
    }

    static ModuleDescriptor widgetModule(String id, String route) {
        String under = id.replace('-', '_');
        String fqcn = "runtime.wp." + under + ".Ctrl";
        String testFqcn = "runtime.wp." + under + ".CtrlTest";
        return ModuleDescriptor.builder().id(id).version("1.0.0")
                .controllerFqcn(fqcn).componentFqcns(List.of(fqcn))
                .isolationMode("worker")
                .sources(Map.of(fqcn, """
                        package runtime.wp.%s;
                        import ext.Widget;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class Ctrl {
                            @GetMapping("%s") public String hello() { return new Widget().hello(); }
                        }
                        """.formatted(under, route)))
                // A version-agnostic bundled test: passes against both widget-v1 and widget-v2, so a binary-compatible
                // live shared-lib update clears the rebind test gate (enforceTestGate on /__admin/redeploy). A module
                // with no bundled test is rejected by that gate on rebind → Plan B (sticky), never adopting the update.
                .tests(Map.of(testFqcn, """
                        package runtime.wp.%s;
                        import ext.Widget;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertTrue;
                        public class CtrlTest {
                            @Test void widget_greets() { assertTrue(new Widget().hello().startsWith("widget")); }
                        }
                        """.formatted(under)))
                .build();
    }

    @AfterEach
    void cleanup() {
        for (String id : List.of("wp-live", "wp-fresh")) {
            try {
                isolation.undeploy(id);
            } catch (RuntimeException ignored) {
            }
        }
        // The store + context are shared across methods; clear it so each test starts from an empty parent tier.
        for (SharedLibStore.StoredLib lib : store.list()) {
            try {
                store.remove(lib.name());
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** A running worker adopts a live put-jar update to a shared lib its module uses, with no worker restart. */
    @Test
    void running_worker_adopts_a_live_shared_lib_update() throws Exception {
        store.deploy(List.of(lib("1.0.0", widgetJar("widget-v1"))));
        // The worker is spawned here and seeded with v1 → its module compiles against ext.Widget v1.
        isolation.deploy(widgetModule("wp-live", "/wp/live"));
        mockMvc.perform(get("/wp/live")).andExpect(status().isOk()).andExpect(content().string("widget-v1"));

        // Upload v2 to the store: fires the generation event → propagator pushes it to the running worker, which
        // republishes its parent tier and rebinds the module in place (no restart, no module redeploy by the test).
        store.deploy(List.of(lib("2.0.0", widgetJar("widget-v2"))));
        awaitContent("/wp/live", "widget-v2");
    }

    /** A worker spawned AFTER a store upload is seeded with the current generation (not just the boot seed dir). */
    @Test
    void freshly_spawned_worker_is_seeded_with_the_current_generation() throws Exception {
        // Upload the jar with no worker running yet.
        store.deploy(List.of(lib("1.0.0", widgetJar("seeded-widget"))));
        // Deploying a module now spawns a fresh worker; it compiles against ext.Widget only if it was seeded.
        isolation.deploy(widgetModule("wp-fresh", "/wp/fresh"));
        mockMvc.perform(get("/wp/fresh")).andExpect(status().isOk()).andExpect(content().string("seeded-widget"));
    }

    /**
     * Polls the endpoint until the asynchronous live shared-lib push has rebound the running worker to serve
     * {@code expected} (the worker republishes its parent tier out of process, so the change is not visible the
     * instant {@code store.deploy} returns), then asserts. Fails with the usual content mismatch if it never adopts.
     */
    private void awaitContent(String path, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            String body = mockMvc.perform(get(path)).andReturn().getResponse().getContentAsString();
            if (expected.equals(body)) {
                break;
            }
            Thread.sleep(100);
        }
        mockMvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string(expected));
    }
}
