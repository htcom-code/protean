/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.config.ProteanConfigService.ApplyResult;
import org.htcom.protean.config.ProteanConfigService.KeyOutcome;
import org.htcom.protean.config.ProteanConfigService.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the runtime config service: classification, validation, atomic apply, and change events. */
class ProteanConfigServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ProteanProperties props;
    private ConfigRegistry registry;
    private ProteanConfigService service;
    private AtomicReference<ConfigChangedEvent> lastEvent;

    @BeforeEach
    void setUp() {
        props = new ProteanProperties();
        registry = new ConfigRegistry(new MockEnvironment());
        lastEvent = new AtomicReference<>();
        service = new ProteanConfigService(props, registry, event -> {
            if (event instanceof ConfigChangedEvent c) {
                lastEvent.set(c);
            }
        });
    }

    private Map<String, JsonNode> patch(Object... kv) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], mapper.valueToTree(kv[i + 1]));
        }
        return m;
    }

    private KeyOutcome outcomeFor(ApplyResult r, String key) {
        return r.outcomes().stream().filter(o -> o.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void listCoversEveryTierAndReportsCurrentValues() {
        List<ProteanConfigService.ConfigEntry> all = service.list();
        assertTrue(all.size() >= 50, "expected the full config surface, got " + all.size());
        // spot-check current values match defaults
        var timeout = all.stream().filter(e -> e.key().equals("module.request-timeout-ms")).findFirst().orElseThrow();
        assertEquals(0L, timeout.value());
        assertEquals(ConfigTier.LIVE, timeout.tier());
    }

    @Test
    void tier1AppliesLiveAndMutatesProperties() {
        ApplyResult r = service.apply(patch("trace.capacity", 50, "gate.tests-enabled", false));
        assertTrue(r.applied());
        assertEquals(Outcome.APPLIED_LIVE, outcomeFor(r, "trace.capacity").outcome());
        assertEquals(Outcome.APPLIED_LIVE, outcomeFor(r, "gate.tests-enabled").outcome());
        assertEquals(50, props.getTrace().getCapacity());
        assertFalse(props.getGate().isTestsEnabled());
        assertNotNull(lastEvent.get());
        assertTrue(lastEvent.get().changed("trace.capacity"));
    }

    @Test
    void tier2AppliesButReportedAsFuture() {
        ApplyResult r = service.apply(patch("isolation.mode", "worker"));
        assertTrue(r.applied());
        assertEquals(Outcome.APPLIED_FUTURE, outcomeFor(r, "isolation.mode").outcome());
        assertEquals("worker", props.getIsolation().getMode());
    }

    @Test
    void deferredDbAdminKeyRequiresRestartWithoutApplying() {
        // worker.db.dialect is Tier 2 but captured by DbScopeProvisioner (live JDBC-admin reconfigure is a separate
        // follow-up), so it is honestly reported REQUIRES_RESTART rather than a silently no-op APPLIED_FUTURE.
        ApplyResult r = service.apply(patch("worker.db.dialect", "postgresql"));
        assertTrue(r.applied(), "batch with only a not-yet-wired key is not an abort");
        assertEquals(Outcome.REQUIRES_RESTART, outcomeFor(r, "worker.db.dialect").outcome());
        assertNull(props.getWorker().getDb().getDialect(), "pending key must not be applied");
        assertNull(lastEvent.get());
        var entry = service.get("worker.db.dialect").orElseThrow();
        assertEquals(ConfigTier.FUTURE, entry.tier());
        assertFalse(entry.liveApplicable(), "captured → not live-applicable");
    }

    @Test
    void promotedSpawnTier2KeyAppliesAsFuture() {
        // worker/container/sidecar spawn+pool keys are now wired for live reads → APPLIED_FUTURE, value mutated.
        var entry = service.get("worker.container.image").orElseThrow();
        assertEquals(ConfigTier.FUTURE, entry.tier());
        assertTrue(entry.liveApplicable(), "spawn key is now live-wired");
        ApplyResult r = service.apply(patch("worker.container.image", "eclipse-temurin:25-jdk",
                "worker.modules-per-worker", 8));
        assertTrue(r.applied());
        assertEquals(Outcome.APPLIED_FUTURE, outcomeFor(r, "worker.container.image").outcome());
        assertEquals(Outcome.APPLIED_FUTURE, outcomeFor(r, "worker.modules-per-worker").outcome());
        assertEquals("eclipse-temurin:25-jdk", props.getWorker().getContainer().getImage());
        assertEquals(8, props.getWorker().getModulesPerWorker());
    }

    @Test
    void promotedDbAdminCredsApplyAsFutureAndMutate() {
        // POC: worker.db.admin-url/username/password are now live-wired (DbScopeProvisioner rebuilds its admin
        // connection when they change) → APPLIED_FUTURE and the value is mutated. dialect stays pending (restart).
        assertTrue(service.get("worker.db.admin-url").orElseThrow().liveApplicable());
        assertTrue(service.get("worker.db.admin-username").orElseThrow().liveApplicable());
        assertTrue(service.get("worker.db.admin-password").orElseThrow().liveApplicable());
        assertFalse(service.get("worker.db.dialect").orElseThrow().liveApplicable(), "dialect stays pending");

        ApplyResult r = service.apply(patch(
                "worker.db.admin-url", "jdbc:mysql://newhost:3306/",
                "worker.db.admin-password", "rotated-pw"));
        assertTrue(r.applied());
        assertEquals(Outcome.APPLIED_FUTURE, outcomeFor(r, "worker.db.admin-url").outcome());
        assertEquals(Outcome.APPLIED_FUTURE, outcomeFor(r, "worker.db.admin-password").outcome());
        assertEquals("jdbc:mysql://newhost:3306/", props.getWorker().getDb().getAdminUrl());
        assertEquals("rotated-pw", props.getWorker().getDb().getAdminPassword());
    }

    @Test
    void wiredTier2KeyIsLiveApplicable() {
        // isolation.mode, trace.metrics.*, and the promoted spawn keys ARE wired for live reads → live-applicable.
        assertTrue(service.get("isolation.mode").orElseThrow().liveApplicable());
        assertTrue(service.get("trace.metrics.max-modules").orElseThrow().liveApplicable());
        assertTrue(service.get("worker.sidecar.jar").orElseThrow().liveApplicable());
        assertTrue(service.get("worker.min-warm").orElseThrow().liveApplicable());
    }

    @Test
    void tier3aRejectedWithoutApplying() {
        boolean before = props.getMcp().isEnabled();
        ApplyResult r = service.apply(patch("mcp.enabled", true));
        assertTrue(r.applied(), "batch with only a restart key is not an abort");
        assertEquals(Outcome.REQUIRES_RESTART, outcomeFor(r, "mcp.enabled").outcome());
        assertEquals(before, props.getMcp().isEnabled(), "restart key must not be applied");
        assertNull(lastEvent.get(), "no live change → no event");
    }

    @Test
    void tier3bRejected() {
        ApplyResult r = service.apply(patch("module.shared-lib-dir", "/tmp/libs"));
        assertEquals(Outcome.REQUIRES_RESTART, outcomeFor(r, "module.shared-lib-dir").outcome());
        assertEquals("", props.getModule().getSharedLibDir());
    }

    @Test
    void unknownKeyAbortsWholeBatch() {
        ApplyResult r = service.apply(patch("trace.capacity", 77, "does.not.exist", 1));
        assertFalse(r.applied());
        assertEquals(Outcome.REJECTED_UNKNOWN, outcomeFor(r, "does.not.exist").outcome());
        assertEquals(Outcome.NOT_APPLIED_BATCH_ABORTED, outcomeFor(r, "trace.capacity").outcome());
        assertEquals(200, props.getTrace().getCapacity(), "nothing applied on abort");
        assertNull(lastEvent.get());
    }

    @Test
    void invalidValueAbortsWholeBatch() {
        // capacity must be >= 1
        ApplyResult r = service.apply(patch("trace.capacity", 0, "trace.enabled", false));
        assertFalse(r.applied());
        assertEquals(Outcome.REJECTED_INVALID, outcomeFor(r, "trace.capacity").outcome());
        assertEquals(Outcome.NOT_APPLIED_BATCH_ABORTED, outcomeFor(r, "trace.enabled").outcome());
        assertTrue(props.getTrace().isEnabled(), "nothing applied on abort");
    }

    @Test
    void wrongTypeIsRejected() {
        ApplyResult r = service.apply(patch("trace.enabled", "yes"));
        assertFalse(r.applied());
        assertEquals(Outcome.REJECTED_INVALID, outcomeFor(r, "trace.enabled").outcome());
    }

    @Test
    void enumValueValidated() {
        ApplyResult r = service.apply(patch("isolation.mode", "bogus"));
        assertFalse(r.applied());
        assertEquals(Outcome.REJECTED_INVALID, outcomeFor(r, "isolation.mode").outcome());
    }

    @Test
    void prefixedKeyIsAccepted() {
        ApplyResult r = service.apply(patch("protean.trace.capacity", 33));
        assertTrue(r.applied());
        assertEquals(33, props.getTrace().getCapacity());
    }

    @Test
    void secretIsMaskedInReads() {
        props.getBridge().setSecret("super-secret-value");
        var entry = service.get("bridge.secret").orElseThrow();
        assertEquals("***", entry.value());
    }

    @Test
    void signatureKeysMapApplies() {
        ApplyResult r = service.apply(patch("gate.signature.keys", Map.of("k1", "base64key")));
        assertTrue(r.applied());
        assertEquals("base64key", props.getGate().getSignature().getKeys().get("k1"));
    }
}
