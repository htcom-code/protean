/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.ModuleClassLoader;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.gate.rules.ForbiddenApiRule;
import org.htcom.protean.module.ModuleContainer;
import org.htcom.protean.module.ProteanTaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module unload lifecycle.
 * (4a) ModuleUnloadCallback is invoked, (4b) a managed ProteanTaskExecutor is injected and shut down on unload,
 * (4c) ForbiddenApiRule rejects Runtime.addShutdownHook.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class ModuleLifecycleTest {

    @Autowired MockMvc mockMvc;
    @Autowired RuntimeCompiler compiler;
    @Autowired ModuleContainer container;

    /** Where module code publishes signals (visible via the parent ClassLoader). */
    public static volatile ProteanTaskExecutor capturedExecutor;
    public static volatile String unloadedId;

    static final String CTRL = "runtime.life.LifeController";
    static final String CB = "runtime.life.LifeUnload";

    static final Map<String, String> SOURCES = Map.of(
            CTRL, """
                    package runtime.life;
                    import org.htcom.protean.module.ProteanTaskExecutor;
                    import org.htcom.protean.ModuleLifecycleTest;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RestController;
                    import java.util.concurrent.Callable;
                    @RestController
                    public class LifeController {
                        private final ProteanTaskExecutor exec;
                        public LifeController(ProteanTaskExecutor exec) { this.exec = exec; }
                        @GetMapping("/life/run")
                        public String run() throws Exception {
                            ModuleLifecycleTest.capturedExecutor = exec;
                            Callable<String> task = () -> "ran-on-" + Thread.currentThread().getName();
                            return exec.submit(task).get();
                        }
                    }
                    """,
            CB, """
                    package runtime.life;
                    import org.htcom.protean.module.ModuleUnloadCallback;
                    import org.htcom.protean.ModuleLifecycleTest;
                    import org.springframework.stereotype.Component;
                    @Component
                    public class LifeUnload implements ModuleUnloadCallback {
                        public void onUnload(String moduleId) { ModuleLifecycleTest.unloadedId = moduleId; }
                    }
                    """
    );

    @BeforeEach
    void reset() {
        capturedExecutor = null;
        unloadedId = null;
    }

    @AfterEach
    void cleanup() {
        if (container.isDeployed("life-mod")) {
            container.undeploy("life-mod");
        }
    }

    @Test
    void managed_executor_and_unload_callback_lifecycle() throws Exception {
        ModuleClassLoader loader = compiler.compileAll(SOURCES);
        container.deploy("life-mod", loader, List.of(CTRL, CB), CTRL);

        // (4b) managed executor injected + task runs on one of its threads.
        mockMvc.perform(get("/life/run")).andExpect(status().isOk());
        // The thread name confirms it ran on the module executor.
        String body = mockMvc.perform(get("/life/run")).andReturn().getResponse().getContentAsString();
        assertThat(body, startsWith("ran-on-protean-mod-life-mod-"));

        ProteanTaskExecutor captured = capturedExecutor;
        assertNotNull(captured, "the module must have a ProteanTaskExecutor injected");
        assertFalse(captured.isShutdown());

        // unload -> (4a) callback invoked + (4b) executor shut down automatically.
        container.undeploy("life-mod");
        assertEquals("life-mod", unloadedId, "the unload callback must be invoked with moduleId");
        assertTrue(captured.isShutdown(), "the managed executor must be shut down on unload");
        mockMvc.perform(get("/life/run")).andExpect(status().isNotFound());
    }

    @Test
    void executor_close_rejects_further_tasks() throws Exception {
        ProteanTaskExecutor e = new ProteanTaskExecutor("unit", 1);
        assertEquals("hi", e.submit(() -> "hi").get());
        e.close();
        assertTrue(e.isShutdown());
        assertThrows(RejectedExecutionException.class, () -> e.execute(() -> { }));
    }

    @Test
    void forbidden_rule_rejects_add_shutdown_hook() {
        String src = """
                package t;
                public class Bad {
                    public void go() { Runtime.getRuntime().addShutdownHook(new Thread()); }
                }
                """;
        ModuleClassLoader loader = compiler.compileAll(Map.of("t.Bad", src));
        ForbiddenApiRule rule = new ForbiddenApiRule();
        List<String> violations = rule.check("t.Bad", loader.bytecode().get("t.Bad"));
        assertFalse(violations.isEmpty(), "addShutdownHook must be rejected");
        assertTrue(violations.get(0).contains("addShutdownHook"), violations.toString());
    }
}
