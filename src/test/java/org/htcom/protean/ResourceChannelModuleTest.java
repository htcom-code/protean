/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.ModuleClassLoader;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.error.ProteanException;
import org.htcom.protean.gate.ModuleSigning;
import org.htcom.protean.module.ModuleContainer;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModuleResource;
import org.htcom.protean.module.ResourcePaths;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.ref.WeakReference;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Resource channel. Verifies that a module can read the non-Java resources it ships through the
 * module ClassLoader, that the ClassLoader is reclaimed after unload, and that signature
 * canonicalization includes the resources.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResourceChannelModuleTest {

    @Autowired MockMvc mockMvc;
    @Autowired RuntimeCompiler compiler;
    @Autowired ModuleContainer container;

    static final String CONTROLLER = "runtime.res.ResController";
    static final String SRC = """
            package runtime.res;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            import java.io.InputStream;
            import java.nio.charset.StandardCharsets;
            @RestController
            public class ResController {
                @GetMapping("/res/text")
                public String text() throws Exception {
                    try (InputStream in = getClass().getClassLoader().getResourceAsStream("mapper/greeting.txt")) {
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
                @GetMapping("/res/bin")
                public String bin() throws Exception {
                    try (InputStream in = getClass().getClassLoader().getResourceAsStream("data/blob.bin")) {
                        byte[] b = in.readAllBytes();
                        return "len=" + b.length + ",first=" + (b[0] & 0xff);
                    }
                }
            }
            """;

    static final Map<String, ModuleResource> RESOURCES = Map.of(
            "mapper/greeting.txt", ModuleResource.text("hello-resource"),
            "data/blob.bin", ModuleResource.binary(new byte[]{(byte) 200, 1, 2, 3})
    );

    private ModuleClassLoader compileWithResources(String moduleId) {
        return compiler.compileAll(Map.of(CONTROLLER, SRC),
                ModuleResource.decodeAll(RESOURCES), moduleId);
    }

    @Test
    void text_and_binary_resources_served_from_module_classloader() throws Exception {
        ModuleClassLoader loader = compileWithResources("res-mod");
        container.deploy("res-mod", loader, List.of(CONTROLLER), CONTROLLER);

        mockMvc.perform(get("/res/text"))
                .andExpect(status().isOk())
                .andExpect(content().string("hello-resource"));
        mockMvc.perform(get("/res/bin"))
                .andExpect(status().isOk())
                .andExpect(content().string("len=4,first=200"));

        container.undeploy("res-mod");
        mockMvc.perform(get("/res/text")).andExpect(status().isNotFound());
    }

    @Test
    void module_classloader_reclaimable_after_undeploy() throws Exception {
        ModuleClassLoader loader = compileWithResources("res-mod-2");
        container.deploy("res-mod-2", loader, List.of(CONTROLLER), CONTROLLER);
        mockMvc.perform(get("/res/text")).andExpect(status().isOk());
        container.undeploy("res-mod-2");

        WeakReference<ClassLoader> ref = new WeakReference<>(loader);
        loader = null;

        boolean reclaimed = false;
        for (int i = 0; i < 8 && !reclaimed; i++) {
            applyMemoryPressure();
            System.gc();
            Thread.sleep(30);
            reclaimed = ref.get() == null;
        }
        assertTrue(reclaimed, "a module that ships resources must also have its ClassLoader reclaimed after undeploy");
    }

    @Test
    void signature_covers_resources() {
        KeyPair kp = ModuleSigning.generateKeyPair();
        ModuleDescriptor base = ModuleDescriptor.builder()
                .id("res-sig").version("1.0")
                .controllerFqcn(CONTROLLER).componentFqcns(List.of(CONTROLLER)).sources(Map.of(CONTROLLER, SRC))
                .resources(Map.of("mapper/x.xml", ModuleResource.text("<a/>")))
                .build();

        String sig = ModuleSigning.sign(base, kp.getPrivate());
        assertTrue(ModuleSigning.verify(base, sig, kp.getPublic()), "the original passes verification");

        ModuleDescriptor tampered = base.withResources(Map.of("mapper/x.xml", ModuleResource.text("<b/>")));
        assertFalse(ModuleSigning.verify(tampered, sig, kp.getPublic()),
                "tampering with a resource must fail signature verification (resources are included in canonicalization)");
    }

    @Test
    void resource_paths_reject_traversal_and_normalize_leading_slash() {
        assertEquals("mapper/x.xml", ResourcePaths.normalize("/mapper/x.xml"));
        // Input-validation failures now carry the stable INVALID_ARGUMENT code + the offending path (self-correction).
        ProteanException traversal = assertThrows(ProteanException.class, () -> ResourcePaths.normalize("../etc/passwd"));
        assertEquals(ErrorCode.INVALID_ARGUMENT, traversal.code());
        assertEquals("../etc/passwd", traversal.extensions().get("path"));
        assertThrows(ProteanException.class, () -> ResourcePaths.normalize("a/../../b"));
        assertThrows(ProteanException.class, () -> ResourcePaths.normalize("  "));
    }

    private static void applyMemoryPressure() {
        try {
            java.util.List<byte[]> ballast = new java.util.ArrayList<>();
            while (true) {
                ballast.add(new byte[8 * 1024 * 1024]);
            }
        } catch (OutOfMemoryError ignored) {
        }
    }
}
