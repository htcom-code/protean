/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.bench;

import org.htcom.protean.ProteanApplication;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.module.ModuleStore;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Standalone restart-scale benchmark. Measures how long a cold server restart takes when the store
 * already holds many ACTIVE modules — because reconcile recompiles every module from source on boot
 * (the compile cache is in-memory and does not survive a restart), boot cost scales with module count.
 *
 * <p>Not a JUnit test (no assertions, opt-in only) — run via the {@code restartBench} Gradle task so
 * heap/Metaspace can be sized generously (the {@code test} task caps heap at 512m for the leak canary).
 *
 * <pre>
 *   ./gradlew restartBench -Dbench.modules=1000
 * </pre>
 *
 * <p>Measurement design:
 * <ol>
 *   <li><b>Seed</b> — boot an empty app, save N ACTIVE {@link ModuleDescriptor}s through the real
 *       {@link ModuleStore} bean (so JSON is guaranteed reconcile-loadable), then shut down. No compile.</li>
 *   <li><b>Baseline boot</b> — boot with an empty store dir to isolate framework boot cost.</li>
 *   <li><b>Restart boot</b> — boot pointing at the seeded dir; {@code ModuleReconciler} recompiles and
 *       redeploys all N modules during {@code SpringApplication.run(...)}. This wall-clock <b>is</b> the
 *       restart-completion time.</li>
 * </ol>
 * Reports total/per-module time, javac compile count, heap and Metaspace footprint, loaded-class count,
 * live threads, and cumulative GC.
 */
public final class RestartBenchmark {

    private RestartBenchmark() {}

    public static void main(String[] args) throws Exception {
        int modules = Integer.getInteger("bench.modules", 1000);
        // Reconcile compile parallelism: 0=auto(cores), 1=serial(legacy), N=cap. Forwarded to Spring config.
        int parallelism = Integer.getInteger("bench.parallelism", 0);
        Path storeDir = Files.createTempDirectory("protean-bench-store");
        String reuse = System.getProperty("bench.reuse", "true");
        System.out.printf("%n=== Protean restart-scale benchmark: %d modules (compile-parallelism=%s, reuse-file-manager=%s) ===%n",
                modules, parallelism == 0 ? "auto" : String.valueOf(parallelism), reuse);
        System.out.println("store dir: " + storeDir);

        // --- Step 1: seed the store with N ACTIVE descriptors (no compile) ---
        long seedStart = System.nanoTime();
        try (ConfigurableApplicationContext seedCtx = boot(storeDir, /*silentReconcile*/ true)) {
            ModuleStore store = seedCtx.getBean(ModuleStore.class);
            for (int i = 0; i < modules; i++) {
                store.save(descriptor(i));
            }
        }
        double seedMs = ms(System.nanoTime() - seedStart);
        System.out.printf("seeded %d descriptors in %.0f ms (%.2f ms/module, boot+save)%n",
                modules, seedMs, seedMs / modules);

        // --- Step 2: baseline boot with an EMPTY store (framework cost only) ---
        Path emptyDir = Files.createTempDirectory("protean-bench-empty");
        long baseStart = System.nanoTime();
        try (ConfigurableApplicationContext ignored = boot(emptyDir, true)) {
            // ready
        }
        double baselineMs = ms(System.nanoTime() - baseStart);
        System.out.printf("baseline boot (0 modules): %.0f ms%n", baselineMs);

        // --- Step 3: the real restart — boot with N ACTIVE modules in the store ---
        forceGc();
        long restartStart = System.nanoTime();
        ConfigurableApplicationContext ctx = boot(storeDir, false);
        double restartMs = ms(System.nanoTime() - restartStart);

        RuntimeCompiler compiler = ctx.getBean(RuntimeCompiler.class);
        long compiles = compiler.compilationCount();
        int recovered = ctx.getBean(ModulePlatform.class).list().size();

        forceGc();
        MemFootprint mem = MemFootprint.capture();
        int threads = Thread.getAllStackTraces().size();
        long loadedClasses = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();

        // Split compile vs deploy: time pure javac for a fresh sample (distinct ids => no cache hit,
        // no child-context creation). Isolates the javac share of the per-module restart cost.
        // Done after the memory snapshot so the sample's classes don't skew the footprint.
        int sample = Math.min(modules, 100);
        long compileOnlyStart = System.nanoTime();
        for (int i = 0; i < sample; i++) {
            int id = 1_000_000 + i;  // fresh namespace, never seeded
            String fqcn = "bench.s" + id + ".C";
            compiler.compileAll(Map.of(fqcn, sampleSource(id, fqcn)), Map.of(), "sample-" + id);
        }
        double compileOnlyMs = ms(System.nanoTime() - compileOnlyStart) / sample;

        ctx.close();

        // --- Report ---
        double moduleMs = restartMs - baselineMs;
        System.out.println();
        System.out.println("==================== RESULT ====================");
        System.out.printf("modules registered (ACTIVE)   : %d%n", modules);
        System.out.printf("modules recovered by reconcile: %d%n", recovered);
        System.out.printf("javac compilations on boot    : %d%n", compiles);
        System.out.println("------------------------------------------------");
        System.out.printf("restart completion (total)    : %,.0f ms  (%.2f s)%n", restartMs, restartMs / 1000);
        System.out.printf("  - framework baseline        : %,.0f ms%n", baselineMs);
        System.out.printf("  - module recompile+deploy   : %,.0f ms%n", moduleMs);
        System.out.printf("  - per module (wall-clock)     : %.1f ms%n", moduleMs / modules);
        System.out.printf("  - single-thread javac (ref)   : %.1f ms/module   (overlap factor %.2fx)%n",
                compileOnlyMs, compileOnlyMs / (moduleMs / modules));
        System.out.println("------------------------------------------------");
        System.out.printf("heap used (after GC)          : %,d MB%n", mem.heapUsedMb);
        System.out.printf("Metaspace used (after GC)     : %,d MB%n", mem.metaspaceUsedMb);
        System.out.printf("  - per module (heap+meta)    : %.2f MB%n",
                (mem.heapUsedMb + mem.metaspaceUsedMb) / (double) modules);
        System.out.printf("loaded classes                : %,d%n", loadedClasses);
        System.out.printf("live threads                  : %,d%n", threads);
        System.out.printf("GC (count / total pause)      : %d / %,d ms%n", mem.gcCount, mem.gcMillis);
        System.out.println("================================================");
    }

    /** Boots the full application on a random port with the store dir overridden. */
    private static ConfigurableApplicationContext boot(Path storeDir, boolean quiet) {
        SpringApplicationBuilder b = new SpringApplicationBuilder(ProteanApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "server.port=0",
                        "protean.module-store.dir=" + storeDir,
                        "protean.reconcile.compile-parallelism=" + Integer.getInteger("bench.parallelism", 0),
                        "protean.reconcile.reuse-file-manager=" + System.getProperty("bench.reuse", "true"),
                        "spring.main.banner-mode=off",
                        // keep the run headless: no MCP stdio launcher hijacking stdin/stdout
                        "logging.level.org.htcom.protean.module.ModulePlatform=" + (quiet ? "WARN" : "INFO"));
        return b.run();
    }

    /** A minimal, unique REST controller module — compiles fast, distinct package + route per index. */
    private static ModuleDescriptor descriptor(int i) {
        String fqcn = "bench.m" + i + ".C";
        String src = ""
                + "package bench.m" + i + ";\n"
                + "import org.springframework.web.bind.annotation.GetMapping;\n"
                + "import org.springframework.web.bind.annotation.RestController;\n"
                + "@RestController\n"
                + "public class C {\n"
                + "    @GetMapping(\"/bench/" + i + "/ping\")\n"
                + "    public String ping() { return \"" + i + "\"; }\n"
                + "}\n";
        return ModuleDescriptor.builder()
                .id("bench-m" + i).version("1.0.0")
                .controllerFqcn(fqcn).componentFqcns(List.of(fqcn))
                .sources(Map.of(fqcn, src))
                // reconcile does not run gates, so no test source is needed to redeploy
                .build();
    }

    private static String sampleSource(int id, String fqcn) {
        return ""
                + "package bench.s" + id + ";\n"
                + "import org.springframework.web.bind.annotation.GetMapping;\n"
                + "import org.springframework.web.bind.annotation.RestController;\n"
                + "@RestController\n"
                + "public class C {\n"
                + "    @GetMapping(\"/bench/s" + id + "/ping\")\n"
                + "    public String ping() { return \"" + id + "\"; }\n"
                + "}\n";
    }

    private static double ms(long nanos) { return nanos / 1_000_000.0; }

    private static void forceGc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private record MemFootprint(long heapUsedMb, long metaspaceUsedMb, long gcCount, long gcMillis) {
        static MemFootprint capture() {
            long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            long meta = 0;
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if ("Metaspace".equals(pool.getName())) {
                    meta = pool.getUsage().getUsed();
                }
            }
            long gcCount = 0, gcMillis = 0;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (gc.getCollectionCount() > 0) gcCount += gc.getCollectionCount();
                if (gc.getCollectionTime() > 0) gcMillis += gc.getCollectionTime();
            }
            return new MemFootprint(heap / (1 << 20), meta / (1 << 20), gcCount, gcMillis);
        }
    }
}
