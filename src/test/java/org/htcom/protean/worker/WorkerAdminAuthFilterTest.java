/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.worker;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.bridge.BridgeHmac;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the worker {@code /__admin/*} auth filter's rejection logic — every branch: missing header, wrong
 * token, valid token, valid HMAC, replayed nonce, stale timestamp, and the {@code /__admin/health} bypass. Drives the
 * filter directly with mock request/response, so it is fast and deterministic (no worker JVM).
 */
class WorkerAdminAuthFilterTest {

    static final String SECRET = "test-admin-secret-value";

    private static WorkerAdminAuthFilter filter(String mode) {
        ProteanProperties props = new ProteanProperties();
        ProteanProperties.AdminAuth cfg = props.getWorker().getAdminAuth();
        cfg.setEnabled(true);
        cfg.setMode(mode);
        cfg.setSecret(SECRET);
        return new WorkerAdminAuthFilter(props);
    }

    /** A FilterChain that records whether the request was allowed through. */
    private static final class Recording implements FilterChain {
        boolean passed;
        @Override public void doFilter(ServletRequest req, ServletResponse res) {
            passed = true;
        }
    }

    private static MockHttpServletRequest post(String uri, byte[] body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setContent(body);
        return req;
    }

    // --- token mode ---

    @Test
    void token_mode_accepts_correct_bearer_and_rejects_others() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        // correct token → passes
        MockHttpServletResponse ok = new MockHttpServletResponse();
        Recording okChain = new Recording();
        MockHttpServletRequest req = post("/__admin/deploy", body);
        req.addHeader("Authorization", "Bearer " + SECRET);
        filter("token").doFilter(req, ok, okChain);
        assertTrue(okChain.passed, "correct bearer token must pass");
        assertEquals(200, ok.getStatus());

        // wrong token → 401
        MockHttpServletResponse bad = new MockHttpServletResponse();
        Recording badChain = new Recording();
        MockHttpServletRequest req2 = post("/__admin/deploy", body);
        req2.addHeader("Authorization", "Bearer wrong");
        filter("token").doFilter(req2, bad, badChain);
        assertFalse(badChain.passed, "wrong token must be blocked");
        assertEquals(401, bad.getStatus());

        // missing header → 401
        MockHttpServletResponse none = new MockHttpServletResponse();
        Recording noneChain = new Recording();
        filter("token").doFilter(post("/__admin/deploy", body), none, noneChain);
        assertFalse(noneChain.passed, "missing token must be blocked");
        assertEquals(401, none.getStatus());
    }

    // --- hmac mode ---

    @Test
    void hmac_mode_accepts_valid_signature() throws Exception {
        byte[] body = "{\"id\":\"m\"}".getBytes(StandardCharsets.UTF_8);
        long ts = System.currentTimeMillis();
        String nonce = "nonce-1";
        MockHttpServletRequest req = post("/__admin/deploy", body);
        req.addHeader(BridgeHmac.TS_HEADER, Long.toString(ts));
        req.addHeader(BridgeHmac.NONCE_HEADER, nonce);
        req.addHeader(BridgeHmac.SIG_HEADER, BridgeHmac.sign(SECRET, ts, nonce, body));

        MockHttpServletResponse res = new MockHttpServletResponse();
        Recording chain = new Recording();
        filter("hmac").doFilter(req, res, chain);
        assertTrue(chain.passed, "valid HMAC must pass");
        assertEquals(200, res.getStatus());
    }

    @Test
    void hmac_mode_rejects_tampered_body_stale_ts_and_replayed_nonce() throws Exception {
        WorkerAdminAuthFilter f = filter("hmac");
        byte[] body = "{\"id\":\"m\"}".getBytes(StandardCharsets.UTF_8);
        long ts = System.currentTimeMillis();

        // signature computed over a different body → mismatch → 401
        MockHttpServletResponse tampered = new MockHttpServletResponse();
        Recording tamperedChain = new Recording();
        MockHttpServletRequest t = post("/__admin/deploy", body);
        t.addHeader(BridgeHmac.TS_HEADER, Long.toString(ts));
        t.addHeader(BridgeHmac.NONCE_HEADER, "n-tamper");
        t.addHeader(BridgeHmac.SIG_HEADER, BridgeHmac.sign(SECRET, ts, "n-tamper",
                "{\"id\":\"other\"}".getBytes(StandardCharsets.UTF_8)));
        f.doFilter(t, tampered, tamperedChain);
        assertFalse(tamperedChain.passed, "body tampering must be blocked");
        assertEquals(401, tampered.getStatus());

        // stale timestamp (well outside the default 30s window) → 401
        long stale = ts - 10 * 60_000;
        MockHttpServletResponse staleRes = new MockHttpServletResponse();
        Recording staleChain = new Recording();
        MockHttpServletRequest s = post("/__admin/deploy", body);
        s.addHeader(BridgeHmac.TS_HEADER, Long.toString(stale));
        s.addHeader(BridgeHmac.NONCE_HEADER, "n-stale");
        s.addHeader(BridgeHmac.SIG_HEADER, BridgeHmac.sign(SECRET, stale, "n-stale", body));
        f.doFilter(s, staleRes, staleChain);
        assertFalse(staleChain.passed, "stale timestamp must be blocked");
        assertEquals(401, staleRes.getStatus());

        // replay: same nonce accepted once, rejected the second time
        String nonce = "n-replay";
        String sig = BridgeHmac.sign(SECRET, ts, nonce, body);
        MockHttpServletRequest first = post("/__admin/deploy", body);
        first.addHeader(BridgeHmac.TS_HEADER, Long.toString(ts));
        first.addHeader(BridgeHmac.NONCE_HEADER, nonce);
        first.addHeader(BridgeHmac.SIG_HEADER, sig);
        Recording firstChain = new Recording();
        f.doFilter(first, new MockHttpServletResponse(), firstChain);
        assertTrue(firstChain.passed, "first use of a nonce must pass");

        MockHttpServletRequest replay = post("/__admin/deploy", body);
        replay.addHeader(BridgeHmac.TS_HEADER, Long.toString(ts));
        replay.addHeader(BridgeHmac.NONCE_HEADER, nonce);
        replay.addHeader(BridgeHmac.SIG_HEADER, sig);
        MockHttpServletResponse replayRes = new MockHttpServletResponse();
        Recording replayChain = new Recording();
        f.doFilter(replay, replayRes, replayChain);
        assertFalse(replayChain.passed, "a replayed nonce must be blocked");
        assertEquals(401, replayRes.getStatus());
    }

    // --- health bypass ---

    @Test
    void health_is_not_gated() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/__admin/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        Recording chain = new Recording();
        filter("hmac").doFilter(req, res, chain);   // no auth headers at all
        assertTrue(chain.passed, "the read-only health probe must bypass auth");
    }
}
