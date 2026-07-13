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
import org.htcom.protean.runtime.TraceMetrics;
import org.htcom.protean.runtime.TraceStore;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the {@link McpDispatcher} for the trace observability tools ({@code protean.query_traces},
 * {@code protean.module_metrics}). MockMvc requests pass through {@code RequestTraceFilter}, so the tools
 * observe real recorded data. MCP and per-module metrics are both enabled.
 */
@SpringBootTest(properties = {"protean.mcp.enabled=true", "protean.trace.metrics.enabled=true"})
@AutoConfigureMockMvc
class McpTraceToolsTest {

    static final Path STORE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "protean-mcp-trace-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("protean.module-store.dir", STORE_DIR::toString);
    }

    @Autowired McpDispatcher dispatcher;
    @Autowired ObjectMapper mapper;
    @Autowired ModulePlatform platform;
    @Autowired MockMvc mockMvc;
    @Autowired TraceStore traceStore;
    @Autowired TraceMetrics traceMetrics;

    @BeforeEach
    void resetTraces() {
        traceStore.clear();
        traceMetrics.clear();
    }

    static final String ID = "trace-tool-mod";
    static final String FQCN = "runtime.tt.TtController";
    static final String TEST_FQCN = "runtime.tt.TtControllerTest";

    static ModuleDescriptor descriptor() {
        String src = """
                package runtime.tt;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class TtController {
                    @GetMapping("/trace-tool/ping") public String ping() { return "ok"; }
                }
                """;
        String test = """
                package runtime.tt;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class TtControllerTest {
                    @Test void ping() { assertEquals("ok", new TtController().ping()); }
                }
                """;
        return ModuleDescriptor.builder()
                .id(ID).version("1.0.0")
                .controllerFqcn(FQCN).componentFqcns(List.of(FQCN))
                .sources(Map.of(FQCN, src)).tests(Map.of(TEST_FQCN, test))
                .build();
    }

    @AfterEach
    void cleanup() {
        try {
            platform.uninstall(ID);
        } catch (RuntimeException ignored) {
        }
    }

    private JsonNode callTool(String tool, ObjectNode args) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/call");
        ObjectNode params = req.putObject("params");
        params.put("name", tool);
        params.set("arguments", args);
        return dispatcher.dispatch(req, McpCallContext.anonymous()).path("result");
    }

    private List<String> toolNames() {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/list");
        JsonNode resp = dispatcher.dispatch(req, McpCallContext.anonymous());
        List<String> names = new ArrayList<>();
        resp.path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
        return names;
    }

    @Test
    void trace_tools_are_listed() {
        List<String> names = toolNames();
        assertTrue(names.contains("protean.query_traces"), names.toString());
        assertTrue(names.contains("protean.module_metrics"), names.toString());
    }

    private JsonNode toolNode(String name) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/list");
        JsonNode resp = dispatcher.dispatch(req, McpCallContext.anonymous());
        for (JsonNode t : resp.path("result").path("tools")) {
            if (name.equals(t.path("name").asText())) {
                return t;
            }
        }
        return mapper.missingNode();
    }

    @Test
    void trace_tools_declare_output_schema() {
        JsonNode metrics = toolNode("protean.module_metrics").path("outputSchema");
        assertTrue(metrics.isObject(), "module_metrics outputSchema serialized");
        List<String> mReq = new ArrayList<>();
        metrics.path("required").forEach(r -> mReq.add(r.asText()));
        assertTrue(mReq.contains("enabled") && mReq.contains("metrics"), mReq.toString());
        assertEquals("integer",
                metrics.path("properties").path("metrics").path("items").path("properties").path("count").path("type").asText());

        JsonNode traces = toolNode("protean.query_traces").path("outputSchema");
        assertTrue(traces.isObject(), "query_traces outputSchema serialized");
        List<String> tReq = new ArrayList<>();
        traces.path("required").forEach(r -> tReq.add(r.asText()));
        assertEquals(List.of("traces"), tReq);
    }

    @Test
    void query_traces_returns_recorded_requests() throws Exception {
        platform.install(descriptor());
        mockMvc.perform(get("/trace-tool/ping")).andExpect(status().isOk());
        mockMvc.perform(get("/trace-tool/ping")).andExpect(status().isOk());

        ObjectNode args = mapper.createObjectNode();
        args.put("moduleId", ID);
        JsonNode result = callTool("protean.query_traces", args);
        assertFalse(result.path("isError").asBoolean(false), result.toString());
        JsonNode traces = result.path("structuredContent").path("traces");
        assertTrue(traces.isArray());
        assertEquals(2, traces.size());
        assertEquals(ID, traces.get(0).path("moduleId").asText());

        // errorsOnly filter excludes the 200s
        ObjectNode errArgs = mapper.createObjectNode();
        errArgs.put("errorsOnly", true);
        JsonNode errors = callTool("protean.query_traces", errArgs).path("structuredContent").path("traces");
        assertEquals(0, errors.size());
    }

    @Test
    void module_metrics_aggregates_when_enabled() throws Exception {
        platform.install(descriptor());
        mockMvc.perform(get("/trace-tool/ping")).andExpect(status().isOk());
        mockMvc.perform(get("/trace-tool/ping")).andExpect(status().isOk());

        ObjectNode args = mapper.createObjectNode();
        args.put("moduleId", ID);
        JsonNode result = callTool("protean.module_metrics", args);
        assertFalse(result.path("isError").asBoolean(false), result.toString());
        JsonNode structured = result.path("structuredContent");
        assertTrue(structured.path("enabled").asBoolean(), "metrics enabled");
        JsonNode metrics = structured.path("metrics");
        assertEquals(1, metrics.size());
        assertEquals(ID, metrics.get(0).path("moduleId").asText());
        assertEquals(2, metrics.get(0).path("count").asLong());
        assertEquals(0, metrics.get(0).path("errorCount").asLong());
    }
}
