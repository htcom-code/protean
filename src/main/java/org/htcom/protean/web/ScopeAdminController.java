/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.web;

import org.htcom.protean.db.ScopeAdminService;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Control-plane REST API for the DB <b>scope</b> lifecycle (tenant/business-domain grouping under
 * {@code worker.db.auto-provision}). An operator creates/opens/closes scopes and, guarded, detaches or destroys them;
 * deployers only <i>select</i> a scope (they cannot create one). Every operation delegates to {@link ScopeAdminService}.
 *
 * <p>Uses explicit action sub-resources rather than the DELETE verb — {@code detach} (data-safe) and {@code destroy}
 * (irreversible) are distinct, consequential operations that must not collapse into a single ambiguous DELETE.
 *
 * <p>Active only when both the management surface is enabled ({@code protean.admin.enabled}, default on) and
 * auto-provision is on ({@code protean.worker.db.auto-provision=true} — scopes exist only then, which is also the
 * condition for the {@link ScopeAdminService} bean this controller depends on).
 */
@RestController
@Profile("!worker")
@Conditional(ScopeAdminController.Enabled.class)
@RequestMapping("/platform/scopes")
public class ScopeAdminController {

    /** admin.enabled (default on) AND auto-provision=true — both must hold for the scope admin surface to exist. */
    static final class Enabled extends AllNestedConditions {
        Enabled() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(name = "protean.admin.enabled", havingValue = "true", matchIfMissing = true)
        static class Admin {
        }

        @ConditionalOnProperty(name = "protean.worker.db.auto-provision", havingValue = "true")
        static class AutoProvision {
        }
    }

    /** Create-scope request body. */
    public record CreateScopeRequest(String name) {
    }

    private final ScopeAdminService scopes;

    public ScopeAdminController(ScopeAdminService scopes) {
        this.scopes = scopes;
    }

    /** All known scopes (registry ∪ seed) with their state and current module count. */
    @GetMapping
    public List<ScopeAdminService.ScopeView> list() {
        return scopes.list();
    }

    /** Single scope (404 if unknown). */
    @GetMapping("/{name}")
    public ResponseEntity<ScopeAdminService.ScopeView> get(@PathVariable String name) {
        return ResponseEntity.of(scopes.get(name));
    }

    /** Create (or reopen) a scope as ACTIVE. The DATABASE is provisioned lazily on the first deploy. Returns 201. */
    @PostMapping
    public ResponseEntity<ScopeAdminService.ScopeView> create(@RequestBody CreateScopeRequest req) {
        ScopeAdminService.ScopeView view = scopes.create(req.name());
        return ResponseEntity.created(URI.create("/platform/scopes/" + view.name())).body(view);
    }

    /** Close a scope — new deploys rejected; running modules keep serving. Reversible via {@code /open}. */
    @PostMapping("/{name}/close")
    public ScopeAdminService.ScopeView close(@PathVariable String name) {
        return scopes.close(name);
    }

    /** Reopen a CLOSED scope back to ACTIVE. */
    @PostMapping("/{name}/open")
    public ScopeAdminService.ScopeView open(@PathVariable String name) {
        return scopes.open(name);
    }

    /** Detach — undeploy the scope's modules and drop its login; DATABASE/data retained (reversible via re-create). */
    @PostMapping("/{name}/detach")
    public ScopeAdminService.ScopeView detach(@PathVariable String name) {
        return scopes.detach(name);
    }

    /**
     * Destroy — irreversibly drop the scope's DATABASE/SCHEMA after undeploying its modules. Guarded: requires
     * {@code protean.worker.db.allow-destroy=true} and {@code confirm} to equal the scope name.
     */
    @PostMapping("/{name}/destroy")
    public ResponseEntity<Map<String, Object>> destroy(@PathVariable String name, @RequestParam String confirm) {
        scopes.destroy(name, confirm);
        return ResponseEntity.ok(Map.of("name", name, "destroyed", true));
    }
}
