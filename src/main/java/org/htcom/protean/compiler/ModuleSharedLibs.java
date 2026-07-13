/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.compiler;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Registry of module parent-tier <b>generations</b> (see {@link Generation}). Each generation is an immutable snapshot
 * of a shared-lib jar set plus the URLClassLoader (parent = platform CL)
 * built from it. A module binds to the {@link #currentGeneration() current} generation when it (re)compiles; a change
 * to the jar set publishes a new generation, and modules move onto it only by redeploying — that is what keeps a live
 * shared-lib upgrade zero-downtime (the JVM cannot re-parent a live ClassLoader).
 *
 * <p>{@code gen0} is the boot-time scan of {@code protean.module.shared-lib-dir}: the fully backward-compatible path
 * for dropping in drivers/libraries without rebuilding the app. It carries the previous application-lifetime semantics
 * and is therefore <b>pinned</b> (never closed), which also preserves the original reason the shared lib was a
 * singleton — avoiding the JDBC {@code DriverManager} hard leak. When {@code shared-lib-dir} is unset, {@code gen0} is
 * empty (module parent = platform CL, no classpath suffix). Later generations (the runtime lib-store / put-jar surface)
 * are a follow-up step; the registry API is shaped for them now.
 *
 * <p>Reference counting: {@link #retain}/{@link #release} track which modules are bound to each generation. gen0 is
 * pinned, so it is never closed; a future non-pinned generation whose reference count reaches zero becomes eligible
 * for leak-safe close (driver deregister etc. — a follow-up step).
 *
 * <p>Note: classes the platform itself uses (e.g. the driver for admin provisioning connections) must be on the app
 * classpath — the app CL cannot see a generation's child CL.
 */
@Component
public class ModuleSharedLibs implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ModuleSharedLibs.class);

    /** All generations by id. gen0 is always present. */
    private final Map<Long, Generation> generations = new ConcurrentHashMap<>();
    /** Generation id → the ids of the modules currently bound to it (reference count for leak-safe close). */
    private final Map<Long, Set<String>> refs = new ConcurrentHashMap<>();
    /** Generation ids that are pinned (never closed): gen0 (app-lifetime) plus any whose close cleanup failed. */
    private final Set<Long> pinned = ConcurrentHashMap.newKeySet();
    /** The generation new module (re)compiles bind to. Advances when the lib-store publishes a new generation. */
    private volatile Generation current;
    /** Source of monotonically increasing generation ids. gen0 is {@link Generation#GEN0}; publishes start at 1. */
    private final AtomicLong nextGenId = new AtomicLong(Generation.GEN0);
    private final Object lock = new Object();

    @Autowired
    public ModuleSharedLibs(ProteanProperties properties) {
        this(buildGen0(properties.getModule().getSharedLibDir()));
    }

    private ModuleSharedLibs(Generation gen0) {
        register(gen0);
        pinned.add(gen0.id());   // gen0 keeps the previous application-lifetime semantics (avoids the JDBC leak)
        this.current = gen0;
    }

    /** For tests / the standalone {@link RuntimeCompiler}: a registry whose gen0 carries no shared-lib jars. */
    public static ModuleSharedLibs standalone() {
        return new ModuleSharedLibs(Generation.standalone());
    }

    /** The generation a module (re)compiles bind to right now. */
    public Generation currentGeneration() {
        return current;
    }

    /** The generation with this id, or empty if it is unknown (e.g. already closed and evicted). */
    public Optional<Generation> generation(long id) {
        return Optional.ofNullable(generations.get(id));
    }

    /**
     * Publishes a new generation built from {@code jars} (the current active shared-lib set — seed overlaid with the
     * live lib-store), registers it, and advances the {@link #currentGeneration() current} pointer to it. Modules that
     * (re)compile from now on bind to it; modules already bound to an earlier generation keep serving on theirs until
     * they redeploy (lazy default → zero-downtime). Returns the new generation. A runtime publish happens after
     * boot, when no reconcile file-manager pool is open, so it cannot race the reconcile classpath scan.
     */
    public Generation publishGeneration(List<Path> jars) {
        synchronized (lock) {
            Generation gen = buildGeneration(nextGenId.incrementAndGet(), jars);
            register(gen);
            this.current = gen;
            log.info("published shared-lib generation {} ({} jars) → now the current parent tier", gen.id(), jars.size());
            return gen;
        }
    }

    /** Records that {@code moduleId} is bound to generation {@code genId} (idempotent per module). */
    public void retain(long genId, String moduleId) {
        synchronized (lock) {
            refs.computeIfAbsent(genId, k -> ConcurrentHashMap.newKeySet()).add(moduleId);
        }
    }

    /**
     * Records that {@code moduleId} is no longer bound to generation {@code genId}. When the last module leaves a
     * non-pinned generation it becomes eligible for close (leak-safe teardown is a follow-up step; gen0 is pinned and
     * never closed here).
     */
    public void release(long genId, String moduleId) {
        synchronized (lock) {
            Set<String> holders = refs.get(genId);
            if (holders != null) {
                holders.remove(moduleId);
            }
        }
    }

    /** The number of modules currently bound to a generation (for observability/tests). */
    public int referenceCount(long genId) {
        Set<String> holders = refs.get(genId);
        return holders == null ? 0 : holders.size();
    }

    /** Whether a generation is pinned (never closed): gen0, or one whose close cleanup could not fully release it. */
    public boolean isPinned(long genId) {
        return pinned.contains(genId);
    }

    /**
     * Closes every generation that is now safe to unload — not {@link #currentGeneration() current}, not pinned, and
     * with zero bound modules (leak-safe unload). Must be called at a <b>safe point</b> (after the
     * container swaps/undeploys that dropped the references have committed), not from inside a compile: the reference
     * count is decremented at compile time, ahead of the physical swap, so closing eagerly would tear down a
     * generation whose old instance is still serving. Closing deregisters the JDBC drivers the generation's CL loaded
     * (the original reason the shared lib was a singleton) and closes the CL; if cleanup fails the generation is
     * pinned and the failure is logged (never a silent leak). Returns the number of generations closed.
     */
    public int closeUnreferenced() {
        int closed = 0;
        synchronized (lock) {
            long currentId = current.id();
            // Copy the ids first — closeGeneration mutates the generations map.
            for (Long id : List.copyOf(generations.keySet())) {
                if (id == currentId || pinned.contains(id) || referenceCount(id) > 0) {
                    continue;
                }
                if (closeGeneration(generations.get(id))) {
                    closed++;
                }
            }
        }
        return closed;
    }

    /**
     * The ids of all live (loaded) generations — current, pinned, and still-referenced. On-disk artifacts backing an
     * id <b>not</b> in this set are no longer used by any generation classloader and can be reclaimed. The worker-side
     * receiver uses this, after a {@link #closeUnreferenced()} sweep, to delete the pushed-jar directories of
     * generations it has superseded.
     */
    public Set<Long> liveGenerationIds() {
        synchronized (lock) {
            return Set.copyOf(generations.keySet());
        }
    }

    /** Cleans up and unloads one generation. Returns true if closed; false if it had to be pinned (cleanup failed). */
    private boolean closeGeneration(Generation gen) {
        URLClassLoader cl = gen.ownClassLoader();
        try {
            int deregistered = deregisterDrivers(cl);
            if (cl != null) {
                cl.close();
            }
            generations.remove(gen.id());
            refs.remove(gen.id());
            log.info("closed shared-lib generation {} (0 references) — deregistered {} JDBC driver(s), CL released",
                    gen.id(), deregistered);
            return true;
        } catch (IOException | RuntimeException e) {
            // Could not fully release → pin it (keep it loaded) and surface loudly. Never a silent leak.
            pinned.add(gen.id());
            log.warn("could not cleanly unload shared-lib generation {} — pinning it (leaked until restart). cause: {}",
                    gen.id(), e.toString());
            return false;
        }
    }

    /**
     * Deregisters the JDBC drivers loaded by {@code cl} from the shared {@link DriverManager}. {@link DriverManager}
     * applies a caller-classloader filter — its {@code getDrivers()}/{@code deregisterDriver} only see and remove
     * drivers loadable from the <b>caller's</b> classloader — so a call from here (the platform CL) could neither see
     * nor remove a driver a child generation CL loaded. The Tomcat fix: define a tiny cleanup helper in a child of the
     * generation CL and invoke it there, so the caller CL is the generation's and the filter passes. Returns the count.
     * Package-private so it can be verified directly (the same caller-CL filter blocks a test from observing via
     * {@link java.sql.DriverManager#getDrivers()}).
     */
    static int deregisterDrivers(ClassLoader cl) {
        if (cl == null) {
            return 0;
        }
        byte[] bytecode;
        try (InputStream in = ModuleSharedLibs.class.getResourceAsStream("SharedLibJdbcCleanup.class")) {
            if (in == null) {
                log.warn("shared-lib JDBC cleanup helper bytecode not found — cannot deregister generation drivers");
                return 0;
            }
            bytecode = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read JDBC cleanup helper bytecode", e);
        }
        try {
            // Child of the generation CL: it sees the generation's driver classes (parent delegation) and becomes the
            // DriverManager caller when the helper runs, so the caller-CL filter allows the deregistration.
            Class<?> helperClass = new HelperLoader(cl).defineHelper(bytecode);
            @SuppressWarnings("unchecked")
            ToIntFunction<ClassLoader> helper =
                    (ToIntFunction<ClassLoader>) helperClass.getDeclaredConstructor().newInstance();
            return helper.applyAsInt(cl);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to run shared-lib JDBC cleanup helper", e);
        }
    }

    /** Defines {@link SharedLibJdbcCleanup} as a child of a generation CL (a subclass may call protected defineClass). */
    private static final class HelperLoader extends ClassLoader {
        HelperLoader(ClassLoader generationCl) {
            super(generationCl);
        }

        Class<?> defineHelper(byte[] bytecode) {
            return defineClass("org.htcom.protean.compiler.SharedLibJdbcCleanup", bytecode, 0, bytecode.length);
        }
    }

    private void register(Generation gen) {
        generations.put(gen.id(), gen);
        refs.computeIfAbsent(gen.id(), k -> ConcurrentHashMap.newKeySet());
    }

    /** Builds gen0 from the {@code shared-lib-dir} (empty generation when unset/blank). */
    private static Generation buildGen0(String dir) {
        if (dir == null || dir.isBlank()) {
            return buildGeneration(Generation.GEN0, List.of());
        }
        Path libDir = Path.of(dir);
        if (!Files.isDirectory(libDir)) {
            throw new IllegalStateException("protean.module.shared-lib-dir is not a directory: " + libDir);
        }
        List<Path> jars = listJars(libDir);
        log.info("shared lib directory active: {} ({} jars) → seeded gen0", libDir, jars.size());
        return buildGeneration(Generation.GEN0, jars);
    }

    /**
     * Builds a generation from a jar set: an isolated {@code URLClassLoader} (parent = platform CL), the compile
     * classpath suffix, and the content-hash index. An empty set yields the empty generation (parent = platform CL,
     * no own CL to close). Jars are sorted for a deterministic classpath; callers dedup by name beforehand so a jar
     * name never appears twice within one generation.
     */
    private static Generation buildGeneration(long id, List<Path> jars) {
        ClassLoader platform = ModuleSharedLibs.class.getClassLoader();
        if (jars.isEmpty()) {
            return new Generation(id, platform, "", Map.of(), null);
        }
        List<Path> sorted = jars.stream().sorted().toList();
        URL[] urls = sorted.stream().map(ModuleSharedLibs::toUrl).toArray(URL[]::new);
        URLClassLoader cl = new URLClassLoader("protean-shared-lib-gen" + id, urls, platform);
        String suffix = File.pathSeparator + sorted.stream().map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator));
        // Index the jars (normalized path → {name, sha256}). The compiler uses this to attribute the shared-lib jars a
        // module's compile actually opened, keyed by content hash.
        Map<String, UsedSharedLib> index = new LinkedHashMap<>();
        for (Path jar : sorted) {
            index.put(normalize(jar), new UsedSharedLib(jar.getFileName().toString(), sha256(jar)));
        }
        return new Generation(id, cl, suffix, index, cl);
    }

    /** Normalizes a jar path to a stable absolute-path string used as the shared-lib index key. */
    static String normalize(Path jar) {
        return jar.toAbsolutePath().normalize().toString();
    }

    /** Lowercase-hex SHA-256 of a jar's content (computed once when the generation is built). */
    private static String sha256(Path jar) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            try (InputStream in = Files.newInputStream(jar)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            return hex(md.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new UncheckedIOException("failed to hash shared lib jar: " + jar,
                    e instanceof IOException io ? io : new IOException(e));
        }
    }

    /** Lowercase-hex SHA-256 of the given bytes — the shared-lib content hash (put-jar idempotent bundle key). */
    public static String sha256Hex(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not provide SHA-256", e);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static List<Path> listJars(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return new ArrayList<>(s.filter(p -> p.toString().endsWith(".jar")).sorted().toList());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to scan shared lib directory: " + dir, e);
        }
    }

    private static URL toUrl(Path p) {
        try {
            return p.toUri().toURL();
        } catch (Exception e) {
            throw new IllegalStateException("failed to convert jar to URL: " + p, e);
        }
    }

    @Override
    public void close() throws IOException {
        for (Generation gen : generations.values()) {
            URLClassLoader cl = gen.ownClassLoader();
            if (cl != null) {
                cl.close();
            }
        }
    }
}
