/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.bridge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards {@code /__bridge/*} with a shared secret (main side only). Registered only when
 * {@code protean.bridge.auth-enabled=true}. Two schemes, selected by {@code protean.bridge.auth-mode}:
 * <ul>
 *   <li>{@code token} — a static {@code Authorization: Bearer <secret>} bearer token;</li>
 *   <li>{@code hmac} — a per-request HMAC-SHA256 over timestamp + nonce + body, additionally rejecting
 *       stale timestamps (outside the configured window) and replayed nonces.</li>
 * </ul>
 * All comparisons are constant-time. Transport confidentiality (TLS) is orthogonal and out of scope.
 */
@Component
@Profile("!worker")
@ConditionalOnProperty(name = "protean.bridge.auth-enabled", havingValue = "true")
public class BridgeAuthFilter extends OncePerRequestFilter {

    private static final String BRIDGE_PREFIX = "/__bridge";
    private static final String BEARER = "Bearer ";

    private final String secret;
    private final byte[] expectedToken;
    private final ProteanProperties props;
    /** Seen nonces → expiry epoch millis, for HMAC replay rejection within the timestamp window. */
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    public BridgeAuthFilter(BridgeSecretHolder holder, ProteanProperties props) {
        this.secret = holder.token();
        this.expectedToken = secret.getBytes(StandardCharsets.UTF_8);
        this.props = props;
    }

    private boolean hmacMode() {
        return "hmac".equalsIgnoreCase(props.getBridge().getAuthMode());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(BRIDGE_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (hmacMode()) {
            doHmac(request, response, chain);
        } else {
            doToken(request, response, chain);
        }
    }

    private void doToken(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        byte[] presented = (header != null && header.startsWith(BEARER))
                ? header.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        // MessageDigest.isEqual is time-constant (JDK 6u17+), so it does not leak the secret via timing.
        if (!MessageDigest.isEqual(expectedToken, presented)) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void doHmac(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long windowMs = props.getBridge().getHmacWindowMs();   // live (Tier 1)
        String tsHeader = request.getHeader(BridgeHmac.TS_HEADER);
        String nonce = request.getHeader(BridgeHmac.NONCE_HEADER);
        String sig = request.getHeader(BridgeHmac.SIG_HEADER);
        if (tsHeader == null || nonce == null || sig == null) {
            reject(response);
            return;
        }
        long ts;
        try {
            ts = Long.parseLong(tsHeader);
        } catch (NumberFormatException e) {
            reject(response);
            return;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > windowMs) {   // stale/future timestamp outside the accepted window
            reject(response);
            return;
        }
        // The body can be read only once, so cache it: needed both to verify the signature and to serve downstream.
        byte[] body = request.getInputStream().readAllBytes();
        byte[] expectedSig = BridgeHmac.sign(secret, ts, nonce, body).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedSig, sig.getBytes(StandardCharsets.UTF_8))) {
            reject(response);
            return;
        }
        // Replay rejection: a nonce is accepted at most once within the window. putIfAbsent is atomic.
        pruneExpired(now);
        if (seenNonces.putIfAbsent(nonce, now + windowMs) != null) {
            reject(response);
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private void pruneExpired(long now) {
        for (Iterator<Map.Entry<String, Long>> it = seenNonces.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() < now) {
                it.remove();
            }
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"urn:protean:error:unauthorized\","
                + "\"title\":\"unauthorized\",\"status\":401,"
                + "\"detail\":\"bridge authentication required\"}");
    }

    /** Wraps a request to replay a pre-read body downstream (the raw stream is consumed for HMAC verification). */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() {
                    return in.read();
                }
                @Override public boolean isFinished() {
                    return in.available() == 0;
                }
                @Override public boolean isReady() {
                    return true;
                }
                @Override public void setReadListener(ReadListener listener) {
                    // synchronous read only
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }
    }
}
