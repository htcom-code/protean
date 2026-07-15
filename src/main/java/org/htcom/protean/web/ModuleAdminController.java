/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.web;

import org.htcom.protean.dynamic.DynamicEndpointRegistrar;
import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.error.ProteanException;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModuleManifestLoader;
import org.htcom.protean.module.ModulePatch;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.module.ModuleResource;
import org.htcom.protean.module.ModuleVersion;
import org.htcom.protean.proxy.ReverseProxy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Control-plane REST API — MCP/external callers deploy, update, uninstall, and query modules over
 * HTTP. Every operation delegates to the {@link ModulePlatform} facade, so promotion gates (1/2),
 * validation (3), and isolation routing all still apply as before. This is a thin layer mapping the
 * lifecycle to HTTP semantics.
 *
 * <p>Authentication/authorization is a separate concern (the current trust model assumes trusted
 * developers).
 *
 * <p>Library consumers may not want to expose this management surface, so it can be opted out:
 * disable it with {@code protean.admin.enabled=false} (default on — preserves existing behavior).
 */
@RestController
@Profile("!worker")
@ConditionalOnProperty(name = "protean.admin.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/platform/modules")
public class ModuleAdminController {

    private final ModulePlatform platform;
    private final ModuleManifestLoader manifestLoader;
    private final DynamicEndpointRegistrar registrar;
    private final ReverseProxy reverseProxy;

    public ModuleAdminController(ModulePlatform platform, ModuleManifestLoader manifestLoader,
                                 DynamicEndpointRegistrar registrar, ReverseProxy reverseProxy) {
        this.platform = platform;
        this.manifestLoader = manifestLoader;
        this.registrar = registrar;
        this.reverseProxy = reverseProxy;
    }

    /** List of ACTIVE module statuses. */
    @GetMapping
    public List<ModuleStatus> list() {
        return platform.list().stream()
                .map(d -> ModuleStatus.from(d, platform.effectiveMode(d), platform.boundGeneration(d.id()),
                        platform.boundLibraryGenerations(d.id()), platform.libraryGeneration(d.id())))
                .toList();
    }

    /** Single module status (404 if not found). */
    @GetMapping("/{id}")
    public ModuleStatus get(@PathVariable String id) {
        ModuleDescriptor d = platform.find(id)
                .orElseThrow(() -> new ProteanException(ErrorCode.MODULE_NOT_FOUND, id).with("moduleId", id));
        return ModuleStatus.from(d, platform.effectiveMode(d), platform.boundGeneration(id),
                platform.boundLibraryGenerations(id), platform.libraryGeneration(id));
    }

    /** Deploy a module. On passing the gates/validation, returns 201 + Location. */
    @PostMapping
    public ResponseEntity<ModuleStatus> install(@RequestBody ModuleDescriptor descriptor) {
        platform.install(descriptor);
        ModuleDescriptor saved = platform.find(descriptor.id())
                .orElseThrow(() -> new IllegalStateException("module not found in store immediately after deploy: " + descriptor.id()));
        return ResponseEntity
                .created(URI.create("/platform/modules/" + saved.id()))
                .body(ModuleStatus.from(saved, platform.effectiveMode(saved)));
    }

    /** Deploy from a declarative module.yaml manifest (inline sources). The body is YAML text. */
    @PostMapping(value = "/from-manifest", consumes = {"text/plain", "application/yaml", "application/x-yaml"})
    public ResponseEntity<ModuleStatus> installFromManifest(@RequestBody String yaml) {
        ModuleDescriptor descriptor = manifestLoader.fromYaml(yaml, null);
        platform.install(descriptor);
        ModuleDescriptor saved = platform.find(descriptor.id())
                .orElseThrow(() -> new IllegalStateException("module not found in store immediately after deploy: " + descriptor.id()));
        return ResponseEntity
                .created(URI.create("/platform/modules/" + saved.id()))
                .body(ModuleStatus.from(saved, platform.effectiveMode(saved)));
    }

    /** Canary module update (hot-swap). The path id and body id must match. */
    @PutMapping("/{id}")
    public ModuleStatus update(@PathVariable String id, @RequestBody ModuleDescriptor descriptor) {
        if (!id.equals(descriptor.id())) {
            throw new ProteanException(ErrorCode.INVALID_ARGUMENT,
                    "path id (" + id + ") does not match body id (" + descriptor.id() + ")");
        }
        if (platform.find(id).isEmpty()) {
            throw new ProteanException(ErrorCode.MODULE_NOT_FOUND, id).with("moduleId", id);
        }
        platform.update(descriptor);
        ModuleDescriptor saved = platform.find(id)
                .orElseThrow(() -> new IllegalStateException("module not found in store immediately after update: " + id));
        return ModuleStatus.from(saved, platform.effectiveMode(saved));
    }

    /**
     * Delta/patch update — send only changed files, overlay them onto the current descriptor, then
     * perform a canary update. Full-replace (PUT) is canonical; this is a convenience for input
     * assembly. Deletions only via an explicit {@code removeFiles}.
     */
    @PatchMapping("/{id}")
    public ModuleStatus patch(@PathVariable String id, @RequestBody ModulePatchRequest req) {
        ModuleDescriptor current = platform.find(id)
                .orElseThrow(() -> new ProteanException(ErrorCode.MODULE_NOT_FOUND, id).with("moduleId", id));
        ModuleDescriptor merged = ModulePatch.apply(current, req.version(),
                req.files() == null ? List.of() : req.files(), req.removeFiles());
        platform.update(merged);
        ModuleDescriptor saved = platform.find(id)
                .orElseThrow(() -> new IllegalStateException("module not found in store immediately after patch: " + id));
        return ModuleStatus.from(saved, platform.effectiveMode(saved));
    }

    /**
     * Resource live-reload — replace resources in place without recompiling or rebuilding the
     * context. For resources read on each request (ineffective for resources parsed once at ORM
     * init). Only resource files are allowed (kind=resource).
     */
    @PostMapping("/{id}/reload-resources")
    public ModuleStatus reloadResources(@PathVariable String id, @RequestBody ModulePatchRequest req) {
        if (platform.find(id).isEmpty()) {
            throw new ProteanException(ErrorCode.MODULE_NOT_FOUND, id).with("moduleId", id);
        }
        Map<String, ModuleResource> add = new java.util.LinkedHashMap<>();
        if (req.files() != null) {
            for (ModulePatch.FileSpec f : req.files()) {
                if (f.kind() != null && !f.kind().isBlank() && !"resource".equalsIgnoreCase(f.kind())) {
                    throw new ProteanException(ErrorCode.INVALID_ARGUMENT,
                            "reload-resources accepts resources only (kind=resource): " + f.filename());
                }
                add.put(f.filename(), new ModuleResource(f.content(), f.base64()));
            }
        }
        platform.reloadResources(id, add, req.removeFiles());
        ModuleDescriptor saved = platform.find(id)
                .orElseThrow(() -> new IllegalStateException("module not found immediately after resource reload: " + id));
        return ModuleStatus.from(saved, platform.effectiveMode(saved));
    }

    /** Module version history (newest-first). */
    @GetMapping("/{id}/versions")
    public List<ModuleVersion> versions(@PathVariable String id) {
        if (platform.find(id).isEmpty()) {
            throw new ProteanException(ErrorCode.MODULE_NOT_FOUND, id).with("moduleId", id);
        }
        return platform.history(id);
    }

    /**
     * Routes the module <b>actually registered on the live mapping</b> (404 if the module is not
     * found). This reflects the measured runtime registration, not the store's desiredState, so it
     * surfaces "ACTIVE but 404" mismatches as an empty list. Aggregates across isolation modes
     * identically: in-process routes come from {@link DynamicEndpointRegistrar} and worker/container
     * routes from {@link ReverseProxy}, both carrying the HTTP method set + path patterns. A module is
     * served by exactly one of the two, so no de-duplication is needed.
     */
    @GetMapping("/{id}/routes")
    public List<DynamicEndpointRegistrar.RouteInfo> routes(@PathVariable String id) {
        if (platform.find(id).isEmpty()) {
            throw new ProteanException(ErrorCode.MODULE_NOT_FOUND, id).with("moduleId", id);
        }
        List<DynamicEndpointRegistrar.RouteInfo> routes = new ArrayList<>(registrar.routesOf(id));
        routes.addAll(reverseProxy.routesForModule(id));
        return routes;
    }

    /** Explicit rollback: revert to {version} from history (canary hot-swap + gates/validation, auto-rollback on failure). */
    @PostMapping("/{id}/rollback")
    public ModuleStatus rollback(@PathVariable String id, @RequestParam String version) {
        platform.rollback(id, version);
        ModuleDescriptor saved = platform.find(id)
                .orElseThrow(() -> new IllegalStateException("module not found in store immediately after rollback: " + id));
        return ModuleStatus.from(saved, platform.effectiveMode(saved));
    }

    /** Promote a PENDING_APPROVAL module via human authorization (validation 3 + deploy -> ACTIVE). The approver identity is audit-logged. */
    @PostMapping("/{id}/approve")
    public ModuleStatus approve(@PathVariable String id, @RequestParam String approver) {
        platform.approve(id, approver);
        ModuleDescriptor saved = platform.find(id)
                .orElseThrow(() -> new IllegalStateException("module not found in store immediately after approval: " + id));
        return ModuleStatus.from(saved, platform.effectiveMode(saved));
    }

    /** Reject a pending-approval module and remove it. Returns 204 on success. */
    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable String id, @RequestParam String approver) {
        platform.reject(id, approver);
        return ResponseEntity.noContent().build();
    }

    /** Uninstall a module. Returns 204 on success. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> uninstall(@PathVariable String id) {
        if (platform.find(id).isEmpty()) {
            throw new ProteanException(ErrorCode.MODULE_NOT_FOUND, id).with("moduleId", id);
        }
        platform.uninstall(id);
        return ResponseEntity.noContent().build();
    }
}
