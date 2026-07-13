/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.compiler;

import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.error.ProteanException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.tools.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Compiles Java source strings inside the server and loads them with a dedicated ClassLoader.
 * No IDE required — uses only the JSR-199 {@link JavaCompiler}.
 */
@Component
public class RuntimeCompiler {

    private static final Logger log = LoggerFactory.getLogger(RuntimeCompiler.class);

    private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

    /** moduleId -> (last compiled sources, the resulting bytecode). Skips javac on redeploy when sources are unchanged. */
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    /** moduleId -> shared-lib jars the last compile of that module actually opened (observed, for precise invalidation). */
    private final ConcurrentHashMap<String, List<UsedSharedLib>> usedSharedLibs = new ConcurrentHashMap<>();
    /** moduleId -> the parent-tier generation id its live ClassLoader is currently bound to (runtime state). */
    private final ConcurrentHashMap<String, Long> boundGeneration = new ConcurrentHashMap<>();
    /** moduleId -> the library-generation ids (sorted) it is currently bound to via {@code uses} (shared-module). */
    private final ConcurrentHashMap<String, List<Long>> boundLibGenerations = new ConcurrentHashMap<>();
    /** Number of actual javac runs (for test observation). The fast-path (cache hit) does not increment it. */
    private final AtomicLong compilations = new AtomicLong();

    /**
     * Active reconcile file-manager pool, or {@code null} for the default per-call manager. Set only for the
     * duration of a boot reconcile parallel section (see {@link #openReconcilePool}); volatile because the
     * section's worker threads read it while the boot thread opens/closes it.
     */
    private volatile ReconcilePool reconcilePool;

    private record Cached(Map<String, String> sources, Map<String, byte[]> bytecode, String tierKey) {
    }

    /** Registry of parent-tier jar generations. A compile binds a module to a generation's parent CL + classpath. */
    private final ModuleSharedLibs sharedLibs;
    /** Registry of library-module generations (shared-module typed sharing), or {@code null} in standalone/worker. */
    private final SharedModuleRegistry sharedModules;

    /** For tests/standalone use — no shared lib (module parent = platform CL), no library sharing. */
    public RuntimeCompiler() {
        this(ModuleSharedLibs.standalone(), (SharedModuleRegistry) null);
    }

    /** For tests — a jar-generation registry but no library sharing. */
    public RuntimeCompiler(ModuleSharedLibs sharedLibs) {
        this(sharedLibs, (SharedModuleRegistry) null);
    }

    @Autowired
    public RuntimeCompiler(ModuleSharedLibs sharedLibs, ObjectProvider<SharedModuleRegistry> sharedModules) {
        this(sharedLibs, sharedModules.getIfAvailable());
    }

    private RuntimeCompiler(ModuleSharedLibs sharedLibs, SharedModuleRegistry sharedModules) {
        if (compiler == null) {
            throw new IllegalStateException("no system JavaCompiler — must run on a JDK, not a JRE");
        }
        this.sharedLibs = sharedLibs;
        this.sharedModules = sharedModules;
    }

    /** Compiles the source, loads it into a new dedicated ClassLoader, and returns a {@link CompiledModule}. */
    /** Single-source compile + load. Returns with mainClass populated. */
    public CompiledModule compile(String fqcn, String source) {
        ModuleClassLoader loader = compileAll(Map.of(fqcn, source));
        try {
            return new CompiledModule(loader, loader.loadClass(fqcn));
        } catch (ClassNotFoundException e) {
            throw new CompilationException("compiled but failed to load [" + fqcn + "]", e);
        }
    }

    /**
     * Compiles multiple sources into one module and returns them in a single {@link ModuleClassLoader}.
     * (A module = Controller + Service + ... managed as multiple classes under one ClassLoader unit.)
     */
    public ModuleClassLoader compileAll(Map<String, String> sources) {
        return compileAll(sources, Map.of(), "module");
    }

    /**
     * Compiles the sources and returns a ClassLoader that also carries non-Java resources (normalized path -> bytes).
     * Resources are not compiled; they are registered only in the loader's in-memory resource map.
     *
     * @param moduleId identifier ensuring uniqueness of resource URLs (protean-res://moduleId/..)
     */
    public ModuleClassLoader compileAll(Map<String, String> sources,
                                        Map<String, byte[]> resources, String moduleId) {
        return compileAll(sources, resources, moduleId, ParentTier.of(sharedLibs.currentGeneration()));
    }

    /**
     * Compiles the sources against a specific jar {@link Generation} and binds the module to it. Backward-compatible
     * shorthand for the {@link ParentTier} form with no library dependencies; used by the jar-generation rebind path.
     */
    public ModuleClassLoader compileAll(Map<String, String> sources, Map<String, byte[]> resources,
                                        String moduleId, Generation gen) {
        return compileAll(sources, resources, moduleId, ParentTier.of(gen));
    }

    /**
     * Compiles the sources against a full parent {@link ParentTier} (jar generation + any {@code uses} library
     * generations) and binds the module to it: the compile classpath and the resulting ClassLoader's parent both come
     * from {@code tier}, and the module's bindings (jar generation + library generations) are updated (releasing any
     * previous binding). The compile cache is keyed by the tier's {@link ParentTier#bindingKey() binding key}, so a
     * jar-generation change <i>or</i> any used-library republish forces a recompile against the new tier.
     */
    public ModuleClassLoader compileAll(Map<String, String> sources, Map<String, byte[]> resources,
                                        String moduleId, ParentTier tier) {
        Map<String, byte[]> bytecode;
        Cached cached = cache.get(moduleId);
        String tierKey = tier.bindingKey();
        if (cached != null && cached.sources().equals(sources) && cached.tierKey().equals(tierKey)) {
            // Resource-only fast-path: sources identical to the previous run AND the same parent tier → skip javac,
            // reuse the compiled result. Build a new loader with only the resources swapped to trigger re-parsing at
            // init time (ORM, etc.); the context rebuild happens at a higher level. The observed shared-lib usage
            // from the last real compile stays valid (same sources + same tier → same jars), so it is left in place.
            // A tier change deliberately misses the cache: bytecode compiled against one tier must not be reused under
            // a different parent tier (that is what makes rebind recompile).
            bytecode = cached.bytecode();
            log.debug("resource-only fast-path: skipping recompile of module '{}' (sources + parent tier unchanged)", moduleId);
        } else {
            CompileResult result = doCompile(sources, tier);
            bytecode = result.bytecode();
            compilations.incrementAndGet();
            cache.put(moduleId, new Cached(Map.copyOf(sources), bytecode, tierKey));
            if (!result.usedSharedLibs().isEmpty()) {
                usedSharedLibs.put(moduleId, result.usedSharedLibs());
            } else {
                usedSharedLibs.remove(moduleId);
            }
        }
        bindTier(moduleId, tier);
        // Parent = the tier's CL: the jar generation CL directly when there are no library deps, else a delegating
        // multiplexer routing exported packages to the owning library CLs (single type identity) — preserves shared
        // SPI type identity, resolves shared-lib drop-ins, and links typed shared code from used libraries.
        return new ModuleClassLoader(bytecode, resources, moduleId, tier.moduleParent());
    }

    /**
     * Compiles a <b>library module</b>'s sources against {@code tier} and returns the raw bytecode (no ClassLoader,
     * no route registration) so the caller can publish it as a {@link LibraryGeneration}. Binds the library to its own
     * parent tier (jar generation + any libraries it uses) and populates the compile cache under {@code moduleId} so a
     * subsequent gate/deploy hits the fast-path.
     */
    public Map<String, byte[]> compileLibrary(Map<String, String> sources, String moduleId, ParentTier tier) {
        Cached cached = cache.get(moduleId);
        String tierKey = tier.bindingKey();
        Map<String, byte[]> bytecode;
        if (cached != null && cached.sources().equals(sources) && cached.tierKey().equals(tierKey)) {
            bytecode = cached.bytecode();
        } else {
            CompileResult result = doCompile(sources, tier);
            bytecode = result.bytecode();
            compilations.incrementAndGet();
            cache.put(moduleId, new Cached(Map.copyOf(sources), bytecode, tierKey));
            if (!result.usedSharedLibs().isEmpty()) {
                usedSharedLibs.put(moduleId, result.usedSharedLibs());
            } else {
                usedSharedLibs.remove(moduleId);
            }
        }
        bindTier(moduleId, tier);
        return bytecode;
    }

    /**
     * Retargets an already-compiled module onto a new parent {@code tier} <b>without recompiling</b> (Plan A1):
     * reuses the module's cached bytecode, rebinds it to {@code tier} (releasing the old library/jar
     * generations, retaining the new), and returns a fresh ClassLoader whose parent is the new tier. Valid only when
     * the tier change is binary-compatible (the caller checks {@link SharedModuleRegistry#isApiSuperset}); the reused
     * bytecode links against the new library CL because every referenced symbol still exists. Throws if the module was
     * never compiled (nothing to reuse) — the caller then falls back to a recompile (Plan A2). No javac runs.
     */
    public ModuleClassLoader retarget(String moduleId, Map<String, byte[]> resources, ParentTier tier) {
        Cached cached = cache.get(moduleId);
        if (cached == null) {
            throw new IllegalStateException("cannot retarget uncompiled module (no cached bytecode): " + moduleId);
        }
        cache.put(moduleId, new Cached(cached.sources(), cached.bytecode(), tier.bindingKey()));
        bindTier(moduleId, tier);
        return new ModuleClassLoader(cached.bytecode(), resources, moduleId, tier.moduleParent());
    }

    /**
     * Compiles sources against {@code tier} into a throwaway ClassLoader <b>without touching the cache or any
     * generation binding</b> — for gate compiles (e.g. the test gate compiles main+test together) that must not
     * pollute a module's cache entry or retain generations that would then never be released.
     */
    public ModuleClassLoader compileEphemeral(Map<String, String> sources, ParentTier tier) {
        Map<String, byte[]> bytecode = doCompile(sources, tier).bytecode();
        compilations.incrementAndGet();
        return new ModuleClassLoader(bytecode, Map.of(), "module", tier.moduleParent());
    }

    /** Binds a module to a full parent tier: its jar generation plus every library generation it uses. */
    private void bindTier(String moduleId, ParentTier tier) {
        bindGeneration(moduleId, tier.jarGeneration());
        bindLibraries(moduleId, tier.libraries());
    }

    /**
     * Moves {@code moduleId}'s library-generation bindings to exactly {@code libs}, retaining newly used generations
     * and releasing ones it no longer uses in {@link SharedModuleRegistry}'s reference counts. No-op when there is no
     * shared-module registry (standalone/worker) or the module uses no libraries and had none before.
     */
    private void bindLibraries(String moduleId, List<LibraryGeneration> libs) {
        if (sharedModules == null) {
            return;
        }
        List<Long> newIds = libs.stream().map(LibraryGeneration::id).sorted().toList();
        boundLibGenerations.compute(moduleId, (id, prev) -> {
            List<Long> old = prev == null ? List.of() : prev;
            for (Long o : old) {
                if (!newIds.contains(o)) {
                    sharedModules.release(o, moduleId);
                }
            }
            for (Long n : newIds) {
                if (!old.contains(n)) {
                    sharedModules.retain(n, moduleId);
                }
            }
            return newIds.isEmpty() ? null : newIds;
        });
    }

    /**
     * Moves {@code moduleId}'s generation binding to {@code gen}, releasing its previous generation (if different) in
     * the registry's reference count. Atomic per module so concurrent recompiles of one module cannot double-count.
     */
    private void bindGeneration(String moduleId, Generation gen) {
        boundGeneration.compute(moduleId, (id, prev) -> {
            if (prev == null) {
                sharedLibs.retain(gen.id(), moduleId);
            } else if (prev != gen.id()) {
                sharedLibs.release(prev, moduleId);
                sharedLibs.retain(gen.id(), moduleId);
            }
            return gen.id();
        });
    }

    /** Compiles only the sources and returns the bytecode plus the shared-lib jars observed (resource-independent). */
    private CompileResult doCompile(Map<String, String> sources, ParentTier tier) {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        // During a boot reconcile section the classpath is identical and read-only for every module, so we
        // reuse this thread's file manager (with its already-scanned jar index) instead of scanning ~100
        // dependency jars again. Its listener is bound once at creation (null); per-compile diagnostics still
        // flow through the CompilationTask's own collector below. Steady-state single compiles use a per-call
        // manager, matching the previous behavior.
        // A module with library dependencies has an extra, per-module compile classpath (its temp jars), so it cannot
        // share the reconcile pool's manager (whose amortized jar scan assumes an identical, read-only classpath for
        // every module). Fall back to a per-call manager for those.
        ReconcilePool pool = tier.hasLibraries() ? null : reconcilePool;
        StandardJavaFileManager standard = pool != null
                ? pool.manager()
                : compiler.getStandardFileManager(diagnostics, null, null);

        // Observe which shared-lib jars this compile actually reads (§B) — but only for steady-state single
        // compiles (pool == null) so the hot reconcile path stays untouched, and only when the target generation
        // even carries jars. The touched set is filled as javac opens class files (see the recording wrapper below).
        Map<String, UsedSharedLib> sharedLibIndex = tier.jarGeneration().sharedLibIndex();
        boolean observe = pool == null && !sharedLibIndex.isEmpty();
        Set<String> touchedJars = observe ? ConcurrentHashMap.newKeySet() : null;

        Map<String, InMemoryClassFile> output = new HashMap<>();
        JavaFileManager fileManager = new ForwardingJavaFileManager<>(standard) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                       JavaFileObject.Kind kind, FileObject sibling) {
                InMemoryClassFile file = new InMemoryClassFile(className);
                output.put(className, file);
                return file;
            }

            @Override
            public Iterable<JavaFileObject> list(Location location, String packageName,
                                                 Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
                Iterable<JavaFileObject> files = super.list(location, packageName, kinds, recurse);
                if (!observe) {
                    return files;
                }
                // Wrap each candidate so we record the jar only when javac actually opens the class (precise-ish;
                // a package list alone would over-count). Unmappable entries are ignored (best-effort).
                List<JavaFileObject> wrapped = new ArrayList<>();
                for (JavaFileObject f : files) {
                    wrapped.add(new RecordingFileObject(f, touchedJars, sharedLibIndex));
                }
                return wrapped;
            }

            @Override
            public String inferBinaryName(Location location, JavaFileObject file) {
                // The standard manager only recognizes file objects it created, so unwrap our recording wrapper
                // before delegating (otherwise it throws IllegalArgumentException on the wrapper type).
                return super.inferBinaryName(location,
                        file instanceof RecordingFileObject r ? r.unwrap() : file);
            }
        };

        List<JavaFileObject> units = new ArrayList<>();
        sources.forEach((fqcn, src) -> units.add(new InMemorySource(fqcn, src)));

        // Pass the current JVM's classpath straight to the compiler so it sees dependency types like Spring.
        // -g: full debug info (line+vars+source) — needed for breakpoint line mapping and local variable name
        // lookup in Level 3 interactive debugging. Only makes class files slightly larger; no runtime impact.
        // -parameters: preserves method parameter names in the class — lets module controllers omit the name on
        // @RequestParam/@PathVariable (like an ordinary Spring controller). Without it, a runtime
        // "Name for argument ... not specified" error occurs.
        List<String> options = List.of(
                "-classpath", System.getProperty("java.class.path") + tier.compileClasspathSuffix(), "-g", "-parameters");

        JavaCompiler.CompilationTask task =
                compiler.getTask(null, fileManager, diagnostics, options, null, units);

        boolean ok = task.call();
        if (!ok) {
            // Structured diagnostics — so an agent can self-correct the source per file and line.
            List<Map<String, Object>> structured = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("kind", d.getKind().toString());
                if (d.getSource() != null) {
                    one.put("source", d.getSource().getName());
                }
                one.put("line", d.getLineNumber());
                one.put("message", d.getMessage(Locale.ROOT));
                structured.add(one);
            }
            String report = structured.stream()
                    .map(d -> d.get("kind") + " @" + d.get("line") + ": " + d.get("message"))
                    .collect(Collectors.joining("\n"));
            throw new CompilationException(sources.keySet() + ":\n" + report, structured);
        }

        Map<String, byte[]> bytecode = new HashMap<>();
        for (Map.Entry<String, InMemoryClassFile> e : output.entrySet()) {
            bytecode.put(e.getKey(), e.getValue().toByteArray());
        }

        List<UsedSharedLib> used = List.of();
        if (observe && !touchedJars.isEmpty()) {
            used = touchedJars.stream()
                    .map(sharedLibIndex::get)
                    .sorted(Comparator.comparing(UsedSharedLib::name).thenComparing(UsedSharedLib::sha256))
                    .toList();
        }
        return new CompileResult(bytecode, used);
    }

    /** Result of an actual javac run: the compiled bytecode and the shared-lib jars it opened (may be empty). */
    private record CompileResult(Map<String, byte[]> bytecode, List<UsedSharedLib> usedSharedLibs) {
    }

    /**
     * Forwarding file object that records its origin jar in {@code touched} when javac opens it. Maps the file's
     * {@code jar:file:/…!/…} URI to the normalized jar path and records it only if it belongs to the shared-lib
     * index; everything else (JDK modules, app classpath, unmappable URIs) is ignored.
     */
    private static final class RecordingFileObject extends ForwardingJavaFileObject<JavaFileObject> {
        private final Set<String> touched;
        private final Map<String, UsedSharedLib> sharedLibIndex;

        RecordingFileObject(JavaFileObject delegate, Set<String> touched, Map<String, UsedSharedLib> sharedLibIndex) {
            super(delegate);
            this.touched = touched;
            this.sharedLibIndex = sharedLibIndex;
        }

        /** The wrapped file object — for handing back to the standard manager (e.g. inferBinaryName). */
        JavaFileObject unwrap() {
            return fileObject;
        }

        @Override
        public InputStream openInputStream() throws IOException {
            String jar = jarPathOf(toUri());
            if (jar != null && sharedLibIndex.containsKey(jar)) {
                touched.add(jar);
            }
            return super.openInputStream();
        }
    }

    /** Extracts the normalized jar path from a {@code jar:file:/abs/x.jar!/entry} URI, or null if not such a URI. */
    private static String jarPathOf(URI uri) {
        if (uri == null || !"jar".equals(uri.getScheme())) {
            return null;
        }
        String ssp = uri.getSchemeSpecificPart();   // e.g. file:/abs/x.jar!/pkg/Cls.class
        int bang = ssp.indexOf("!/");
        if (bang < 0) {
            return null;
        }
        try {
            return Path.of(URI.create(ssp.substring(0, bang))).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return null;   // best-effort: unmappable entries are simply not attributed
        }
    }

    /** Removes the module from the compile cache on unload (so a redeploy recompiles) and releases its generations. */
    public void evict(String moduleId) {
        cache.remove(moduleId);
        usedSharedLibs.remove(moduleId);
        boundGeneration.compute(moduleId, (id, prev) -> {
            if (prev != null) {
                sharedLibs.release(prev, moduleId);
            }
            return null;   // drop the entry
        });
        boundLibGenerations.compute(moduleId, (id, prev) -> {
            if (prev != null && sharedModules != null) {
                prev.forEach(genId -> sharedModules.release(genId, moduleId));
            }
            return null;   // drop the entry
        });
    }

    /**
     * The parent-tier generation id this module's live ClassLoader is currently bound to, or empty if it is not
     * loaded (never compiled under this id, or already evicted). Exposed for observability (which jar-set generation
     * a module is serving on) and for the precise-invalidation track (which modules must move to a new generation).
     */
    public OptionalLong boundGeneration(String moduleId) {
        Long g = boundGeneration.get(moduleId);
        return g == null ? OptionalLong.empty() : OptionalLong.of(g);
    }

    /**
     * The library-module generation ids this module's live ClassLoader is currently bound to via {@code uses}
     * (shared-module typed sharing), sorted; empty when it uses no libraries or is not loaded. For observability
     * (which library generations a dependent is actually serving on — may lag the current one under sticky Plan B).
     */
    public List<Long> boundLibraryGenerations(String moduleId) {
        List<Long> g = boundLibGenerations.get(moduleId);
        return g == null ? List.of() : List.copyOf(g);
    }

    /**
     * The shared-lib jars ({name, sha256}) the last compile of this module actually opened, or an empty list when
     * none were observed (no shared lib configured, or not yet compiled under this id). Sorted, deduplicated.
     * Produced/stored only — the consumer that acts on jar changes (rebind/deactivate) lives elsewhere.
     */
    public List<UsedSharedLib> usedSharedLibs(String moduleId) {
        return usedSharedLibs.getOrDefault(moduleId, List.of());
    }

    /** Number of actual javac runs so far (for test observation). The fast-path (cache hit) does not increment it. */
    public long compilationCount() {
        return compilations.get();
    }

    /**
     * Unloads shared-lib generations that no live module references anymore (leak-safe close).
     * Delegates to the registry; must be called at a safe point (after the container swap/undeploy committed), not
     * mid-compile. Returns the number of generations closed.
     */
    public int closeUnreferencedGenerations() {
        int closed = sharedLibs.closeUnreferenced();
        if (sharedModules != null) {
            closed += sharedModules.closeUnreferenced();
        }
        return closed;
    }

    /** The shared-module (library) registry, or {@code null} in standalone/worker. For the install/deploy branch. */
    public SharedModuleRegistry sharedModuleRegistry() {
        return sharedModules;
    }

    /** Resolves the parent tier a module with these {@code uses} compiles/loads against (jar generation + libraries). */
    public ParentTier resolveParentTier(List<String> uses) {
        if (sharedModules == null || uses == null || uses.isEmpty()) {
            return ParentTier.of(sharedLibs.currentGeneration());
        }
        return sharedModules.resolveParentTier(uses);
    }

    /**
     * A reconcile file-manager section. Closing it releases every pooled manager's file handles; it MUST be
     * closed (try/finally) at the end of the reconcile parallel section. {@link #close()} deliberately declares
     * no checked exception so callers can use try-with-resources without a forced catch.
     */
    public interface PoolScope extends AutoCloseable {
        @Override
        void close();
    }

    /** No-op scope returned when reuse is disabled (kill switch) or a pool is already open. */
    private static final PoolScope NO_POOL = () -> { };

    /**
     * Opens a per-thread file-manager pool for a reconcile parallel section, amortizing the classpath scan
     * (open + index of the ~100 dependency jars) from once-per-compile to once-per-worker-thread. The caller
     * MUST close the returned scope at the section end (try/finally) to release the pooled managers' file
     * descriptors. Passing {@code reuse=false} — or calling while a pool is already open — returns a no-op
     * scope, falling back to the per-call manager (the {@code reuse-file-manager} kill switch / re-entrancy
     * guard). Boot-reconcile only: it must be open solely while the reconcile workers are the sole compilers.
     *
     * <p><b>Generation seam (C):</b> a fresh pool is created per reconcile run, so it is implicitly keyed to
     * the current {@link Generation}'s shared-lib classpath as it stands at that moment. Today gen0 is the only
     * generation (scanned once at boot) and its classpath is fixed for the app lifetime, so no mid-section
     * invalidation is possible or needed. When the runtime lib-store publishes new generations (a follow-up step),
     * a generation change must close the current pool so the next reconcile rescans against the new classpath.
     */
    public PoolScope openReconcilePool(boolean reuse) {
        if (!reuse || reconcilePool != null) {
            return NO_POOL;
        }
        ReconcilePool p = new ReconcilePool();
        this.reconcilePool = p;
        return p;
    }

    /**
     * Per-thread reusable {@link StandardJavaFileManager} for a boot reconcile parallel section. The manager is
     * not thread-safe, so exactly one is created lazily per worker thread; its classpath index (open jar
     * handles + zip central directories) is shared across that thread's compiles, while each compile isolates
     * its {@code *.class} output through a per-call {@link ForwardingJavaFileManager} (see {@link #doCompile}).
     * All created managers are tracked and closed together at {@link #close()} to avoid leaking ~N×100 fds.
     */
    private final class ReconcilePool implements PoolScope {
        private final ThreadLocal<StandardJavaFileManager> perThread = new ThreadLocal<>();
        private final List<StandardJavaFileManager> all = new CopyOnWriteArrayList<>();

        StandardJavaFileManager manager() {
            StandardJavaFileManager m = perThread.get();
            if (m == null) {
                m = compiler.getStandardFileManager(null, null, null);
                perThread.set(m);
                all.add(m);
            }
            return m;
        }

        @Override
        public void close() {
            reconcilePool = null;   // stop new acquisitions before tearing the managers down
            for (StandardJavaFileManager m : all) {
                try {
                    m.close();
                } catch (IOException e) {
                    log.debug("reconcile file-manager close failed (ignored): {}", e.toString());
                }
            }
            all.clear();
        }
    }

    /**
     * Runtime compilation failure. Carries the stable code {@link ErrorCode#COMPILATION_FAILED} plus a structured
     * {@code diagnostics} extension member (file, line, kind, message) that the boundary emits.
     */
    public static class CompilationException extends ProteanException {
        CompilationException(String detail) {
            super(ErrorCode.COMPILATION_FAILED, detail);
        }

        CompilationException(String detail, Throwable cause) {
            super(ErrorCode.COMPILATION_FAILED, cause, detail);
        }

        CompilationException(String detail, List<Map<String, Object>> diagnostics) {
            super(ErrorCode.COMPILATION_FAILED, detail);
            with("diagnostics", diagnostics);
        }
    }
}
