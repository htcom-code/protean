/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The whitelist + runtime-mutability classification of every known {@code protean.*} configuration key.
 * Any key absent here is unknown and rejected by
 * {@link ProteanConfigService} (fail-safe).
 *
 * <p>Keys use the canonical relative kebab-case form (no {@code protean.} prefix), e.g. {@code trace.capacity}.
 */
@Component
public class ConfigRegistry {

    private final Map<String, ConfigKey> keys = new LinkedHashMap<>();

    public ConfigRegistry(Environment environment) {
        // ---- Tier 1 (LIVE) ----------------------------------------------------------------------
        bool("gate.tests-enabled", ConfigTier.LIVE,
                p -> p.getGate().isTestsEnabled(), (p, v) -> p.getGate().setTestsEnabled(v));
        bool("gate.review-enabled", ConfigTier.LIVE,
                p -> p.getGate().isReviewEnabled(), (p, v) -> p.getGate().setReviewEnabled(v));
        bool("module.shared-lib.eager-invalidation", ConfigTier.LIVE,
                p -> p.getModule().isEagerSharedLibInvalidation(),
                (p, v) -> p.getModule().setEagerSharedLibInvalidation(v));
        bool("gate.signature.required", ConfigTier.LIVE,
                p -> p.getGate().getSignature().isRequired(), (p, v) -> p.getGate().getSignature().setRequired(v));
        bool("gate.signature.shared-lib-required", ConfigTier.LIVE,
                p -> p.getGate().getSignature().isSharedLibRequired(),
                (p, v) -> p.getGate().getSignature().setSharedLibRequired(v));
        stringMap("gate.signature.keys", ConfigTier.LIVE,
                p -> p.getGate().getSignature().getKeys(), (p, v) -> p.getGate().getSignature().setKeys(v));
        bool("gate.approval.required", ConfigTier.LIVE,
                p -> p.getGate().getApproval().isRequired(), (p, v) -> p.getGate().getApproval().setRequired(v));
        bool("mcp.capture-test-output", ConfigTier.LIVE,
                p -> p.getMcp().isCaptureTestOutput(), (p, v) -> p.getMcp().setCaptureTestOutput(v));
        bool("mcp.strict-schema", ConfigTier.LIVE,
                p -> p.getMcp().isStrictSchema(), (p, v) -> p.getMcp().setStrictSchema(v));
        longNum("module.request-timeout-ms", ConfigTier.LIVE, 0,
                p -> p.getModule().getRequestTimeoutMs(), (p, v) -> p.getModule().setRequestTimeoutMs(v));
        bool("trace.enabled", ConfigTier.LIVE,
                p -> p.getTrace().isEnabled(), (p, v) -> p.getTrace().setEnabled(v));
        intNum("trace.capacity", ConfigTier.LIVE, 1,
                p -> p.getTrace().getCapacity(), (p, v) -> p.getTrace().setCapacity(v));
        bool("trace.metrics.enabled", ConfigTier.LIVE,
                p -> p.getTrace().getMetrics().isEnabled(), (p, v) -> p.getTrace().getMetrics().setEnabled(v));
        bool("worker.auto-restart", ConfigTier.LIVE,
                p -> p.getWorker().isAutoRestart(), (p, v) -> p.getWorker().setAutoRestart(v));
        bool("worker.container.auto-restart", ConfigTier.LIVE,
                p -> p.getWorker().getContainer().isAutoRestart(), (p, v) -> p.getWorker().getContainer().setAutoRestart(v));
        bool("worker.db.deprovision-on-undeploy", ConfigTier.LIVE,
                p -> p.getWorker().getDb().isDeprovisionOnUndeploy(), (p, v) -> p.getWorker().getDb().setDeprovisionOnUndeploy(v));
        longNum("bridge.hmac-window-ms", ConfigTier.LIVE, 1,
                p -> p.getBridge().getHmacWindowMs(), (p, v) -> p.getBridge().setHmacWindowMs(v));
        stringList("mcp.authorization.scopes-supported", ConfigTier.LIVE,
                p -> p.getMcp().getAuthorization().getScopesSupported(), (p, v) -> p.getMcp().getAuthorization().setScopesSupported(v));
        stringList("mcp.authorization.authorization-servers", ConfigTier.LIVE,
                p -> p.getMcp().getAuthorization().getAuthorizationServers(), (p, v) -> p.getMcp().getAuthorization().setAuthorizationServers(v));
        stringList("mcp.authorization.bearer-methods-supported", ConfigTier.LIVE,
                p -> p.getMcp().getAuthorization().getBearerMethodsSupported(), (p, v) -> p.getMcp().getAuthorization().setBearerMethodsSupported(v));

        // ---- Tier 2 (FUTURE) --------------------------------------------------------------------
        enumStr("isolation.mode", ConfigTier.FUTURE, Set.of("in-process", "worker", "container"),
                p -> p.getIsolation().getMode(), (p, v) -> p.getIsolation().setMode(v));
        intNum("module.executor.pool-size", ConfigTier.FUTURE, 1,
                p -> p.getModule().getExecutor().getPoolSize(), (p, v) -> p.getModule().getExecutor().setPoolSize(v));
        intNum("worker.modules-per-worker", ConfigTier.FUTURE, 1,
                p -> p.getWorker().getModulesPerWorker(), (p, v) -> p.getWorker().setModulesPerWorker(v));
        intNum("worker.min-warm", ConfigTier.FUTURE, 0,
                p -> p.getWorker().getMinWarm(), (p, v) -> p.getWorker().setMinWarm(v));
        str("worker.datasource.url", ConfigTier.FUTURE,
                p -> p.getWorker().getDatasource().getUrl(), (p, v) -> p.getWorker().getDatasource().setUrl(v));
        str("worker.container.image", ConfigTier.FUTURE,
                p -> p.getWorker().getContainer().getImage(), (p, v) -> p.getWorker().getContainer().setImage(v));
        str("worker.container.jar", ConfigTier.FUTURE,
                p -> p.getWorker().getContainer().getJar(), (p, v) -> p.getWorker().getContainer().setJar(v));
        str("worker.container.memory", ConfigTier.FUTURE,
                p -> p.getWorker().getContainer().getMemory(), (p, v) -> p.getWorker().getContainer().setMemory(v));
        longNum("worker.container.pids-limit", ConfigTier.FUTURE, 1,
                p -> p.getWorker().getContainer().getPidsLimit(), (p, v) -> p.getWorker().getContainer().setPidsLimit(v));
        str("worker.container.network", ConfigTier.FUTURE,
                p -> p.getWorker().getContainer().getNetwork(), (p, v) -> p.getWorker().getContainer().setNetwork(v));
        str("worker.container.seccomp", ConfigTier.FUTURE,
                p -> p.getWorker().getContainer().getSeccomp(), (p, v) -> p.getWorker().getContainer().setSeccomp(v));
        str("worker.container.db-host", ConfigTier.FUTURE,
                p -> p.getWorker().getContainer().getDbHost(), (p, v) -> p.getWorker().getContainer().setDbHost(v));
        str("worker.db.dialect", ConfigTier.FUTURE,
                p -> p.getWorker().getDb().getDialect(), (p, v) -> p.getWorker().getDb().setDialect(v));
        str("worker.db.admin-url", ConfigTier.FUTURE,
                p -> p.getWorker().getDb().getAdminUrl(), (p, v) -> p.getWorker().getDb().setAdminUrl(v));
        str("worker.db.admin-username", ConfigTier.FUTURE,
                p -> p.getWorker().getDb().getAdminUsername(), (p, v) -> p.getWorker().getDb().setAdminUsername(v));
        str("worker.db.admin-password", ConfigTier.FUTURE,
                p -> p.getWorker().getDb().getAdminPassword(), (p, v) -> p.getWorker().getDb().setAdminPassword(v));
        str("worker.sidecar.jar", ConfigTier.FUTURE,
                p -> p.getWorker().getSidecar().getJar(), (p, v) -> p.getWorker().getSidecar().setJar(v));
        str("worker.sidecar.image", ConfigTier.FUTURE,
                p -> p.getWorker().getSidecar().getImage(), (p, v) -> p.getWorker().getSidecar().setImage(v));
        str("worker.sidecar.shared-api", ConfigTier.FUTURE,
                p -> p.getWorker().getSidecar().getSharedApi(), (p, v) -> p.getWorker().getSidecar().setSharedApi(v));
        intNum("trace.metrics.latency-buckets", ConfigTier.FUTURE, 2,
                p -> p.getTrace().getMetrics().getLatencyBuckets(), (p, v) -> p.getTrace().getMetrics().setLatencyBuckets(v));
        intNum("trace.metrics.max-modules", ConfigTier.FUTURE, 1,
                p -> p.getTrace().getMetrics().getMaxModules(), (p, v) -> p.getTrace().getMetrics().setMaxModules(v));

        // ---- Tier 3a (RESTART_CONDITIONAL — @ConditionalOnProperty) -----------------------------
        restart("admin.enabled", ConfigTier.RESTART_CONDITIONAL, p -> p.getAdmin().isEnabled());
        restart("mcp.enabled", ConfigTier.RESTART_CONDITIONAL, p -> p.getMcp().isEnabled());
        restart("mcp.stdio", ConfigTier.RESTART_CONDITIONAL, p -> p.getMcp().isStdio());
        // No ProteanProperties field (raw @ConditionalOnProperty, default true) — read from the Environment.
        restart("mcp.session.enabled", ConfigTier.RESTART_CONDITIONAL,
                p -> environment.getProperty("protean.mcp.session.enabled", Boolean.class, Boolean.TRUE));
        restart("mcp.debug.enabled", ConfigTier.RESTART_CONDITIONAL, p -> p.getMcp().getDebug().isEnabled());
        restart("mcp.authorization.resource", ConfigTier.RESTART_CONDITIONAL, p -> p.getMcp().getAuthorization().getResource());
        restart("bridge.auth-enabled", ConfigTier.RESTART_CONDITIONAL, p -> p.getBridge().isAuthEnabled());
        restart("worker.rpc-bridge", ConfigTier.RESTART_CONDITIONAL, p -> p.getWorker().isRpcBridge());
        restart("worker.runtime", ConfigTier.RESTART_CONDITIONAL, p -> p.getWorker().getRuntime());
        restart("module-store.backend", ConfigTier.RESTART_CONDITIONAL, p -> p.getModuleStore().getBackend());
        restart("worker.db.auto-provision", ConfigTier.RESTART_CONDITIONAL, p -> p.getWorker().getDb().isAutoProvision());

        // ---- Tier 3b (RESTART_ARTIFACT — init-time artifact baked from the value) ---------------
        restart("module.shared-lib-dir", ConfigTier.RESTART_ARTIFACT, p -> p.getModule().getSharedLibDir());
        restart("module.shared-lib-store-dir", ConfigTier.RESTART_ARTIFACT, p -> p.getModule().getSharedLibStoreDir());
        restart("module-store.dir", ConfigTier.RESTART_ARTIFACT, p -> p.getModuleStore().getDir());
        restart("bridge.secret", ConfigTier.RESTART_ARTIFACT, p -> mask(p.getBridge().getSecret()));
        restart("bridge.url", ConfigTier.RESTART_ARTIFACT, p -> p.getBridge().getUrl());
        restart("bridge.auth-mode", ConfigTier.RESTART_ARTIFACT, p -> p.getBridge().getAuthMode());
        restart("mcp.debug.session-idle-timeout", ConfigTier.RESTART_ARTIFACT,
                p -> String.valueOf(p.getMcp().getDebug().getSessionIdleTimeout()));

        // The worker/container/sidecar spawn + pool Tier 2 keys are now wired for live reads (the runtime reads them
        // at spawn/acquire time), so they apply to future instances — no longer pending.
        //
        // worker.db.admin-url/username/password are now live too (POC): DbScopeProvisioner re-reads them via a supplier
        // and rebuilds its admin JDBC connection when they change, so an admin-credential rotation applies to the next
        // provision/deprovision without a restart (APPLIED_FUTURE — not retroactive to already-provisioned scopes).
        // (protean.worker.db.deprovision-on-undeploy IS live — read per undeploy in DbScopeProvisioner.)
        //
        // Still pending: worker.db.dialect selects a strategy object, and existing scopes were created under the old
        // dialect's DDL/URL shape, so a live dialect swap is a distinct, riskier follow-up — honestly reported
        // REQUIRES_RESTART rather than a silent no-op.
        markPending("worker.db.dialect");
    }

    private void markPending(String... pendingKeys) {
        for (String k : pendingKeys) {
            ConfigKey ck = keys.get(k);
            if (ck == null) {
                throw new IllegalStateException("markPending references an unregistered key: " + k);
            }
            ck.markLiveReadPending();
        }
    }

    /** The config key descriptor, or {@code null} if the key is unknown. Accepts an optional {@code protean.} prefix. */
    public ConfigKey get(String key) {
        if (key == null) {
            return null;
        }
        String normalized = key.startsWith("protean.") ? key.substring("protean.".length()) : key;
        return keys.get(normalized);
    }

    /** All known keys, in declaration order (Tier 1 → 2 → 3a → 3b). */
    public List<ConfigKey> all() {
        return List.copyOf(keys.values());
    }

    /** Whether the key is known (whitelisted). */
    public boolean known(String key) {
        return get(key) != null;
    }

    // --- typed builders --------------------------------------------------------------------------

    private void add(String key, ConfigTier tier, Function<ProteanProperties, Object> getter,
                     Function<JsonNode, Object> parse, BiConsumer<ProteanProperties, Object> setter) {
        keys.put(key, new ConfigKey(key, tier, getter, parse, setter));
    }

    private void bool(String key, ConfigTier tier, Function<ProteanProperties, Object> getter,
                      BiConsumer<ProteanProperties, Boolean> setter) {
        add(key, tier, getter, node -> {
            if (node == null || !node.isBoolean()) {
                throw new IllegalArgumentException("expected a boolean");
            }
            return node.booleanValue();
        }, (p, v) -> setter.accept(p, (Boolean) v));
    }

    private void intNum(String key, ConfigTier tier, int min, Function<ProteanProperties, Object> getter,
                        BiConsumer<ProteanProperties, Integer> setter) {
        add(key, tier, getter, node -> {
            if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
                throw new IllegalArgumentException("expected an integer");
            }
            int v = node.intValue();
            if (v < min) {
                throw new IllegalArgumentException("must be >= " + min);
            }
            return v;
        }, (p, v) -> setter.accept(p, (Integer) v));
    }

    private void longNum(String key, ConfigTier tier, long min, Function<ProteanProperties, Object> getter,
                         BiConsumer<ProteanProperties, Long> setter) {
        add(key, tier, getter, node -> {
            if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
                throw new IllegalArgumentException("expected an integer");
            }
            long v = node.longValue();
            if (v < min) {
                throw new IllegalArgumentException("must be >= " + min);
            }
            return v;
        }, (p, v) -> setter.accept(p, (Long) v));
    }

    private void str(String key, ConfigTier tier, Function<ProteanProperties, Object> getter,
                     BiConsumer<ProteanProperties, String> setter) {
        add(key, tier, getter, node -> {
            if (node == null || node.isNull()) {
                return "";
            }
            if (!node.isTextual()) {
                throw new IllegalArgumentException("expected a string");
            }
            return node.textValue();
        }, (p, v) -> setter.accept(p, (String) v));
    }

    private void enumStr(String key, ConfigTier tier, Set<String> allowed, Function<ProteanProperties, Object> getter,
                         BiConsumer<ProteanProperties, String> setter) {
        add(key, tier, getter, node -> {
            if (node == null || !node.isTextual()) {
                throw new IllegalArgumentException("expected a string");
            }
            String v = node.textValue();
            if (!allowed.contains(v)) {
                throw new IllegalArgumentException("must be one of " + allowed);
            }
            return v;
        }, (p, v) -> setter.accept(p, (String) v));
    }

    @SuppressWarnings("unchecked")
    private void stringList(String key, ConfigTier tier, Function<ProteanProperties, Object> getter,
                            BiConsumer<ProteanProperties, List<String>> setter) {
        add(key, tier, getter, node -> {
            if (node == null || !node.isArray()) {
                throw new IllegalArgumentException("expected an array of strings");
            }
            java.util.List<String> out = new java.util.ArrayList<>();
            for (JsonNode e : node) {
                if (!e.isTextual()) {
                    throw new IllegalArgumentException("expected an array of strings");
                }
                out.add(e.textValue());
            }
            return out;
        }, (p, v) -> setter.accept(p, (List<String>) v));
    }

    @SuppressWarnings("unchecked")
    private void stringMap(String key, ConfigTier tier, Function<ProteanProperties, Object> getter,
                           BiConsumer<ProteanProperties, Map<String, String>> setter) {
        add(key, tier, getter, node -> {
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("expected an object of string->string");
            }
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                if (!e.getValue().isTextual()) {
                    throw new IllegalArgumentException("expected an object of string->string");
                }
                out.put(e.getKey(), e.getValue().textValue());
            }
            return out;
        }, (p, v) -> setter.accept(p, (Map<String, String>) v));
    }

    /** A restart-tier key: readable for list/get, never applicable live (no parse/setter). */
    private void restart(String key, ConfigTier tier, Function<ProteanProperties, Object> getter) {
        add(key, tier, getter, null, null);
    }

    /** Redact a secret for display: never echo the value, only whether it is set. */
    private static String mask(String secret) {
        return (secret == null || secret.isBlank()) ? "" : "***";
    }

    /** Unmodifiable view of the raw key map (for tests). */
    Map<String, ConfigKey> view() {
        return Collections.unmodifiableMap(keys);
    }
}
