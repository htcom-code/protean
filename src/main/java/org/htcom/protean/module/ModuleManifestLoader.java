/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.error.ProteanException;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Converts a {@code module.yaml} declarative manifest into a {@link ModuleDescriptor}.
 * This is the entry point that lets modules previously expressed only in memory or as JSON
 * be declared as files.
 *
 * <p>Sources and tests support both styles at once (merged):
 * <ul>
 *   <li>Inline: {@code sources}/{@code tests} maps (FQCN &rarr; source string) — also suitable for an HTTP body</li>
 *   <li>File: scan of *.java under {@code sourceDir}/{@code testDir} (relative to the manifest) —
 *       the FQCN is derived from each file's {@code package} declaration plus its file name</li>
 * </ul>
 *
 * <pre>{@code
 * id: my-mod
 * version: 1.0.0
 * trustTier: TRUSTED          # optional (default TRUSTED)
 * isolationMode: in-process   # optional (null = global default)
 * needsSharedBeans: false     # optional (default false)
 * controller: com.foo.Ctrl    # required
 * components: [com.foo.Ctrl]  # optional (default [controller])
 * sourceDir: src              # optional
 * testDir: test               # optional
 * }</pre>
 */
@Component
public class ModuleManifestLoader {

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    /** Reads a manifest file and converts it into a descriptor (file references are resolved against the manifest directory). */
    public ModuleDescriptor load(Path manifestFile) {
        try {
            String yaml = Files.readString(manifestFile);
            return fromYaml(yaml, manifestFile.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read manifest: " + manifestFile, e);
        }
    }

    /**
     * Converts a YAML string into a descriptor.
     *
     * @param baseDir base directory for resolving file references (sourceDir/testDir). If null, file references are not allowed (inline only).
     */
    public ModuleDescriptor fromYaml(String yaml, Path baseDir) {
        Object parsed = new Yaml().load(yaml);
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new ProteanException(ErrorCode.INVALID_ARGUMENT, "manifest is empty or not a mapping");
        }

        String id = requireString(root, "id");
        String version = requireString(root, "version");
        ModuleDescriptor.ModuleKind kind = ModuleDescriptor.ModuleKind.valueOf(
                stringOr(root, "kind", "NORMAL").toUpperCase(java.util.Locale.ROOT));
        // A library module registers no routes, so it has no controller; a normal module requires one.
        String controller = kind == ModuleDescriptor.ModuleKind.LIBRARY
                ? stringOr(root, "controller", null)
                : requireString(root, "controller");

        ModuleDescriptor.TrustTier trustTier = ModuleDescriptor.TrustTier.valueOf(
                stringOr(root, "trustTier", "TRUSTED"));
        String isolationMode = stringOr(root, "isolationMode", null);
        boolean needsSharedBeans = Boolean.parseBoolean(stringOr(root, "needsSharedBeans", "false"));
        List<String> components = stringList(root, "components");
        if (components.isEmpty() && controller != null) {
            components = List.of(controller);
        }
        List<String> bridged = stringList(root, "bridgedInterfaces");
        List<String> exports = stringList(root, "exports");
        List<String> uses = stringList(root, "uses");

        Map<String, String> sources = mergeSources(root, "sources", "sourceDir", baseDir);
        Map<String, String> tests = mergeSources(root, "tests", "testDir", baseDir);
        Map<String, ModuleResource> resources = mergeResources(root, "resources", "resourceDir", baseDir);

        return ModuleDescriptor.builder()
                .id(id).version(version).trustTier(trustTier)
                .desiredState(ModuleDescriptor.DesiredState.ACTIVE)
                .controllerFqcn(controller).componentFqcns(components)
                .sources(sources).tests(tests).needsSharedBeans(needsSharedBeans)
                .isolationMode(isolationMode).bridgedInterfaces(bridged.isEmpty() ? null : bridged)
                .resources(resources)
                .kind(kind).exports(exports).uses(uses)
                .build();
    }

    /**
     * Merges the inline {@code resources} map (path &rarr; plain text) with a {@code resourceDir} scan (arbitrary files &rarr; binary).
     * Paths are normalized and validated via {@link ResourcePaths#normalize}.
     */
    private Map<String, ModuleResource> mergeResources(Map<?, ?> root, String inlineKey, String dirKey, Path baseDir) {
        Map<String, ModuleResource> out = new LinkedHashMap<>();
        Object inline = root.get(inlineKey);
        if (inline instanceof Map<?, ?> m) {
            m.forEach((k, v) ->
                    out.put(ResourcePaths.normalize(String.valueOf(k)), ModuleResource.text(String.valueOf(v))));
        }
        String dir = stringOr(root, dirKey, null);
        if (dir != null) {
            if (baseDir == null) {
                throw new ProteanException(ErrorCode.INVALID_ARGUMENT,
                        "'" + dirKey + "' can only be used with a file-based manifest (no baseDir)");
            }
            out.putAll(scanResourceDir(baseDir.resolve(dir)));
        }
        return out;
    }

    /** Scans all files under a directory into (root-relative path &rarr; binary resource) entries. */
    private Map<String, ModuleResource> scanResourceDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new ProteanException(ErrorCode.INVALID_ARGUMENT, "resource directory does not exist: " + dir);
        }
        Map<String, ModuleResource> out = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            List<Path> files = paths.filter(Files::isRegularFile).toList();
            for (Path p : files) {
                String rel = dir.relativize(p).toString();
                out.put(ResourcePaths.normalize(rel), ModuleResource.binary(Files.readAllBytes(p)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to scan resource directory: " + dir, e);
        }
        return out;
    }

    /** FQCN &rarr; source map merged from the inline map and a directory scan. */
    private Map<String, String> mergeSources(Map<?, ?> root, String inlineKey, String dirKey, Path baseDir) {
        Map<String, String> out = new LinkedHashMap<>();
        Object inline = root.get(inlineKey);
        if (inline instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        }
        String dir = stringOr(root, dirKey, null);
        if (dir != null) {
            if (baseDir == null) {
                throw new ProteanException(ErrorCode.INVALID_ARGUMENT,
                        "'" + dirKey + "' can only be used with a file-based manifest (no baseDir)");
            }
            out.putAll(scanJavaDir(baseDir.resolve(dir)));
        }
        return out;
    }

    /** Scans *.java under a directory into an FQCN &rarr; content map. FQCN = package declaration + file name (without extension). */
    private Map<String, String> scanJavaDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new ProteanException(ErrorCode.INVALID_ARGUMENT, "source directory does not exist: " + dir);
        }
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            List<Path> javaFiles = new ArrayList<>(paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList());
            for (Path p : javaFiles) {
                String content = Files.readString(p);
                String fileName = p.getFileName().toString();
                String stem = fileName.substring(0, fileName.length() - ".java".length());
                out.put(deriveFqcn(stem, content), content);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to scan source directory: " + dir, e);
        }
        return out;
    }

    /** FQCN = {@code package} declaration + class stem (file name minus .java). Also reused for MCP files[] input. */
    public static String deriveFqcn(String classStem, String content) {
        Matcher m = PACKAGE.matcher(content);
        return m.find() ? m.group(1) + "." + classStem : classStem;
    }

    private String requireString(Map<?, ?> root, String key) {
        Object v = root.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ProteanException(ErrorCode.INVALID_ARGUMENT, "missing required manifest field: " + key)
                    .with("missingFields", List.of(key));
        }
        return String.valueOf(v);
    }

    private String stringOr(Map<?, ?> root, String key, String fallback) {
        Object v = root.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private List<String> stringList(Map<?, ?> root, String key) {
        Object v = root.get(key);
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
