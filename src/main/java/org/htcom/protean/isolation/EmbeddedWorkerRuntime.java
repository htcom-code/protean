/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.isolation;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.boot.ProteanWorkerLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Default worker runtime = <b>Embed</b>. The worker re-runs the host artifact as-is.
 * <ul>
 *   <li>process track: host classpath ({@code java.class.path}) + {@link ProteanWorkerLauncher}.</li>
 *   <li>container track: explode the host bootJar into an exploded (BOOT-INF) layout, mount it read-only, and run.</li>
 * </ul>
 *
 * <p>Because the worker compiles module sources at runtime (and sources may reference host/consumer types),
 * <b>classpath parity is free</b> — this is embed's key advantage. bootJar explode is an embed-only concern, so it
 * was moved here.
 *
 * <p>Active when: {@code protean.worker.runtime=embed} (default if unset).
 */
@Component
@Profile("!worker")
@ConditionalOnProperty(name = "protean.worker.runtime", havingValue = "embed", matchIfMissing = true)
public class EmbeddedWorkerRuntime implements WorkerRuntimeProvider {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedWorkerRuntime.class);

    // Read live so worker.container.image / worker.container.jar changes apply to the next spawn (Tier 2, future).
    private final ProteanProperties props;

    public EmbeddedWorkerRuntime(ProteanProperties props) {
        this.props = props;
    }

    @Override
    public List<String> processLaunchPrefix() {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        // Process track has no memory bound, so no cgroup-relative heap default — operator sizes heap via jvm-args.
        return WorkerRuntimeProvider.javaCommand(javaBin, false, props.getWorker().getJvmArgs(),
                List.of("-cp", System.getProperty("java.class.path"), ProteanWorkerLauncher.MAIN_CLASS));
    }

    @Override
    public ContainerLaunchSpec containerLaunchSpec() {
        String appDir = resolveExplodedDir();
        // The classpath must also include /app (jar root): the auto-config imports file lives at the bootJar root
        // (META-INF/spring/...AutoConfiguration.imports), so BOOT-INF/classes alone cannot find it.
        return new ContainerLaunchSpec(props.getWorker().getContainer().getImage(),
                List.of("-v", appDir + ":/app:ro"),
                WorkerRuntimeProvider.javaCommand("java", true, props.getWorker().getJvmArgs(),
                        List.of("-cp", "/app:/app/BOOT-INF/classes:/app/BOOT-INF/lib/*",
                                ProteanWorkerLauncher.MAIN_CLASS)));
    }

    // --- bootJar explode (embed-only) ---

    /** Explodes the bootJar into an exploded layout (BOOT-INF/...) and returns that directory path. Mounted ro into the container. */
    private synchronized String resolveExplodedDir() {
        String jarPathConfig = props.getWorker().getContainer().getJar();
        Path jar = jarPathConfig.isBlank() ? findBootJar() : Path.of(jarPathConfig);
        Path dir = Path.of("build", "worker-exploded").toAbsolutePath();
        Path bootInf = dir.resolve("BOOT-INF");
        // Re-explode when the layout is missing OR the bootJar is newer than the last explode — otherwise a rebuilt app
        // would run STALE code in the container (e.g. missing a new /__admin route), silently diverging from the host.
        if (!Files.isDirectory(bootInf) || isStale(bootInf, jar)) {
            explode(jar, dir);
        }
        return dir.toString();
    }

    /** Whether the exploded layout predates the bootJar (so it must be re-exploded). Safe default: re-explode. */
    private static boolean isStale(Path exploded, Path jar) {
        try {
            return Files.getLastModifiedTime(exploded).toInstant()
                    .isBefore(Files.getLastModifiedTime(jar).toInstant());
        } catch (IOException e) {
            return true;
        }
    }

    private Path findBootJar() {
        Path libs = Path.of("build", "libs");
        try (Stream<Path> s = Files.list(libs)) {
            // The bootJar has the '-boot' classifier (to distinguish it from the plain library jar). Only the executable one with a BOOT-INF layout is exploded.
            return s.filter(p -> p.toString().endsWith("-boot.jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no bootJar (-boot.jar) — run 'gradle bootJar' first: " + libs));
        } catch (IOException e) {
            throw new IllegalStateException("bootJar lookup failed (build/libs). 'gradle bootJar' required", e);
        }
    }

    private void explode(Path jar, Path dest) {
        try {
            deleteRecursive(dest);   // clear a prior (possibly stale) layout so removed classes do not linger
            Files.createDirectories(dest);
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    Path out = dest.resolve(e.getName()).normalize();
                    if (!out.startsWith(dest)) {
                        continue;  // zip-slip guard
                    }
                    if (e.isDirectory()) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            log.info("embed worker bootJar explode complete: {} → {}", jar, dest);
        } catch (IOException e) {
            throw new IllegalStateException("bootJar explode failed: " + jar, e);
        }
    }

    /** Deletes a directory tree if it exists (deepest-first). No-op when absent. */
    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
