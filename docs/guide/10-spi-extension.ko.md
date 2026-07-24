[English](10-spi-extension.md) | **한국어**

# 10. SPI 확장

Protean 은 라이브러리다 — 소비자는 소스를 포크하지 않고 **Spring 빈을 등록**해 동작을 확장한다. 각 확장축은 인터페이스 + 기본 구현(batteries-included)으로 오고, 소비자 빈을 두면 자동 수집되거나 기본을 대체한다. 아래 각 SPI마다 (a) 시그니처 (b) 빈 등록 방식 (c) 예제 (d) 관련 설정키를 정리한다.

## CodeRule — 승격 게이트 ② 코드 룰

컴파일된 클래스 바이트코드를 정적 검사하는 룰. 내장 룰(`ForbiddenApiRule` 등)은 항상 동작하고, 추가 룰은 `RuleSystem` 이 **모든 `CodeRule` 빈을 자동 수집**해 적용한다.

```java
package org.htcom.protean.gate.rules;

public interface CodeRule {
    String name();
    /** 한 클래스를 검사해 위반 메시지 목록 반환(빈 목록 = 통과). */
    List<String> check(String className, byte[] bytecode);
}
```

빈 등록: `@Component` 또는 `@Bean` 으로 `CodeRule` 을 노출하면 `RuleSystem(List<CodeRule>)` 이 주입받아 자동 강제한다.

```java
@Component
public class NoReflectionRule implements CodeRule {
    @Override public String name() { return "no-reflection"; }

    @Override public List<String> check(String className, byte[] bytecode) {
        // ASM 등으로 bytecode 분석
        return containsReflection(bytecode)
            ? List.of(className + ": java.lang.reflect 사용 금지")
            : List.of();
    }
}
```

관련 설정: `protean.gate.review-enabled`(기본 `true`, `false` 면 게이트 ② 코드 체크 생략).

## DbDialect — DB 프로비저닝 벤더

모듈당 격리 DB 스코프를 만드는 벤더별 전략. 내장 `MySqlDialect`/`PostgresDialect` 를 기본 제공하되, 소비자가 `DbDialect` 빈을 등록하면 registry 에 합류한다(같은 `id()` 면 내장을 덮어씀 — Oracle/SQL Server/MariaDB 등 추가 가능).

```java
package org.htcom.protean.db;

public interface DbDialect {
    String id();
    /** 식별자 최대 길이(MySQL 유저 32, Postgres 63 등). */
    int maxNameLength();
    /** 격리 스코프 생성: 전용 DB/스키마 + 전용 유저/롤 + 한정 GRANT. */
    void createScope(JdbcTemplate admin, String name, String password);
    /** scope 완전 제거 — DB/스키마와 로그인을 함께(destroy 의미). */
    void dropScope(JdbcTemplate admin, String name);
    /** 관리자 URL 로부터 그 scope 접속 JDBC URL 생성. */
    String scopedUrl(String adminUrl, String name);

    /**
     * scope detach: 로그인만 제거(DB/스키마·데이터는 보존). 가역 — 이후 createScope 가 로그인을 재활성화.
     * default 는 throw — detach 미구현 커스텀 dialect 가 데이터를 조용히 파괴하지 않도록. 내장 MySQL=DROP USER,
     * PostgreSQL=ALTER ROLE … NOLOGIN.
     */
    default void detachScope(JdbcTemplate admin, String name) { throw new UnsupportedOperationException(); }

    /**
     * scope destroy: DROP DATABASE/SCHEMA CASCADE + 로그인 — 비가역, 데이터 전부 소실. default 는 dropScope 위임
     * (레거시 dialect 의 완전 드롭이 곧 destroy). 내장은 둘 다 override.
     */
    default void destroyScope(JdbcTemplate admin, String name) { dropScope(admin, name); }
}
```

`detachScope`/`destroyScope` 는 scope 관리 라이프사이클([11. 운영](11-operations.ko.md))을 뒷받침한다: `detach` 는 데이터 보존(가역), `destroy` 는 가드된 비가역 드롭. 둘 다 default 메서드라 기존 세 메서드만 구현한 dialect 도 컴파일된다 — 다만 데이터 안전 detach 를 제공하려면 `detachScope` 를 override 해야 한다(default 는 destroy 로 흘리지 않고 거부).

빈 등록: `DbDialect` 빈을 노출하면 `DbProvisioningConfig` 가 `List<DbDialect>` 로 수집해 `id()` 키 registry 에 넣는다. `protean.worker.db.dialect` 값과 `id()` 가 일치하는 dialect 가 선택된다.

```java
@Bean
DbDialect mariaDbDialect() {
    return new DbDialect() {
        @Override public String id() { return "mariadb"; }
        @Override public int maxNameLength() { return 80; }
        @Override public void createScope(JdbcTemplate admin, String name, String password) {
            // DDL 식별자는 바인드 파라미터 불가 → 반드시 새니타이즈된 name 을 받는다(Identifiers.safeName).
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

> 주의: DDL 식별자(DB/스키마/유저명)는 바인드 파라미터로 넣을 수 없어 문자열로 박힌다. 호출 전 이미 새니타이즈된 이름이 넘어오지만, 커스텀 구현도 이 계약을 지켜 인젝션을 막아야 한다.

관련 설정(`auto-provision=true` 일 때만 프로비저너 활성):

```yaml
protean:
  worker:
    db:
      auto-provision: true
      dialect: mariadb          # DbDialect.id() 와 일치
      admin-url: jdbc:mariadb://db:3306/
      admin-username: root
      admin-password: ${DB_ADMIN_PW}
      deprovision-on-undeploy: false
```

## ModuleStoreDialect — module-store DDL 벤더

JDBC `ModuleStore` 백엔드(`protean.module-store.backend=jdbc`)의 벤더별 DDL 전략. **위 `DbDialect` 와 다른 축**이다: `DbDialect` 는 모듈당 격리 DB 스코프를 프로비저닝하고, 이것은 module-store 자체 스키마를 만든다. 내장 `h2`/`mysql`/`postgresql` 를 제공하며, `ModuleStoreDialect` 빈을 등록하면 벤더를 추가(예: Oracle)하거나 같은 `id()` 로 내장을 덮어쓴다.

store 가 테이블/컬럼 이름과 모든 CRUD SQL 을 소유하고, dialect 는 벤더별로 달라지는 두 조각만 제공한다 — `descriptor_json` 의 대용량 문자 타입과 `module_version.seq` 의 auto-increment 정의 — 그리고 선택적 pre/post-table DDL(sequence·trigger). `descriptor_json` 은 디스크립터 전체(모듈 소스 포함)를 JSON 으로 담으므로 `jsonTextColumnType()` 은 반드시 unbounded 대용량 문자 타입이어야 한다. bounded `VARCHAR` 는 기동 self-check 가 거부한다.

```java
package org.htcom.protean.module;

public interface ModuleStoreDialect {
    String id();
    /** descriptor_json 의 대용량 문자 타입 (H2 "CLOB", MySQL "LONGTEXT", PostgreSQL "TEXT"). */
    String jsonTextColumnType();
    /** auto-increment module_version.seq 기본키의 전체 컬럼 정의. */
    String autoIncrementColumnDefinition();
    /** 테이블 생성 전 DDL(예: sequence). 멱등, 기본 빈 값. */
    default List<String> preTableDdl() { return List.of(); }
    /** 테이블 생성 후 DDL(예: trigger·index). 멱등, 기본 빈 값. */
    default List<String> postTableDdl() { return List.of(); }
}
```

빈 등록: `ModuleStoreDialect` 빈을 노출하면 `id()` 키 registry 에 합류한다(같은 `id()` 면 내장 오버라이드). 활성 dialect 는 `protean.module-store.dialect`(override)로, 비어 있으면 데이터베이스 product name 감지로 선택한다. 미지원 벤더는 기동 시 fail-fast.

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

내장의 한 조각만 바꾸려면 상속한다(내장은 non-final):

```java
@Bean
ModuleStoreDialect mysqlJsonColumn() {
    return new MySqlStoreDialect() {
        @Override public String jsonTextColumnType() { return "JSON"; }  // 여전히 대용량 → self-check 통과
    };
}
```

> 테이블/컬럼 이름(`module`·`module_version`·`seq`·`descriptor_json`·`id`·`version`·`desired_state`·`module_id`·`saved_at`)은 안정 계약이라 `preTableDdl`/`postTableDdl` 이 참조해도 된다. 스키마 init 이 매 부팅 재실행되므로 이 DDL 은 멱등이어야 한다.

관련 설정:

```yaml
protean:
  module-store:
    backend: jdbc
    dialect: oracle            # ModuleStoreDialect.id() 와 일치; 빈 값 = 자동 감지
```

## ModuleActionAuthorizer — MCP 인가

MCP 툴 호출의 공통 choke point. Protean 은 인증을 구현하지 않고(소비자의 Spring Security 에 위임) "누가 무엇을 할 수 있나"의 **정책**만 이 빈으로 꽂는다. 기본 구현(`PermissiveModuleActionAuthorizer`)은 전부 allow 라 기존 무인증 REST admin 과 동일 태세다.

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

빈 등록: 기본은 `@ConditionalOnMissingBean(ModuleActionAuthorizer.class)` 로 등록되므로, 소비자가 자기 `ModuleActionAuthorizer` 빈을 하나 두면 기본이 물러난다.

```java
@Bean
ModuleActionAuthorizer authorizer() {
    return (caller, action, moduleId) -> {
        if (caller == null) return ModuleActionAuthorizer.Decision.deny("인증 필요");
        // 파괴적 동작은 관리자에게만
        boolean destructive = switch (action) {
            case DEPLOY, UPDATE, DELETE, APPROVE, DEBUG -> true;
            default -> false;
        };
        if (destructive && !isAdmin(caller))
            return ModuleActionAuthorizer.Decision.deny("권한 없음: " + action);
        return ModuleActionAuthorizer.Decision.allow();
    };
}
```

관련 설정: `protean.mcp.enabled`(MCP surface, 기본 off), `protean.mcp.debug.enabled`(`DEBUG` 툴). 자세한 내용은 [08. MCP 연동](08-mcp-integration.ko.md), [12. 보안](12-security.ko.md).

## WorkerRuntimeProvider — 워커 런타임 제공자

"워커 JVM/컨테이너를 무엇으로 어떻게 띄우나"를 격리 전략에서 분리한 SPI. 내장은 `protean.worker.runtime` 으로 고르는 `embed`(기본)·`sidecar` 둘이다.

```java
package org.htcom.protean.isolation;

public interface WorkerRuntimeProvider {
    /** process track: 워커 JVM 실행 명령 접두부 [javaBin, -cp, classpath, mainClass]. */
    List<String> processLaunchPrefix();
    /** container track: 이미지 + docker run 마운트 인자 + 컨테이너 내부 실행 접두부. */
    ContainerLaunchSpec containerLaunchSpec();

    record ContainerLaunchSpec(String image, List<String> mountArgs, List<String> commandPrefix) {}
}
```

공통 `--spring.*` 인자(profile/isolation.mode/server.port/datasource)는 격리 전략이 뒤에 덧붙이므로, 이 SPI 는 그 앞의 "JVM/컨테이너 기동" 부분만 책임진다.

빈 등록: 내장 두 구현은 각각 `protean.worker.runtime=embed`(미설정 시 기본)·`=sidecar` 조건으로만 활성화된다. 커스텀 제공자를 꽂으려면 `WorkerRuntimeProvider` 빈을 등록하고 `protean.worker.runtime` 을 `embed`/`sidecar` 가 **아닌** 값으로 두어 내장 조건이 모두 비활성이 되게 한다(빈 충돌 방지).

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

관련 설정: `protean.worker.runtime`, `protean.worker.sidecar.jar`/`.image`/`.shared-api`. 트랙별 상세는 [05. 격리 모드](05-isolation-modes.ko.md).

## ModuleUnloadCallback — 언로드 정리 훅

모듈 child 컨텍스트를 close 하기 **직전** 에 호출되는 훅. child.close() 가 못 닿는 컨텍스트 밖 자원(공유/풀 스레드의 ThreadLocal, static 캐시 등록, JMX MBean, 커스텀 클라이언트 등) 정리용이다.

```java
package org.htcom.protean.module;

public interface ModuleUnloadCallback {
    void onUnload(String moduleId);
}
```

빈 등록: 모듈 child 컨텍스트에 두거나(모듈 자기 정리) 소비자 루트 컨텍스트에 둘 수 있다 — 플랫폼이 `getBeansOfType(ModuleUnloadCallback.class)`(부모 컨텍스트 포함)로 모아 호출한다. 예외는 삼켜지고 로그된다(한 콜백 실패가 다른 정리·언로드를 막지 않는다).

```java
@Component   // 모듈 소스 안 또는 소비자 앱 안
public class CacheEvictCallback implements ModuleUnloadCallback {
    @Override public void onUnload(String moduleId) {
        SharedStaticCache.evictByPrefix(moduleId);   // 컨텍스트 밖 static 자원 정리
    }
}
```

## 모듈에 주입되는 지원 타입

SPI 는 아니지만 소비자가 모듈 코드에서 활용하는 두 축이다.

### ProteanTaskExecutor — 관리형 실행기

모듈이 raw `new Thread` 대신 주입받아 async/scheduled 작업을 돌리는 실행기. 모듈 언로드 시 child 컨텍스트 close 로 자동 `shutdownNow()` 되어 스레드·잡이 정리된다(죽은 ClassLoader 를 무는 스레드 누수 방지). per-module·lazy(주입 시 생성)·bounded(고정 풀), 스레드는 데몬 + `protean-mod-<moduleId>-N` 이름표.

```java
@Component
public class PollingComponent {
    public PollingComponent(ProteanTaskExecutor exec) {   // 타입으로 주입
        exec.scheduleAtFixedRate(this::poll, 0, 30, TimeUnit.SECONDS);
    }
    // execute(Runnable) / submit(Runnable|Callable) / schedule(...) / raw() / isShutdown()
    private void poll() { /* ... */ }
}
```

관련 설정: `protean.module.executor.pool-size`(per-module 풀 크기, 기본 `2`).

### ModuleDescriptor.bridgedInterfaces — RPC 브리지 인터페이스 선언

`worker` 모드에서 모듈이 메인 공유 빈을 RPC 로 호출할 인터페이스 FQCN 목록. 디스크립터에 선언하면 워커 측 `WorkerBridgeRegistrar` 가 그 인터페이스마다 동적 프록시 빈을 워커 루트 컨텍스트에 등록하고, 모듈은 그 타입을 평범하게 주입받는다(호출은 메인으로 포워딩).

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

전제: `protean.worker.rpc-bridge=true`(미활성 상태에서 `bridgedInterfaces` 를 선언하면 배포가 fail-fast). 브리지 동작·트랜잭션·예외 전파는 [05. 격리 모드](05-isolation-modes.ko.md).

## 관련 문서

- [02. 모듈 작성](02-module-authoring.ko.md)
- [05. 격리 모드](05-isolation-modes.ko.md)
- [06. 승격 게이트](06-promotion-gates.ko.md)
- [07. 데이터 접근](07-data-access.ko.md)
- [08. MCP 연동](08-mcp-integration.ko.md)
- [12. 보안](12-security.ko.md)
- [README](../../README.ko.md)
