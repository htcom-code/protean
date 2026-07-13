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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpDispatcher;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Route lookup resource ({@code protean://modules/{id}/routes}) plus {@code list_modules} search/pagination.
 * Drives the dispatcher for real to verify deploy-then-query end-to-end.
 */
@SpringBootTest(properties = "protean.mcp.enabled=true")
class McpModuleRoutesAndSearchTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-routes-search-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired McpDispatcher dispatcher;
    @Autowired ObjectMapper mapper;
    @Autowired ModulePlatform platform;

    static final String[] IDS = {"alpha-mod", "beta-mod", "gamma-mod"};

    /** Gives each module a unique package/path to avoid route collisions. */
    private static String source(String id) {
        String pkg = id.replace("-", "");
        return "package gen." + pkg + ";\n"
                + "import org.springframework.web.bind.annotation.GetMapping;\n"
                + "import org.springframework.web.bind.annotation.RestController;\n"
                + "@RestController\n"
                + "public class C { @GetMapping(\"/" + pkg + "/ping\") public String ping() { return \"ok\"; } }\n";
    }

    private static String test(String id) {
        String pkg = id.replace("-", "");
        return "package gen." + pkg + ";\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "import static org.junit.jupiter.api.Assertions.assertEquals;\n"
                + "public class CTest { @Test void ping() { assertEquals(\"ok\", new C().ping()); } }\n";
    }

    @BeforeEach
    void deployAll() {
        for (String id : IDS) {
            String pkg = id.replace("-", "");
            ObjectNode args = mapper.createObjectNode();
            args.put("id", id);
            args.put("version", "1.0.0");
            args.put("controller", "gen." + pkg + ".C");
            ArrayNode files = args.putArray("files");
            ObjectNode src = files.addObject();
            src.put("kind", "source");
            src.put("filename", "C.java");
            src.put("content", source(id));
            ObjectNode t = files.addObject();
            t.put("kind", "test");
            t.put("filename", "CTest.java");
            t.put("content", test(id));
            JsonNode r = callTool("protean.deploy_module", args).path("result");
            assertFalse(r.path("isError").asBoolean(false), "deploy must succeed: " + id);
        }
    }

    @AfterEach
    void cleanup() {
        for (String id : IDS) {
            try {
                platform.uninstall(id);
            } catch (RuntimeException ignored) {
            }
        }
    }

    // --- route lookup resource ---

    @Test
    void resource_templates_advertise_routes() {
        List<String> templates = new ArrayList<>();
        call("resources/templates/list", null).path("result").path("resourceTemplates")
                .forEach(t -> templates.add(t.path("uriTemplate").asText()));
        assertTrue(templates.contains("protean://modules/{id}/routes"), templates.toString());
    }

    @Test
    void routes_resource_returns_registered_route() {
        JsonNode routes = readResource("protean://modules/alpha-mod/routes");
        assertTrue(routes.isArray() && !routes.isEmpty(), "alpha-mod must have routes");
        boolean found = false;
        for (JsonNode route : routes) {
            List<String> methods = new ArrayList<>();
            route.path("methods").forEach(m -> methods.add(m.asText()));
            List<String> patterns = new ArrayList<>();
            route.path("patterns").forEach(p -> patterns.add(p.asText()));
            if (methods.contains("GET") && patterns.contains("/alphamod/ping")) {
                found = true;
            }
        }
        assertTrue(found, "GET /alphamod/ping route must be observed");
    }

    @Test
    void routes_resource_unknown_module_is_invalid_params() {
        ObjectNode params = mapper.createObjectNode();
        params.put("uri", "protean://modules/does-not-exist/routes");
        JsonNode resp = call("resources/read", params);
        assertEquals(-32602, resp.path("error").path("code").asInt());
    }

    // --- list_modules search/pagination ---

    @Test
    void list_modules_no_args_returns_all() {
        JsonNode structured = listModules(null);
        assertTrue(structured.path("modules").size() >= IDS.length);
    }

    @Test
    void list_modules_query_filters_by_substring() {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", "beta");
        JsonNode modules = listModules(args).path("modules");
        assertEquals(1, modules.size());
        assertEquals("beta-mod", modules.get(0).path("id").asText());
    }

    @Test
    void list_modules_query_matches_controller_fqcn() {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", "gen.gammamod");
        JsonNode modules = listModules(args).path("modules");
        assertEquals(1, modules.size());
        assertEquals("gamma-mod", modules.get(0).path("id").asText());
    }

    @Test
    void list_modules_limit_and_cursor_paginate() {
        // Narrow to our 3 modules (excluding leftovers from other tests) to make paging boundaries deterministic.
        ObjectNode p1 = mapper.createObjectNode();
        p1.put("query", "-mod");
        p1.put("limit", 2);
        JsonNode page1 = listModules(p1);
        assertEquals(2, page1.path("modules").size());
        assertTrue(page1.hasNonNull("nextCursor"), "nextCursor present when more remain");
        // id ascending: alpha, beta first.
        assertEquals("alpha-mod", page1.path("modules").get(0).path("id").asText());
        assertEquals("beta-mod", page1.path("modules").get(1).path("id").asText());

        ObjectNode p2 = mapper.createObjectNode();
        p2.put("query", "-mod");
        p2.put("limit", 2);
        p2.put("cursor", page1.path("nextCursor").asText());
        JsonNode page2 = listModules(p2);
        assertEquals(1, page2.path("modules").size());
        assertEquals("gamma-mod", page2.path("modules").get(0).path("id").asText());
        assertFalse(page2.has("nextCursor"), "no nextCursor on the last page");
    }

    @Test
    void list_modules_limit_zero_returns_all_unbounded() {
        // limit=0 means "all" — no paging cap, no nextCursor even across our filtered set.
        ObjectNode args = mapper.createObjectNode();
        args.put("query", "-mod");
        args.put("limit", 0);
        JsonNode page = listModules(args);
        assertEquals(3, page.path("modules").size());
        assertFalse(page.has("nextCursor"), "limit=0 returns everything, so no nextCursor");
    }

    @Test
    void list_modules_bad_cursor_falls_back_to_start() {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", "-mod");
        args.put("cursor", "!!!not-base64!!!");
        JsonNode modules = listModules(args).path("modules");
        assertEquals(3, modules.size(), "a corrupt cursor falls back to the start");
    }

    // --- helpers ---

    private JsonNode listModules(ObjectNode args) {
        JsonNode result = callTool("protean.list_modules", args == null ? mapper.createObjectNode() : args)
                .path("result");
        assertFalse(result.path("isError").asBoolean(false));
        return result.path("structuredContent");
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

    private JsonNode callTool(String tool, ObjectNode args) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", tool);
        params.set("arguments", args);
        return call("tools/call", params);
    }
}
