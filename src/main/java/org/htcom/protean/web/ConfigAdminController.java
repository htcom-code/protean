/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.web;

import com.fasterxml.jackson.databind.JsonNode;
import org.htcom.protean.config.ProteanConfigService;
import org.htcom.protean.config.ProteanConfigService.ApplyResult;
import org.htcom.protean.config.ProteanConfigService.ConfigEntry;
import org.htcom.protean.error.ErrorCode;
import org.htcom.protean.error.ProteanException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Control-plane REST API for runtime configuration ({@code protean.*}). Reads current values and applies changes
 * to the live {@link ProteanConfigService}. Shares the
 * {@code protean.admin.enabled} gate with the other admin controllers; authorization is the consumer's concern.
 *
 * <ul>
 *   <li>{@code GET /platform/config} — every key with its current value and tier;</li>
 *   <li>{@code GET /platform/config/{key}} — a single key (400 {@code INVALID_ARGUMENT} if unknown);</li>
 *   <li>{@code PATCH /platform/config} — apply a {@code {key: value}} patch (400 if the batch is aborted by an
 *       unknown/invalid key; nothing is applied in that case).</li>
 * </ul>
 */
@RestController
@Profile("!worker")
@ConditionalOnProperty(name = "protean.admin.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/platform/config")
public class ConfigAdminController {

    private final ProteanConfigService configService;

    public ConfigAdminController(ProteanConfigService configService) {
        this.configService = configService;
    }

    /** All known keys with current value + tier. */
    @GetMapping
    public List<ConfigEntry> list() {
        return configService.list();
    }

    /** A single key (404 if unknown). */
    @GetMapping("/{key}")
    public ConfigEntry get(@PathVariable String key) {
        return configService.get(key)
                .orElseThrow(() -> new ProteanException(ErrorCode.INVALID_ARGUMENT, "unknown config key: " + key)
                        .with("key", key));
    }

    /**
     * Apply a {@code {key: value}} patch. Returns 200 with per-key outcomes when committed; 400 with the same
     * outcome breakdown when the batch is aborted (unknown/invalid key) and nothing was applied.
     */
    @PatchMapping
    public ResponseEntity<ApplyResult> apply(@RequestBody Map<String, JsonNode> patch) {
        ApplyResult result = configService.apply(patch, "rest");
        return result.applied() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }
}
