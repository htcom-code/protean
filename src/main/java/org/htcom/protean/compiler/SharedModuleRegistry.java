/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.compiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;

/**
 * Registry of <b>library-module generations</b> — the typed-code-sharing half of the live parent tier. It is the
 * sibling of {@link ModuleSharedLibs}: where that registry holds the
 * single linear chain of shared-lib jar generations, this one holds <b>per-library independent</b> generations
 * (design decision Y), keyed by library id, each with its own generation lineage, reference count, and leak-safe
 * close. A library module publishes its compiled {@code exports} here (as a {@link LibraryGeneration}); dependents
 * that {@code use} it {@linkplain #resolveParentTier resolve} a {@link ParentTier} that layers those libraries onto
 * the current jar generation.
 *
 * <p>Each generation materializes the compiled bytes to a temp jar (reused for both the dependent compile classpath
 * and the runtime defining CL) whose parent is the current jar generation's CL — so shared-lib and app types keep a
 * single identity across the library boundary. Superseded generations are closed (CL released, JDBC drivers
 * deregistered via {@link ModuleSharedLibs#deregisterDrivers}, temp jar deleted) once no dependent references them.
 *
 * <p>Present in <b>all</b> profiles, including {@code worker}: a worker is itself a protean app, so when the main
 * pushes a worker dependent's {@code uses} closure (library descriptors → sources) to it, the worker compiles and
 * publishes those libraries into its own registry and links its dependent against them exactly as an in-process
 * deploy would (typed sharing). The main-only eager-propagation beans
 * ({@code SharedModuleInvalidator}, {@code SharedModuleUsageIndex}) stay {@code @Profile("!worker")} — the main drives
 * worker rebinds.
 */
@Component
public class SharedModuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(SharedModuleRegistry.class);

    private final ModuleSharedLibs sharedLibs;
    private final ApplicationEventPublisher events;

    /** library id → its current (latest published) generation. */
    private final Map<String, LibraryGeneration> current = new ConcurrentHashMap<>();
    /** Superseded/retired generations still awaiting close (kept until their reference count reaches zero). */
    private final Map<Long, LibraryGeneration> retiring = new ConcurrentHashMap<>();
    /** library-generation id → the ids of the dependent modules currently bound to it (reference count). */
    private final Map<Long, Set<String>> refs = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong();
    private final Object lock = new Object();
    /** Managed directory holding the generation temp jars (deleted individually on close). */
    private final Path tempDir;

    public SharedModuleRegistry(ModuleSharedLibs sharedLibs, ApplicationEventPublisher events) {
        this.sharedLibs = sharedLibs;
        this.events = events;
        try {
            this.tempDir = Files.createTempDirectory("protean-shared-module");
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create shared-module temp dir", e);
        }
    }

    /**
     * Publishes {@code compiledBytes} as a new generation of library {@code libraryId} exposing {@code exports},
     * making it the current generation. Writes a temp jar, builds a runtime {@code URLClassLoader} over it whose
     * parent is the current jar generation's CL, and supersedes the previous generation (kept alive until its
     * dependents release it). Idempotent: republishing identical bytes for the same library reuses the current
     * generation (same content hash) rather than minting a new id.
     */
    public LibraryGeneration publish(String libraryId, List<String> exports, Map<String, byte[]> compiledBytes,
                                     ParentTier ownTier) {
        validateExports(libraryId, exports);
        String sha = hashClasses(compiledBytes);
        List<String> exportedClasses = compiledBytes.keySet().stream()
                .filter(fqcn -> inExportedPackage(fqcn, exports))
                .sorted()
                .toList();
        LibraryGeneration previous;
        LibraryGeneration gen;
        synchronized (lock) {
            LibraryGeneration existing = current.get(libraryId);
            if (existing != null && existing.sha256().equals(sha) && existing.exports().equals(List.copyOf(exports))) {
                return existing;   // identical bytes + exports → no new generation, no event (idempotent)
            }
            long id = nextId.incrementAndGet();
            Path jar = writeJar(libraryId, id, compiledBytes);
            // Parent = the library's OWN parent tier: the jar generation CL directly, or a multiplexer over the
            // libraries this library itself uses (library→library DAG) — so its classes resolve their used libraries'
            // types at runtime, not just at compile time.
            URLClassLoader cl = new URLClassLoader(
                    "protean-library-" + libraryId + "-gen" + id,
                    new URL[]{toUrl(jar)},
                    ownTier.moduleParent());
            gen = new LibraryGeneration(id, libraryId, jar, cl, exports, exportedClasses, sha);
            refs.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
            previous = existing;
            if (existing != null) {
                retiring.put(existing.id(), existing);   // supersede: close once its dependents move off it
            }
            current.put(libraryId, gen);
            log.info("published library generation {} for '{}' (exports={}, {} classes)",
                    id, libraryId, exports, compiledBytes.size());
        }
        // Fire outside the lock so an eager-invalidation listener (which rebinds dependents → calls back here) cannot
        // deadlock. previous == null (first publish) means no dependent was on an older generation to migrate.
        events.publishEvent(new SharedModuleGenerationPublishedEvent(libraryId, previous, gen));
        return gen;
    }

    /**
     * Resolves the parent tier for a module that {@code uses} the given library ids: the current jar generation plus
     * each used library's current generation. Rejects a use of a library that is not currently published, and a pair
     * of used libraries that export the same package (an ambiguous single-identity route). The empty case returns the
     * plain jar-generation tier (backward compatible).
     */
    public ParentTier resolveParentTier(List<String> uses) {
        Generation jarGen = sharedLibs.currentGeneration();
        if (uses == null || uses.isEmpty()) {
            return ParentTier.of(jarGen);
        }
        List<LibraryGeneration> libs = new ArrayList<>(uses.size());
        Set<String> seenPackages = new LinkedHashSet<>();
        for (String libId : uses) {
            LibraryGeneration gen = current.get(libId);
            if (gen == null) {
                throw new IllegalStateException("module uses library '" + libId
                        + "', which is not an active library module (deploy it first)");
            }
            for (String pkg : gen.exports()) {
                if (!seenPackages.add(pkg)) {
                    throw new IllegalStateException("libraries used together export the same package '" + pkg
                            + "' — ambiguous shared type route");
                }
            }
            libs.add(gen);
        }
        return new ParentTier(jarGen, libs);
    }

    /** The current generation of a library, or empty if none is published (observability / tests). */
    public Optional<LibraryGeneration> currentGeneration(String libraryId) {
        return Optional.ofNullable(current.get(libraryId));
    }

    /** The runtime ClassLoader defining a library's current generation, or null if none (observability / tests). */
    public ClassLoader runtimeClassLoader(String libraryId) {
        LibraryGeneration gen = current.get(libraryId);
        return gen == null ? null : gen.classLoader();
    }

    /**
     * Whether moving a dependent from {@code previous} to {@code current} is binary-compatible — i.e. every public
     * member (constructor/method/field) the dependent's already-compiled bytecode could reference still exists with an
     * identical signature. This is the Plan A1 gate: when true, a dependent can be retargeted onto
     * the new generation <b>without recompiling</b> (its old bytecode links against the new library CL); when false, an
     * exported API surface changed and the dependent must be recompiled (Plan A2). Conservative: any class the current
     * generation drops, or any load/reflection failure, is treated as incompatible (fall back to recompile). Additive
     * changes (new classes/members) stay compatible.
     */
    public boolean isApiSuperset(LibraryGeneration previous, LibraryGeneration current) {
        if (previous == null) {
            return true;   // nothing to migrate from
        }
        try {
            for (String className : previous.exportedClasses()) {
                if (!current.exportedClasses().contains(className)) {
                    return false;   // an exported class was removed → not a superset
                }
                Set<String> prevApi = publicApi(previous.classLoader().loadClass(className));
                Set<String> currApi = publicApi(current.classLoader().loadClass(className));
                if (!currApi.containsAll(prevApi)) {
                    return false;   // a public member was removed or its signature changed
                }
            }
            return true;
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            log.debug("binary-compat check for library '{}' fell back to recompile: {}", current.libraryId(), e.toString());
            return false;
        }
    }

    /** The public/protected member signatures of a class (constructors, methods, fields) — its linkable API surface. */
    private static Set<String> publicApi(Class<?> c) {
        Set<String> api = new TreeSet<>();
        for (Constructor<?> ctor : c.getDeclaredConstructors()) {
            if (isApiVisible(ctor.getModifiers())) {
                api.add("ctor" + descriptor(ctor.getParameterTypes()));
            }
        }
        for (Method m : c.getDeclaredMethods()) {
            if (isApiVisible(m.getModifiers())) {
                api.add("m:" + m.getName() + descriptor(m.getParameterTypes()) + ":" + m.getReturnType().getName());
            }
        }
        for (Field f : c.getDeclaredFields()) {
            if (isApiVisible(f.getModifiers())) {
                api.add("f:" + f.getName() + ":" + f.getType().getName());
            }
        }
        return api;
    }

    private static boolean isApiVisible(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static String descriptor(Class<?>[] params) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params) {
            sb.append(p.getName()).append(',');
        }
        return sb.append(')').toString();
    }

    /** Whether an FQCN's package is one of the exported packages (exact package match, not prefix). */
    private static boolean inExportedPackage(String fqcn, List<String> exports) {
        int dot = fqcn.lastIndexOf('.');
        String pkg = dot < 0 ? "" : fqcn.substring(0, dot);
        return exports.contains(pkg);
    }

    /** Package prefixes a library must never export — the delegation multiplexer would shadow core/framework types. */
    private static final List<String> RESERVED_PREFIXES =
            List.of("java.", "javax.", "jakarta.", "org.springframework.", "org.htcom.protean.");

    /**
     * Rejects an {@code exports} declaration that would shadow types the parent tier already provides. Because the
     * multiplexer routes an exported package to the library CL <b>ahead</b> of parent-first delegation, a library that
     * exported a core/framework package (or a package a shared-lib jar supplies) would hide the real types from its
     * dependents — a silent, dangerous override. Reserved core prefixes and any package present in the current jar
     * generation are refused up front with a clear error.
     */
    private void validateExports(String libraryId, List<String> exports) {
        for (String pkg : exports) {
            for (String reserved : RESERVED_PREFIXES) {
                if ((pkg + ".").startsWith(reserved)) {
                    throw new IllegalStateException("library '" + libraryId + "' cannot export the reserved package '"
                            + pkg + "' (it would shadow core/framework types for its dependents)");
                }
            }
        }
        Set<String> jarPackages = jarGenerationPackages();
        for (String pkg : exports) {
            if (jarPackages.contains(pkg)) {
                throw new IllegalStateException("library '" + libraryId + "' cannot export package '" + pkg
                        + "' — a shared-lib jar already provides it (the export would shadow the jar's classes)");
            }
        }
    }

    /** The set of package names provided by the current jar generation's shared-lib jars (for the collision guard). */
    private Set<String> jarGenerationPackages() {
        Set<String> packages = new HashSet<>();
        for (String jarPath : sharedLibs.currentGeneration().sharedLibIndex().keySet()) {
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath)) {
                jar.stream().forEach(entry -> {
                    String name = entry.getName();
                    int slash = name.lastIndexOf('/');
                    if (name.endsWith(".class") && slash > 0) {
                        packages.add(name.substring(0, slash).replace('/', '.'));
                    }
                });
            } catch (IOException e) {
                log.debug("could not scan shared-lib jar {} for the export-collision guard (skipped): {}",
                        jarPath, e.toString());
            }
        }
        return packages;
    }

    /** Records that {@code moduleId} is bound to library generation {@code genId} (idempotent per module). */
    public void retain(long genId, String moduleId) {
        synchronized (lock) {
            refs.computeIfAbsent(genId, k -> ConcurrentHashMap.newKeySet()).add(moduleId);
        }
    }

    /** Records that {@code moduleId} is no longer bound to library generation {@code genId}. */
    public void release(long genId, String moduleId) {
        synchronized (lock) {
            Set<String> holders = refs.get(genId);
            if (holders != null) {
                holders.remove(moduleId);
            }
        }
    }

    /** The number of dependent modules currently bound to a library generation (observability / tests). */
    public int referenceCount(long genId) {
        Set<String> holders = refs.get(genId);
        return holders == null ? 0 : holders.size();
    }

    /**
     * Retires a library module entirely (uninstall): its current generation is moved to the superseded set so it is
     * closed once its dependents release it. No-op if the library was never published.
     */
    public void retire(String libraryId) {
        synchronized (lock) {
            LibraryGeneration gen = current.remove(libraryId);
            if (gen != null) {
                retiring.put(gen.id(), gen);
            }
        }
    }

    /**
     * Closes every superseded/retired library generation that no dependent references anymore — CL released, JDBC
     * drivers deregistered (reusing the Tomcat-pattern helper), temp jar deleted. Current generations are never
     * closed here. Must be called at a safe point (after dependent rebinds/undeploys committed). Returns the count.
     */
    public int closeUnreferenced() {
        int closed = 0;
        synchronized (lock) {
            for (Long id : List.copyOf(retiring.keySet())) {
                if (referenceCount(id) > 0) {
                    continue;
                }
                if (closeGeneration(retiring.get(id))) {
                    closed++;
                }
            }
        }
        return closed;
    }

    private boolean closeGeneration(LibraryGeneration gen) {
        try {
            int deregistered = ModuleSharedLibs.deregisterDrivers(gen.ownClassLoader());
            gen.ownClassLoader().close();
            Files.deleteIfExists(gen.jar());
            retiring.remove(gen.id());
            refs.remove(gen.id());
            log.info("closed library generation {} ({}) — deregistered {} driver(s), CL + temp jar released",
                    gen.id(), gen.libraryId(), deregistered);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("could not cleanly close library generation {} ({}) — leaving it loaded. cause: {}",
                    gen.id(), gen.libraryId(), e.toString());
            return false;
        }
    }

    /** Writes the compiled classes to a temp jar (one {@code .class} entry per class), returning its path. */
    private Path writeJar(String libraryId, long id, Map<String, byte[]> classes) {
        Path jar = tempDir.resolve(libraryId + "-gen" + id + ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                byte[] bytes = e.getValue();
                JarEntry entry = new JarEntry(e.getKey().replace('.', '/') + ".class");
                // Fixed metadata (STORED with explicit size/CRC) keeps the jar deterministic — no wall-clock timestamps.
                entry.setTime(0L);
                entry.setMethod(JarEntry.STORED);
                entry.setSize(bytes.length);
                entry.setCompressedSize(bytes.length);
                CRC32 crc = new CRC32();
                crc.update(bytes);
                entry.setCrc(crc.getValue());
                jos.putNextEntry(entry);
                jos.write(bytes);
                jos.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write library jar for " + libraryId, e);
        }
        return jar;
    }

    /** Content hash over the compiled classes (sorted by FQCN) — stable identity independent of jar packaging. */
    private static String hashClasses(Map<String, byte[]> classes) {
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not provide SHA-256", e);
        }
        classes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    md.update(e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    md.update((byte) 0);
                    md.update(e.getValue());
                });
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static URL toUrl(Path p) {
        try {
            return p.toUri().toURL();
        } catch (Exception e) {
            throw new IllegalStateException("failed to convert library jar to URL: " + p, e);
        }
    }
}
