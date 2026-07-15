/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpDispatcher;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the REST↔MCP routes parity bug: a <b>worker-isolated</b> module serves its routes through the
 * {@link org.htcom.protean.proxy.ReverseProxy} (not the in-process registrar), so the MCP resource
 * {@code protean://modules/{id}/routes} must aggregate the proxied paths just like REST
 * {@code GET /platform/modules/{id}/routes} does. Before the fix the resource read only the registrar and returned
 * an empty list for worker/container modules — misreading a healthy module as "no routes registered".
 */
@SpringBootTest(properties = "protean.mcp.enabled=true")
class McpWorkerRoutesParityTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-worker-routes-parity-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.isolation.mode", () -> "worker");
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired McpDispatcher dispatcher;
    @Autowired ObjectMapper mapper;
    @Autowired ModulePlatform platform;

    static final String ID = "worker-routes-parity";
    static final String FQCN = "gen.wrp.C";
    static final String SRC = """
            package gen.wrp;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class C {
                /** Pure function — target of gate 1 unit test. */
                public static String ok() { return "pong"; }
                @GetMapping("/wrp/ping")
                public String ping() { return ok(); }
            }
            """;
    static final String TEST_SRC = """
            package gen.wrp;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            public class CTest { @Test void ok() { assertEquals("pong", C.ok()); } }
            """;

    @BeforeEach
    void deployWorkerModule() {
        platform.install(ModuleDescriptor.builder()
                .id(ID).version("1")
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN))
                .sources(Map.of(FQCN, SRC)).tests(Map.of("gen.wrp.CTest", TEST_SRC))
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
    void worker_module_routes_are_visible_via_mcp_resource() {
        // Premise: the module is actually worker-isolated (served via ReverseProxy, absent from the in-process registrar).
        assertEquals("worker", platform.effectiveMode(platform.find(ID).orElseThrow()),
                "module must be worker-isolated for this parity test to be meaningful");

        JsonNode routes = readResource("protean://modules/" + ID + "/routes");
        assertTrue(routes.isArray() && !routes.isEmpty(),
                "worker module routes must be visible via MCP (was empty before the ReverseProxy aggregation fix): " + routes);

        boolean found = false;
        List<String> patterns = new ArrayList<>();
        for (JsonNode route : routes) {
            List<String> methods = new ArrayList<>();
            route.path("methods").forEach(m -> methods.add(m.asText()));
            route.path("patterns").forEach(p -> patterns.add(p.asText()));
            if (route.path("patterns").toString().contains("/wrp/ping")) {
                // Parity with in-process: the proxied worker route must carry its real method, not an empty set.
                assertTrue(methods.contains("GET"),
                        "worker route /wrp/ping must report methods:[GET] via MCP (was empty before the fix): " + methods);
                found = true;
            }
        }
        assertTrue(found && patterns.contains("/wrp/ping"),
                "proxied worker route /wrp/ping must appear in the MCP routes resource: " + patterns);
    }

    private JsonNode readResource(String uri) {
        ObjectNode params = mapper.createObjectNode();
        params.put("uri", uri);
        String text = call("resources/read", params).path("result").path("contents").get(0).path("text").asText();
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode call(String method, ObjectNode params) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        return dispatcher.dispatch(req, McpCallContext.anonymous());
    }
}
