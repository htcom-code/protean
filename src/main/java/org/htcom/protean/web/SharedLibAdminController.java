/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.web;

import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.error.ProteanException;
import org.htcom.protean.gate.SharedLibSignatureGate;
import org.htcom.protean.module.SharedLibStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Control-plane REST API for the live shared-lib store (the put-jar transmission surface). Multipart is the primary transport (native jars are 1–5&nbsp;MB;
 * base64-over-MCP adds ~33% — MCP is for small/reference use). Errors flow through the shared
 * {@link ProteanAdminErrorAdvice} (RFC&nbsp;9457).
 *
 * <p>A deploy is a bundle = one generation: pass parallel {@code name}/{@code version} form fields and one
 * {@code file} part per jar. Authentication/authorization is a separate opt-in concern (the current trust model
 * assumes trusted developers; the Ed25519 signature seam is opt-in). Opt out of the whole surface with
 * {@code protean.admin.enabled=false}.
 */
@RestController
@Profile("!worker")
@ConditionalOnProperty(name = "protean.admin.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/platform/shared-libs")
public class SharedLibAdminController {

    private final SharedLibStore store;
    private final SharedLibSignatureGate signatureGate;

    public SharedLibAdminController(SharedLibStore store, SharedLibSignatureGate signatureGate) {
        this.store = store;
        this.signatureGate = signatureGate;
    }

    /** The current generation id and the live stored libs. */
    @GetMapping
    public SharedLibStore.SharedLibsView list() {
        return store.view();
    }

    /** Single stored lib metadata (404 if not stored). */
    @GetMapping("/{name}")
    public SharedLibStore.StoredLib get(@PathVariable String name) {
        return store.get(name)
                .orElseThrow(() -> new ProteanException(ErrorCode.SHARED_LIB_NOT_FOUND, name).with("name", name));
    }

    /**
     * Uploads a bundle of jars as one new generation. {@code name}/{@code version} (and the optional
     * {@code signerKeyId}/{@code signature}) are parallel to the {@code file} parts. Returns 201 with the resulting
     * view. An upload whose every jar already matches the store is idempotent (no new generation).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SharedLibStore.SharedLibsView> deploy(
            @RequestParam("name") List<String> names,
            @RequestParam("version") List<String> versions,
            @RequestParam(value = "signerKeyId", required = false) List<String> signerKeyIds,
            @RequestParam(value = "signature", required = false) List<String> signatures,
            @RequestPart("file") List<MultipartFile> files) {
        int n = files.size();
        if (names.size() != n || versions.size() != n) {
            throw new ProteanException(ErrorCode.INVALID_ARGUMENT,
                    "shared-lib deploy: name (" + names.size() + "), version (" + versions.size()
                            + ") and file (" + n + ") counts must match");
        }
        requireParallel("signerKeyId", signerKeyIds, n);
        requireParallel("signature", signatures, n);

        List<SharedLibStore.IncomingLib> bundle = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            SharedLibStore.IncomingLib in = new SharedLibStore.IncomingLib(names.get(i), versions.get(i),
                    bytes(files.get(i)), at(signerKeyIds, i), at(signatures, i));
            signatureGate.enforce(in.name(), in.bytes(), in.signerKeyId(), in.signature());
            bundle.add(in);
        }
        store.deploy(bundle);
        return ResponseEntity.status(201).body(store.view());
    }

    /** Removes a lib from the store (future generations only; in-use generations keep it). 204 on success. */
    @DeleteMapping("/{name}")
    public ResponseEntity<Void> remove(@PathVariable String name) {
        store.remove(name);
        return ResponseEntity.noContent().build();
    }

    private static void requireParallel(String field, List<String> list, int n) {
        if (list != null && list.size() != n) {
            throw new ProteanException(ErrorCode.INVALID_ARGUMENT,
                    "shared-lib deploy: " + field + " (" + list.size() + ") must be parallel to file (" + n + ")");
        }
    }

    private static String at(List<String> list, int i) {
        return list == null ? null : list.get(i);
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read uploaded jar: " + file.getOriginalFilename(), e);
        }
    }
}
