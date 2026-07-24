/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.db;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.module.ModuleDescriptor;
import org.htcom.protean.module.ModulePlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Coordinates the scope lifecycle for the scope-admin surface (REST {@code /platform/scopes} + MCP {@code scope.*}).
 * Sits above the pure registry ({@link ScopeManager}) and orchestrates the cross-cutting work an operator action needs:
 * undeploying the scope's modules, reclaiming its workers, and running the vendor DDL through the {@link DbScopeProvisioner}.
 *
 * <p>Only present under {@code protean.worker.db.auto-provision=true} (scopes exist only then). The scope state machine:
 * <pre>
 *   create/open ─▶ ACTIVE ──close──▶ CLOSED ──open──▶ ACTIVE
 *                    │
 *                    ├──detach──▶ DETACHED   (login dropped, DB+data retained; re-create re-provisions)
 *                    └──destroy─▶ (removed)  (DROP DATABASE/SCHEMA — irreversible; guarded)
 * </pre>
 *
 * <p><b>Destroy is guarded</b>: it requires {@code protean.worker.db.allow-destroy=true} <i>and</i> the caller to echo
 * the scope name as confirmation, and it is audit-logged. Default (allow-destroy off) it is refused — data deletion is a
 * DBA-owned action, not a routine deploy-path operation.
 */
@Service
@Profile("!worker")
@ConditionalOnProperty(name = "protean.worker.db.auto-provision", havingValue = "true")
public class ScopeAdminService {

    private static final Logger log = LoggerFactory.getLogger(ScopeAdminService.class);

    /** A scope's registry state plus the count of ACTIVE modules currently bound to it. */
    public record ScopeView(String name, ScopeRecord.State state, String dialectId, int modules) {
    }

    private final ScopeManager scopes;
    private final DbScopeProvisioner provisioner;
    private final ModulePlatform platform;
    private final List<ScopeReclaimable> reclaimables;
    private final ProteanProperties props;

    public ScopeAdminService(ScopeManager scopes, DbScopeProvisioner provisioner, ModulePlatform platform,
                             List<ScopeReclaimable> reclaimables, ProteanProperties props) {
        this.scopes = scopes;
        this.provisioner = provisioner;
        this.platform = platform;
        this.reclaimables = reclaimables;
        this.props = props;
    }

    private String dialectId() {
        return provisioner.dialect().id();
    }

    private ScopeView view(ScopeRecord rec) {
        return new ScopeView(rec.name(), rec.state(), rec.dialectId(), moduleIdsIn(rec.name()).size());
    }

    /** Module ids currently bound to a scope (from the durable module store). */
    private List<String> moduleIdsIn(String scopeName) {
        return platform.list().stream()
                .filter(d -> scopeName.equals(d.scope()))
                .map(ModuleDescriptor::id)
                .toList();
    }

    public List<ScopeView> list() {
        return scopes.listAll(dialectId()).stream().map(this::view).toList();
    }

    public Optional<ScopeView> get(String name) {
        if (!scopes.isKnown(name)) {
            return Optional.empty();
        }
        return scopes.listAll(dialectId()).stream()
                .filter(r -> r.name().equals(name))
                .findFirst()
                .map(this::view);
    }

    /** Create (or reopen) a scope as ACTIVE. The DATABASE is provisioned lazily on the next deploy. Idempotent. */
    public ScopeView create(String name) {
        requireName(name);
        scopes.create(name, dialectId());
        log.info("scope admin: created/opened scope '{}' (dialect {})", name, dialectId());
        return get(name).orElseThrow();
    }

    /** Close a scope: removed from the deployable allowlist; running modules keep serving. Reversible via {@link #open}. */
    public ScopeView close(String name) {
        requireKnown(name);
        scopes.close(name, dialectId());
        log.info("scope admin: closed scope '{}' (running modules keep serving; new deploys rejected)", name);
        return get(name).orElseThrow();
    }

    /** Reopen a CLOSED scope back to ACTIVE. */
    public ScopeView open(String name) {
        requireKnown(name);
        scopes.open(name, dialectId());
        log.info("scope admin: reopened scope '{}'", name);
        return get(name).orElseThrow();
    }

    /**
     * Detach a scope: undeploy its modules, reclaim its workers, and drop only its DB login — the DATABASE/SCHEMA and
     * all data are retained. Reversible: re-creating the scope and redeploying re-provisions the login.
     */
    public ScopeView detach(String name) {
        requireKnown(name);
        int undeployed = takeDownModules(name);
        provisioner.detach(name);
        scopes.markDetached(name, dialectId());
        log.info("scope admin: detached scope '{}' — {} module(s) undeployed, login dropped, data retained", name, undeployed);
        return get(name).orElseThrow();
    }

    /**
     * Destroy a scope: irreversibly drop its DATABASE/SCHEMA (CASCADE) and login after taking down its modules.
     * Guarded — requires {@code allow-destroy=true} and {@code confirm} to equal {@code name}.
     */
    public void destroy(String name, String confirm) {
        requireKnown(name);
        if (!props.getWorker().getDb().isAllowDestroy()) {
            throw new IllegalStateException("scope destroy is disabled — data deletion requires "
                    + "protean.worker.db.allow-destroy=true (DBA-owned action)");
        }
        if (confirm == null || !confirm.equals(name)) {
            throw new IllegalStateException("destroy confirmation mismatch: pass confirm='" + name
                    + "' to acknowledge irreversible deletion of scope '" + name + "'");
        }
        int undeployed = takeDownModules(name);
        provisioner.destroy(name);
        scopes.remove(name);
        log.warn("scope admin: DESTROYED scope '{}' — {} module(s) undeployed, DATABASE/SCHEMA dropped (irreversible)",
                name, undeployed);
        if (scopes.seedNames().contains(name)) {
            log.warn("scope '{}' is still listed in protean.worker.db.scopes — it will reappear as an empty ACTIVE "
                    + "scope on the next restart; remove it from config to retire it permanently", name);
        }
    }

    /** Undeploys every module bound to the scope and drops each strategy's cached provision. Returns the count taken down. */
    private int takeDownModules(String name) {
        List<String> moduleIds = moduleIdsIn(name);
        for (String moduleId : moduleIds) {
            platform.uninstall(moduleId);
        }
        for (ScopeReclaimable r : reclaimables) {
            r.forgetScope(name);
        }
        return moduleIds.size();
    }

    private void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("scope name must not be blank");
        }
    }

    private void requireKnown(String name) {
        requireName(name);
        if (!scopes.isKnown(name)) {
            throw new IllegalArgumentException("unknown scope '" + name + "'");
        }
    }
}
