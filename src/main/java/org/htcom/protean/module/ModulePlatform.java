/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.compiler.UsedSharedLib;
import org.htcom.protean.gate.PromotionPipeline;
import org.htcom.protean.gate.VerificationGate;
import org.htcom.protean.isolation.IsolationStrategy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Module platform facade — ties together the durable store (ModuleStore) and the isolation
 * strategy (IsolationStrategy).
 *
 * <b>Per-module isolation mode</b>: multiple IsolationStrategy beans (in-process/worker) coexist,
 * and each module is routed to a strategy chosen by the descriptor's {@code isolationMode}
 * (null = global default).
 */
@Service
@Profile("!worker")
public class ModulePlatform {

    private static final Logger log = LoggerFactory.getLogger(ModulePlatform.class);

    private final ModuleStore store;
    private final PromotionPipeline promotionPipeline;
    private final VerificationGate verificationGate;
    private final Map<String, IsolationStrategy> strategies;  // mode -> strategy
    private final ProteanProperties props;
    private final RuntimeCompiler compiler;   // reconcile file-manager pool lifecycle
    private final Map<String, String> moduleMode = new ConcurrentHashMap<>();  // id -> mode (undeploy routing)
    /** Module-change (install/approve/reject/update/resource-reload/remove/rollback) listeners — subscribed to by MCP resources/updated notifications and similar. */
    private final List<Consumer<String>> changeListeners = new CopyOnWriteArrayList<>();

    public ModulePlatform(ModuleStore store, List<IsolationStrategy> strategyBeans,
                          PromotionPipeline promotionPipeline, VerificationGate verificationGate,
                          ProteanProperties props, RuntimeCompiler compiler) {
        this.store = store;
        this.promotionPipeline = promotionPipeline;
        this.verificationGate = verificationGate;
        this.strategies = strategyBeans.stream()
                .collect(Collectors.toMap(IsolationStrategy::mode, Function.identity()));
        this.props = props;
        this.compiler = compiler;
    }

    @PostConstruct
    void validateMode() {
        String defaultMode = props.getIsolation().getMode();
        if (!strategies.containsKey(defaultMode)) {
            throw new IllegalStateException("no strategy for default isolation.mode='" + defaultMode
                    + "' (available: " + strategies.keySet() + ")");
        }
    }

    /**
     * The default isolation mode, read live so a runtime {@code protean.isolation.mode} change applies to
     * subsequent deploys (Tier 2 — future deploys only; already-deployed modules keep their mode).
     */
    private String resolveMode(ModuleDescriptor descriptor) {
        return descriptor.isolationMode() != null ? descriptor.isolationMode() : props.getIsolation().getMode();
    }

    /**
     * Registers a module-change listener. When an install/approve/reject/update/resource-reload/remove
     * <b>succeeds</b>, listeners are notified with that module id. Listener exceptions are swallowed so
     * they never affect platform operations (observation only).
     */
    public void addChangeListener(Consumer<String> listener) {
        if (listener != null) {
            changeListeners.add(listener);
        }
    }

    private void notifyChanged(String moduleId) {
        for (Consumer<String> l : changeListeners) {
            try {
                l.accept(moduleId);
            } catch (RuntimeException e) {
                log.warn("module-change listener failed (ignored): {}", e.toString());
            }
        }
    }

    private IsolationStrategy strategyFor(String mode) {
        IsolationStrategy strategy = strategies.get(mode);
        if (strategy == null) {
            throw new IllegalStateException("unknown isolation mode: " + mode + " (available: " + strategies.keySet() + ")");
        }
        return strategy;
    }

    /**
     * Installs a module: mode routing -> compatibility -> automated gates (signature, gate 1, gate 2)
     * -> write-ahead save -> deploy -> gate 3.
     *
     * <p>When the approval gate is enabled ({@code protean.gate.approval.required=true}), only the
     * automated gates are run and the module is saved as PENDING_APPROVAL without being deployed or
     * verified. It must be approved via {@link #approve}, which runs gate 3 verification and deploys
     * it, to become ACTIVE.
     */
    public void install(ModuleDescriptor descriptor) {
        install(descriptor, ModuleProgress.NONE);
    }

    /** {@link #install(ModuleDescriptor)} plus a stage-progress callback. The other signature delegates with {@link ModuleProgress#NONE}. */
    public void install(ModuleDescriptor descriptor, ModuleProgress progress) {
        String mode = resolveMode(descriptor);
        IsolationStrategy isolation = strategyFor(mode);
        if (!isolation.supports(descriptor)) {
            throw new IllegalStateException("isolation mode '" + mode + "' does not support module '"
                    + descriptor.id() + "' (e.g. shared-bean dependency). Deployment rejected.");
        }
        // If the module is already being served (e.g. startup reconcile restored it first), reject
        // without touching any state. Blocking before any state change prevents the deploy-failure
        // rollback below (undeploy + store.remove) from tearing down the existing healthy deployment
        // and store record (safe against duplicate/concurrent install).
        if (moduleMode.containsKey(descriptor.id())) {
            throw new IllegalStateException("module already deployed: " + descriptor.id());
        }
        assertNoUsesCycle(descriptor);
        progress.stage("compile + gates 1 & 2 (test/review)");
        promotionPipeline.runGates(descriptor);
        descriptor = enrichUsedSharedLibs(descriptor);

        // Read live so protean.gate.approval.required changes apply to subsequent installs (Tier 1).
        if (props.getGate().getApproval().isRequired()) {
            // Save what passed the automated gates as PENDING only — not served, not a reconcile target (only ACTIVE is).
            store.save(descriptor.withDesiredState(ModuleDescriptor.DesiredState.PENDING_APPROVAL));
            progress.stage("awaiting approval (PENDING_APPROVAL)");
            log.info("module {} passed automated gates -> awaiting approval (PENDING_APPROVAL); approve required", descriptor.id());
            notifyChanged(descriptor.id());
            return;
        }

        ModuleDescriptor active = descriptor.withDesiredState(ModuleDescriptor.DesiredState.ACTIVE);
        store.save(active);
        moduleMode.put(active.id(), mode);
        try {
            progress.stage("deploy");
            isolation.deploy(active);
            if (!active.isLibrary()) {
                // Gate 3 exercises a live route; a library registers none — its activation is publishing a generation.
                progress.stage("verification (gate 3)");
                verificationGate.verify(active);
            }
            progress.stage("active (ACTIVE)");
        } catch (RuntimeException e) {
            try {
                isolation.undeploy(active.id());
            } catch (RuntimeException ignored) {
            }
            store.remove(active.id());
            moduleMode.remove(active.id());
            throw e;
        }
        notifyChanged(active.id());
    }

    /**
     * Promotes a PENDING_APPROVAL module by human authorization: gate 3 verification + deploy -> ACTIVE.
     * On verification/deploy failure it is reverted to PENDING (not served). The approver's identity
     * is recorded in the audit log.
     */
    public void approve(String moduleId, String approver) {
        ModuleDescriptor pending = store.load(moduleId)
                .orElseThrow(() -> new IllegalStateException("no module to approve: " + moduleId));
        if (pending.desiredState() != ModuleDescriptor.DesiredState.PENDING_APPROVAL) {
            throw new IllegalStateException("not in awaiting-approval state: " + moduleId + " (" + pending.desiredState() + ")");
        }
        String mode = resolveMode(pending);
        IsolationStrategy isolation = strategyFor(mode);
        ModuleDescriptor active = pending.withDesiredState(ModuleDescriptor.DesiredState.ACTIVE);
        store.save(active);
        moduleMode.put(active.id(), mode);
        try {
            isolation.deploy(active);
            if (!active.isLibrary()) {
                verificationGate.verify(active);
            }
        } catch (RuntimeException e) {
            try {
                isolation.undeploy(active.id());
            } catch (RuntimeException ignored) {
            }
            moduleMode.remove(active.id());
            store.save(pending);   // ACTIVE promotion failed -> revert back to PENDING (not served)
            throw e;
        }
        log.info("module {} approved (approver={}) -> ACTIVE", moduleId, approver);
        notifyChanged(moduleId);
    }

    /** Rejects and removes a module awaiting approval. The approver's identity is recorded in the audit log. */
    public void reject(String moduleId, String approver) {
        ModuleDescriptor pending = store.load(moduleId)
                .orElseThrow(() -> new IllegalStateException("no module to reject: " + moduleId));
        if (pending.desiredState() != ModuleDescriptor.DesiredState.PENDING_APPROVAL) {
            throw new IllegalStateException("not in awaiting-approval state: " + moduleId + " (" + pending.desiredState() + ")");
        }
        store.remove(moduleId);
        log.info("module {} rejected (approver={}) -> removed", moduleId, approver);
        notifyChanged(moduleId);
    }

    /** Updates a module gradually (canary): gates 1 and 2 -> hot-swap -> gate 3, with automatic rollback on failure. */
    public void update(ModuleDescriptor next) {
        update(next, ModuleProgress.NONE);
    }

    /** {@link #update(ModuleDescriptor)} plus a stage-progress callback. */
    public void update(ModuleDescriptor next, ModuleProgress progress) {
        ModuleDescriptor current = store.load(next.id())
                .orElseThrow(() -> new IllegalStateException("no module to update: " + next.id()));
        String mode = resolveMode(next);
        // Reject an in-place isolation-mode change: update() hot-swaps with the new mode's strategy while the
        // module is still deployed under the old mode, which would orphan the old-mode instance (a leaked worker
        // JVM) and leave a conflicting route. A mode change is a re-deploy in the target mode (re-verified there),
        // so require uninstall + install until a first-class cross-mode migration exists.
        String currentMode = moduleMode.getOrDefault(next.id(), resolveMode(current));
        if (!mode.equals(currentMode)) {
            throw new IllegalStateException("cannot change the isolation mode of module '" + next.id()
                    + "' via update (" + currentMode + " -> " + mode + "); uninstall and install to change the mode");
        }
        IsolationStrategy isolation = strategyFor(mode);
        if (!isolation.supports(next)) {
            throw new IllegalStateException("isolation mode does not support updating module '" + next.id() + "'.");
        }
        assertNoUsesCycle(next);
        progress.stage("compile + gates 1 & 2 (test/review)");
        promotionPipeline.runGates(next);

        ModuleDescriptor active = enrichUsedSharedLibs(next).withDesiredState(ModuleDescriptor.DesiredState.ACTIVE);
        progress.stage("hot-swap");
        isolation.hotSwap(active);
        try {
            if (!active.isLibrary()) {
                progress.stage("verification (gate 3)");
                verificationGate.verify(active);
            }
        } catch (RuntimeException e) {
            isolation.hotSwap(current);
            throw e;
        }
        store.save(active);
        moduleMode.put(active.id(), mode);
        progress.stage("active (ACTIVE)");
        notifyChanged(active.id());
    }

    /**
     * Plan A rebind: recompiles an ACTIVE in-process module's current sources against the
     * <b>current</b> shared-lib generation and hot-swaps it, re-running the gates. Used by precise invalidation when a
     * shared-lib generation is published. Zero-downtime on the dominant failure mode — an incompatible jar fails at
     * compile/review <b>before</b> any swap, so the module keeps serving on its old generation (sticky Plan B). The
     * compile cache is keyed by generation, so re-running the gates here recompiles against the new generation
     * without disturbing the live module's binding until the swap commits. Throws on failure so the caller records
     * the Plan B outcome; a gate-3 failure occurs after the swap, leaving the newly bound instance deployed.
     */
    public void rebind(String moduleId) {
        ModuleDescriptor current = store.load(moduleId).orElse(null);
        if (current == null || current.desiredState() != ModuleDescriptor.DesiredState.ACTIVE) {
            return;   // only bound, served modules participate
        }
        String mode = moduleMode.getOrDefault(moduleId, resolveMode(current));
        if (!"in-process".equals(mode)) {
            // Worker/container shared-lib propagation is not yet supported; their parent tier is a separate JVM.
            log.debug("rebind skipped for module {} (mode={}, in-process only)", moduleId, mode);
            return;
        }
        IsolationStrategy isolation = strategyFor(mode);
        promotionPipeline.runGates(current);   // recompile (new gen) + gates 1&2 (throws pre-swap → sticky)
        ModuleDescriptor active = enrichUsedSharedLibs(current).withDesiredState(ModuleDescriptor.DesiredState.ACTIVE);
        isolation.hotSwap(active);             // route swaps to the new-generation instance
        if (!active.isLibrary()) {
            verificationGate.verify(active);   // gate 3 (throws post-swap on failure); a library has no route to hit
        }
        store.save(active);                    // usedSharedLibs now carries the new sha → reverse index re-keys
        moduleMode.put(moduleId, mode);
        notifyChanged(moduleId);
        log.info("module {} rebound to shared-lib generation {}", moduleId, compiler.boundGeneration(moduleId).orElse(-1));
    }

    /**
     * Plan A1 rebind: retargets an ACTIVE in-process dependent onto the current parent tier
     * <b>without recompiling</b> — used when a library it {@code uses} republished a binary-compatible generation, so
     * the dependent's existing bytecode can be re-parented onto the new library CL. Returns {@code true} if it was
     * retargeted; {@code false} when this fast path does not apply (module not ACTIVE/in-process, or a library
     * dependent) so the caller falls back to {@link #rebind} (Plan A2, recompile). Throws on hot-swap failure so the
     * caller records Plan B (sticky). No gates run: the dependent's bytecode is unchanged, and the library was already
     * gated at its own publish; A1 is deliberately cheap (delegation retarget only).
     */
    public boolean rebindFast(String moduleId) {
        ModuleDescriptor current = store.load(moduleId).orElse(null);
        if (current == null || current.desiredState() != ModuleDescriptor.DesiredState.ACTIVE) {
            return false;
        }
        String mode = moduleMode.getOrDefault(moduleId, resolveMode(current));
        if (!"in-process".equals(mode)) {
            return false;   // worker/container library propagation is not yet supported
        }
        // Re-run the dependent's TEST gate against the new
        // parent tier BEFORE retargeting. enforceTestGate compiles main+test ephemerally against
        // resolveParentTier(uses) — i.e. the just-published library generation — so a binary-compatible library
        // impl change that alters/breaks this dependent's asserted behavior fails here and throws, which the caller
        // records as Plan B (sticky, stays on the prior library). This closes the gap where A1 propagated a
        // value-changing impl unverified and left the dependent drifted from its own gate. The live module is still
        // retargeted without a full recompile (only the ephemeral gate compile runs), so A1 stays lighter than A2.
        promotionPipeline.enforceTestGate(current);
        boolean retargeted = strategyFor(mode).retargetLibraries(current);
        if (!retargeted) {
            return false;   // not eligible for the fast path → caller uses Plan A2
        }
        notifyChanged(moduleId);
        log.info("module {} retargeted onto the current library tier (Plan A1, gate re-run, no recompile)", moduleId);
        return true;
    }

    /** {@link #reloadResources(String, Map, List, ModuleProgress)} with the default progress callback. */
    public void reloadResources(String moduleId, Map<String, ModuleResource> add, List<String> remove) {
        reloadResources(moduleId, add, remove, ModuleProgress.NONE);
    }

    /**
     * Resource live-reload: after overlaying (add/remove) onto the current resources, replaces the
     * module classloader's resources in place <b>without recompiling or rebuilding the context</b>.
     * Resources read per request take effect immediately. This is ineffective for init-parse resources
     * (e.g. ORM), so it is an op the caller chooses deliberately. If the isolation mode does not
     * support live-reload (worker/container), it falls back to a full update.
     */
    public void reloadResources(String moduleId, Map<String, ModuleResource> add, List<String> remove,
                                ModuleProgress progress) {
        ModuleDescriptor current = store.load(moduleId)
                .orElseThrow(() -> new IllegalStateException("no module to reload resources for: " + moduleId));
        Map<String, ModuleResource> merged = new LinkedHashMap<>(current.resources());
        if (add != null) {
            add.forEach((k, v) -> merged.put(ResourcePaths.normalize(k), v));
        }
        if (remove != null) {
            remove.forEach(k -> merged.remove(ResourcePaths.normalize(k)));
        }
        ModuleDescriptor next = current.withResources(merged);
        IsolationStrategy isolation = strategyFor(resolveMode(next));
        progress.stage("in-place resource swap");
        boolean live = isolation.reloadResources(next);
        if (!live) {
            // This isolation mode does not support live-reload (separate JVM) -> safely fall back to a full update.
            update(next, progress);
            return;
        }
        if (next.verification() != null) {
            progress.stage("verification (gate 3)");
            verificationGate.verify(next);
        }
        store.save(next);
        progress.stage("done");
        notifyChanged(moduleId);
    }

    /** Removes a module: tears it down with the strategy used at deploy time -> deletes it from the store. */
    public void uninstall(String moduleId) {
        String mode = moduleMode.remove(moduleId);
        if (mode == null) {
            mode = store.load(moduleId).map(this::resolveMode).orElse(props.getIsolation().getMode());
        }
        strategyFor(mode).undeploy(moduleId);   // releases the module's generation reference (compiler.evict)
        store.remove(moduleId);
        closeUnreferencedGenerations();          // safe point: the module is torn down → its old generation may now be free
        notifyChanged(moduleId);
    }

    /** For control-plane queries: all ACTIVE modules in the store. */
    public List<ModuleDescriptor> list() {
        return store.listActive();
    }

    /** For control-plane queries: finds a module descriptor by id. */
    public java.util.Optional<ModuleDescriptor> find(String moduleId) {
        return store.load(moduleId);
    }

    /** The actual isolation mode that will apply to the descriptor (null = global default). For status responses. */
    public String effectiveMode(ModuleDescriptor descriptor) {
        return resolveMode(descriptor);
    }

    /**
     * The parent-tier shared-lib generation the module's live ClassLoader is currently bound to, or null when it is
     * not loaded (INACTIVE/PENDING, or worker mode). For status responses (observability).
     */
    public Long boundGeneration(String moduleId) {
        return compiler.boundGeneration(moduleId).stream().boxed().findFirst().orElse(null);
    }

    /**
     * The library-module generation ids a dependent's live ClassLoader is bound to via {@code uses} (shared-module
     * typed sharing), or empty when it uses no libraries / is not loaded. For status responses.
     */
    public List<Long> boundLibraryGenerations(String moduleId) {
        return compiler.boundLibraryGenerations(moduleId);
    }

    /**
     * For a LIBRARY module, the id of its currently published parent-tier generation; null if it is not a library or
     * not published (e.g. INACTIVE/PENDING, or worker mode). For status responses.
     */
    public Long libraryGeneration(String moduleId) {
        org.htcom.protean.compiler.SharedModuleRegistry registry = compiler.sharedModuleRegistry();
        if (registry == null) {
            return null;
        }
        return registry.currentGeneration(moduleId)
                .map(org.htcom.protean.compiler.LibraryGeneration::id).orElse(null);
    }

    /**
     * Unloads shared-lib generations no live module references anymore (leak-safe close). Called
     * at safe points (after invalidation rebinds and after uninstall commit). Returns the number of generations closed.
     */
    public int closeUnreferencedGenerations() {
        return compiler.closeUnreferencedGenerations();
    }

    /** The module's version history (newest first). */
    public List<ModuleVersion> history(String moduleId) {
        return store.history(moduleId);
    }

    /** For control-plane queries: finds a specific version's descriptor (source included) from history. */
    public java.util.Optional<ModuleDescriptor> findVersion(String moduleId, String version) {
        return store.loadVersion(moduleId, version);
    }

    /**
     * Explicitly rolls a module back to a specific version from history.
     * Because it redeploys that version's descriptor, it follows the same safe path as
     * {@link #update} (canary hot-swap + gates 1/2/3 + automatic rollback on failure). The rollback
     * result is also recorded as a new history entry (audit trail).
     */
    public void rollback(String moduleId, String toVersion) {
        if (store.load(moduleId).isEmpty()) {
            throw new IllegalStateException("cannot roll back a module that is not installed: " + moduleId);
        }
        ModuleDescriptor target = store.loadVersion(moduleId, toVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "no version to roll back to: " + moduleId + " v" + toVersion));
        update(target);
    }

    /**
     * Redeploys the store's ACTIVE modules, each with its own mode (startup/recovery).
     *
     * <p><b>Phase A (parallel pre-compile)</b>: pre-warms deploy for every module concurrently (in-process =
     * javac into the shared cache; other modes = no-op). Because startup recompiles every module from source
     * and javac dominates (~96% of per-module cost), doing it in parallel cuts restart time roughly by the
     * core count. <b>Phase B (serial deploy)</b>: the existing ordered deploy loop, which for in-process now
     * hits the compile-cache fast-path (no javac) and only performs the serialized context/route registration.
     * Deploy-path concurrency is unchanged. Set {@code protean.reconcile.compile-parallelism=1} to disable
     * Phase A and restore the fully serial legacy behavior.
     */
    public int reconcile() {
        List<ModuleDescriptor> all = store.listActive();
        // Library modules must publish their parent-tier generations before any dependent compiles against them, so
        // deploy libraries first (serial), then pre-compile + deploy the rest. (One-level `uses` for now; a
        // library-on-library DAG order is a follow-up — a dependent whose library is not yet published is skipped and
        // retried on the next reconcile.)
        List<ModuleDescriptor> libraries = all.stream().filter(ModuleDescriptor::isLibrary).toList();
        List<ModuleDescriptor> others = all.stream().filter(d -> !d.isLibrary()).toList();

        int deployed = deployAll(topoSortLibraries(libraries));

        // Phase A — parallel pre-compile of the non-library modules (warms the compile cache; skipped when trivial).
        prewarm(others);
        // Phase B — serial deploy (ordered, deterministic route registration; deploy stays synchronized).
        deployed += deployAll(others);

        if (!all.isEmpty()) {
            log.info("reconcile: recovered {}/{} modules", deployed, all.size());
        }
        return deployed;
    }

    /**
     * Orders library modules so a library is deployed after the libraries it {@code uses} (a library→library DAG needs
     * its dependencies published first — see the reconcile ordering). Depth-first post-order; the graph is acyclic
     * (cycles are rejected at install by {@link #assertNoUsesCycle}), and any back-edge is skipped defensively.
     */
    private List<ModuleDescriptor> topoSortLibraries(List<ModuleDescriptor> libraries) {
        Map<String, ModuleDescriptor> byId = libraries.stream()
                .collect(Collectors.toMap(ModuleDescriptor::id, Function.identity(), (a, b) -> a));
        List<ModuleDescriptor> ordered = new ArrayList<>();
        java.util.Set<String> done = new java.util.HashSet<>();
        for (ModuleDescriptor d : libraries) {
            topoVisit(d, byId, done, new java.util.HashSet<>(), ordered);
        }
        return ordered;
    }

    private void topoVisit(ModuleDescriptor d, Map<String, ModuleDescriptor> byId, java.util.Set<String> done,
                           java.util.Set<String> onStack, List<ModuleDescriptor> ordered) {
        if (done.contains(d.id()) || !onStack.add(d.id())) {
            return;
        }
        for (String usedLib : d.uses()) {
            ModuleDescriptor dep = byId.get(usedLib);
            if (dep != null) {
                topoVisit(dep, byId, done, onStack, ordered);
            }
        }
        onStack.remove(d.id());
        if (done.add(d.id())) {
            ordered.add(d);
        }
    }

    /** Deploys each descriptor with its own isolation mode, logging and skipping individual failures. Returns count. */
    private int deployAll(List<ModuleDescriptor> modules) {
        int deployed = 0;
        for (ModuleDescriptor d : modules) {
            String mode = resolveMode(d);
            try {
                strategyFor(mode).deploy(d);
                moduleMode.put(d.id(), mode);
                deployed++;
            } catch (RuntimeException e) {
                log.error("module recovery failed (skipped): {} [{}] - {}", d.id(), mode, e.getMessage());
            }
        }
        return deployed;
    }

    /**
     * Attaches the shared-lib jars observed while compiling this module (§B) to the descriptor so the persisted
     * version records what it actually used — for precise invalidation. The gate ②
     * (review) compile runs under the real module id, so the observation is available here before the state save.
     * No-op (returns the descriptor unchanged) when no shared lib is configured or the review gate was disabled.
     */
    private ModuleDescriptor enrichUsedSharedLibs(ModuleDescriptor d) {
        List<UsedSharedLib> used = compiler.usedSharedLibs(d.id());
        return used.isEmpty() ? d : d.withUsedSharedLibs(used);
    }

    /**
     * Rejects a module whose {@code uses} would form a cycle in the library dependency graph: a
     * cyclic library→library dependency has no valid topological compile/load order and would loop the ClassLoader
     * parent chain. The graph is the ACTIVE modules' {@code uses} with the incoming descriptor overlaid, so an install
     * or update that introduces a back-edge is caught before any compile.
     */
    private void assertNoUsesCycle(ModuleDescriptor incoming) {
        if (incoming.uses().isEmpty()) {
            return;
        }
        Map<String, List<String>> graph = new java.util.HashMap<>();
        for (ModuleDescriptor d : store.listActive()) {
            graph.put(d.id(), d.uses());
        }
        graph.put(incoming.id(), incoming.uses());   // overlay the incoming version
        for (String lib : incoming.uses()) {
            if (lib.equals(incoming.id()) || reaches(lib, incoming.id(), graph, new java.util.HashSet<>())) {
                throw new IllegalStateException("module '" + incoming.id() + "' declares a cyclic `uses` dependency "
                        + "(via '" + lib + "') — library dependencies must form a DAG");
            }
        }
    }

    /** Whether {@code target} is reachable from {@code from} by following {@code uses} edges (DFS, cycle-safe). */
    private boolean reaches(String from, String target, Map<String, List<String>> graph, java.util.Set<String> seen) {
        if (from.equals(target)) {
            return true;
        }
        if (!seen.add(from)) {
            return false;
        }
        for (String next : graph.getOrDefault(from, List.of())) {
            if (reaches(next, target, graph, seen)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pre-compiles the given modules in parallel (best-effort). A prewarm failure is swallowed here — Phase B's
     * deploy re-attempts that module (cache miss) and surfaces the real error through the normal recovery path.
     */
    private void prewarm(List<ModuleDescriptor> active) {
        int parallelism = props.getReconcile().getCompileParallelism();
        if (parallelism <= 0) {
            parallelism = Runtime.getRuntime().availableProcessors();
        }
        if (parallelism <= 1 || active.size() <= 1) {
            return;   // serial mode or nothing to overlap
        }
        int threads = Math.min(parallelism, active.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "protean-reconcile-compile");
            t.setDaemon(true);
            return t;
        });
        // Reuse one javac file manager per worker thread for the section so the identical, read-only compile
        // classpath is scanned once per thread instead of once per compile. The scope closes (releasing the
        // pooled managers' file handles) only after the f.get() barrier below, so no worker is still compiling.
        try (RuntimeCompiler.PoolScope ignored =
                     compiler.openReconcilePool(props.getReconcile().isReuseFileManager())) {
            List<Future<?>> futures = new ArrayList<>(active.size());
            for (ModuleDescriptor d : active) {
                futures.add(pool.submit(() -> {
                    try {
                        strategyFor(resolveMode(d)).prewarm(d);
                    } catch (RuntimeException e) {
                        log.debug("prewarm failed (deploy will retry): {} - {}", d.id(), e.getMessage());
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();   // barrier: all pre-compiles complete before serial deploy begins
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException e) {
                    log.debug("prewarm task error (deploy will retry): {}", e.getCause() == null ? e.toString() : e.getCause().toString());
                }
            }
            log.debug("reconcile: parallel pre-compile done ({} modules, {} threads)", active.size(), threads);
        } finally {
            pool.shutdown();
        }
    }
}
