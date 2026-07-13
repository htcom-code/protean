/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.gate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.htcom.protean.module.ModuleDescriptor;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Module signing/verification utility (promotion-gate signature check). Uses JDK-native Ed25519 (no dependencies).
 *
 * <p>The signed content is {@link #canonicalBytes(ModuleDescriptor)} — a <b>deterministic canonicalization</b>
 * of the module content excluding signerKeyId/signature (map keys sorted). Consumers sign with a private key and
 * attach signerKeyId/signature to the descriptor; the server verifies against a trust store (keyId to public key)
 * to obtain <b>integrity, authenticity, and authorization</b>.
 */
public final class ModuleSigning {

    private static final String ALGORITHM = "Ed25519";
    /** Canonical serialization: deterministic via sorted map keys (list order is significant and preserved). */
    private static final ObjectMapper CANON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private ModuleSigning() {
    }

    /** Canonical bytes to be signed (excludes signerKeyId/signature, deterministic). */
    public static byte[] canonicalBytes(ModuleDescriptor d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("version", d.version());
        m.put("trustTier", d.trustTier() == null ? null : d.trustTier().name());
        m.put("desiredState", d.desiredState() == null ? null : d.desiredState().name());
        m.put("controllerFqcn", d.controllerFqcn());
        m.put("componentFqcns", d.componentFqcns());
        m.put("sources", d.sources());
        m.put("tests", d.tests());
        m.put("needsSharedBeans", d.needsSharedBeans());
        m.put("verification", d.verification());
        m.put("isolationMode", d.isolationMode());
        m.put("bridgedInterfaces", d.bridgedInterfaces());
        m.put("resources", d.resources());
        m.put("kind", d.kind() == null ? null : d.kind().name());
        m.put("exports", d.exports());
        m.put("uses", d.uses());
        // NOTE: usedSharedLibs is intentionally excluded — it is server-observed (not consumer-authored), so it must
        // not participate in the signature (the consumer signs before the server observes it). signerKeyId/signature
        // are likewise excluded (a signature cannot cover itself). kind/exports/uses ARE consumer-authored declarations
        // (shared-module typed sharing), so they are covered by the signature (tamper protection).
        try {
            return CANON.writeValueAsBytes(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("module canonicalization failed: " + d.id(), e);
        }
    }

    /** Sign the canonical content with a private key and return the Base64 signature. */
    public static String sign(ModuleDescriptor d, PrivateKey key) {
        return sign(canonicalBytes(d), key);
    }

    /** Sign arbitrary content bytes (e.g. a shared-lib jar) and return the Base64 Ed25519 signature. */
    public static String sign(byte[] content, PrivateKey key) {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initSign(key);
            s.update(content);
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("signing failed", e);
        }
    }

    /** Verify a signature with a public key. Algorithm/decode errors and mismatches all return false (never throws). */
    public static boolean verify(ModuleDescriptor d, String base64Signature, PublicKey key) {
        return verify(canonicalBytes(d), base64Signature, key);
    }

    /**
     * Verify an Ed25519 signature over arbitrary content bytes (e.g. a shared-lib jar). Algorithm/decode errors and
     * mismatches all return false (never throws). Used by the opt-in shared-lib signature seam.
     */
    public static boolean verify(byte[] content, String base64Signature, PublicKey key) {
        if (base64Signature == null || key == null) {
            return false;
        }
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(key);
            s.update(content);
            return s.verify(Base64.getDecoder().decode(base64Signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Base64(X.509 DER) to Ed25519 public key. */
    public static PublicKey publicKeyFromBase64(String base64X509) {
        try {
            byte[] der = Base64.getDecoder().decode(base64X509);
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Ed25519 public key parsing failed", e);
        }
    }

    /** Public key to Base64(X.509 DER). For generating trust store configuration values. */
    public static String publicKeyToBase64(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /** Generate an Ed25519 key pair (for signature issuers and tests). */
    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not support Ed25519", e);
        }
    }
}
