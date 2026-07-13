/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.compiler.Generation;
import org.htcom.protean.compiler.ModuleSharedLibs;
import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.error.ProteanException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Server-managed store for live shared-lib jars — the runtime <b>put-jar</b> surface. Jars uploaded at runtime are persisted under
 * {@code protean.module.shared-lib-store-dir} (separate from the read-only {@code shared-lib-dir} seed) and folded,
 * on top of the seed, into a new parent-tier {@link Generation} via {@link ModuleSharedLibs#publishGeneration}.
 *
 * <p><b>Model.</b> The store is keyed by a logical lib {@code name} (one active version per name): re-deploying a name
 * replaces its entry and publishes a new generation. The active jar set of a generation is {@code seed ∪ store} — an
 * upload <i>adds</i> a lib; it does not override a seed jar (parent-first keeps app-provided classes authoritative).
 * {@code remove} affects future generations only; modules bound to an earlier generation keep serving on it
 * (lazy default → zero-downtime).
 *
 * <p><b>Idempotency</b> (bundle = one generation): within a deploy, a jar whose {@code name+version+sha256}
 * already matches the stored entry is a no-op; the same {@code name+version} with different bytes is a coordinate
 * conflict (rejected). A deploy in which every jar is a no-op publishes no new generation.
 *
 * <p><b>Trust</b> is a separate opt-in seam: {@code signerKeyId}/{@code signature} are carried and persisted here;
 * their verification (default = consumer authz; opt-in = Ed25519) is applied by the caller/gate, not this store.
 */
@Component
@Profile("!worker")
public class SharedLibStore {

    private static final Logger log = LoggerFactory.getLogger(SharedLibStore.class);
    /** Conservative name/version charset — also the guard against path traversal in the on-disk file name. */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** Metadata for one stored lib (the bytes live alongside in {@code <name>.jar}). */
    public record StoredLib(String name, String version, String sha256, long size,
                            String signerKeyId, String signature) {
    }

    /** A jar submitted for deployment. {@code signerKeyId}/{@code signature} are optional (trust seam). */
    public record IncomingLib(String name, String version, byte[] bytes, String signerKeyId, String signature) {
    }

    /** Observability view: the current generation id and the live stored libs. */
    public record SharedLibsView(long generation, List<StoredLib> libs) {
    }

    private final Path storeDir;
    private final String seedDir;
    private final ModuleSharedLibs sharedLibs;
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher events;

    /** Logical name → its active stored metadata. Guarded by {@code this} on mutation; read under snapshot copy. */
    private final Map<String, StoredLib> live = new LinkedHashMap<>();

    public SharedLibStore(ProteanProperties props, ModuleSharedLibs sharedLibs, ObjectMapper mapper,
                          ApplicationEventPublisher events) {
        String dir = props.getModule().getSharedLibStoreDir();
        this.storeDir = Path.of(dir == null || dir.isBlank()
                ? System.getProperty("java.io.tmpdir") + "/protean-shared-libs" : dir);
        this.seedDir = props.getModule().getSharedLibDir();
        this.sharedLibs = sharedLibs;
        this.mapper = mapper;
        this.events = events;
    }

    /**
     * Reloads persisted uploads at boot and, if any exist, publishes them (on top of the seed) as the current
     * generation — so live shared libs survive a restart. Runs during bean init, before {@code ModuleReconciler}
     * ({@code ApplicationRunner}) binds ACTIVE modules, so recovered modules bind to the restored generation.
     */
    @PostConstruct
    synchronized void load() {
        if (Files.isDirectory(storeDir)) {
            try (Stream<Path> metas = Files.list(storeDir)) {
                metas.filter(p -> p.toString().endsWith(".json")).sorted().forEach(this::loadOne);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to scan shared-lib store: " + storeDir, e);
            }
        }
        if (!live.isEmpty()) {
            Generation gen = sharedLibs.publishGeneration(activeJarPaths());
            log.info("shared-lib store restored: {} live lib(s) → generation {}", live.size(), gen.id());
        }
    }

    private void loadOne(Path meta) {
        try {
            StoredLib lib = mapper.readValue(meta.toFile(), StoredLib.class);
            if (Files.exists(jarFile(lib.name()))) {
                live.put(lib.name(), lib);
            } else {
                log.warn("shared-lib store: metadata {} has no jar file — skipping", meta.getFileName());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to parse shared-lib metadata: " + meta, e);
        }
    }

    /**
     * Deploys a bundle of jars as a single new generation (or a no-op when every jar is already stored identically).
     * Returns the resulting current generation.
     */
    public synchronized Generation deploy(List<IncomingLib> bundle) {
        if (bundle == null || bundle.isEmpty()) {
            throw new ProteanException(ErrorCode.INVALID_ARGUMENT, "shared-lib deploy: bundle must contain at least one jar");
        }
        boolean changed = false;
        for (IncomingLib in : bundle) {
            validate(in);
            String sha = ModuleSharedLibs.sha256Hex(in.bytes());
            StoredLib existing = live.get(in.name());
            if (existing != null && existing.version().equals(in.version())) {
                if (existing.sha256().equals(sha)) {
                    continue;   // identical name+version+sha256 → idempotent no-op
                }
                throw new ProteanException(ErrorCode.STATE_CONFLICT,
                        "shared lib " + in.name() + " version " + in.version()
                                + " already exists with different content (sha256 mismatch)")
                        .with("name", in.name()).with("version", in.version());
            }
            StoredLib stored = new StoredLib(in.name(), in.version(), sha, in.bytes().length,
                    in.signerKeyId(), in.signature());
            persist(stored, in.bytes());
            live.put(in.name(), stored);
            changed = true;
        }
        if (!changed) {
            return sharedLibs.currentGeneration();   // whole bundle was a no-op → no new generation
        }
        return advanceGeneration();
    }

    /** Removes a lib from the store, publishing a new generation without it. In-use older generations keep it. */
    public synchronized Generation remove(String name) {
        StoredLib gone = live.remove(name);
        if (gone == null) {
            throw new ProteanException(ErrorCode.SHARED_LIB_NOT_FOUND, name).with("name", name);
        }
        try {
            Files.deleteIfExists(jarFile(name));
            Files.deleteIfExists(metaFile(name));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to remove shared lib: " + name, e);
        }
        return advanceGeneration();
    }

    /** Publishes a new generation from the current active set and announces it (fires precise invalidation). */
    private Generation advanceGeneration() {
        Generation prev = sharedLibs.currentGeneration();
        Generation next = sharedLibs.publishGeneration(activeJarPaths());
        events.publishEvent(new SharedLibGenerationPublishedEvent(prev, next));
        return next;
    }

    /** A snapshot of the live stored libs (metadata only). */
    public synchronized List<StoredLib> list() {
        return List.copyOf(live.values());
    }

    /**
     * The live store jars as a bundle of bytes, for pushing to worker JVMs. Only the
     * store entries are included — the seed jars already reach a worker through the shared host FS ({@code
     * shared-lib-dir}), so re-sending them would be redundant. Empty when nothing has been uploaded at runtime.
     */
    public synchronized List<IncomingLib> pushBundle() {
        List<IncomingLib> bundle = new ArrayList<>(live.size());
        for (StoredLib lib : live.values()) {
            try {
                byte[] bytes = Files.readAllBytes(jarFile(lib.name()));
                bundle.add(new IncomingLib(lib.name(), lib.version(), bytes, lib.signerKeyId(), lib.signature()));
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read stored shared lib for worker push: " + lib.name(), e);
            }
        }
        return bundle;
    }

    /** The stored metadata for a lib name, or empty if not stored. */
    public synchronized Optional<StoredLib> get(String name) {
        return Optional.ofNullable(live.get(name));
    }

    /** The current parent-tier generation id (advances on each store-changing deploy/remove). */
    public long currentGenerationId() {
        return sharedLibs.currentGeneration().id();
    }

    /** The current generation id plus a snapshot of the live stored libs. */
    public synchronized SharedLibsView view() {
        return new SharedLibsView(currentGenerationId(), List.copyOf(live.values()));
    }

    private void validate(IncomingLib in) {
        if (in.name() == null || !SAFE.matcher(in.name()).matches()) {
            throw new ProteanException(ErrorCode.INVALID_ARGUMENT,
                    "shared-lib name must match " + SAFE.pattern() + ": " + in.name());
        }
        if (in.version() == null || !SAFE.matcher(in.version()).matches()) {
            throw new ProteanException(ErrorCode.INVALID_ARGUMENT,
                    "shared-lib version must match " + SAFE.pattern() + ": " + in.version());
        }
        if (in.bytes() == null || in.bytes().length == 0) {
            throw new ProteanException(ErrorCode.INVALID_ARGUMENT, "shared-lib " + in.name() + ": jar bytes are empty");
        }
    }

    /** Writes the jar bytes and metadata atomically (temp + ATOMIC_MOVE), mirroring the module store's durability. */
    private void persist(StoredLib lib, byte[] bytes) {
        try {
            Files.createDirectories(storeDir);
            Path jarTmp = Files.createTempFile(storeDir, lib.name(), ".jar.tmp");
            Files.write(jarTmp, bytes);
            Files.move(jarTmp, jarFile(lib.name()),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Path metaTmp = Files.createTempFile(storeDir, lib.name(), ".json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(metaTmp.toFile(), lib);
            Files.move(metaTmp, metaFile(lib.name()),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist shared lib: " + lib.name(), e);
        }
    }

    /** The current active jar set = every seed jar plus every live store jar (paths for a new generation). */
    private List<Path> activeJarPaths() {
        List<Path> jars = new ArrayList<>();
        if (seedDir != null && !seedDir.isBlank()) {
            Path seed = Path.of(seedDir);
            if (Files.isDirectory(seed)) {
                try (Stream<Path> s = Files.list(seed)) {
                    s.filter(p -> p.toString().endsWith(".jar")).forEach(jars::add);
                } catch (IOException e) {
                    throw new UncheckedIOException("failed to scan shared-lib seed dir: " + seed, e);
                }
            }
        }
        for (String name : live.keySet()) {
            jars.add(jarFile(name));
        }
        return jars;
    }

    private Path jarFile(String name) {
        return storeDir.resolve(name + ".jar");
    }

    private Path metaFile(String name) {
        return storeDir.resolve(name + ".json");
    }
}
