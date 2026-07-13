/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * delta/patch update merge. Overlays partial files (add/replace) and a removal list onto the current
 * descriptor to produce a <b>complete</b> {@link ModuleDescriptor}. The result is fed into the existing
 * {@code ModulePlatform.update} pipeline (no new core path; full-replace remains canonical). Non-patch
 * fields (isolation mode, bridged, signature, etc.) retain their current values.
 *
 * <p>Shared by REST {@code PATCH} and MCP {@code patch_module} (does not depend on MCP-specific normalization).
 */
public final class ModulePatch {

    private ModulePatch() {
    }

    /** A single patch file. For source/test the FQCN is derived from filename; for resource the filename is the classpath path. */
    public record FileSpec(String kind, String filename, String content, boolean base64) {
    }

    /**
     * @param current    current deployed descriptor (overlay base)
     * @param newVersion new version (keeps the current version if blank)
     * @param files      files to add/replace (replaces on key collision)
     * @param removeKeys keys to remove (source/test FQCN or resource path)
     */
    public static ModuleDescriptor apply(ModuleDescriptor current, String newVersion,
                                         List<FileSpec> files, List<String> removeKeys) {
        Map<String, String> sources = new LinkedHashMap<>(current.sources());
        Map<String, String> tests = new LinkedHashMap<>(current.tests());
        Map<String, ModuleResource> resources = new LinkedHashMap<>(current.resources());

        if (files != null) {
            for (FileSpec f : files) {
                if ("resource".equalsIgnoreCase(f.kind())) {
                    resources.put(ResourcePaths.normalize(f.filename()),
                            new ModuleResource(f.content(), f.base64()));
                    continue;
                }
                String name = f.filename();
                String stem = name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
                String fqcn = ModuleManifestLoader.deriveFqcn(stem, f.content());
                if ("test".equalsIgnoreCase(f.kind())) {
                    tests.put(fqcn, f.content());
                } else {
                    sources.put(fqcn, f.content());
                }
            }
        }
        if (removeKeys != null) {
            for (String k : removeKeys) {
                sources.remove(k);
                tests.remove(k);
                resources.remove(k);
                resources.remove(ResourcePaths.normalize(k));   // resources are keyed by normalized path
            }
        }

        String version = (newVersion != null && !newVersion.isBlank()) ? newVersion : current.version();
        // Non-patch fields (isolation mode, bridged, signature, trustTier, etc.) retain current values via toBuilder functional update.
        return current.toBuilder()
                .version(version)
                .sources(sources).tests(tests).resources(resources)
                .build();
    }
}
