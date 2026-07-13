/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.worker;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.compiler.Generation;
import org.htcom.protean.compiler.ModuleSharedLibs;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.compiler.UsedSharedLib;
import org.htcom.protean.module.ModuleContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Worker-side receiver for live shared-lib generations pushed from the main process. A
 * worker is itself a protean app with its own {@link ModuleSharedLibs} generation machinery, but no runtime put-jar
 * surface of its own ({@code SharedLibStore} is main-only) — its gen0 is a frozen boot scan of the read-only seed dir.
 * This bean closes that gap: it accepts the main's live store jars, folds them (on top of the same seed) into a new
 * {@link ModuleSharedLibs#publishGeneration generation}, and reports which of the worker's ACTIVE modules use a jar
 * that changed, so the main can rebind exactly those (Plan A2 recompile via {@code /__admin/redeploy}).
 *
 * <p>Each push materializes its bundle into a fresh, generation-scoped subdirectory so a superseded generation's
 * {@code URLClassLoader} keeps its jar files (they are only released when that generation is closed). "Store adds, does
 * not override the seed" (parent-first): a pushed jar whose name collides with a seed jar is ignored in favor of
 * the seed.
 */
@Component
@Profile("worker")
public class WorkerSharedLibReceiver {

    private static final Logger log = LoggerFactory.getLogger(WorkerSharedLibReceiver.class);

    /** One jar pushed from the main (bytes carried as base64 in JSON). */
    public record IncomingJar(String name, byte[] bytes) {
    }

    /** The main's push: the full current store bundle plus the jar names that changed since the previous generation. */
    public record PushRequest(List<IncomingJar> bundle, List<String> changedJars) {
    }

    /** The worker's ack: the newly published generation id and the ACTIVE modules that must rebind onto it. */
    public record PublishResult(long generation, List<String> affectedModuleIds) {
    }

    private final ModuleSharedLibs sharedLibs;
    private final ModuleContainer container;
    private final RuntimeCompiler compiler;
    private final String seedDir;
    private final Path receivedRoot;
    private final AtomicLong pushCounter = new AtomicLong();
    /** Generation id → the pushed-jar directory backing it. Lets a safe-point sweep delete the dirs of superseded
     * generations once their classloaders are closed. Guarded by {@code this} (only touched from the synchronized paths). */
    private final Map<Long, Path> genDirs = new LinkedHashMap<>();

    public WorkerSharedLibReceiver(ModuleSharedLibs sharedLibs, ModuleContainer container,
                                   RuntimeCompiler compiler, ProteanProperties props) {
        this.sharedLibs = sharedLibs;
        this.container = container;
        this.compiler = compiler;
        this.seedDir = props.getModule().getSharedLibDir();
        try {
            this.receivedRoot = Files.createTempDirectory("protean-worker-shared-libs");
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create worker shared-lib receive dir", e);
        }
    }

    /**
     * Materializes the pushed bundle and publishes it (seed ∪ pushed) as the worker's current generation, then returns
     * the new generation id and the ACTIVE modules whose compile actually used one of the {@code changedJars}. An empty
     * bundle republishes the seed alone (used for a fresh-worker seed when the store is empty). Synchronized so
     * concurrent pushes serialize on generation advancement.
     */
    public synchronized PublishResult publish(PushRequest request) {
        // Safe-point sweep first: reclaim generations superseded by earlier pushes whose rebinds have since committed
        // (their modules moved to a newer generation → refcount 0). Sweeping here lags reclamation by one push, which
        // keeps it off the compile/redeploy hot path while bounding accumulation on a long-lived worker (tmpfs in a
        // container). The immediately-prior generation is still referenced now and is reclaimed on the next push.
        sweepUnreferencedGenerations();

        Path genDir = writeBundle(request.bundle());
        Generation gen = sharedLibs.publishGeneration(mergedJarPaths(genDir));
        if (genDirs.putIfAbsent(gen.id(), genDir) != null) {
            // Idempotent republish: publishGeneration reused an existing generation (same content hash), so the
            // freshly-written dir is redundant — the live generation keeps using the already-mapped dir.
            deleteRecursively(genDir);
        }

        Set<String> changed = Set.copyOf(request.changedJars() == null ? List.of() : request.changedJars());
        List<String> affected = new ArrayList<>();
        if (!changed.isEmpty()) {
            for (String moduleId : container.deployedModuleIds()) {
                for (UsedSharedLib used : compiler.usedSharedLibs(moduleId)) {
                    if (changed.contains(used.name())) {
                        affected.add(moduleId);
                        break;
                    }
                }
            }
        }
        log.info("received shared-lib push → generation {} ({} jar(s)); {} module(s) to rebind",
                gen.id(), request.bundle() == null ? 0 : request.bundle().size(), affected.size());
        return new PublishResult(gen.id(), affected);
    }

    /** Writes the pushed jars into a fresh generation-scoped subdirectory and returns it. */
    private Path writeBundle(List<IncomingJar> bundle) {
        Path genDir = receivedRoot.resolve("gen" + pushCounter.incrementAndGet());
        try {
            Files.createDirectories(genDir);
            if (bundle != null) {
                for (IncomingJar jar : bundle) {
                    Files.write(genDir.resolve(jar.name() + ".jar"), jar.bytes());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write pushed shared-lib bundle", e);
        }
        return genDir;
    }

    /** Seed jars ∪ the pushed jars in {@code genDir}, deduplicated by file name with the seed authoritative. */
    private List<Path> mergedJarPaths(Path genDir) {
        Map<String, Path> byName = new LinkedHashMap<>();
        // Pushed jars first, then seed jars overlaid so the seed wins on a name collision (store never overrides seed).
        listJars(genDir).forEach(p -> byName.put(p.getFileName().toString(), p));
        if (seedDir != null && !seedDir.isBlank()) {
            listJars(Path.of(seedDir)).forEach(p -> byName.put(p.getFileName().toString(), p));
        }
        return new ArrayList<>(byName.values());
    }

    private static List<Path> listJars(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".jar")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list jars in " + dir, e);
        }
    }

    /**
     * Closes generation classloaders that are no longer referenced (mirrors the main-side safe-point sweep), then
     * deletes the on-disk pushed-jar directory of every generation that is no longer live — {@code closeUnreferenced}
     * releases the CL but leaves the jar files behind, so the disk reclaim is done here against
     * {@link ModuleSharedLibs#liveGenerationIds()}. gen0 (the boot seed) is never in {@link #genDirs}, so the read-only
     * seed dir is never touched.
     */
    private void sweepUnreferencedGenerations() {
        int closed = sharedLibs.closeUnreferenced();
        Set<Long> live = sharedLibs.liveGenerationIds();
        int deletedDirs = 0;
        for (Iterator<Map.Entry<Long, Path>> it = genDirs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Path> e = it.next();
            if (live.contains(e.getKey())) {
                continue;
            }
            deleteRecursively(e.getValue());
            it.remove();
            deletedDirs++;
        }
        if (closed > 0 || deletedDirs > 0) {
            log.info("worker shared-lib sweep: closed {} unreferenced generation(s), deleted {} superseded jar dir(s)",
                    closed, deletedDirs);
        }
    }

    /** Best-effort recursive delete (deepest-first). A failure is logged, not thrown — cleanup must never fail a push. */
    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("failed to delete worker shared-lib artifact {}: {}", p, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("failed to walk worker shared-lib dir {} for cleanup: {}", root, e.toString());
        }
    }

    /** Removes the entire receive root when the worker shuts down, so a host-process worker leaves no temp jars behind
     * (a container's tmpfs is discarded with the container, but a pooled host worker's temp dir would otherwise linger). */
    @PreDestroy
    synchronized void cleanUp() {
        deleteRecursively(receivedRoot);
        genDirs.clear();
    }
}
