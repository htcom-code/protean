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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Hands a spawned worker its secrets through an owner-only file instead of its command line.
 *
 * <p>Process arguments are the wrong channel for a secret. The process table is world-readable, so any local user can
 * read another tenant's scoped DB password out of {@code ps}; for a container the same values are worse off, because
 * {@code docker run} arguments are kept in the container's metadata and stay visible to {@code docker inspect} for as
 * long as the container exists. Under the scope model those credentials are what makes a tenant's database a tenant's,
 * so leaving them on argv contradicts the isolation the model provides. A consumer cannot fix this — the spawn is the
 * library's own code — so the library carries the fix.
 *
 * <p>The file is written with POSIX {@code rw-------} before the worker starts and handed over as
 * {@code --spring.config.import}, which Spring reads at boot and which therefore only puts a <b>path</b> on argv.
 * Container workers get the same file bind-mounted read-only, so their metadata carries a mount path rather than the
 * values. Files are deleted when the worker they belong to is retired, and any left by a previous JVM are purged at
 * startup (reconcile respawns those workers, so their old files are dead weight).
 *
 * <p>On a filesystem without POSIX permissions the restrictive mode cannot be applied; the file is still written (the
 * worker must start) and the caller logs the degradation rather than failing the deploy.
 */
final class WorkerSecretsFile {

    private static final Logger log = LoggerFactory.getLogger(WorkerSecretsFile.class);

    /** Owner read/write only — the worker runs as the same user as the main. */
    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private WorkerSecretsFile() {
    }

    /** The directory holding per-worker secret files (created on demand, owner-only). */
    static Path directory() {
        return Path.of(System.getProperty("java.io.tmpdir"), "protean-worker-secrets");
    }

    /**
     * Writes {@code properties} as a Spring properties file readable only by the current user.
     *
     * @param id  worker/container identity, used as the file name
     * @return the file path, to be handed over as {@code --spring.config.import=optional:file:<path>}
     */
    static Path write(String id, Map<String, String> properties) {
        Path dir = directory();
        try {
            Files.createDirectories(dir);
            trySetPermissions(dir, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            Path file = dir.resolve(id + ".properties");
            StringBuilder body = new StringBuilder();
            properties.forEach((k, v) -> body.append(k).append('=').append(escape(v)).append('\n'));
            // Create with the restrictive mode from the start where possible, so the values are never briefly world-readable.
            Files.deleteIfExists(file);
            try {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            } catch (UnsupportedOperationException e) {
                Files.createFile(file);   // non-POSIX filesystem
                log.warn("worker secrets file {} could not be created owner-only (non-POSIX filesystem) — the "
                        + "credentials it carries are readable by anything that can read this path", file);
            }
            Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write the worker secrets file in " + dir, e);
        }
    }

    /** Removes a retired worker's secrets file. Best-effort: a leftover is purged on the next main start. */
    static void delete(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("could not delete the worker secrets file {} (it will be purged on the next start): {}",
                    file, e.toString());
        }
    }

    /**
     * Deletes every secrets file left behind by a previous JVM. Safe at startup: no worker of <i>this</i> JVM exists
     * yet, and reconcile respawns the recovered modules' workers with freshly written files.
     */
    static void purgeStale() {
        Path dir = directory();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            int purged = 0;
            for (Path file : files.toList()) {
                try {
                    if (Files.deleteIfExists(file)) {
                        purged++;
                    }
                } catch (IOException ignored) {
                    // a file we cannot remove is not worth failing startup over
                }
            }
            if (purged > 0) {
                log.info("startup: purged {} worker secrets file(s) left by a previous run", purged);
            }
        } catch (IOException e) {
            log.warn("could not purge stale worker secrets files in {} (ignored): {}", dir, e.toString());
        }
    }

    /** Escapes the value for a properties file: backslashes only — the values here are URLs, users, and secrets. */
    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\");
    }

    private static void trySetPermissions(Path path, Set<PosixFilePermission> perms) {
        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (IOException | UnsupportedOperationException ignored) {
            // non-POSIX filesystem, or a pre-existing dir we do not own — write() logs the file-level degradation
        }
    }
}
