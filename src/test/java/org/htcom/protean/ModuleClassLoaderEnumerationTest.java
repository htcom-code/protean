/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.ModuleClassLoader;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that ModuleClassLoader serves and enumerates both compiled {@code *.class} files and non-Java
 * resources, and exposes all paths via {@link ModuleClassLoader#resourceIndex()}
 * (the foundation for {@code classpath*:} pattern scanning).
 */
class ModuleClassLoaderEnumerationTest {

    private static final byte[] BAR = {1, 2, 3, 4};
    private static final byte[] BAZ = {5, 6};
    private static final byte[] XML = "<mapper/>".getBytes(StandardCharsets.UTF_8);

    private ModuleClassLoader loader() {
        Map<String, byte[]> classes = Map.of("com.foo.Bar", BAR, "com.foo.Baz", BAZ);
        Map<String, byte[]> resources = Map.of("mapper/X.xml", XML);
        return new ModuleClassLoader(classes, resources, "m", getClass().getClassLoader());
    }

    @Test
    void index_exposes_classes_and_resources_as_paths() {
        Set<String> index = loader().resourceIndex();
        assertTrue(index.contains("com/foo/Bar.class"), index.toString());
        assertTrue(index.contains("com/foo/Baz.class"), index.toString());
        assertTrue(index.contains("mapper/X.xml"), index.toString());
    }

    @Test
    void serves_class_bytes_as_resource() throws Exception {
        ModuleClassLoader cl = loader();
        URL url = cl.getResource("com/foo/Bar.class");
        assertNotNull(url, "a compiled class must be served as a *.class resource");
        try (InputStream in = url.openStream()) {
            assertArrayEquals(BAR, in.readAllBytes());
        }
        try (InputStream in = cl.getResourceAsStream("com/foo/Baz.class")) {
            assertArrayEquals(BAZ, in.readAllBytes());
        }
    }

    @Test
    void serves_and_enumerates_resource_files() throws Exception {
        ModuleClassLoader cl = loader();
        try (InputStream in = cl.getResourceAsStream("mapper/X.xml")) {
            assertArrayEquals(XML, in.readAllBytes());
        }
        List<URL> found = Collections.list(cl.getResources("mapper/X.xml"));
        assertEquals(1, found.stream().filter(u -> u.getProtocol().equals("protean-res")).count(),
                "getResources must enumerate module resources");
    }

    @Test
    void unknown_path_delegates_to_parent_and_is_absent() {
        assertNull(loader().getResource("nope/does-not-exist.txt"));
    }
}
