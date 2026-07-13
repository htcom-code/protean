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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RPC bridge: a worker module calls the main's shared bean (GreetingPort) over RPC.
 * The worker is injected with a GreetingPort proxy and calls it -> main /__bridge/invoke -> main GreetingPortImpl runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WorkerRpcBridgeTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-rpc-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");
        registry.add("protean.worker.rpc-bridge", () -> "true");
    }

    @Autowired MockMvc mockMvc;
    @Autowired WorkerProcessIsolation isolation;

    static final String FQCN = "runtime.rpc.RpcController";
    static final String SRC = """
            package runtime.rpc;
            import org.htcom.protean.bridge.GreetingPort;
            import org.htcom.protean.bridge.MathPort;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class RpcController {
                private final GreetingPort greeting;   // main shared bean (bridge proxy injected)
                private final MathPort math;           // an arbitrary second interface uses the same mechanism
                public RpcController(GreetingPort greeting, MathPort math) {
                    this.greeting = greeting; this.math = math;
                }
                @GetMapping("/rpc/ping")
                public String ping() { return greeting.greet("world") + "|" + math.add(2, 3); }
            }
            """;

    static ModuleDescriptor descriptor() {
        // needsSharedBeans=true — allowed on the worker because the bridge is enabled. Declare both interfaces as bridgedInterfaces.
        return ModuleDescriptor.builder()
                .id("rpc-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, SRC))
                .needsSharedBeans(true)
                .bridgedInterfaces(List.of("org.htcom.protean.bridge.GreetingPort", "org.htcom.protean.bridge.MathPort"))
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy("rpc-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void worker_module_calls_main_shared_bean_via_bridge() throws Exception {
        // with the bridge enabled, the worker supports needsSharedBeans modules too
        assertTrue(isolation.supports(descriptor()));

        isolation.deploy(descriptor());

        // both arbitrary interfaces (GreetingPort + MathPort) are bridged by the same mechanism -> "hello world|5"
        mockMvc.perform(get("/rpc/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("hello world|5"));
    }
}
