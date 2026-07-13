/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.compiler;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resource pattern resolver for module child contexts. The standard {@link PathMatchingResourcePatternResolver}
 * cannot enumerate directories on the in-memory {@link ModuleClassLoader}, so {@code classpath*:} patterns fail
 * to find module resources and classes. In that case this resolver augments the result by Ant-pattern matching
 * against the module class loader's {@link ModuleClassLoader#resourceIndex()} → making
 * {@code @MapperScan}/{@code @EntityScan}/{@code mapper-locations=classpath*:...} work.
 *
 * <p>Parent/host resources are handled by {@code super} and unioned with that result. For patterns that are not
 * {@code classpath*:}, or when the class loader is not a module class loader, this delegates purely.
 */
public class ProteanResourcePatternResolver extends PathMatchingResourcePatternResolver {

    public ProteanResourcePatternResolver(ResourceLoader resourceLoader) {
        super(resourceLoader);
    }

    @Override
    public Resource[] getResources(String locationPattern) throws IOException {
        Resource[] base = super.getResources(locationPattern);

        if (!(getClassLoader() instanceof ModuleClassLoader mcl)
                || !locationPattern.startsWith(CLASSPATH_ALL_URL_PREFIX)) {
            return base;
        }
        String subPattern = locationPattern.substring(CLASSPATH_ALL_URL_PREFIX.length());
        // A plain path (not a pattern) is already handled by super via CL.getResources(exact name) → no augmentation needed.
        if (!getPathMatcher().isPattern(subPattern)) {
            return base;
        }

        List<Resource> extra = new ArrayList<>();
        for (String path : mcl.resourceIndex()) {
            if (getPathMatcher().match(subPattern, path)) {
                URL url = mcl.resourceUrl(path);
                if (url != null) {
                    extra.add(new UrlResource(url));
                }
            }
        }
        if (extra.isEmpty()) {
            return base;
        }
        // union (deduplicated by description) — parent results + module index matches.
        Map<String, Resource> merged = new LinkedHashMap<>();
        for (Resource r : base) {
            merged.put(r.getDescription(), r);
        }
        for (Resource r : extra) {
            merged.put(r.getDescription(), r);
        }
        return merged.values().toArray(new Resource[0]);
    }
}
