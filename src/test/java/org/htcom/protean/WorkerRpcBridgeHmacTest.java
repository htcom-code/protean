/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.bridge.BridgeHmac;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bridge HMAC auth mode ({@code protean.bridge.auth-mode=hmac}). Verifies that worker-to-main RPC still
 * works transparently, and that direct calls are rejected unless they carry a valid, fresh, non-replayed
 * HMAC signature over the exact body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WorkerRpcBridgeHmacTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-rpc-hmac-test");
    static final String SECRET = "poc-test-hmac-secret";
    static final byte[] BODY = ("""
            {"iface":"org.htcom.protean.bridge.GreetingPort","method":"greet",
             "argTypes":["java.lang.String"],"args":["world"],"beanName":null}
            """).getBytes(StandardCharsets.UTF_8);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");
        registry.add("protean.worker.rpc-bridge", () -> "true");
        registry.add("protean.bridge.auth-enabled", () -> "true");
        registry.add("protean.bridge.auth-mode", () -> "hmac");
        registry.add("protean.bridge.secret", () -> SECRET);
    }

    @Autowired MockMvc mockMvc;
    @Autowired WorkerProcessIsolation isolation;

    static final String FQCN = "runtime.rpchmac.RpcHmacController";
    static final String SRC = """
            package runtime.rpchmac;
            import org.htcom.protean.bridge.GreetingPort;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class RpcHmacController {
                private final GreetingPort greeting;
                public RpcHmacController(GreetingPort greeting) { this.greeting = greeting; }
                @GetMapping("/rpc-hmac/ping")
                public String ping() { return greeting.greet("world"); }
            }
            """;

    static ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder()
                .id("rpc-hmac-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, SRC))
                .needsSharedBeans(true)
                .bridgedInterfaces(List.of("org.htcom.protean.bridge.GreetingPort"))
                .build();
    }

    /** Builds a POST to /__bridge/invoke signed for the given timestamp/nonce over BODY. */
    private static MockHttpServletRequestBuilder signed(long ts, String nonce) {
        return post("/__bridge/invoke")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header(BridgeHmac.TS_HEADER, Long.toString(ts))
                .header(BridgeHmac.NONCE_HEADER, nonce)
                .header(BridgeHmac.SIG_HEADER, BridgeHmac.sign(SECRET, ts, nonce, BODY));
    }

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy("rpc-hmac-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void worker_bridge_call_works_transparently_in_hmac_mode() throws Exception {
        assertTrue(isolation.supports(descriptor()));
        isolation.deploy(descriptor());

        mockMvc.perform(get("/rpc-hmac/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("hello world"));
    }

    @Test
    void direct_call_without_signature_is_rejected() throws Exception {
        mockMvc.perform(post("/__bridge/invoke")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void valid_signature_is_accepted() throws Exception {
        mockMvc.perform(signed(System.currentTimeMillis(), "nonce-ok"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hello world")));
    }

    @Test
    void tampered_body_is_rejected() throws Exception {
        long ts = System.currentTimeMillis();
        String nonce = "nonce-tamper";
        // Sign the canonical BODY but send a different body → signature no longer matches.
        byte[] tampered = "{\"iface\":\"x\",\"method\":\"y\",\"argTypes\":[],\"args\":[],\"beanName\":null}"
                .getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/__bridge/invoke")
                        .contentType(MediaType.APPLICATION_JSON).content(tampered)
                        .header(BridgeHmac.TS_HEADER, Long.toString(ts))
                        .header(BridgeHmac.NONCE_HEADER, nonce)
                        .header(BridgeHmac.SIG_HEADER, BridgeHmac.sign(SECRET, ts, nonce, BODY)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void replayed_nonce_is_rejected() throws Exception {
        long ts = System.currentTimeMillis();
        String nonce = "nonce-replay";
        mockMvc.perform(signed(ts, nonce)).andExpect(status().isOk());
        // Same ts/nonce/sig again → replay, rejected.
        mockMvc.perform(signed(ts, nonce)).andExpect(status().isUnauthorized());
    }

    @Test
    void stale_timestamp_is_rejected() throws Exception {
        // Default window is 30s; a 10-minute-old timestamp is well outside it.
        long ts = System.currentTimeMillis() - 600_000;
        mockMvc.perform(signed(ts, "nonce-stale"))
                .andExpect(status().isUnauthorized());
    }
}
