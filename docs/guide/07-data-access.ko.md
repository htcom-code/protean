[English](07-data-access.md) | **한국어**

# 07. 데이터 접근

Protean 은 데이터 접근 엔진을 고르지 않는다. 드라이버 로딩·리소스 서빙·격리 DB 프로비저닝 같은 **메커니즘만** 제공하고, 어떤 ORM 을 쓸지·풀을 어떻게 잡을지 같은 **정책은 소비자(모듈 저자)** 가 자기 `@Configuration` 으로 결정한다. 이 문서는 그 메커니즘을 실제로 쓰는 법을 다룬다.

## 원칙: 드라이버·ORM 은 호스트 번들, 모듈은 소스-온리

모듈은 런타임 컴파일되는 **Java 소스**(+ 비-Java 리소스)로만 배포된다. jar 의존성을 함께 실어 보낼 수 없다. 따라서 JDBC 드라이버·MyBatis·Hibernate 같은 라이브러리는 **호스트 앱(소비자 앱)의 클래스패스에 있어야** 하고, 모듈 `ModuleClassLoader` 는 그 타입을 **parent-first** 로 위임 해소한다(모듈 CL 은 컴파일된 `*.class` 와 리소스만 자기가 정의, 공유 타입은 부모에게 위임 — `ModuleClassLoader`).

즉 모듈이 `com.zaxxer.hikari.HikariDataSource` 나 `org.apache.ibatis.session.SqlSessionFactory` 를 쓰려면, 소비자 앱 `build.gradle` 에 그 의존성이 있어야 한다.

```groovy
dependencies {
    implementation 'org.htcom:protean:<version>'

    // 모듈이 쓸 데이터 접근 스택은 호스트가 번들한다.
    runtimeOnly 'com.mysql:mysql-connector-j'      // 또는 org.postgresql:postgresql
    implementation 'org.mybatis:mybatis:3.5.16'    // MyBatis 를 쓸 경우
    // JdbcTemplate·DataSource 는 spring-boot-starter-jdbc(Protean 이 이미 의존)에 포함
}
```

### Protean 이 번들한 mysql/postgres 드라이버는 발행 POM 에서 optional

Protean 자신은 자기 `bootJar`·테스트를 위해 `mysql-connector-j`·`postgresql` 을 `runtimeOnly` 로 갖지만, **발행 POM 에서 이 둘을 `<optional>true</optional>`** 로 표시한다(`build.gradle` 의 `publishing.pom.withXml`). 그래서 소비자에게 전이(transitive)되지 않는다. worker DB 프로비저닝(아래 6절)이나 모듈 DB 접근을 쓰려면 **소비자가 자기 드라이버를 명시적으로 추가**해야 한다. 벤더 `DbDialect` 클래스(`MySqlDialect`·`PostgresDialect`)는 드라이버 없이도 로드되도록 코어에 남아 있다(드라이버 무결합).

## 앱 재빌드 없이 라이브러리 드롭인: `shared-lib-dir`

호스트를 재빌드하지 않고 드라이버·라이브러리를 추가하고 싶으면 `protean.module.shared-lib-dir` 를 쓴다. 지정한 디렉터리의 `*.jar` 로 **앱-수명 `URLClassLoader`**(부모 = 플랫폼 CL)를 만들어

- 모듈 `ModuleClassLoader` 의 **부모**로 삽입한다(해소 순서: `module → sharedLib → app`) — 런타임 해소
- 모듈 컴파일 클래스패스에도 그 jar 들을 더한다 — 컴파일 해소

```yaml
protean:
  module:
    shared-lib-dir: /opt/protean/libs   # 이 디렉터리의 *.jar 를 드롭인
```

이 CL 은 **앱 수명 내내 살아 있어** 매 배포마다 새로 만들지 않는다 — 그래서 `DriverManager` 에 등록된 JDBC 드라이버가 죽은 CL 을 물고 누수되는 문제가 없다. 비워 두면 off(모듈 부모 = 플랫폼 CL).

주의 두 가지:

- **플랫폼 자신이 쓰는 클래스는 shared-lib 에 두면 안 된다.** 앱 CL 은 이 자식 CL 을 볼 수 없다. 특히 worker DB 프로비저닝(6절)의 admin 접속 드라이버는 플랫폼(앱) 클래스패스에 있어야 한다.
- 대상은 in-process 모드다. worker 모드도 같은 호스트 FS 를 공유하면 워커 프로세스가 자기 `shared-lib-dir` 로 같은 jar 를 읽는다(`--protean.module.shared-lib-dir` 전달).

## 재시작 없이 라이브 jar 갱신: shared-lib 스토어

위의 `shared-lib-dir` 는 **정적 부팅 시점** seed 로, jar 가 앱 수명 내내 고정이다. 네이티브 jar 를 **런타임에** 추가·교체하려면 shared-lib **스토어**를 쓴다: `POST /platform/shared-libs`(또는 `protean.deploy_shared_lib` MCP 툴)로 업로드한 jar 는 `protean.module.shared-lib-store-dir`(비우면 `${java.io.tmpdir}/protean-shared-libs`)에 영속화되어 seed 위에 얹힌다. 한 **generation** 의 활성 jar 집합은 `seed ∪ store` 이고, 스토어는 재시작을 견딘다(영속 업로드가 ACTIVE 모듈 바인딩 전에 현재 generation 으로 재발행됨).

스토어를 바꾸는 deploy/remove 는 매번 **새 generation** 을 발행한다. deploy 는 `name+version+sha256` 에 대해 멱등이고(일치하는 jar 는 no-op; 모든 jar 가 no-op 인 묶음은 새 generation 을 발행하지 않음), 같은 `name+version` 에 다른 바이트는 좌표 충돌로 거부된다. remove 는 **향후 generation 에만** 영향을 준다 — 사용 중인 generation 은 그 jar 를 유지한다.

### 정밀 무효화

새 generation 발행이 모든 모듈을 무작정 재빌드하지 않는다. jar→module 역인덱스(`SharedLibUsageIndex`, `{name, sha256}` 키라 같은 파일명의 다른 내용이 섞이지 않음)가 어느 ACTIVE 모듈의 컴파일이 실제로 각 jar 를 열었는지 기록한다. generation 변경 시 `SharedLibInvalidator` 가 이전·현재 generation 을 jar 이름/sha 로 diff 하고, 바뀌거나 제거된 jar 를 참조하는 모듈**만** rebind 한다 — 순수 추가는 아무에게도 영향이 없고, 영향 없는 모듈은 그대로 둔다:

- **Plan A** — 모듈을 새 generation 으로 rebind.
- **Plan B** — rebind 가 실패하면 그 모듈은 이전 generation 에 *sticky* 로 남고 크게 로깅된다; 조용히 비활성화하지 않으며(무중단 위반), sticky 모듈이 물고 있는 한 옛 generation 도 살려둔다.

이는 `protean.module.eager-shared-lib-invalidation`(기본 `true`; [03. 설정](03-configuration.ko.md) 참고)로 제어된다. 끄면 모듈은 다음 재배포 전까지 바인딩된 generation 에 머문다.

이 네이티브-jar 메커니즘은 [02. 모듈 작성 §8](02-module-authoring.ko.md)의 `LIBRARY` 모듈 **라이브 전파**의 거울상이다: 거기선 트리거가 "어느 의존 모듈이 이 라이브러리를 `use` 하나"이고, 여기선 "어느 모듈의 컴파일이 이 jar 를 열었나"(역인덱스)다. 둘은 별개 메커니즘이며 — 네이티브-jar 쪽은 `usedSharedLibs` 로 추적된다.

worker/container 모드에서는 같은 이벤트가 `WorkerSharedLibPropagator` 를 구동해, 전체 스토어 묶음과 바뀐 jar 이름을 각 워커에 push 한다(`POST /__admin/shared-libs`); 각 워커는 이를 자기 seed 위에 병합해 자기 generation 을 재발행하고, 자기 정밀 rebind 대상을 로컬에서 계산한다.

## 리소스 채널: mapper XML·SQL 등 비-Java 파일 싣기

mapper XML, `persistence.xml`, 마이그레이션 SQL, `.properties`, keystore 처럼 컴파일 대상이 아닌 파일은 **리소스 채널**로 모듈에 실어 보낸다. `ModuleDescriptor.resources` 는 `classpath 경로 → ModuleResource` 맵이고, 각 `ModuleResource` 는 평문 텍스트 또는 base64 바이너리다.

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
        "keystore/module.p12", ModuleResource.binary(keystoreBytes)  // 바이너리는 base64 로 보관
);
```

이 리소스는 모듈 `ModuleClassLoader` 가 **owned-child-first** 로 서빙한다. 즉 모듈 코드에서 표준 classpath 조회로 읽는다.

```java
getClass().getClassLoader().getResourceAsStream("mapper/GreetingMapper.xml");
```

`*.class` 까지 포함한 전체 서빙 경로가 로더에 색인되어(`resourceIndex()`), Spring 의 `classpath*:` 패턴 스캔(예: `classpath*:mapper/*.xml`)도 인메모리 리소스를 열거한다.

### 경로 정규화 규칙 (`ResourcePaths`)

리소스 경로는 classpath 루트 상대여야 한다. 정규화·검증 규칙(`ResourcePaths.normalize`):

- 백슬래시 `\` → 슬래시 `/`, 앞 슬래시 제거(`/mapper/x.xml` → `mapper/x.xml`)
- 스킴/드라이브(`:` 포함) 거부
- 상위 탈출(`..` 세그먼트) 거부 — path traversal 차단
- null/blank 거부

리소스 내용은 모듈 서명 정규화에 **포함**된다 — 리소스를 변조하면 서명 검증이 실패한다.

### 다른 입력 경로에서 리소스 선언

- **MCP `files[]`**: 파일 항목에 `kind: "resource"` 를 주면 소스가 아닌 리소스로 취급한다. `filename` 이 곧 classpath 경로(FQCN 도출 안 함), `base64: true` 로 바이너리 지정.

  ```json
  { "filename": "mapper/GreetingMapper.xml", "content": "<mapper .../>", "kind": "resource" }
  ```

- **`module.yaml` 매니페스트**: 인라인 `resources:`(경로 → 평문 텍스트) 맵, 또는 `resourceDir:`(디렉터리 스캔 → 하위 모든 파일을 바이너리 리소스로) 사용.

  ```yaml
  resources:
    mapper/GreetingMapper.xml: |
      <mapper namespace="greeting"> ... </mapper>
  # 또는 디렉터리 통째로:
  resourceDir: src/main/resources
  ```

## 모듈 안에서 데이터 접근 스택 구성하기

모듈은 자기 child 컨텍스트 안에서 `@Configuration` 으로 `DataSource`·`JdbcTemplate`·`SqlSessionFactory` 등을 정의한다. child 컨텍스트의 부모는 호스트 루트 컨텍스트이므로, 필요하면 호스트가 노출한 빈(공유 `DataSource` 등)을 주입받을 수도 있다(parent-first 빈 해소).

### MyBatis (per-module `SqlSessionFactory`)

리소스 채널로 mapper XML 을 싣고, 모듈이 자기 `SqlSessionFactory` 를 구성해 그 XML 을 파싱한다(`MyBatisModuleTest` 패턴). 여기선 호스트가 제공한 `DataSource` 를 주입받는 예다.

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

### 멀티 DataSource + `@Qualifier`

모듈이 여러 `DataSource` 와 각 `JdbcTemplate` 을 자기 `@Configuration` 으로 정의해 서로 독립된 DB 에 접근한다(`MultiDataSourceModuleTest` 패턴). 플랫폼은 메커니즘만 — 모듈이 풀을 정의하고, 플랫폼이 lifecycle 정리를 맡는다.

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

모듈이 정의한 `HikariDataSource` 풀은 `AutoCloseable` 이므로, 언로드 시 child 컨텍스트 close 로 **모두 자동 close** 된다. 풀 사이징·라우팅 정책은 소비자 몫이다.

## 트랜잭션 참여 규칙

모듈 트랜잭션이 호스트 트랜잭션에 참여하는지는 격리 모드와 `DataSource` 구성에 따라 갈린다.

| 상황 | 결과 |
| --- | --- |
| **in-process** + 호스트가 노출한 **공유 `DataSource`** + 호스트 `PlatformTransactionManager` 주입 | 호스트 tx 에 참여(`REQUIRED`) — 같은 커넥션·같은 tx 경계 |
| **in-process** + 모듈이 정의한 **자체 `DataSource`**(위 멀티 DataSource 예) | 격리 — 호스트 tx 와 무관한 독립 tx |
| **worker / container** 모드 | 별도 JVM/프로세스라 **항상 격리** — 호스트 tx 를 공유할 방법이 없음 |

worker/container 에서 호스트 로직과 트랜잭션적으로 엮이려면 인-메모리 tx 공유가 아니라 **RPC 브리지**로 호스트 공유 빈을 호출해야 한다(각 쪽이 자기 tx 를 가짐). 격리 모드 자체는 [05. 격리 모드](05-isolation-modes.ko.md), 브리지는 관련 문서 참조.

## worker 전용 DB 자동 프로비저닝

worker 모드에서 모듈마다 **격리된 전용 DB 스코프**를 자동 생성할 수 있다. 켜면 `DbScopeProvisioner` 가 admin 커넥션으로 모듈마다 전용 DB/스키마 + 전용 유저/롤 + 자기 영역으로 한정된 `GRANT` 를 만들고, 그 스코프 접속 정보(url/user/password)를 워커 프로세스에 `spring.datasource.*` 로 주입한다. 워커는 그 자격으로만 접속하므로 타 모듈 DB 를 볼 수 없다.

```yaml
protean:
  worker:
    db:
      auto-provision: true
      dialect: mysql            # mysql | postgresql (postgres 는 postgresql 별칭)
      admin-url: jdbc:mysql://localhost:3306/
      admin-username: root
      admin-password: ${DB_ADMIN_PW}
      deprovision-on-undeploy: false   # 기본 보존; true 면 undeploy 시 스코프 제거
```

동작 상세:

- 벤더별 격리 방식: **MySQL** = 모듈당 전용 `DATABASE` + 전용 `USER` + 그 DB 로 한정된 `GRANT`. **PostgreSQL** = 같은 DB 안의 전용 `SCHEMA` + 전용 `ROLE` + 그 스키마로 한정된 `GRANT`(+ `search_path` 고정).
- `auto-provision` 시 모듈당 전용 DB → **모듈당 전용 워커**(capacity=1, 워밍 재사용 금지).
- 모듈 id 는 DDL 식별자로 새니타이즈된다(`[a-z0-9_]` 화이트리스트, 글자로 시작, 벤더 최대 길이 초과 시 해시 축약). DDL 식별자는 바인드 파라미터로 넣을 수 없어 문자열로 박히므로 인젝션 방지에 이 새니타이즈가 필수다.
- admin 접속 드라이버(mysql/postgres)는 **호스트(앱) 클래스패스**에 있어야 한다(위 optional 주의 참조). shared-lib CL 에 두면 안 된다.
- 스코프 유저의 비밀번호는 24자 난수(`SecureRandom`)로 생성된다.

### 벤더 확장은 `DbDialect` 빈

내장은 `mysql`·`postgresql` 뿐이지만, 라이브러리 소스를 포크하지 않고 `DbDialect` 빈을 등록하면 Oracle·SQL Server·MariaDB 등 임의 벤더를 추가할 수 있다. 같은 `DbDialect.id()` 를 반환하면 내장 dialect 를 덮어쓴다. 구현·등록 방법은 [10. SPI 확장](10-spi-extension.ko.md) 참조.

## 자원 누수 방지: 관리형 실행기와 언로드 훅

모듈이 스레드·풀·외부 클라이언트 같은 자원을 잡으면, 언로드 시 정리하지 않는 한 죽은 `ClassLoader` 를 물고 누수된다. 두 장치가 이를 막는다.

### `ProteanTaskExecutor` (관리형 실행기)

raw `new Thread` 대신 주입받아 async/scheduled 작업을 돌린다. per-module·lazy(주입될 때만 생성, 안 쓰면 스레드 0)·bounded(고정 풀, 데몬 스레드)이며, `AutoCloseable` 이라 언로드 시 child 컨텍스트 close 로 자동 `shutdownNow()` 된다.

```java
@Service
public class PollingService {
    public PollingService(ProteanTaskExecutor executor) {
        executor.scheduleAtFixedRate(this::poll, 0, 10, TimeUnit.SECONDS);
    }
    private void poll() { /* ... */ }
}
```

풀 크기는 `protean.module.executor.pool-size`(기본 `2`)로 설정한다.

### `ModuleUnloadCallback` (언로드 훅)

child 컨텍스트 close 로는 못 닿는 **컨텍스트 밖 자원**(공유/풀 스레드에 건 `ThreadLocal`, static 캐시 등록, JMX MBean, 커스텀 클라이언트 등)을 정리하는 자리다. 모듈(child 컨텍스트) 또는 소비자(루트 컨텍스트)가 이 타입 빈을 두면, 플랫폼이 child 컨텍스트를 close 하기 **직전** 에 호출한다. 콜백 예외는 삼켜지고 로그된다(한 콜백 실패가 다른 정리·언로드를 막지 않음).

```java
@Component
public class DrainOnUnload implements ModuleUnloadCallback {
    @Override
    public void onUnload(String moduleId) {
        // static 캐시·ThreadLocal·MBean 등 컨텍스트 밖 자원 정리
    }
}
```

`DataSource` 풀처럼 컨텍스트 안의 `AutoCloseable` 빈은 이 훅 없이도 child.close() 로 정리되므로, 훅은 컨텍스트가 닿지 못하는 것만 맡으면 된다.

## 리소스만 바뀐 업데이트는 재컴파일 스킵

모듈을 업데이트할 때 **소스가 직전과 동일하고 리소스만 바뀌었으면** `RuntimeCompiler` 가 캐시된 바이트코드를 재사용하고 `javac` 를 스킵한다(리소스-온리 fast-path). mapper XML·SQL 만 고친 배포에서 컴파일 비용을 치르지 않고 무중단 swap 을 유지한다.

더 가벼운 경로로, 요청마다 다시 읽는 리소스(mapper XML 등)는 **컴파일·컨텍스트 재빌드 없이** `ModuleClassLoader.replaceResources` 로 제자리 교체하는 live-reload 도 있다(in-process 모드 한정). MCP `reload-resources`·관리 REST 로 호출하며, `kind: "resource"` 파일만 허용한다. 단 **초기화 시 한 번 파싱하는 리소스**(ORM 부트스트랩 등)엔 제자리 교체가 반영되지 않으므로, 그런 경우는 전체 update 를 써야 한다. worker/container 모드는 live-reload 미지원이라 자동으로 전체 update 로 폴백한다.

## 범위 밖 (소비자 정책)

다음은 Protean 이 정하지 않는다 — 모듈/소비자가 자기 코드로 결정한다: 커넥션 풀 사이징, 샤딩·읽기/쓰기 라우팅, 멀티테넌시, XA/분산 트랜잭션, ORM 선택(JPA/MyBatis/JOOQ/raw JDBC).

## 관련 문서

- [02. 모듈 작성](02-module-authoring.ko.md)
- [03. 설정 레퍼런스](03-configuration.ko.md)
- [05. 격리 모드](05-isolation-modes.ko.md)
- [08. MCP 연동](08-mcp-integration.ko.md)
- [10. SPI 확장](10-spi-extension.ko.md)
- [11. 운영](11-operations.ko.md)
- [README](../../README.ko.md)
