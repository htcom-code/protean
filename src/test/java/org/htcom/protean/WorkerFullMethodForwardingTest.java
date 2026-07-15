/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression for the worker/container GET-only forwarding bug: the {@link org.htcom.protean.proxy.ReverseProxy}
 * must forward the request verbatim (method + body + headers) to the worker and report the route's real HTTP
 * methods, so an isolated module behaves the same as an in-process one. Before the fix a {@code @PostMapping}
 * on a worker module returned 405 (forwarded as bodyless GET) and its listed {@code methods} were empty.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WorkerFullMethodForwardingTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-worker-full-method-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.isolation.mode", () -> "worker");
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ModulePlatform platform;

    static final String ID = "fm-worker";
    static final String FQCN = "gen.fm.C";
    static final String SRC = """
            package gen.fm;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RequestHeader;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class C {
                /** Pure function — gate 1 unit test target. */
                public static String tag(String s) { return "echo:" + s; }
                @PostMapping("/fm/echo")
                public String echo(@RequestBody(required = false) String body) { return tag(body == null ? "<null>" : body); }
                @GetMapping("/fm/hdr")
                public String hdr(@RequestHeader(value = "X-Demo", required = false) String h) { return "hdr:" + h; }
            }
            """;
    static final String TEST_SRC = """
            package gen.fm;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            public class CTest { @Test void tag() { assertEquals("echo:x", C.tag("x")); } }
            """;

    @BeforeEach
    void deploy() {
        platform.install(ModuleDescriptor.builder()
                .id(ID).version("1")
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN))
                .sources(Map.of(FQCN, SRC)).tests(Map.of("gen.fm.CTest", TEST_SRC))
                .isolationMode("worker")
                .build());
    }

    @AfterEach
    void cleanup() {
        try {
            platform.uninstall(ID);
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void post_with_body_is_forwarded_to_worker() throws Exception {
        // Before the fix: proxied as bodyless GET → worker's @PostMapping rejects with 405.
        mockMvc.perform(post("/fm/echo").contentType("text/plain").content("hello"))
                .andExpect(status().isOk())
                .andExpect(content().string("echo:hello"));
    }

    @Test
    void request_headers_are_forwarded_to_worker() throws Exception {
        mockMvc.perform(get("/fm/hdr").header("X-Demo", "abc"))
                .andExpect(status().isOk())
                .andExpect(content().string("hdr:abc"));
    }

    @Test
    void route_listing_reports_worker_route_methods() throws Exception {
        // The proxied POST route must surface methods:["POST"] just like an in-process module would.
        mockMvc.perform(get("/platform/modules/" + ID + "/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.patterns[0] == '/fm/echo')].methods[0]").value(containsInAnyOrder("POST")))
                .andExpect(jsonPath("$[?(@.patterns[0] == '/fm/hdr')].methods[0]").value(containsInAnyOrder("GET")));
    }
}
