/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Filesystem-backed ModuleStore. Stores descriptors at baseDir/&lt;id&gt;.json.
 * Writes go through a temp file &rarr; atomic move to prevent partial writes on crash (write-ahead durability).
 */
@Component
@Profile("!worker")
@ConditionalOnProperty(name = "protean.module-store.backend", havingValue = "filesystem", matchIfMissing = true)
public class FileSystemModuleStore implements ModuleStore {

    private final Path baseDir;
    private final ObjectMapper mapper;

    public FileSystemModuleStore(ProteanProperties props, ObjectMapper mapper) {
        this.baseDir = Path.of(props.getModuleStore().getDir());
        this.mapper = mapper;
    }

    @Override
    public void save(ModuleDescriptor descriptor) {
        try {
            Files.createDirectories(baseDir);
            // 1) Append a snapshot to the version history (for auditing/rollback)
            appendHistory(descriptor);
            // 2) Atomically replace the current state file (write-ahead durability)
            Path target = fileFor(descriptor.id());
            Path tmp = Files.createTempFile(baseDir, descriptor.id(), ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), descriptor);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save module descriptor: " + descriptor.id(), e);
        }
    }

    private void appendHistory(ModuleDescriptor descriptor) throws IOException {
        Path dir = historyDir(descriptor.id());
        Files.createDirectories(dir);
        long seq = nextSeq(dir);
        Path tmp = Files.createTempFile(dir, "h", ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), descriptor);
        Files.move(tmp, dir.resolve(String.format("%012d.json", seq)),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private long nextSeq(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .mapToLong(n -> Long.parseLong(n.substring(0, n.length() - ".json".length())))
                    .max().orElse(0L) + 1;
        }
    }

    @Override
    public Optional<ModuleDescriptor> load(String moduleId) {
        Path file = fileFor(moduleId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), ModuleDescriptor.class));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load module descriptor: " + moduleId, e);
        }
    }

    @Override
    public List<ModuleDescriptor> listActive() {
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        List<ModuleDescriptor> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(baseDir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    ModuleDescriptor d = mapper.readValue(p.toFile(), ModuleDescriptor.class);
                    if (d.desiredState() == ModuleDescriptor.DesiredState.ACTIVE) {
                        result.add(d);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("failed to parse module descriptor: " + p, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list module store: " + baseDir, e);
        }
        return result;
    }

    @Override
    public void remove(String moduleId) {
        try {
            Files.deleteIfExists(fileFor(moduleId));
            deleteRecursively(historyDir(moduleId));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to delete module descriptor: " + moduleId, e);
        }
    }

    @Override
    public List<ModuleVersion> history(String moduleId) {
        Path dir = historyDir(moduleId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<ModuleVersion> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    String stem = p.getFileName().toString();
                    long seq = Long.parseLong(stem.substring(0, stem.length() - ".json".length()));
                    ModuleDescriptor d = mapper.readValue(p.toFile(), ModuleDescriptor.class);
                    long savedAt = Files.getLastModifiedTime(p).toMillis();
                    result.add(new ModuleVersion(seq, d.version(), savedAt, d.desiredState()));
                } catch (IOException e) {
                    throw new UncheckedIOException("failed to parse version history: " + p, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read version history: " + dir, e);
        }
        result.sort((a, b) -> Long.compare(b.seq(), a.seq()));  // newest first
        return result;
    }

    @Override
    public Optional<ModuleDescriptor> loadVersion(String moduleId, String version) {
        Path dir = historyDir(moduleId);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            // If the same version appears multiple times, pick the one with the largest seq (= most recent).
            return files.filter(p -> p.toString().endsWith(".json"))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .map(p -> {
                        try {
                            return mapper.readValue(p.toFile(), ModuleDescriptor.class);
                        } catch (IOException e) {
                            throw new UncheckedIOException("failed to load version descriptor: " + p, e);
                        }
                    })
                    .filter(d -> d.version().equals(version))
                    .findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read version history: " + dir, e);
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())  // deepest first
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException("failed to delete history: " + p, e);
                        }
                    });
        }
    }

    private Path fileFor(String moduleId) {
        return baseDir.resolve(moduleId + ".json");
    }

    private Path historyDir(String moduleId) {
        return baseDir.resolve(moduleId + ".history");
    }
}
