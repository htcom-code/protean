/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.isolation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reaps process-track worker JVMs that were orphaned by an <b>unclean</b> exit of the main process (SIGKILL, JVM crash,
 * OOM, power loss) — the case {@link WorkerProcessIsolation#shutdown()} (the graceful {@code @PreDestroy}) cannot cover,
 * because it never runs on an unclean exit. This is the process-track counterpart to the container track's startup
 * {@code docker rm -f} safety net; unlike containers, process workers have no fixed name, so identity is carried by a
 * per-spawn {@link UUID}.
 *
 * <p><b>Mechanism.</b> Each spawned worker is launched with {@code -Dprotean.worker.id=<uuid>} on its command line and a
 * marker file {@code <dir>/<uuid>} (holding its PID) is written durably before the process starts. A clean exit removes
 * the marker. On the next startup, {@link #reapOrphans()} treats every leftover marker as a prior-run orphan: it scans
 * the live process table for a JVM whose command line carries that uuid and force-kills the match. Matching by uuid
 * (not by the stored PID) sidesteps <b>PID reuse</b> — a recycled PID cannot be mistaken for our worker — and, because
 * uuids are unique per spawn, it cannot touch another protean instance's workers.
 *
 * <p><b>Instance scoping.</b> Markers live under the module-store directory. Two main instances sharing one store
 * directory is already unsupported (they would corrupt each other's module store), so the store dir is the natural
 * per-instance boundary for markers too.
 *
 * <p><b>Portability caveat.</b> {@link ProcessHandle.Info#commandLine()} can be empty on some platforms/permissions
 * (notably macOS for cross-context reads). When a marker's PID is still alive but its command line is unreadable, the
 * reaper cannot confirm identity, so it conservatively leaves the process alone and warns rather than risk killing an
 * unrelated PID.
 */
final class OrphanWorkerReaper {

    private static final Logger log = LoggerFactory.getLogger(OrphanWorkerReaper.class);

    /** System-property key carried on each worker's command line for out-of-band identification. */
    static final String MARKER_PROP = "protean.worker.id";
    /** Extracts the uuid from a worker command line (canonical UUID form). */
    private static final Pattern MARKER_PATTERN =
            Pattern.compile(Pattern.quote(MARKER_PROP) + "=([0-9a-fA-F-]{36})");
    /** Max wait for a force-killed orphan to actually be reaped before moving on. */
    private static final int REAP_WAIT_SEC = 10;

    private final Path dir;

    OrphanWorkerReaper(Path dir) {
        this.dir = dir;
    }

    /** The JVM argument injected into a spawned worker so this reaper can recognize it later. */
    static String markerArg(UUID id) {
        return "-D" + MARKER_PROP + "=" + id;
    }

    /** Durably records a live worker (called right after its process starts). Best-effort: a failure only weakens
     * unclean-exit reaping, so it is logged and swallowed rather than failing the spawn. */
    void record(UUID id, long pid) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(id.toString()), Long.toString(pid),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException e) {
            log.warn("failed to record worker marker {} (unclean-exit reaping weakened): {}", id, e.toString());
        }
    }

    /** Removes a worker's marker (called when the worker exits or is intentionally torn down). Idempotent. */
    void forget(UUID id) {
        if (id == null) {
            return;
        }
        try {
            Files.deleteIfExists(dir.resolve(id.toString()));
        } catch (IOException | RuntimeException e) {
            log.warn("failed to remove worker marker {}: {}", id, e.toString());
        }
    }

    /**
     * Reaps orphans left by a prior unclean exit. Reads the marker directory once (a snapshot — markers written by
     * this run's later spawns are not present yet and are never touched), and for each marker force-kills the live JVM
     * whose command line carries the matching uuid. Returns the number of orphans killed.
     */
    int reapOrphans() {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        List<Path> markers;
        try (Stream<Path> s = Files.list(dir)) {
            markers = s.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            log.warn("could not list worker marker dir {} — skipping orphan reap: {}", dir, e.toString());
            return 0;
        }
        if (markers.isEmpty()) {
            return 0;
        }
        Map<String, ProcessHandle> liveByUuid = scanLiveWorkers();
        int reaped = 0;
        for (Path marker : markers) {
            String uuid = marker.getFileName().toString();
            ProcessHandle live = liveByUuid.get(uuid);
            if (live != null) {
                long pid = live.pid();
                live.destroyForcibly();
                awaitExit(live);
                forgetPath(marker);
                reaped++;
                log.warn("reaped orphan worker JVM from a previous unclean exit: uuid={} pid={}", uuid, pid);
            } else if (isPidAliveButUnverifiable(marker)) {
                // Alive PID recorded, but no command line carries the uuid (unreadable on this platform) — cannot
                // confirm identity, so leave it rather than risk killing an unrelated process. Marker is kept so a
                // later run (or the OS supervisor) can still clean up.
                log.warn("orphan worker candidate uuid={} is alive but unverifiable (command line unreadable) — "
                        + "leaving it; rely on the OS process supervisor if it lingers", uuid);
            } else {
                forgetPath(marker);   // the worker is already gone — just a stale marker
            }
        }
        return reaped;
    }

    /** One pass over the process table, indexing every JVM that carries a worker marker by its uuid. */
    private Map<String, ProcessHandle> scanLiveWorkers() {
        Map<String, ProcessHandle> byUuid = new HashMap<>();
        ProcessHandle.allProcesses().forEach(h -> {
            Optional<String> cmd = h.info().commandLine();
            if (cmd.isPresent()) {
                Matcher m = MARKER_PATTERN.matcher(cmd.get());
                if (m.find()) {
                    byUuid.put(m.group(1), h);
                }
            }
        });
        return byUuid;
    }

    /** True when the marker's recorded PID is still alive (used only to decide leave-and-warn vs delete-stale). */
    private boolean isPidAliveButUnverifiable(Path marker) {
        try {
            long pid = Long.parseLong(Files.readString(marker).trim());
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (IOException | RuntimeException e) {
            return false;   // unreadable/corrupt marker (incl. NumberFormatException) → treat as gone
        }
    }

    private void awaitExit(ProcessHandle h) {
        try {
            h.onExit().get(REAP_WAIT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // timed out or already gone — the SIGKILL is still in flight; best effort
        }
    }

    private void forgetPath(Path marker) {
        try {
            Files.deleteIfExists(marker);
        } catch (IOException | RuntimeException e) {
            log.warn("failed to remove worker marker {}: {}", marker.getFileName(), e.toString());
        }
    }
}
