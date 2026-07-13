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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bridge shared-secret auth (opt-in). With {@code protean.bridge.auth-enabled=true}:
 * <ul>
 *   <li>the worker's bridge calls carry the injected secret, so worker-to-main RPC still works transparently;</li>
 *   <li>a direct call to {@code /__bridge/invoke} with no/wrong bearer token is rejected with 401;</li>
 *   <li>a direct call with the correct bearer token is accepted.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WorkerRpcBridgeAuthTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-rpc-auth-test");
    static final String SECRET = "poc-test-bridge-secret";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");
        registry.add("protean.worker.rpc-bridge", () -> "true");
        registry.add("protean.bridge.auth-enabled", () -> "true");
        registry.add("protean.bridge.secret", () -> SECRET);
    }

    @Autowired MockMvc mockMvc;
    @Autowired WorkerProcessIsolation isolation;

    static final String FQCN = "runtime.rpcauth.RpcAuthController";
    static final String SRC = """
            package runtime.rpcauth;
            import org.htcom.protean.bridge.GreetingPort;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class RpcAuthController {
                private final GreetingPort greeting;
                public RpcAuthController(GreetingPort greeting) { this.greeting = greeting; }
                @GetMapping("/rpc-auth/ping")
                public String ping() { return greeting.greet("world"); }
            }
            """;

    static ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder()
                .id("rpc-auth-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, SRC))
                .needsSharedBeans(true)
                .bridgedInterfaces(List.of("org.htcom.protean.bridge.GreetingPort"))
                .build();
    }

    static final String INVOCATION = """
            {"iface":"org.htcom.protean.bridge.GreetingPort","method":"greet",
             "argTypes":["java.lang.String"],"args":["world"],"beanName":null}
            """;

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy("rpc-auth-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void worker_bridge_call_works_transparently_with_auth_enabled() throws Exception {
        assertTrue(isolation.supports(descriptor()));
        isolation.deploy(descriptor());

        // The worker was spawned with the injected secret -> its bridge call authenticates -> "hello world".
        mockMvc.perform(get("/rpc-auth/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("hello world"));
    }

    @Test
    void direct_bridge_call_without_token_is_rejected() throws Exception {
        mockMvc.perform(post("/__bridge/invoke")
                        .contentType(MediaType.APPLICATION_JSON).content(INVOCATION))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void direct_bridge_call_with_wrong_token_is_rejected() throws Exception {
        mockMvc.perform(post("/__bridge/invoke")
                        .header("Authorization", "Bearer wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content(INVOCATION))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void direct_bridge_call_with_correct_token_is_accepted() throws Exception {
        mockMvc.perform(post("/__bridge/invoke")
                        .header("Authorization", "Bearer " + SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content(INVOCATION))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("hello world")));
    }
}
