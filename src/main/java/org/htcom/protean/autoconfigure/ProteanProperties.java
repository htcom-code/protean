/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Type-safe Protean configuration ({@code protean.*}). Consolidates the scattered {@code @Value}
 * injections into one place so consumers get IDE auto-completion and validation
 * (spring-boot-configuration-processor metadata).
 *
 * <p>The defaults replicate the existing {@code @Value} defaults exactly — the goal is to preserve
 * identical behavior. Keys used by {@code @ConditionalOnProperty} (admin.enabled, worker.runtime,
 * worker.db.auto-provision, worker.rpc-bridge, module-store.backend) are modeled here as well for
 * documentation and metadata (condition evaluation still reads the raw properties).
 */
@ConfigurationProperties("protean")
public class ProteanProperties {

    @NestedConfigurationProperty
    private final Admin admin = new Admin();
    @NestedConfigurationProperty
    private final Mcp mcp = new Mcp();
    @NestedConfigurationProperty
    private final Bridge bridge = new Bridge();
    @NestedConfigurationProperty
    private final Gate gate = new Gate();
    @NestedConfigurationProperty
    private final Isolation isolation = new Isolation();
    @NestedConfigurationProperty
    private final Module module = new Module();
    @NestedConfigurationProperty
    private final ModuleStore moduleStore = new ModuleStore();
    @NestedConfigurationProperty
    private final Reconcile reconcile = new Reconcile();
    @NestedConfigurationProperty
    private final Trace trace = new Trace();
    @NestedConfigurationProperty
    private final Worker worker = new Worker();

    public Admin getAdmin() { return admin; }
    public Mcp getMcp() { return mcp; }
    public Bridge getBridge() { return bridge; }
    public Gate getGate() { return gate; }
    public Isolation getIsolation() { return isolation; }
    public Module getModule() { return module; }
    public ModuleStore getModuleStore() { return moduleStore; }
    public Reconcile getReconcile() { return reconcile; }
    public Trace getTrace() { return trace; }
    public Worker getWorker() { return worker; }

    /** Whether the admin REST surface (/platform/*) is exposed. */
    public static class Admin {
        /** When false, ModuleAdminController/TraceAdminController are not registered. */
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /**
     * MCP adapter surface (/platform/mcp + stdio). The entry point through which MCP agents deploy
     * modules directly. Because it is an RCE surface with permissive authorization by default
     * (consumers control it via Security/authorizer), it is <b>off by default</b> — the consumer
     * must explicitly enable it to start it (fail-safe). Authentication is not implemented by the
     * library and is delegated to the consumer's Spring Security.
     */
    public static class Mcp {
        /** Must be true to register the MCP server (McpHttpController, etc.) (off by default). */
        private boolean enabled = false;
        /** Enables the stdio transport (newline-delimited JSON-RPC). For entry points spawned by local agents (off by default). */
        private boolean stdio = false;
        /** Captures stdout/stderr during gate-1 test execution and includes it in failure diagnostics (opt-in, since it intercepts the global System.out). */
        private volatile boolean captureTestOutput = false;
        /**
         * Full JSON-Schema validation of tool arguments (against inputSchema) and structured results (against
         * outputSchema) at the dispatch boundary. Off by default → only the zero-dep top-level guard runs. When on,
         * it requires a JSON-Schema validator on the classpath (networknt); if absent, it degrades to the top-level
         * guard. Mainly for consumers whose custom tools should be contract-enforced at runtime.
         */
        private volatile boolean strictSchema = false;
        @NestedConfigurationProperty
        private final Debug debug = new Debug();
        @NestedConfigurationProperty
        private final Authorization authorization = new Authorization();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isStdio() { return stdio; }
        public void setStdio(boolean stdio) { this.stdio = stdio; }
        public boolean isCaptureTestOutput() { return captureTestOutput; }
        public void setCaptureTestOutput(boolean captureTestOutput) { this.captureTestOutput = captureTestOutput; }
        public boolean isStrictSchema() { return strictSchema; }
        public void setStrictSchema(boolean strictSchema) { this.strictSchema = strictSchema; }
        public Debug getDebug() { return debug; }
        public Authorization getAuthorization() { return authorization; }
    }

    /**
     * OAuth2 Protected Resource Metadata (RFC 9728) — <b>opt-in discovery surface only</b>.
     * protean does not implement authentication and delegates it to the consumer's Spring Security
     * (token validation, 401, and {@code WWW-Authenticate} remain the consumer's responsibility).
     * When {@code resource} is set, {@code McpProtectedResourceMetadataController} registers
     * {@code /.well-known/oauth-protected-resource} so MCP clients can discover "which Authorization
     * Server should I get a token from." When unset, this surface does not exist (pure delegation
     * preserved).
     *
     * <p>Note: this endpoint must be reachable without authentication, so the consumer's Security
     * must {@code permitAll} it; to carry the {@code resource_metadata} pointer in a 401 response,
     * the consumer adds it in their {@code AuthenticationEntryPoint} (protean deliberately does not
     * reach into that part).
     */
    public static class Authorization {
        /** Protected resource identifier (usually the MCP endpoint URL). When set, the metadata endpoint is active. */
        private String resource;
        /** List of Authorization Server issuer URLs that issue tokens for this resource. */
        private java.util.List<String> authorizationServers = new java.util.ArrayList<>();
        /** Supported scopes (optional). */
        private java.util.List<String> scopesSupported = new java.util.ArrayList<>();
        /** Bearer token delivery method (RFC 9728 {@code bearer_methods_supported}, default header). */
        private java.util.List<String> bearerMethodsSupported = new java.util.ArrayList<>(java.util.List.of("header"));
        public String getResource() { return resource; }
        public void setResource(String resource) { this.resource = resource; }
        public java.util.List<String> getAuthorizationServers() { return authorizationServers; }
        public void setAuthorizationServers(java.util.List<String> v) { this.authorizationServers = v; }
        public java.util.List<String> getScopesSupported() { return scopesSupported; }
        public void setScopesSupported(java.util.List<String> v) { this.scopesSupported = v; }
        public java.util.List<String> getBearerMethodsSupported() { return bearerMethodsSupported; }
        public void setBearerMethodsSupported(java.util.List<String> v) { this.bearerMethodsSupported = v; }
    }

    /**
     * Interactive debug surface (debug.* tools, Level 3). More dangerous than deployment, so it is
     * a separate opt-in.
     * <p>The enable key is {@code protean.mcp.debug.enabled} (dotted) — the
     * {@code @ConditionalOnProperty} of {@code DebugMcpConfiguration} and the idle-timeout
     * {@code @Value} read this nested path.
     */
    public static class Debug {
        /** Whether the debug.* tools are wired (off by default). The DebugMcpConfiguration gate key. */
        private boolean enabled = false;
        /** Threshold for automatically reclaiming idle debug sessions (default 30m; disabled when 0 or negative). */
        private java.time.Duration sessionIdleTimeout = java.time.Duration.ofMinutes(30);
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public java.time.Duration getSessionIdleTimeout() { return sessionIdleTimeout; }
        public void setSessionIdleTimeout(java.time.Duration sessionIdleTimeout) { this.sessionIdleTimeout = sessionIdleTimeout; }
    }

    /** Worker-to-main RPC bridge. */
    public static class Bridge {
        /** The main bridge URL to which the worker forwards shared-bean calls (injected into the worker process). */
        private String url;
        /**
         * Require a shared-secret bearer token on {@code /__bridge/*} (opt-in, default off to preserve
         * existing behavior). When on, the main side generates/uses a token and injects it into spawned
         * workers, and unauthenticated calls are rejected with 401.
         */
        private boolean authEnabled = false;
        /**
         * The shared secret used to authenticate worker-to-main bridge calls. When blank and auth is
         * enabled, the main side auto-generates one (per JVM lifetime) and injects it into workers.
         * Set explicitly to pin a stable, externally managed secret.
         */
        private String secret;
        /**
         * Authentication scheme: {@code token} (a static bearer token, default) or {@code hmac}
         * (per-request HMAC-SHA256 over timestamp + nonce + body, which additionally defends against
         * replay and body tampering). Both share the same symmetric secret.
         */
        private volatile String authMode = "token";
        /** HMAC mode: max accepted clock skew (ms) between the worker's request timestamp and the main clock. */
        private volatile long hmacWindowMs = 30_000;
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public boolean isAuthEnabled() { return authEnabled; }
        public void setAuthEnabled(boolean authEnabled) { this.authEnabled = authEnabled; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public String getAuthMode() { return authMode; }
        public void setAuthMode(String authMode) { this.authMode = authMode; }
        public long getHmacWindowMs() { return hmacWindowMs; }
        public void setHmacWindowMs(long hmacWindowMs) { this.hmacWindowMs = hmacWindowMs; }
    }

    /**
     * Promotion gate toggles. The defaults are on the safe side (all on) — preserving the existing
     * behavior. A library consumer can relax (turn off) individual gates to match their trust level.
     */
    public static class Gate {
        /** Gate 1: require bundled unit tests and enforce that they pass (when false, passes even with no tests). */
        private volatile boolean testsEnabled = true;
        /** Gate 2: bytecode review (CodeRules such as ForbiddenApiRule) (when false, skips code checks). */
        private volatile boolean reviewEnabled = true;
        @NestedConfigurationProperty
        private final Signature signature = new Signature();
        @NestedConfigurationProperty
        private final Approval approval = new Approval();
        public boolean isTestsEnabled() { return testsEnabled; }
        public void setTestsEnabled(boolean testsEnabled) { this.testsEnabled = testsEnabled; }
        public boolean isReviewEnabled() { return reviewEnabled; }
        public void setReviewEnabled(boolean reviewEnabled) { this.reviewEnabled = reviewEnabled; }
        public Signature getSignature() { return signature; }
        public Approval getApproval() { return approval; }

        /** Signature verification gate (opt-in). When enabled, every install must be signed with a trusted key to pass (runs first in the gate chain). */
        public static class Signature {
            /** When true, enforces the signature verification gate (off by default). */
            private volatile boolean required = false;
            /**
             * When true, live shared-lib uploads (the put-jar surface) must be signed with a trusted key — the opt-in
             * Ed25519 seam for untrusted/relay submissions. Off by default (the trusted-developer
             * model relies on consumer authz). Reuses {@link #keys} as the trust store.
             */
            private volatile boolean sharedLibRequired = false;
            /** Trusted public keys: keyId -> Base64(X.509 Ed25519 public key). */
            private Map<String, String> keys = new LinkedHashMap<>();
            public boolean isRequired() { return required; }
            public void setRequired(boolean required) { this.required = required; }
            public boolean isSharedLibRequired() { return sharedLibRequired; }
            public void setSharedLibRequired(boolean sharedLibRequired) { this.sharedLibRequired = sharedLibRequired; }
            public Map<String, String> getKeys() { return keys; }
            public void setKeys(Map<String, String> keys) { this.keys = keys; }
        }

        /**
         * Approval gate (opt-in, human authorization). When enabled, an install passes only the
         * automated gates and is stored as PENDING_APPROVAL (not served); it must be approved via
         * POST /{id}/approve to run gate 3 + deploy and become ACTIVE. Because reconcile only
         * revives ACTIVE modules, an unapproved module is not served even after a restart (bypass
         * blocked).
         */
        public static class Approval {
            /** When true, enforces the approval gate (off by default). */
            private volatile boolean required = false;
            public boolean isRequired() { return required; }
            public void setRequired(boolean required) { this.required = required; }
        }
    }

    /** Isolation strategy selection. */
    public static class Isolation {
        /** Global default isolation mode: in-process | worker | container. */
        private String mode = "in-process";
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
    }

    /** Module request execution control. */
    public static class Module {
        /** Module request timeout (ms). 0 = unlimited. */
        private volatile long requestTimeoutMs = 0;
        /**
         * Shared lib directory. When set, an app-lifetime URLClassLoader is built from the *.jar
         * files in that directory and inserted as the parent of the module ClassLoader
         * (module -> sharedLib -> app), and it is also added to the module compile classpath. Lets
         * consumers drop in drivers/libraries without rebuilding the app. Empty = off (module parent
         * = platform CL). Applies to in-process mode.
         */
        private String sharedLibDir = "";
        /**
         * Directory for the server-managed live shared-lib store (the put-jar surface). Jars uploaded at runtime are
         * persisted here (separate from the read-only {@link #sharedLibDir} seed) and folded, on top of the seed, into
         * each new shared-lib generation. Empty = default ({@code java.io.tmpdir/protean-shared-libs}). The path is a
         * restart artifact; the jar set it holds is live.
         */
        private String sharedLibStoreDir = "";
        /**
         * When a shared-lib generation is published (a put-jar deploy/remove changed a jar in use), eagerly rebind the
         * ACTIVE modules that use the changed jar onto the new generation (Plan A). {@code true}
         * (default) keeps the live system consistent (zero-downtime principle); {@code false} leaves modules on their
         * bound generation until they redeploy (lazy). Read live.
         */
        private volatile boolean eagerSharedLibInvalidation = true;
        /**
         * When a library module republishes its generation (shared-module typed sharing), eagerly propagate it to the
         * ACTIVE dependents that {@code use} it — Plan A1 (retarget onto the new generation without recompiling when
         * the change is binary-compatible) or Plan A2 (recompile). {@code true} (default) keeps the live system
         * consistent (zero-downtime principle); {@code false} leaves dependents on their bound generation until they
         * redeploy. Read live.
         */
        private volatile boolean eagerSharedModuleInvalidation = true;
        @NestedConfigurationProperty
        private final Executor executor = new Executor();
        public long getRequestTimeoutMs() { return requestTimeoutMs; }
        public void setRequestTimeoutMs(long requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
        public String getSharedLibDir() { return sharedLibDir; }
        public void setSharedLibDir(String sharedLibDir) { this.sharedLibDir = sharedLibDir; }
        public String getSharedLibStoreDir() { return sharedLibStoreDir; }
        public void setSharedLibStoreDir(String sharedLibStoreDir) { this.sharedLibStoreDir = sharedLibStoreDir; }
        public boolean isEagerSharedLibInvalidation() { return eagerSharedLibInvalidation; }
        public void setEagerSharedLibInvalidation(boolean v) { this.eagerSharedLibInvalidation = v; }
        public boolean isEagerSharedModuleInvalidation() { return eagerSharedModuleInvalidation; }
        public void setEagerSharedModuleInvalidation(boolean v) { this.eagerSharedModuleInvalidation = v; }
        public Executor getExecutor() { return executor; }

        /** Module-managed executor (ProteanTaskExecutor) settings. Injected into modules for use in async/scheduled tasks. */
        public static class Executor {
            /** Per-module thread pool size. */
            private int poolSize = 2;
            public int getPoolSize() { return poolSize; }
            public void setPoolSize(int poolSize) { this.poolSize = poolSize; }
        }
    }

    /** Startup reconcile (recovery of ACTIVE modules after a restart) settings. */
    public static class Reconcile {
        /**
         * Thread-pool size for the parallel pre-compile phase of startup reconcile. On boot the platform
         * recompiles every ACTIVE module from source (javac dominates — ~96% of per-module cost); pre-compiling
         * into the shared compile cache in parallel lets the subsequent serial deploy hit the fast-path (no javac).
         * {@code 0} = auto ({@link Runtime#availableProcessors()}), {@code 1} = fully serial (legacy behavior,
         * kill switch), {@code N} = cap at N threads. Boot-time only (not live-reloadable).
         */
        private int compileParallelism = 0;
        public int getCompileParallelism() { return compileParallelism; }
        public void setCompileParallelism(int compileParallelism) { this.compileParallelism = compileParallelism; }

        /**
         * Reuse one javac file manager per worker thread across the parallel pre-compile phase. The compile
         * classpath is identical and read-only for every module, so its jar index is scanned once per thread
         * instead of once per compile — cutting the classpath rescan that caps parallel efficiency. {@code true}
         * (default) enables it; {@code false} restores the per-call file manager (kill switch). Only takes
         * effect when {@link #compileParallelism} runs the parallel phase. Boot-time only (not live-reloadable).
         */
        private boolean reuseFileManager = true;
        public boolean isReuseFileManager() { return reuseFileManager; }
        public void setReuseFileManager(boolean reuseFileManager) { this.reuseFileManager = reuseFileManager; }
    }

    /** Durable store for module descriptors. */
    public static class ModuleStore {
        /** Storage backend: filesystem | jdbc. */
        private String backend = "filesystem";
        /** Storage directory for the filesystem backend. */
        private String dir = System.getProperty("java.io.tmpdir") + "/protean-modules";
        public String getBackend() { return backend; }
        public void setBackend(String backend) { this.backend = backend; }
        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
    }

    /** Runtime trace recording. */
    public static class Trace {
        private volatile boolean enabled = true;
        /** Ring buffer capacity (most recent N requests). */
        private volatile int capacity = 200;

        @NestedConfigurationProperty
        private final Metrics metrics = new Metrics();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }
        public Metrics getMetrics() { return metrics; }
    }

    /**
     * Per-module aggregated request metrics (opt-in). When disabled (default) the recording hot path
     * pays nothing beyond a single boolean check.
     */
    public static class Metrics {
        /** Aggregate per-module counters/latency histograms on the recording path. */
        private volatile boolean enabled = false;
        /** Latency histogram buckets per module (log-linear). More buckets = finer percentiles, more memory. */
        private int latencyBuckets = 20;
        /** Maximum number of distinct modules tracked; the least-recently-seen is evicted past this. */
        private int maxModules = 512;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getLatencyBuckets() { return latencyBuckets; }
        public void setLatencyBuckets(int latencyBuckets) { this.latencyBuckets = latencyBuckets; }
        public int getMaxModules() { return maxModules; }
        public void setMaxModules(int maxModules) { this.maxModules = maxModules; }
    }

    /** External worker (process/container) settings. */
    public static class Worker {
        /** Maximum modules per worker (1 = a dedicated JVM per module). */
        private int modulesPerWorker = 4;
        /** Number of empty workers to keep warm (for reuse). */
        private int minWarm = 0;
        /** Automatically restart modules of a crashed worker (process track). */
        private boolean autoRestart = false;
        /** Allow the worker to call main shared beans via the RPC bridge. */
        private boolean rpcBridge = false;
        /** Worker runtime deployment model: embed | sidecar. */
        private String runtime = "embed";

        @NestedConfigurationProperty
        private final Datasource datasource = new Datasource();
        @NestedConfigurationProperty
        private final Container container = new Container();
        @NestedConfigurationProperty
        private final Db db = new Db();
        @NestedConfigurationProperty
        private final Sidecar sidecar = new Sidecar();
        @NestedConfigurationProperty
        private final AdminAuth adminAuth = new AdminAuth();

        public int getModulesPerWorker() { return modulesPerWorker; }
        public void setModulesPerWorker(int modulesPerWorker) { this.modulesPerWorker = modulesPerWorker; }
        public int getMinWarm() { return minWarm; }
        public void setMinWarm(int minWarm) { this.minWarm = minWarm; }
        public boolean isAutoRestart() { return autoRestart; }
        public void setAutoRestart(boolean autoRestart) { this.autoRestart = autoRestart; }
        public boolean isRpcBridge() { return rpcBridge; }
        public void setRpcBridge(boolean rpcBridge) { this.rpcBridge = rpcBridge; }
        public String getRuntime() { return runtime; }
        public void setRuntime(String runtime) { this.runtime = runtime; }
        public Datasource getDatasource() { return datasource; }
        public Container getContainer() { return container; }
        public Db getDb() { return db; }
        public Sidecar getSidecar() { return sidecar; }
        public AdminAuth getAdminAuth() { return adminAuth; }
    }

    /**
     * Optional authentication on the worker {@code /__admin/*} control plane (main → worker). Off by default: the
     * plane is localhost/host-scoped and the trust model is trusted-developer ({@code /__admin/deploy} already accepts
     * source). Turn on as defense-in-depth — chiefly for the container track, whose published port is more exposed —
     * so an attacker who reaches a worker's admin port cannot deploy/redeploy/undeploy without the shared secret. The
     * read-only {@code /__admin/health} probe stays open. Mirrors the RPC-bridge auth scheme (reuses {@code BridgeHmac})
     * but on its own toggle/secret, independent of {@code protean.bridge.*}.
     */
    public static class AdminAuth {
        /** Require auth on mutating {@code /__admin/*} calls. When on, the main generates/uses a secret and injects it
         * (with this flag and the mode) into spawned workers; unauthenticated mutating calls are rejected with 401. */
        private boolean enabled = false;
        /** The shared secret. Blank + enabled → the main auto-generates one per JVM lifetime and injects it into workers.
         * Set explicitly to pin a stable, externally managed secret. On a worker it is the injected value. */
        private String secret;
        /** {@code hmac} (per-request HMAC-SHA256 over timestamp + nonce + body, with replay/tamper defense — the default,
         * strongest option) or {@code token} (a static bearer token). */
        private volatile String mode = "hmac";
        /** hmac mode: max accepted clock skew (ms) between the sender timestamp and the worker clock. */
        private volatile long hmacWindowMs = 30_000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public long getHmacWindowMs() { return hmacWindowMs; }
        public void setHmacWindowMs(long hmacWindowMs) { this.hmacWindowMs = hmacWindowMs; }
    }

    /** Worker-wide manual DB scope (used when auto-provision is off). */
    public static class Datasource {
        private String url = "";
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }

    /** Container track (Docker) settings. */
    public static class Container {
        private String image = "eclipse-temurin:21-jdk";
        /** Explicit worker jar path (empty = auto-detect the -boot.jar in build/libs). */
        private String jar = "";
        private String memory = "256m";
        /** PID limit for fork-bomb protection. */
        private long pidsLimit = 512;
        /** Network for egress isolation (e.g. internal). Empty = default. */
        private String network = "";
        /** seccomp profile path. Empty = Docker default. */
        private String seccomp = "";
        private boolean autoRestart = false;
        /** Hostname rewrite target so the container can reach the host DB. */
        private String dbHost = "host.docker.internal";

        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }
        public String getJar() { return jar; }
        public void setJar(String jar) { this.jar = jar; }
        public String getMemory() { return memory; }
        public void setMemory(String memory) { this.memory = memory; }
        public long getPidsLimit() { return pidsLimit; }
        public void setPidsLimit(long pidsLimit) { this.pidsLimit = pidsLimit; }
        public String getNetwork() { return network; }
        public void setNetwork(String network) { this.network = network; }
        public String getSeccomp() { return seccomp; }
        public void setSeccomp(String seccomp) { this.seccomp = seccomp; }
        public boolean isAutoRestart() { return autoRestart; }
        public void setAutoRestart(boolean autoRestart) { this.autoRestart = autoRestart; }
        public String getDbHost() { return dbHost; }
        public void setDbHost(String dbHost) { this.dbHost = dbHost; }
    }

    /** Automatic provisioning of a per-module isolated DB scope. */
    public static class Db {
        private boolean autoProvision = false;
        /** mysql | postgresql. */
        private String dialect;
        // volatile: read live off the provisioning thread after a runtime admin-credential rotation (see DbScopeProvisioner).
        private volatile String adminUrl;
        private volatile String adminUsername;
        private volatile String adminPassword;
        /** Whether to remove the provisioned scope on undeploy. */
        private boolean deprovisionOnUndeploy = false;

        public boolean isAutoProvision() { return autoProvision; }
        public void setAutoProvision(boolean autoProvision) { this.autoProvision = autoProvision; }
        public String getDialect() { return dialect; }
        public void setDialect(String dialect) { this.dialect = dialect; }
        public String getAdminUrl() { return adminUrl; }
        public void setAdminUrl(String adminUrl) { this.adminUrl = adminUrl; }
        public String getAdminUsername() { return adminUsername; }
        public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }
        public String getAdminPassword() { return adminPassword; }
        public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
        public boolean isDeprovisionOnUndeploy() { return deprovisionOnUndeploy; }
        public void setDeprovisionOnUndeploy(boolean v) { this.deprovisionOnUndeploy = v; }
    }

    /** Sidecar worker runtime (opt-in). */
    public static class Sidecar {
        private String jar = "";
        private String image = "";
        /** Shared-type jar for worker compilation. */
        private String sharedApi = "";
        public String getJar() { return jar; }
        public void setJar(String jar) { this.jar = jar; }
        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }
        public String getSharedApi() { return sharedApi; }
        public void setSharedApi(String sharedApi) { this.sharedApi = sharedApi; }
    }
}
