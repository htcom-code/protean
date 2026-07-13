/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.compiler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Module-specific ClassLoader. It defines only the compiled bytecode itself and delegates shared types
 * (Spring, platform SPI, etc.) to the parent (parent-first).
 *
 * <p>It serves and enumerates non-Java resources (mapper XML, etc.) and the <b>compiled {@code *.class} files
 * themselves</b> in memory. Classes are parent-first (identity), but <b>resources are owned-child-first</b>.
 * {@link #resourceIndex()} exposes all served paths so that {@code classpath*:} pattern scans can enumerate
 * mappers and entities.
 *
 * <p>{@link #replaceResources(Map)} allows resources to be <b>replaced in place</b> (live-reload): resources read
 * per request take effect immediately without rebuilding the context. Classes are immutable (cannot be replaced).
 */
public final class ModuleClassLoader extends ClassLoader {

    private final Map<String, byte[]> classes;   // FQCN -> bytecode (for findClass, immutable)
    /** Normalized path -> bytes. Resources ∪ compiled classes (*.class). Only the resource part is replaceable via live-reload (volatile). */
    private volatile Map<String, byte[]> served;
    private final String moduleId;
    private final URLStreamHandler resourceHandler;

    public ModuleClassLoader(Map<String, byte[]> classes, ClassLoader parent) {
        this(classes, Map.of(), "module", parent);
    }

    public ModuleClassLoader(Map<String, byte[]> classes, Map<String, byte[]> resources,
                             String moduleId, ClassLoader parent) {
        super(parent);
        this.classes = classes;
        this.moduleId = moduleId == null ? "module" : moduleId;
        this.served = buildServed(resources);
        this.resourceHandler = new InMemoryResourceHandler(this);
    }

    /** Serving map combining the resource map with each class's *.class path. */
    private Map<String, byte[]> buildServed(Map<String, byte[]> resources) {
        Map<String, byte[]> merged = new LinkedHashMap<>();
        if (resources != null) {
            merged.putAll(resources);
        }
        classes.forEach((fqcn, bytes) -> merged.put(fqcn.replace('.', '/') + ".class", bytes));
        return Collections.unmodifiableMap(merged);
    }

    /**
     * Replaces resources in place. Classes ({@code *.class}) are kept; only the resource part is swapped to a new map.
     * Afterward {@code getResourceAsStream} sees the new content (resources read per request = zero-reload).
     * In-flight reads see the old content.
     */
    public void replaceResources(Map<String, byte[]> newResources) {
        this.served = buildServed(newResources);
    }

    /** Class bytecode this loader defines (FQCN -> bytes). For static guardrail scanning (read-only). */
    public Map<String, byte[]> bytecode() {
        return Collections.unmodifiableMap(classes);
    }

    /** All served resource paths (resources + *.class). For classpath* pattern scanning (read-only, current snapshot). */
    public Set<String> resourceIndex() {
        return served.keySet();
    }

    /** Current served bytes (volatile read). Looked up live by the URL handler. */
    byte[] servedBytes(String key) {
        return served.get(key);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytecode = classes.get(name);
        if (bytecode == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, bytecode, 0, bytecode.length);
    }

    // ---- Resource serving (owned-child-first) ----

    @Override
    public URL getResource(String name) {
        String key = strip(name);
        if (served.containsKey(key)) {
            return resourceUrl(key);
        }
        return super.getResource(name);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        byte[] bytes = served.get(strip(name));
        if (bytes != null) {
            return new ByteArrayInputStream(bytes);
        }
        return super.getResourceAsStream(name);
    }

    @Override
    protected URL findResource(String name) {
        String key = strip(name);
        return served.containsKey(key) ? resourceUrl(key) : null;
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        String key = strip(name);
        if (served.containsKey(key)) {
            return Collections.enumeration(List.of(resourceUrl(key)));
        }
        return Collections.emptyEnumeration();
    }

    private static String strip(String name) {
        if (name == null) {
            return "";
        }
        String n = name;
        while (n.startsWith("/")) {
            n = n.substring(1);
        }
        return n;
    }

    /** protean-res URL for a served path. Returns null if not served. */
    URL resourceUrl(String key) {
        if (!served.containsKey(key)) {
            return null;
        }
        try {
            return new URL("protean-res", moduleId, -1, "/" + key, resourceHandler);
        } catch (Exception e) {
            throw new IllegalStateException("failed to create resource URL: " + key, e);
        }
    }

    /** URLStreamHandler that opens the in-memory served bytes (live lookup through the owning class loader). */
    private static final class InMemoryResourceHandler extends URLStreamHandler {
        private final ModuleClassLoader owner;

        InMemoryResourceHandler(ModuleClassLoader owner) {
            this.owner = owner;
        }

        @Override
        protected URLConnection openConnection(URL u) throws IOException {
            String path = u.getPath();
            String key = path.startsWith("/") ? path.substring(1) : path;
            byte[] bytes = owner.servedBytes(key);
            if (bytes == null) {
                throw new IOException("module resource not found: " + key);
            }
            return new URLConnection(u) {
                @Override
                public void connect() {
                    // no-op (in-memory)
                }

                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream(bytes);
                }

                @Override
                public long getContentLengthLong() {
                    return bytes.length;
                }
            };
        }
    }
}
