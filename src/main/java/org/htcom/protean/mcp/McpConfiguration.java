/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.htcom.protean.config.ProteanConfigService;
import org.htcom.protean.mcp.tools.ApproveModuleTool;
import org.htcom.protean.mcp.tools.ConfigGetTool;
import org.htcom.protean.mcp.tools.ConfigListTool;
import org.htcom.protean.mcp.tools.ConfigSetTool;
import org.htcom.protean.mcp.tools.DeployModuleTool;
import org.htcom.protean.mcp.tools.DeploySharedLibTool;
import org.htcom.protean.mcp.tools.GetModuleSourceTool;
import org.htcom.protean.mcp.tools.GetModuleTool;
import org.htcom.protean.mcp.tools.GetSharedLibTool;
import org.htcom.protean.mcp.tools.ListSharedLibsTool;
import org.htcom.protean.mcp.tools.RemoveSharedLibTool;
import org.htcom.protean.mcp.tools.ListModulesTool;
import org.htcom.protean.mcp.tools.ModuleMetricsTool;
import org.htcom.protean.mcp.tools.ModuleVersionsTool;
import org.htcom.protean.mcp.tools.QueryTracesTool;
import org.htcom.protean.mcp.tools.PatchModuleTool;
import org.htcom.protean.mcp.tools.RejectModuleTool;
import org.htcom.protean.mcp.tools.ReloadModuleResourcesTool;
import org.htcom.protean.mcp.tools.RollbackModuleTool;
import org.htcom.protean.mcp.tools.UninstallModuleTool;
import org.htcom.protean.mcp.tools.UpdateModuleTool;
import org.htcom.protean.mcp.debug.DebugSurfaceState;
import org.htcom.protean.mcp.session.McpServerNotifier;
import org.htcom.protean.mcp.session.McpSessionRegistry;
import org.htcom.protean.mcp.transport.McpStdioServer;
import org.springframework.beans.factory.ObjectProvider;
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.htcom.protean.module.ModuleManifestLoader;
import org.htcom.protean.module.ModulePlatform;
import org.htcom.protean.module.SharedLibStore;
import org.htcom.protean.runtime.TraceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.List;

/**
 * MCP adapter wiring. The whole configuration is active only under {@code protean.mcp.enabled=true} plus
 * the {@code !worker} profile — with no configuration the MCP server never starts (off by default, fail-safe).
 *
 * <p>The built-in tools are registered here as {@code @Bean}s so they cease to exist when the toggle is off.
 * Consumer-provided custom {@link McpTool} beans are registered by their own definitions, and
 * {@link McpDispatcher} collects them together as a {@code List<McpTool>} (open core).
 */
@Configuration(proxyBeanMethods = false)
@Profile("!worker")
@ConditionalOnProperty(name = "protean.mcp.enabled", havingValue = "true")
public class McpConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpConfiguration.class);

    /** Default authorization = permissive. Backs off when the consumer registers its own bean. */
    @Bean
    @ConditionalOnMissingBean(ModuleActionAuthorizer.class)
    ModuleActionAuthorizer permissiveModuleActionAuthorizer() {
        return new PermissiveModuleActionAuthorizer();
    }

    @Bean
    McpDispatcher mcpDispatcher(ObjectMapper mapper, List<McpTool> tools, ModuleActionAuthorizer authorizer,
                                McpResources resources, McpPrompts prompts,
                                ObjectProvider<McpServerNotifier> notifier,
                                ObjectProvider<DebugSurfaceState> debugState,
                                ProteanProperties properties) {
        // Strict schema mode is opt-in and now runtime-toggleable (protean.mcp.strict-schema, Tier 1), so always
        // resolve the validator up front — create() returns unavailable() when the jar is absent (→ top-level guard).
        // The dispatcher reads the strict flag live from properties; the validator instance is classpath-bound.
        SchemaValidator validator = SchemaValidator.create();
        boolean strict = properties.getMcp().isStrictSchema();
        // The networknt validator is compileOnly in the core (zero-dependency design), so strict mode silently
        // degrades to a no-op unless the consumer adds it to the runtime classpath. Warn loudly rather than let a
        // requested validation flag quietly do nothing.
        if (strict && !validator.available()) {
            log.warn("protean.mcp.strict-schema=true but the schema validator is not on the classpath — strict "
                    + "validation is DISABLED (no-op). Add 'com.networknt:json-schema-validator' to the runtime "
                    + "classpath to enable full-schema tool I/O validation.");
        }
        return new McpDispatcher(mapper, tools, authorizer, resources, prompts,
                notifier.getIfAvailable(), debugState.getIfAvailable(), properties, validator);
    }

    /**
     * Streamable HTTP session registry plus the server-to-client notification channel.
     * {@code protean.mcp.session.enabled} is on by default; when false this bean is absent and the
     * controller operates purely statelessly (current behavior). Sessions that exceed the idle timeout
     * are reclaimed automatically, and the persistent stream supports resumption via a replay buffer.
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "protean.mcp.session.enabled", havingValue = "true", matchIfMissing = true)
    McpSessionRegistry mcpSessionRegistry(
            ObjectMapper mapper,
            @Value("${protean.mcp.session.timeout:30m}") Duration idleTimeout,
            @Value("${protean.mcp.session.replay-buffer:256}") int replayBuffer,
            @Value("${protean.mcp.session.stream-timeout:1h}") Duration streamTimeout) {
        return new McpSessionRegistry(mapper, idleTimeout.toMillis(), replayBuffer, streamTimeout.toMillis());
    }

    @Bean
    McpResources mcpResources(ObjectMapper mapper, ModulePlatform platform, TraceStore traceStore,
                              org.htcom.protean.dynamic.DynamicEndpointRegistrar registrar,
                              org.htcom.protean.proxy.ReverseProxy reverseProxy) {
        return new McpResources(mapper, platform, traceStore, registrar, reverseProxy);
    }

    /** Bridge that turns module changes into resources/updated notifications. */
    @Bean
    McpResourceUpdateBridge mcpResourceUpdateBridge(McpDispatcher dispatcher, ModulePlatform platform) {
        return new McpResourceUpdateBridge(dispatcher, platform);
    }

    @Bean
    McpPrompts mcpPrompts(ObjectMapper mapper) {
        return new McpPrompts(mapper);
    }

    @Bean
    McpTool listModulesTool(ObjectMapper mapper, ModulePlatform platform) {
        return new ListModulesTool(mapper, platform);
    }

    @Bean
    McpTool getModuleTool(ObjectMapper mapper, ModulePlatform platform) {
        return new GetModuleTool(mapper, platform);
    }

    @Bean
    McpTool moduleVersionsTool(ObjectMapper mapper, ModulePlatform platform) {
        return new ModuleVersionsTool(mapper, platform);
    }

    @Bean
    McpTool getModuleSourceTool(ObjectMapper mapper, ModulePlatform platform) {
        return new GetModuleSourceTool(mapper, platform);
    }

    @Bean
    McpTool queryTracesTool(ObjectMapper mapper, org.htcom.protean.runtime.TraceStore traceStore) {
        return new QueryTracesTool(mapper, traceStore);
    }

    @Bean
    McpTool moduleMetricsTool(ObjectMapper mapper, org.htcom.protean.runtime.TraceMetrics traceMetrics) {
        return new ModuleMetricsTool(mapper, traceMetrics);
    }

    @Bean
    McpTool configListTool(ObjectMapper mapper, ProteanConfigService configService) {
        return new ConfigListTool(mapper, configService);
    }

    @Bean
    McpTool configGetTool(ObjectMapper mapper, ProteanConfigService configService) {
        return new ConfigGetTool(mapper, configService);
    }

    @Bean
    McpTool configSetTool(ObjectMapper mapper, ProteanConfigService configService) {
        return new ConfigSetTool(mapper, configService);
    }

    @Bean
    McpTool deploySharedLibTool(ObjectMapper mapper, SharedLibStore sharedLibStore,
                                org.htcom.protean.gate.SharedLibSignatureGate signatureGate) {
        return new DeploySharedLibTool(mapper, sharedLibStore, signatureGate);
    }

    @Bean
    McpTool listSharedLibsTool(ObjectMapper mapper, SharedLibStore sharedLibStore) {
        return new ListSharedLibsTool(mapper, sharedLibStore);
    }

    @Bean
    McpTool getSharedLibTool(ObjectMapper mapper, SharedLibStore sharedLibStore) {
        return new GetSharedLibTool(mapper, sharedLibStore);
    }

    @Bean
    McpTool removeSharedLibTool(ObjectMapper mapper, SharedLibStore sharedLibStore) {
        return new RemoveSharedLibTool(mapper, sharedLibStore);
    }

    @Bean
    ModuleInputNormalizer moduleInputNormalizer(ObjectMapper mapper, ModuleManifestLoader manifestLoader) {
        return new ModuleInputNormalizer(mapper, manifestLoader);
    }

    @Bean
    McpTool deployModuleTool(ObjectMapper mapper, ModulePlatform platform, ModuleInputNormalizer normalizer) {
        return new DeployModuleTool(mapper, platform, normalizer);
    }

    @Bean
    McpTool updateModuleTool(ObjectMapper mapper, ModulePlatform platform, ModuleInputNormalizer normalizer) {
        return new UpdateModuleTool(mapper, platform, normalizer);
    }

    @Bean
    McpTool patchModuleTool(ObjectMapper mapper, ModulePlatform platform) {
        return new PatchModuleTool(mapper, platform);
    }

    @Bean
    McpTool reloadModuleResourcesTool(ObjectMapper mapper, ModulePlatform platform) {
        return new ReloadModuleResourcesTool(mapper, platform);
    }

    @Bean
    McpTool rollbackModuleTool(ObjectMapper mapper, ModulePlatform platform) {
        return new RollbackModuleTool(mapper, platform);
    }

    @Bean
    McpTool uninstallModuleTool(ObjectMapper mapper, ModulePlatform platform) {
        return new UninstallModuleTool(mapper, platform);
    }

    @Bean
    McpTool approveModuleTool(ObjectMapper mapper, ModulePlatform platform) {
        return new ApproveModuleTool(mapper, platform);
    }

    @Bean
    McpTool rejectModuleTool(ObjectMapper mapper, ModulePlatform platform) {
        return new RejectModuleTool(mapper, platform);
    }

    // --- scope admin tools (protean.scope_*). Following the debug.* convention, these are always listed (registered
    //     under mcp.enabled) so an agent can discover them; the ScopeAdminService they operate on exists only under
    //     auto-provision, so each tool resolves it via ObjectProvider and returns a clear call-time error when off. ---

    @Bean
    McpTool scopeListTool(ObjectMapper mapper, ObjectProvider<org.htcom.protean.db.ScopeAdminService> scopes) {
        return new org.htcom.protean.mcp.tools.ScopeTools.ListTool(mapper, scopes);
    }

    @Bean
    McpTool scopeGetTool(ObjectMapper mapper, ObjectProvider<org.htcom.protean.db.ScopeAdminService> scopes) {
        return new org.htcom.protean.mcp.tools.ScopeTools.GetTool(mapper, scopes);
    }

    @Bean
    McpTool scopeCreateTool(ObjectMapper mapper, ObjectProvider<org.htcom.protean.db.ScopeAdminService> scopes) {
        return new org.htcom.protean.mcp.tools.ScopeTools.CreateTool(mapper, scopes);
    }

    @Bean
    McpTool scopeOpenTool(ObjectMapper mapper, ObjectProvider<org.htcom.protean.db.ScopeAdminService> scopes) {
        return new org.htcom.protean.mcp.tools.ScopeTools.OpenTool(mapper, scopes);
    }

    @Bean
    McpTool scopeCloseTool(ObjectMapper mapper, ObjectProvider<org.htcom.protean.db.ScopeAdminService> scopes) {
        return new org.htcom.protean.mcp.tools.ScopeTools.CloseTool(mapper, scopes);
    }

    @Bean
    McpTool scopeDetachTool(ObjectMapper mapper, ObjectProvider<org.htcom.protean.db.ScopeAdminService> scopes) {
        return new org.htcom.protean.mcp.tools.ScopeTools.DetachTool(mapper, scopes);
    }

    @Bean
    McpTool scopeDestroyTool(ObjectMapper mapper, ObjectProvider<org.htcom.protean.db.ScopeAdminService> scopes) {
        return new org.htcom.protean.mcp.tools.ScopeTools.DestroyTool(mapper, scopes);
    }

    /** stdio transport entry point — only when {@code protean.mcp.stdio=true} (local spawn scenario). */
    @Bean
    @ConditionalOnProperty(name = "protean.mcp.stdio", havingValue = "true")
    McpStdioServer mcpStdioServer(McpDispatcher dispatcher, ObjectMapper mapper) {
        return new McpStdioServer(dispatcher, mapper);
    }
}
