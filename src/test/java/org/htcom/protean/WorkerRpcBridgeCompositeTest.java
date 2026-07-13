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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RPC bridge primitive/composite types: a worker module calls the main's EchoPort.
 * addInt(int,int) = primitive, move(Point,int,int)=Point = composite DTO + primitive.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WorkerRpcBridgeCompositeTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-rpcx-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
        registry.add("protean.isolation.mode", () -> "worker");
        registry.add("protean.worker.rpc-bridge", () -> "true");
    }

    @Autowired MockMvc mockMvc;
    @Autowired WorkerProcessIsolation isolation;

    static final String FQCN = "runtime.rpcx.RpcxController";
    static final String SRC = """
            package runtime.rpcx;
            import org.htcom.protean.bridge.EchoPort;
            import org.htcom.protean.bridge.Point;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class RpcxController {
                private final EchoPort echo;
                public RpcxController(EchoPort echo) { this.echo = echo; }
                @GetMapping("/rpcx/ping")
                public String ping() {
                    int sum = echo.addInt(2, 3);                       // primitive
                    Point p = echo.move(new Point(1, 1), 4, 5);        // composite DTO + primitive
                    return "sum=" + sum + ",pt=" + p.x() + "," + p.y();
                }
            }
            """;

    @AfterEach
    void cleanup() {
        try {
            isolation.undeploy("rpcx-mod");
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void bridge_handles_primitive_and_composite_types() throws Exception {
        isolation.deploy(ModuleDescriptor.builder()
                .id("rpcx-mod").version("1.0.0").trustTier(ModuleDescriptor.TrustTier.UNTRUSTED)
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN)).sources(Map.of(FQCN, SRC))
                .needsSharedBeans(true)
                .bridgedInterfaces(List.of("org.htcom.protean.bridge.EchoPort"))
                .build());

        // addInt(2,3)=5, move(Point(1,1),4,5)=Point(5,6)
        mockMvc.perform(get("/rpcx/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("sum=5,pt=5,6"));
    }
}
