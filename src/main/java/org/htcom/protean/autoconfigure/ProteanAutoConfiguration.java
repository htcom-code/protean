/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.autoconfigure;

import org.htcom.protean.ProteanApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Protean auto-configuration — library consumers receive Protean beans automatically via this class.
 *
 * <p>When the consumer app's {@code @SpringBootApplication} (→ {@code @EnableAutoConfiguration})
 * loads this class through
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, the
 * {@code @ComponentScan} below registers all components in the {@code org.htcom.protean} package
 * (isolation strategies, the module platform, gates, the bridge, controllers, etc.). The consumer's
 * own package does not need to include {@code org.htcom.protean}, so the component-scan coupling
 * disappears.
 *
 * <p>Scan exclusions:
 * <ul>
 *   <li>{@link ProteanApplication} — the entry point for standalone/dev/test and worker boot (prevents re-registration as a configuration).</li>
 *   <li>This class itself — already handled by auto-config, so this prevents duplicate registration.</li>
 * </ul>
 * Worker beans are split by {@code @Profile("worker")} and host beans by {@code @Profile("!worker")},
 * so even though the scan scope is broad, the profiles/conditions narrow what actually gets
 * registered.
 */
@AutoConfiguration
@EnableConfigurationProperties(ProteanProperties.class)
@ComponentScan(
        basePackages = "org.htcom.protean",
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {ProteanApplication.class, ProteanAutoConfiguration.class}))
public class ProteanAutoConfiguration {

    /**
     * Single shared {@link HttpClient} for Protean's internal control/forward planes (worker admin, worker/container
     * deploy, reverse-proxy forwarding, the promotion gate). {@code HttpClient} is thread-safe and designed to be
     * shared; each independent instance spawns its own selector-manager daemon thread and connection pool.
     *
     * <p>Before consolidation there were five field-level clients plus a per-call client in the verification gate.
     * Under a heavy full-suite run (many {@code @SpringBootTest} context loads, a deliberately small heap) that
     * selector-thread churn intermittently surfaced as {@code "selector manager closed"} I/O failures in the worker
     * e2e tests. One shared bean cuts the per-context selector managers to a single instance and, being a bean,
     * is closed deterministically on context shutdown (after its consumers) rather than at unpredictable GC time.
     *
     * <p>{@link ConditionalOnMissingBean} lets a consumer override with their own tuned client.
     */
    @Bean
    @ConditionalOnMissingBean
    public HttpClient proteanHttpClient() {
        // A connect timeout so a dead/unreachable worker fails the TCP connect promptly instead of hanging the caller;
        // per-request read timeouts are set by the callers that need them (e.g. WorkerAdminClient's control-plane sends).
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }
}
