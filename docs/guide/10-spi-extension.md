**English** | [한국어](10-spi-extension.ko.md)

# 10. SPI Extension

Protean is a library — consumers extend behavior by **registering Spring beans**, not by forking the source. Each extension axis comes as an interface plus a batteries-included default implementation; place a consumer bean and it is auto-collected or replaces the default. For each SPI below we cover (a) the signature, (b) how to register the bean, (c) an example, and (d) related config keys.

## CodeRule — promotion gate ② code rule

A rule that statically inspects compiled class bytecode. Built-in rules (`ForbiddenApiRule`, etc.) always run, and additional rules are applied by `RuleSystem`, which **auto-collects every `CodeRule` bean**.

```java
package org.htcom.protean.gate.rules;

public interface CodeRule {
    String name();
    /** Inspect a single class and return a list of violation messages (empty list = pass). */
    List<String> check(String className, byte[] bytecode);
}
```

Bean registration: expose a `CodeRule` via `@Component` or `@Bean` and `RuleSystem(List<CodeRule>)` injects it and enforces it automatically.

```java
@Component
public class NoReflectionRule implements CodeRule {
    @Override public String name() { return "no-reflection"; }

    @Override public List<String> check(String className, byte[] bytecode) {
        // Analyze bytecode with ASM, etc.
        return containsReflection(bytecode)
            ? List.of(className + ": java.lang.reflect is forbidden")
            : List.of();
    }
}
```

Related config: `protean.gate.review-enabled` (default `true`; `false` skips the gate ② code check).

## DbDialect — DB provisioning vendor

The per-vendor strategy that creates an isolated DB scope per module. The built-in `MySqlDialect`/`PostgresDialect` are provided by default, but registering a consumer `DbDialect` bean joins the registry (the same `id()` overrides the built-in — you can add Oracle/SQL Server/MariaDB, etc.).

```java
package org.htcom.protean.db;

public interface DbDialect {
    String id();
    /** Maximum identifier length (MySQL user 32, Postgres 63, etc.). */
    int maxNameLength();
    /** Create an isolated scope: dedicated DB/schema + dedicated user/role + scoped GRANT. */
    void createScope(JdbcTemplate admin, String name, String password);
    /** Drop the scope fully — DB/schema and its login together (destroy semantics). */
    void dropScope(JdbcTemplate admin, String name);
    /** Build the JDBC URL for connecting to that scope from the admin URL. */
    String scopedUrl(String adminUrl, String name);

    /**
     * Detach a scope: drop only its login (keep the DB/schema and all data). Reversible — a later
     * createScope re-enables the login. Default throws, so a custom dialect that has not implemented
     * detach never silently destroys data. Built-in MySQL = DROP USER; PostgreSQL = ALTER ROLE … NOLOGIN.
     */
    default void detachScope(JdbcTemplate admin, String name) { throw new UnsupportedOperationException(); }

    /**
     * Destroy a scope: DROP DATABASE/SCHEMA CASCADE + login — irreversible, all data lost. Default
     * delegates to dropScope (a legacy dialect's full drop is exactly destroy). Built-ins override both.
     */
    default void destroyScope(JdbcTemplate admin, String name) { dropScope(admin, name); }
}
```

`detachScope`/`destroyScope` back the scope admin lifecycle (see [11. Operations](11-operations.md)): `detach` keeps data (reversible), `destroy` is the guarded, irreversible drop. Both are default methods, so a dialect that only implements the original three keeps compiling — but it must override `detachScope` to offer data-safe detach (the default refuses rather than fall through to a destroy).

Bean registration: expose a `DbDialect` bean and `DbProvisioningConfig` collects it as `List<DbDialect>` into an `id()`-keyed registry. The dialect whose `id()` matches the `protean.worker.db.dialect` value is selected.

```java
@Bean
DbDialect mariaDbDialect() {
    return new DbDialect() {
        @Override public String id() { return "mariadb"; }
        @Override public int maxNameLength() { return 80; }
        @Override public void createScope(JdbcTemplate admin, String name, String password) {
            // DDL identifiers cannot be bind parameters → you must receive an already-sanitized name (Identifiers.safeName).
            admin.execute("CREATE DATABASE `" + name + "`");
            admin.execute("CREATE USER '" + name + "'@'%' IDENTIFIED BY '" + password + "'");
            admin.execute("GRANT ALL ON `" + name + "`.* TO '" + name + "'@'%'");
        }
        @Override public void dropScope(JdbcTemplate admin, String name) {
            admin.execute("DROP DATABASE IF EXISTS `" + name + "`");
            admin.execute("DROP USER IF EXISTS '" + name + "'@'%'");
        }
        @Override public String scopedUrl(String adminUrl, String name) {
            return adminUrl.replaceFirst("/[^/?]*(\\?|$)", "/" + name + "$1");
        }
    };
}
```

> Note: DDL identifiers (DB/schema/user names) cannot be passed as bind parameters and are embedded as strings. An already-sanitized name is passed in before the call, but a custom implementation must also honor this contract to prevent injection.

Related config (the provisioner is active only when `auto-provision=true`):

```yaml
protean:
  worker:
    db:
      auto-provision: true
      dialect: mariadb          # matches DbDialect.id()
      admin-url: jdbc:mariadb://db:3306/
      admin-username: root
      admin-password: ${DB_ADMIN_PW}
      deprovision-on-undeploy: false
```

## ModuleStoreDialect — module-store DDL vendor

The per-vendor DDL strategy for the JDBC `ModuleStore` backend (`protean.module-store.backend=jdbc`). **Distinct from `DbDialect` above**: `DbDialect` provisions an isolated DB scope per module, whereas this shapes the module-store's own schema. The built-in `h2`/`mysql`/`postgresql` dialects are provided; register a `ModuleStoreDialect` bean to add a vendor (e.g. Oracle) or, by returning an existing `id()`, to override a built-in.

The store owns the table/column names and all CRUD SQL; a dialect supplies only the two vendor-variable fragments — the large-text type for `descriptor_json` and the auto-increment definition for `module_version.seq` — plus optional pre/post-table DDL (sequences, triggers). Because `descriptor_json` stores a whole descriptor as JSON (including full module source), `jsonTextColumnType()` MUST be an unbounded large-character type; a bounded `VARCHAR` is rejected by the startup self-check.

```java
package org.htcom.protean.module;

public interface ModuleStoreDialect {
    String id();
    /** Large-character type for descriptor_json (H2 "CLOB", MySQL "LONGTEXT", PostgreSQL "TEXT"). */
    String jsonTextColumnType();
    /** Full column definition for the auto-incrementing module_version.seq primary key. */
    String autoIncrementColumnDefinition();
    /** DDL run before the tables are created (e.g. a sequence). Idempotent; empty by default. */
    default List<String> preTableDdl() { return List.of(); }
    /** DDL run after the tables are created (e.g. triggers/indexes). Idempotent; empty by default. */
    default List<String> postTableDdl() { return List.of(); }
}
```

Bean registration: expose a `ModuleStoreDialect` bean and it joins the `id()`-keyed registry (same `id()` overrides a built-in). The active dialect is chosen by `protean.module-store.dialect` (override) or, when empty, by detecting the database product name; an unknown vendor fails fast at startup.

```java
@Bean
ModuleStoreDialect oracleModuleStoreDialect() {
    return new ModuleStoreDialect() {
        @Override public String id() { return "oracle"; }
        @Override public String jsonTextColumnType() { return "CLOB"; }
        @Override public String autoIncrementColumnDefinition() {
            return "NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY";  // Oracle 12c+
        }
    };
}
```

To customize only one fragment of a built-in, subclass it (the built-ins are non-final):

```java
@Bean
ModuleStoreDialect mysqlJsonColumn() {
    return new MySqlStoreDialect() {
        @Override public String jsonTextColumnType() { return "JSON"; }  // still large; still passes the self-check
    };
}
```

> The table/column names (`module`, `module_version`, `seq`, `descriptor_json`, `id`, `version`, `desired_state`, `module_id`, `saved_at`) are a stable contract that `preTableDdl`/`postTableDdl` may reference; those statements must be idempotent since schema init re-runs on every boot.

Related config:

```yaml
protean:
  module-store:
    backend: jdbc
    dialect: oracle            # matches ModuleStoreDialect.id(); empty = auto-detect
```

## ModuleActionAuthorizer — MCP authorization

The common choke point for MCP tool calls. Protean does not implement authentication (it delegates to the consumer's Spring Security) and plugs in only the **policy** of "who can do what" via this bean. The default implementation (`PermissiveModuleActionAuthorizer`) allows everything, so the posture matches the existing unauthenticated REST admin.

```java
package org.htcom.protean.mcp;

public interface ModuleActionAuthorizer {
    Decision authorize(Principal caller, ModuleAction action, String moduleId);

    enum ModuleAction { READ, DEPLOY, UPDATE, DELETE, APPROVE, DEBUG, CUSTOM }

    record Decision(boolean allowed, String reason) {
        public static Decision allow();
        public static Decision deny(String reason);
    }
}
```

Bean registration: the default is registered via `@ConditionalOnMissingBean(ModuleActionAuthorizer.class)`, so if a consumer places their own `ModuleActionAuthorizer` bean, the default steps aside.

```java
@Bean
ModuleActionAuthorizer authorizer() {
    return (caller, action, moduleId) -> {
        if (caller == null) return ModuleActionAuthorizer.Decision.deny("authentication required");
        // Destructive actions for admins only
        boolean destructive = switch (action) {
            case DEPLOY, UPDATE, DELETE, APPROVE, DEBUG -> true;
            default -> false;
        };
        if (destructive && !isAdmin(caller))
            return ModuleActionAuthorizer.Decision.deny("not permitted: " + action);
        return ModuleActionAuthorizer.Decision.allow();
    };
}
```

Related config: `protean.mcp.enabled` (MCP surface, off by default), `protean.mcp.debug.enabled` (`DEBUG` tools). For details see [08. MCP Integration](08-mcp-integration.md), [12. Security](12-security.md).

## WorkerRuntimeProvider — worker runtime provider

An SPI that separates "with what and how to launch the worker JVM/container" from the isolation strategy. The built-ins are `embed` (default) and `sidecar`, chosen via `protean.worker.runtime`.

```java
package org.htcom.protean.isolation;

public interface WorkerRuntimeProvider {
    /** process track: the worker JVM launch command prefix [javaBin, -cp, classpath, mainClass]. */
    List<String> processLaunchPrefix();
    /** container track: image + docker run mount args + in-container execution prefix. */
    ContainerLaunchSpec containerLaunchSpec();

    record ContainerLaunchSpec(String image, List<String> mountArgs, List<String> commandPrefix) {}
}
```

The common `--spring.*` arguments (profile/isolation.mode/server.port/datasource) are appended afterward by the isolation strategy, so this SPI is responsible only for the "JVM/container startup" part that comes before them.

Bean registration: the two built-in implementations are activated only under the conditions `protean.worker.runtime=embed` (default when unset) and `=sidecar` respectively. To plug in a custom provider, register a `WorkerRuntimeProvider` bean and set `protean.worker.runtime` to a value **other than** `embed`/`sidecar` so all built-in conditions are disabled (to avoid a bean conflict).

```java
@Bean
WorkerRuntimeProvider customRuntime() {   // + protean.worker.runtime=custom
    return new WorkerRuntimeProvider() {
        @Override public List<String> processLaunchPrefix() {
            return List.of("/opt/jdk/bin/java", "-XX:+UseZGC",
                "-cp", System.getProperty("java.class.path"),
                "org.htcom.protean.boot.ProteanWorkerLauncher");
        }
        @Override public ContainerLaunchSpec containerLaunchSpec() {
            return new ContainerLaunchSpec("registry/my-worker:1.0",
                List.of(),
                List.of("java", "-cp", "/app/*", "org.htcom.protean.boot.ProteanWorkerLauncher"));
        }
    };
}
```

Related config: `protean.worker.runtime`, `protean.worker.sidecar.jar`/`.image`/`.shared-api`. For per-track details see [05. Isolation Modes](05-isolation-modes.md).

## ModuleUnloadCallback — unload cleanup hook

A hook invoked **just before** the module's child context is closed. It is for cleaning up out-of-context resources that `child.close()` cannot reach (ThreadLocals on shared/pool threads, static cache registrations, JMX MBeans, custom clients, etc.).

```java
package org.htcom.protean.module;

public interface ModuleUnloadCallback {
    void onUnload(String moduleId);
}
```

Bean registration: it can live in the module's child context (the module cleaning up after itself) or in the consumer's root context — the platform gathers them via `getBeansOfType(ModuleUnloadCallback.class)` (including the parent context) and invokes them. Exceptions are swallowed and logged (one callback's failure does not block other cleanups or the unload).

```java
@Component   // inside the module source or inside the consumer app
public class CacheEvictCallback implements ModuleUnloadCallback {
    @Override public void onUnload(String moduleId) {
        SharedStaticCache.evictByPrefix(moduleId);   // clean up out-of-context static resources
    }
}
```

## Support types injected into modules

Not SPIs, but two axes that consumers use from module code.

### ProteanTaskExecutor — managed executor

An executor a module injects instead of a raw `new Thread` to run async/scheduled work. On module unload, closing the child context automatically calls `shutdownNow()` so threads and jobs are cleaned up (preventing thread leaks that pin a dead ClassLoader). It is per-module, lazy (created on injection), and bounded (fixed pool); threads are daemon and labeled `protean-mod-<moduleId>-N`.

```java
@Component
public class PollingComponent {
    public PollingComponent(ProteanTaskExecutor exec) {   // inject by type
        exec.scheduleAtFixedRate(this::poll, 0, 30, TimeUnit.SECONDS);
    }
    // execute(Runnable) / submit(Runnable|Callable) / schedule(...) / raw() / isShutdown()
    private void poll() { /* ... */ }
}
```

Related config: `protean.module.executor.pool-size` (per-module pool size, default `2`).

### ModuleDescriptor.bridgedInterfaces — RPC bridge interface declaration

In `worker` mode, the list of interface FQCNs the module will call the main process's shared beans over RPC. Declared in the descriptor, the worker-side `WorkerBridgeRegistrar` registers a dynamic proxy bean for each of those interfaces in the worker root context, and the module injects that type normally (calls are forwarded to the main process).

```java
new ModuleDescriptor(
    "orders", "1.0.0",
    ModuleDescriptor.TrustTier.TRUSTED,
    ModuleDescriptor.DesiredState.ACTIVE,
    "com.acme.OrdersController",
    List.of("com.acme.OrdersController"),
    sources, tests,
    /* needsSharedBeans   */ true,
    /* verification       */ null,
    /* isolationMode      */ "worker",
    /* bridgedInterfaces  */ List.of("com.acme.api.InventoryPort"));
```

Prerequisite: `protean.worker.rpc-bridge=true` (declaring `bridgedInterfaces` while it is disabled makes the deploy fail-fast). For bridge behavior, transactions, and exception propagation see [05. Isolation Modes](05-isolation-modes.md).

## Related documents

- [02. Module Authoring](02-module-authoring.md)
- [05. Isolation Modes](05-isolation-modes.md)
- [06. Promotion Gates](06-promotion-gates.md)
- [07. Data Access](07-data-access.md)
- [08. MCP Integration](08-mcp-integration.md)
- [12. Security](12-security.md)
- [README](../../README.md)
