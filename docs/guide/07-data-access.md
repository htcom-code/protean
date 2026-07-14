**English** | [한국어](07-data-access.ko.md)

# 07. Data Access

Protean does not pick a data-access engine. It provides **mechanism only** — driver loading, resource serving, isolated DB provisioning — and leaves the **policy** — which ORM to use, how to size the pool — to the **consumer (module author)** via their own `@Configuration`. This document covers how to actually use that mechanism.

## Principle: drivers/ORM are host-bundled, the module is source-only

A module is deployed only as **Java source** (+ non-Java resources) that is compiled at runtime. It cannot ship jar dependencies alongside it. So libraries such as JDBC drivers, MyBatis, and Hibernate **must be on the classpath of the host app (consumer app)**, and the module `ModuleClassLoader` resolves those types **parent-first** (the module CL defines only its own compiled `*.class` files and resources; shared types are delegated to the parent — `ModuleClassLoader`).

In other words, for a module to use `com.zaxxer.hikari.HikariDataSource` or `org.apache.ibatis.session.SqlSessionFactory`, that dependency must be present in the consumer app's `build.gradle`.

```groovy
dependencies {
    implementation 'org.htcom:protean:<version>'

    // The host bundles the data-access stack the module will use.
    runtimeOnly 'com.mysql:mysql-connector-j'      // or org.postgresql:postgresql
    implementation 'org.mybatis:mybatis:3.5.16'    // if using MyBatis
    // JdbcTemplate/DataSource are included via spring-boot-starter-jdbc (Protean already depends on it)
}
```

### Protean's bundled mysql/postgres drivers are optional in the published POM

Protean itself carries `mysql-connector-j` and `postgresql` as `runtimeOnly` for its own `bootJar`/tests, but marks both as **`<optional>true</optional>` in the published POM** (`publishing.pom.withXml` in `build.gradle`). So they are not transitive to consumers. To use worker DB provisioning (section 6 below) or module DB access, the **consumer must explicitly add their own driver**. The vendor `DbDialect` classes (`MySqlDialect`/`PostgresDialect`) remain in the core so they load even without a driver (driver-decoupled).

## Library drop-in without rebuilding the app: `shared-lib-dir`

To add drivers/libraries without rebuilding the host, use `protean.module.shared-lib-dir`. The `*.jar` files in the given directory build an **app-lifetime `URLClassLoader`** (parent = platform CL) that:

- is inserted as the **parent** of the module `ModuleClassLoader` (resolution order: `module → sharedLib → app`) — runtime resolution
- adds those jars to the module compile classpath as well — compile resolution

```yaml
protean:
  module:
    shared-lib-dir: /opt/protean/libs   # drop *.jar into this directory
```

This CL **stays alive for the whole app lifetime** and is not recreated on each deploy — so there is no leak where a JDBC driver registered with `DriverManager` pins a dead CL. Leave it empty for off (module parent = platform CL).

Two cautions:

- **Classes the platform itself uses must not go in shared-lib.** The app CL cannot see this child CL. In particular, the admin-connection driver for worker DB provisioning (section 6) must be on the platform (app) classpath.
- The target is in-process mode. If a worker-mode setup shares the same host FS, the worker process reads the same jars from its own `shared-lib-dir` (`--protean.module.shared-lib-dir` is passed through).

## Live jar updates without a restart: the shared-lib store

`shared-lib-dir` above is a **static, boot-time** seed — its jars are fixed for the app's lifetime. To add or replace a native jar **at runtime**, use the shared-lib **store**: jars uploaded via `POST /platform/shared-libs` (or the `protean.deploy_shared_lib` MCP tool) are persisted under `protean.module.shared-lib-store-dir` (empty = `${java.io.tmpdir}/protean-shared-libs`) and layered on top of the seed. The active jar set of a **generation** is `seed ∪ store`, and the store survives restarts (persisted uploads are republished as the current generation before ACTIVE modules bind).

Each store-changing deploy or remove publishes a **new generation**. A deploy is idempotent on `name+version+sha256` (a matching jar is a no-op; a bundle whose every jar is a no-op publishes no new generation), and the same `name+version` with different bytes is rejected as a coordinate conflict. A remove affects **future generations only** — generations still in use keep the jar.

### Precise invalidation

Publishing a new generation does not blindly rebuild every module. A jar→module reverse index (`SharedLibUsageIndex`, keyed by `{name, sha256}` so different content under the same file name is never conflated) records which ACTIVE modules' compiles actually opened each jar. On a generation change, `SharedLibInvalidator` diffs the previous and current generation by jar name/sha, then rebinds **only** the modules that reference a changed or removed jar — a pure addition affects nobody, and unaffected modules are left untouched:

- **Plan A** — the module is rebound onto the new generation.
- **Plan B** — if a rebind fails, the module stays *sticky* on its prior generation, logged loudly; it is never silently deactivated (that would break zero-downtime), and the old generation is kept alive as long as a sticky module holds it.

This is governed by `protean.module.eager-shared-lib-invalidation` (default `true`; see [03. Configuration](03-configuration.md)). With it off, modules stay on their bound generation until they are next redeployed.

This native-jar mechanism is the mirror image of the `LIBRARY`-module **Live propagation** in [02. Module Authoring §8](02-module-authoring.md#8-library-modules-shared-module-typed-sharing): there the trigger is "which dependents `use` this library"; here it is "which modules' compile opened this jar" (the reverse index). The two are distinct mechanisms — the native-jar side is tracked by `usedSharedLibs`.

In worker/container modes the same event drives `WorkerSharedLibPropagator`, which pushes the full store bundle plus the changed-jar names to each worker (via `POST /__admin/shared-libs`); each worker merges them over its own seed, republishes its own generation, and computes its own precise rebind set locally.

## Resource channel: shipping non-Java files such as mapper XML and SQL

Files that are not compilation targets — mapper XML, `persistence.xml`, migration SQL, `.properties`, keystore — are shipped to the module over the **resource channel**. `ModuleDescriptor.resources` is a `classpath path → ModuleResource` map, and each `ModuleResource` is either plain text or base64 binary.

```java
Map<String, ModuleResource> resources = Map.of(
        "mapper/GreetingMapper.xml", ModuleResource.text("""
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="greeting">
                    <insert id="ins">INSERT INTO greet(id) VALUES (#{id})</insert>
                    <select id="count" resultType="int">SELECT COUNT(*) FROM greet</select>
                </mapper>
                """),
        "keystore/module.p12", ModuleResource.binary(keystoreBytes)  // binaries are stored as base64
);
```

The module `ModuleClassLoader` serves these resources **owned-child-first**. That is, module code reads them via standard classpath lookup.

```java
getClass().getClassLoader().getResourceAsStream("mapper/GreetingMapper.xml");
```

The full serving path including `*.class` is indexed in the loader (`resourceIndex()`), so Spring's `classpath*:` pattern scan (e.g. `classpath*:mapper/*.xml`) also enumerates the in-memory resources.

### Path normalization rules (`ResourcePaths`)

Resource paths must be relative to the classpath root. Normalization/validation rules (`ResourcePaths.normalize`):

- backslash `\` → slash `/`, strip leading slash (`/mapper/x.xml` → `mapper/x.xml`)
- reject scheme/drive (containing `:`)
- reject parent escape (`..` segments) — blocks path traversal
- reject null/blank

Resource content is **included** in module signature normalization — tampering with a resource fails signature verification.

### Declaring resources from other input paths

- **MCP `files[]`**: giving a file entry `kind: "resource"` treats it as a resource, not source. The `filename` is the classpath path directly (no FQCN derivation), and `base64: true` marks a binary.

  ```json
  { "filename": "mapper/GreetingMapper.xml", "content": "<mapper .../>", "kind": "resource" }
  ```

- **`module.yaml` manifest**: use an inline `resources:` (path → plain text) map, or `resourceDir:` (directory scan → all files under it as binary resources).

  ```yaml
  resources:
    mapper/GreetingMapper.xml: |
      <mapper namespace="greeting"> ... </mapper>
  # or a whole directory:
  resourceDir: src/main/resources
  ```

## Configuring the data-access stack inside a module

A module defines its `DataSource`/`JdbcTemplate`/`SqlSessionFactory` etc. via `@Configuration` inside its own child context. The parent of the child context is the host root context, so if needed it can also inject beans the host exposes (a shared `DataSource`, etc.) via parent-first bean resolution.

### MyBatis (per-module `SqlSessionFactory`)

Ship the mapper XML over the resource channel, and the module builds its own `SqlSessionFactory` to parse that XML (the `MyBatisModuleTest` pattern). Here is an example injecting a `DataSource` provided by the host.

```java
@Configuration
public class MyBatisConfig {
    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
        org.apache.ibatis.session.Configuration cfg = new org.apache.ibatis.session.Configuration();
        cfg.setEnvironment(new Environment("mod", new JdbcTransactionFactory(), ds));
        String res = "mapper/GreetingMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(res)) {
            new XMLMapperBuilder(in, cfg, res, cfg.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }
}
```

### Multiple DataSources + `@Qualifier`

A module defines several `DataSource`s and a `JdbcTemplate` for each via its own `@Configuration` to reach mutually independent DBs (the `MultiDataSourceModuleTest` pattern). The platform provides mechanism only — the module defines the pools, and the platform handles lifecycle cleanup.

```java
@Configuration
public class MultiConfig {
    private static DataSource h2(String name) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:h2:mem:" + name);
        ds.setUsername("sa");
        return ds;
    }
    @Bean("dsA") public DataSource dsA() { return h2("mds-a"); }
    @Bean("dsB") public DataSource dsB() { return h2("mds-b"); }
    @Bean("jtA") public JdbcTemplate jtA(@Qualifier("dsA") DataSource ds) { return new JdbcTemplate(ds); }
    @Bean("jtB") public JdbcTemplate jtB(@Qualifier("dsB") DataSource ds) { return new JdbcTemplate(ds); }
}
```

The `HikariDataSource` pools the module defines are `AutoCloseable`, so on unload they are **all closed automatically** when the child context closes. Pool sizing and routing policy are the consumer's responsibility.

## Transaction participation rules

Whether a module transaction participates in the host transaction depends on the isolation mode and `DataSource` configuration.

| Situation | Result |
| --- | --- |
| **in-process** + a **shared `DataSource`** exposed by the host + host `PlatformTransactionManager` injected | Participates in the host tx (`REQUIRED`) — same connection, same tx boundary |
| **in-process** + the module's **own `DataSource`** (the multi-DataSource example above) | Isolated — an independent tx unrelated to the host tx |
| **worker / container** mode | Separate JVM/process, so **always isolated** — no way to share the host tx |

To bind transactionally with host logic in worker/container mode, you must call host shared beans over the **RPC bridge** rather than sharing an in-memory tx (each side has its own tx). For isolation modes themselves see [05. Isolation Modes](05-isolation-modes.md); for the bridge see the related docs.

## worker-only automatic DB provisioning

In worker mode, an **isolated dedicated DB scope** can be auto-created per module. When on, `DbScopeProvisioner` uses an admin connection to create, per module, a dedicated DB/schema + a dedicated user/role + a `GRANT` scoped to its own area, and injects that scope's connection info (url/username/password) into the worker process as `spring.datasource.*`. The worker connects only with those credentials, so it cannot see other modules' DBs.

```yaml
protean:
  worker:
    db:
      auto-provision: true
      dialect: mysql            # mysql | postgresql (postgres is an alias for postgresql)
      admin-url: jdbc:mysql://localhost:3306/
      admin-username: root
      admin-password: ${DB_ADMIN_PW}
      deprovision-on-undeploy: false   # preserved by default; true drops the scope on undeploy
```

Behavior details:

- Per-vendor isolation method: **MySQL** = a dedicated `DATABASE` + dedicated `USER` + a `GRANT` scoped to that DB, per module. **PostgreSQL** = a dedicated `SCHEMA` inside the same DB + dedicated `ROLE` + a `GRANT` scoped to that schema (+ a fixed `search_path`).
- With `auto-provision`, a dedicated DB per module → **a dedicated worker per module** (capacity=1, no warm reuse).
- The module id is sanitized into a DDL identifier (`[a-z0-9_]` whitelist, must start with a letter, hash-truncated if it exceeds the vendor max length). DDL identifiers cannot be passed as bind parameters and are inlined as strings, so this sanitization is essential to prevent injection.
- The admin-connection driver (mysql/postgres) must be on the **host (app) classpath** (see the optional caution above). It must not go in the shared-lib CL.
- The scope user's password is generated as a 24-character random string (`SecureRandom`).

### Vendor extension is a `DbDialect` bean

Only `mysql` and `postgresql` are built in, but by registering a `DbDialect` bean without forking the library source you can add arbitrary vendors such as Oracle, SQL Server, or MariaDB. Returning the same `DbDialect.id()` overrides the built-in dialect. For implementation and registration see [10. SPI Extension](10-spi-extension.md).

## Preventing resource leaks: managed executor and unload hooks

When a module holds resources such as threads, pools, or external clients, they leak by pinning a dead `ClassLoader` unless cleaned up on unload. Two mechanisms prevent this.

### `ProteanTaskExecutor` (managed executor)

Instead of raw `new Thread`, inject this to run async/scheduled tasks. It is per-module, lazy (created only when injected; 0 threads if unused), and bounded (fixed pool, daemon threads), and being `AutoCloseable` it is automatically `shutdownNow()`ed on unload when the child context closes.

```java
@Service
public class PollingService {
    public PollingService(ProteanTaskExecutor executor) {
        executor.scheduleAtFixedRate(this::poll, 0, 10, TimeUnit.SECONDS);
    }
    private void poll() { /* ... */ }
}
```

Pool size is set via `protean.module.executor.pool-size` (default `2`).

### `ModuleUnloadCallback` (unload hook)

This is the place to clean up **out-of-context resources** that closing the child context can't reach (a `ThreadLocal` set on a shared/pool thread, a static cache registration, a JMX MBean, a custom client, etc.). If the module (child context) or the consumer (root context) provides a bean of this type, the platform calls it **just before** closing the child context. Callback exceptions are swallowed and logged (one callback's failure does not block other cleanups or the unload).

```java
@Component
public class DrainOnUnload implements ModuleUnloadCallback {
    @Override
    public void onUnload(String moduleId) {
        // clean up out-of-context resources: static caches, ThreadLocals, MBeans, etc.
    }
}
```

In-context `AutoCloseable` beans such as `DataSource` pools are cleaned up by child.close() even without this hook, so the hook only needs to handle what the context can't reach.

## A resource-only update skips recompilation

When updating a module, **if the source is identical to the previous one and only resources changed**, `RuntimeCompiler` reuses the cached bytecode and skips `javac` (the resource-only fast-path). A deploy that only touched mapper XML/SQL pays no compile cost while keeping the zero-downtime swap.

For an even lighter path, resources re-read per request (mapper XML, etc.) can be swapped in place via `ModuleClassLoader.replaceResources` **without recompilation or context rebuild** — a live-reload (in-process mode only). It is invoked via MCP `reload-resources` / the admin REST, and allows only `kind: "resource"` files. Note that an in-place swap is not reflected in **resources parsed once at init** (ORM bootstrap, etc.), so those cases must use a full update. worker/container modes do not support live-reload and automatically fall back to a full update.

## Out of scope (consumer policy)

Protean does not decide the following — the module/consumer decides in its own code: connection pool sizing, sharding / read-write routing, multi-tenancy, XA / distributed transactions, ORM choice (JPA/MyBatis/JOOQ/raw JDBC).

## Related docs

- [02. Module Authoring](02-module-authoring.md)
- [03. Configuration Reference](03-configuration.md)
- [05. Isolation Modes](05-isolation-modes.md)
- [08. MCP Integration](08-mcp-integration.md)
- [10. SPI Extension](10-spi-extension.md)
- [11. Operations](11-operations.md)
- [README](../../README.md)
