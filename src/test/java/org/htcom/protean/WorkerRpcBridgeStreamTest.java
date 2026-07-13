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
 * Streamed bridge return: a worker module calls a shared bean whose return type is {@link java.io.InputStream}.
 * Main streams the payload as {@code application/octet-stream} (chunked, generated lazily) and the worker
 * consumes it as a stream. Run with HMAC auth enabled to also prove that streaming the response does not
 * interfere with request authentication (auth applies to the small JSON invocation, not the streamed body).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WorkerRpcBridgeStreamTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-rpc-stream-test");
    static final int SIZE = 500_000;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");
        registry.add("protean.worker.rpc-bridge", () -> "true");
        // Auth on (hmac) to prove response streaming is orthogonal to request auth.
        registry.add("protean.bridge.auth-enabled", () -> "true");
        registry.add("protean.bridge.auth-mode", () -> "hmac");
        registry.add("protean.bridge.secret", () -> "poc-stream-secret");
    }

    @Autowired MockMvc mockMvc;
    @Autowired WorkerProcessIsolation isolation;

    static final String FQCN = "runtime.rpcstream.RpcStreamController";
    static final String SRC = """
            package runtime.rpcstream;
            import org.htcom.protean.bridge.StreamPort;
            import java.io.InputStream;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class RpcStreamController {
                private final StreamPort stream;
                public RpcStreamController(StreamPort stream) { this.stream = stream; }
                @GetMapping("/rpc-stream/download")
                public String download() throws Exception {
                    InputStream in = stream.download(500000);   // returns a bridge-streamed InputStream
                    long len = 0, sum = 0;
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        for (int i = 0; i < r; i++) { sum = (sum + (buf[i] & 0xff)) % 1000000007L; len++; }
                    }
                    in.close();
                    return "len=" + len + ",sum=" + sum;
                }
            }
            """;

    static ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder()
                .id("rpc-stream-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, SRC))
                .needsSharedBeans(true)
                .bridgedInterfaces(List.of("org.htcom.protean.bridge.StreamPort"))
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy("rpc-stream-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void worker_consumes_streamed_bridge_return() throws Exception {
        assertTrue(isolation.supports(descriptor()));
        isolation.deploy(descriptor());

        // Expected checksum over the same deterministic pattern main generates (byte i -> i % 251).
        long sum = 0;
        for (long i = 0; i < SIZE; i++) {
            sum = (sum + (i % 251)) % 1000000007L;
        }

        mockMvc.perform(get("/rpc-stream/download"))
                .andExpect(status().isOk())
                .andExpect(content().string("len=" + SIZE + ",sum=" + sum));
    }
}
