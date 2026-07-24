/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.db;

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
 * Filesystem-backed {@link ScopeStore}. Records live at {@code <module-store.dir>/scopes/<name>.json}, written via a
 * temp file + atomic move (crash-safe). Active when {@code module-store.backend=filesystem} (the default).
 */
@Component
@Profile("!worker")
@ConditionalOnProperty(name = "protean.module-store.backend", havingValue = "filesystem", matchIfMissing = true)
public class FileSystemScopeStore implements ScopeStore {

    private final Path baseDir;
    private final ObjectMapper mapper;

    public FileSystemScopeStore(ProteanProperties props, ObjectMapper mapper) {
        this.baseDir = Path.of(props.getModuleStore().getDir()).resolve("scopes");
        this.mapper = mapper;
    }

    @Override
    public void save(ScopeRecord scope) {
        try {
            Files.createDirectories(baseDir);
            Path tmp = Files.createTempFile(baseDir, scope.name(), ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), scope);
            Files.move(tmp, fileFor(scope.name()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save scope: " + scope.name(), e);
        }
    }

    @Override
    public Optional<ScopeRecord> load(String name) {
        Path file = fileFor(name);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), ScopeRecord.class));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load scope: " + name, e);
        }
    }

    @Override
    public List<ScopeRecord> list() {
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        List<ScopeRecord> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(baseDir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    result.add(mapper.readValue(p.toFile(), ScopeRecord.class));
                } catch (IOException e) {
                    throw new UncheckedIOException("failed to parse scope: " + p, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list scopes: " + baseDir, e);
        }
        return result;
    }

    @Override
    public void remove(String name) {
        try {
            Files.deleteIfExists(fileFor(name));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to delete scope: " + name, e);
        }
    }

    private Path fileFor(String name) {
        return baseDir.resolve(name + ".json");
    }
}
