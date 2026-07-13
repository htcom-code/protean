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
import org.htcom.protean.compiler.ModuleClassLoader;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.mcp.McpCallContext;
import org.htcom.protean.mcp.McpDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end for the put-jar transmission surface: the REST multipart endpoint and the four MCP tools,
 * plus the live effect — a module compiled after an upload actually resolves the uploaded jar (proving the new
 * generation is the live parent tier). MCP tool outputs are checked against their declared {@code outputSchema}.
 */
@SpringBootTest(properties = "protean.mcp.enabled=true")
@AutoConfigureMockMvc
class SharedLibPutJarTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        // A clean, isolated store dir so boot starts at gen0 and assertions are deterministic.
        Path dir = Files.createTempDirectory("protean-put-jar-test");
        registry.add("protean.module.shared-lib-store-dir", dir::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired McpDispatcher dispatcher;
    @Autowired RuntimeCompiler compiler;

    /** Builds a real jar carrying one class {@code pkg.Cls} with {@code public int v(){return value;}}. */
    private static byte[] jarWith(String pkg, String cls, int value) throws Exception {
        Path base = Files.createTempDirectory("protean-lib-fixture");
        Path src = base.resolve(cls + ".java");
        Files.writeString(src, "package " + pkg + "; public class " + cls
                + " { public int v() { return " + value + "; } }");
        Path out = Files.createDirectories(base.resolve("classes"));
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", out.toString(), src.toString());
        if (rc != 0) {
            throw new IllegalStateException("fixture compile failed");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(bos)) {
            String entry = pkg.replace('.', '/') + "/" + cls + ".class";
            jos.putNextEntry(new JarEntry(entry));
            jos.write(Files.readAllBytes(out.resolve(entry)));
            jos.closeEntry();
        }
        return bos.toByteArray();
    }

    @Test
    void rest_multipart_upload_publishes_a_generation_a_module_can_use() throws Exception {
        byte[] jar = jarWith("ext.live", "Widget", 99);
        MockMultipartFile file = new MockMultipartFile("file", "widget.jar", "application/java-archive", jar);

        mockMvc.perform(multipart("/platform/shared-libs").file(file)
                        .param("name", "widget").param("version", "1.0.0"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.generation").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.libs[?(@.name=='widget')].version").value("1.0.0"));

        // The live proof: a module compiled now resolves ext.live.Widget from the uploaded jar (new generation is
        // on the compile classpath and the runtime parent CL).
        ModuleClassLoader loader = compiler.compileAll(Map.of("runtime.live.Uses", """
                package runtime.live;
                import ext.live.Widget;
                public class Uses { public int go() { return new Widget().v(); } }
                """), Map.of(), "put-jar-user");
        Class<?> uses = loader.loadClass("runtime.live.Uses");
        Object result = uses.getMethod("go").invoke(uses.getDeclaredConstructor().newInstance());
        assertEquals(99, result, "the module resolves the class from the uploaded shared-lib jar");
        assertTrue(compiler.boundGeneration("put-jar-user").orElseThrow() >= 1,
                "the module is bound to the published generation, not gen0");

        mockMvc.perform(get("/platform/shared-libs/widget")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sha256").isNotEmpty());
        mockMvc.perform(get("/platform/shared-libs/nope")).andExpect(status().isNotFound());
    }

    @Test
    void mcp_tools_deploy_list_get_remove_with_schema_conformance() throws Exception {
        byte[] jar = jarWith("ext.mcp", "Gizmo", 7);
        ObjectNode args = mapper.createObjectNode();
        args.put("name", "gizmo").put("version", "2.1.0")
                .put("bytesBase64", Base64.getEncoder().encodeToString(jar));

        JsonNode deploy = callTool("protean.deploy_shared_lib", args);
        conforms("protean.deploy_shared_lib", deploy);

        JsonNode list = callTool("protean.list_shared_libs", mapper.createObjectNode());
        conforms("protean.list_shared_libs", list);
        assertTrue(list.path("structuredContent").path("libs").toString().contains("gizmo"));

        JsonNode get = callTool("protean.get_shared_lib", mapper.createObjectNode().put("name", "gizmo"));
        conforms("protean.get_shared_lib", get);
        assertEquals("2.1.0", get.path("structuredContent").path("version").asText());
        assertEquals(sha256(jar), get.path("structuredContent").path("sha256").asText());

        JsonNode remove = callTool("protean.remove_shared_lib", mapper.createObjectNode().put("name", "gizmo"));
        conforms("protean.remove_shared_lib", remove);

        JsonNode gone = callTool("protean.get_shared_lib", mapper.createObjectNode().put("name", "gizmo"));
        assertTrue(gone.path("isError").asBoolean(), "get on a removed lib is an error");
    }

    private static String sha256(byte[] bytes) {
        return org.htcom.protean.compiler.ModuleSharedLibs.sha256Hex(bytes);
    }

    private JsonNode callTool(String name, ObjectNode arguments) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 2);
        req.put("method", "tools/call");
        req.set("params", params);
        return dispatcher.dispatch(req, McpCallContext.anonymous()).path("result");
    }

    private JsonNode schemaOf(String name) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/list");
        for (JsonNode t : dispatcher.dispatch(req, McpCallContext.anonymous()).path("result").path("tools")) {
            if (name.equals(t.path("name").asText())) {
                return t.path("outputSchema");
            }
        }
        return mapper.missingNode();
    }

    private void conforms(String name, JsonNode result) {
        assertTrue(!result.path("isError").asBoolean(false), name + " errored: " + result);
        SchemaConformance.assertConforms(schemaOf(name), result.path("structuredContent"), name);
    }
}
