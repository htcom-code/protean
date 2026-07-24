[English](CHANGELOG.md) | **한국어**

# 변경 이력

이 프로젝트의 주요 변경은 여기에 기록한다.
형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따르고,
버전은 [유의적 버전](https://semver.org/lang/ko/)을 준수한다.

버전이 `0.x` 인 동안 공개 API 는 마이너 릴리스 사이에 바뀔 수 있다.

## [Unreleased]

**Protean** 의 발행 전 baseline — Spring Boot 를 런타임 플랫폼으로 쓰는 라이브러리.
실행 중에 Java 소스를 받아 컴파일 → 전용 ClassLoader 로드 → REST 엔드포인트 등록 →
무중단 교체·롤백·해제까지 한다. 좌표 `org.htcom:protean`, Spring Boot 3.5.x / Java 21.
현재는 `mavenLocal` 로만 발행하며, 원격 발행(GitHub Packages)은 이관 이후다.

### 추가

- Maven Central(Sonatype Central Portal) 발행을 `com.vanniktech.maven.publish.base`
  플러그인으로 배선. 발행 POM 에 Central 필수 메타데이터(name·description·url·
  MPL-2.0 license·developer·scm)를 담고, 아티팩트 서명은 property-gated — in-memory
  GPG 키가 없으면 서명을 건너뛰어 `publishToMavenLocal` / GitHub Packages 는
  unsigned 로 발행되고, 릴리스 파이프라인이 키와 Central Portal 토큰을 주입한다.
  발행 산출물은 plain + sources + javadoc + worker 유지(boot jar 제외). 네임스페이스
  검증·GPG/토큰 설정·릴리스 컷은 외부 단계로 아직 미수행.
- worker DB admin 자격증명(`protean.worker.db.admin-url` / `username` /
  `password`)을 재기동 없이 런타임 rotation 가능. `DbScopeProvisioner` 가
  provision/deprovision 마다 `AdminCreds` 스냅샷을 읽어 자격증명이 바뀔 때만 admin
  `JdbcTemplate` 을 재구성한다(`REQUIRES_RESTART` → `APPLIED_FUTURE`). rotation 은
  먼저 검증한다 — 후보 자격증명으로 커넥션 1개를 열어 `Connection.isValid` 를 통과해야
  swap 하며, 잘못된 rotation 은 명확히 실패하고 기존 커넥션을 유지한다. (dialect 는
  재기동 필요 유지.)
- trace SSE 스트림(`GET /platform/traces/stream`)이 관측성 콘솔 헤더용 네 번째
  `summary` 이벤트를 push 한다: 윈도 `TraceSummary` 집계(`protean.trace.summary-window-ms`,
  기본 60s)로 현재 윈도의 요청 수·에러율·p50~p99 지연, 이전 동일 윈도 대비 trend
  (baseline 없으면 null — 가짜 delta 안 만듦), 그리고 활성 모듈을 isolation mode 별로
  센 point-in-time 카운트를 담는다. trace 링버퍼에서 out-of-band 로 계산(기록 핫패스
  무손상)하며 `protean.trace.metrics.enabled` 와 무관하다.

- **DB scope 모델.** `worker.db.auto-provision` 을 "모듈마다 격리"에서 "**scope 선택**"으로
  재정의. scope(tenant/업무 도메인 묶음)가 DB 프로비저닝과 worker/container 패킹의 단위다 —
  같은 scope 모듈은 프로비저닝된 DB 하나를 공유하고 그 scope 의 worker/container 에
  `worker.modules-per-worker`까지 패킹되며, 서로 다른 scope 는 격리된다. 배포는 알려진 ACTIVE
  `scope` 를 지정해야 한다(module.yaml / 배포 API / `ModuleDescriptor.scope`); 시작 seed 허용목록
  `worker.db.scopes`(비우면 `default` 하나)와 신설 `ScopeStore`/`ScopeManager` 레지스트리가 알려진
  scope 를 추적하고 재기동을 넘겨 유지한다.
- **scope 관리 표면.** REST `/platform/scopes`(list · get · create · close · open · detach ·
  destroy — 명시적 action 하위 리소스, `DELETE` 동사 미사용; `admin.enabled` + `auto-provision`
  하에서 활성)와 MCP `protean.scope_*` 툴(`debug.*` 처럼 **항상 목록 노출**, call-time 게이트 —
  `auto-provision` 이 꺼지면 `isError`). 라이프사이클: create/open → ACTIVE, close →
  CLOSED, detach(로그인만 제거·데이터 보존 — 가역), destroy(`DROP DATABASE/SCHEMA` — 비가역).
  `destroy` 는 신설 `worker.db.allow-destroy`(기본 `false`) + 이름 확인으로 가드되고 감사 로그가 남는다.
- `DbDialect` 에 `detachScope`(로그인만·가역)와 `destroyScope`(CASCADE·비가역)를 하위호환 default
  메서드로 추가; 내장 MySQL/PostgreSQL 이 둘 다 override.

### 변경

- 워커 패킹 기본값을 운영 밀도 기준으로 상향. `worker.modules-per-worker` `4` → `128`
  (작은 값에선 워커 JVM 베이스 오버헤드 ~200~300MB가 비용을 지배; 운영계는 검증된 코드라
  크래시 위험 낮음). 이를 수용하도록 컨테이너 트랙 동반 조정: `worker.container.memory`
  `256m` → `512m`, `worker.container.pids-limit` `512` → `1024`, 컨테이너 워커는
  `-XX:MaxRAMPercentage=75.0`으로 기동. 신설 `worker.jvm-args`는 메모리 경계가 없는
  process/embed/sidecar 트랙의 heap 사이징용(경계가 없어 퍼센트는 위험). `modules-per-worker`를
  키우면 이 값들도 함께 상향.
- `worker.db.auto-provision` 하에서 **worker·container 모드 모두 이제 같은 scope 모듈을** 공유
  worker/container 에 `worker.modules-per-worker`까지 패킹한다(격리 경계 = 모듈이 아니라 scope) —
  container 모드는 더 이상 컨테이너당 1모듈이 아니다. 엄격한 worker/container 당 1모듈 경계는
  `worker.modules-per-worker=1`. scope 를 선언한 모듈이 in-process 로 라우팅되면 거부되고(in-process 는
  scope 별 datasource 에 바인딩 불가), auto-provision 이 꺼진 채 선언된 scope 는 경고와 함께 무시된다.

### Deprecated

- `worker.db.deprovision-on-undeploy` — undeploy 는 scope 를 해제하지 않는다; scope 라이프사이클은
  scope 관리 API(detach/destroy)로 운영자가 주도한다. 호환성 위해 유지되나 scope 의 DB 에 영향 없음.

### 수정

- worker·container 풀의 hot-swap 드레인 레이스: 스왑 후 비워진 old 워커/컨테이너가 grace 창 동안
  풀에 남아 동시 배포에 재사용된 뒤 지연 정리에 의해 종료되던 문제(워커 `/__admin/deploy` 500 으로
  표출). 이제 비면 즉시 retiring 표시 + 풀에서 제거하고, 프로세스 종료 / `docker rm` 만 유예한다.

- 라이브러리가 내부 RPC bridge 데모 빈(`Echo`/`Greeting`/`Math`/`Ledger`/`Stream`
  `*Port`)을 더 이상 소비자 앱에 등록하지 않는다. `src/main` 의 `@Component` 라
  오토컨피그 컴포넌트 스캔에 쓸려 모든 소비자에 등록됐고, `LedgerPortImpl` 이 기동 시
  소비자 DB 에 `ledger` 테이블을 만들었다. 이제 테스트 전용 스캐폴딩으로, 발행 jar 에서
  제외된다.
- 워커 JVM 이 module-store 빈을 더 이상 생성하지 않는다. `JdbcModuleStore` /
  `FileSystemModuleStore` 에 프로파일 게이트가 없어, `module-store.backend=jdbc` 를
  상속한 워커(process·container)가 각 모듈의 auto-provision scope DB 안에 플랫폼의
  `module` / `module_version` 테이블을 만들고 기동 self-check 까지 돌렸다 — 워커가
  쓰지 않는 dead 산출물. 이제 둘 다 호스트 전용 소비자와 동일하게 `@Profile("!worker")`.
- JDBC module-store 백엔드가 H2 뿐 아니라 MySQL·PostgreSQL 에서도 동작. 스키마가
  H2 전용 타입(`descriptor_json CLOB`, `seq BIGINT AUTO_INCREMENT`)으로 하드코딩돼
  `module-store.backend=jdbc` 가 다른 엔진에선 기동 시 실패했다(CLOB 은 둘 다 없고
  Postgres 엔 AUTO_INCREMENT 없음). 이제 `ModuleStoreDialect` SPI 로 DDL 이 벤더
  적응형이다 — H2/MySQL/PostgreSQL 내장, 그 외 벤더는 빈으로 확장 — 자동 감지 또는
  `protean.module-store.dialect` 로 선택하며, 미지원 벤더는 H2 DDL 로 조용히 폴백하지
  않고 fail-fast 한다. 기동 self-check 가 descriptor 컬럼이 truncation 없이 대용량
  문자를 담는지, `seq` 가 auto-increment 되는지 검증한다. `protean.module-store.dialect`
  는 설정 표면에 읽기 전용으로 노출된다.
- worker/container 격리 모듈에 모든 HTTP method·요청 body 포워딩. 기존 ReverseProxy
  가 body 없는 GET 으로 하드코딩돼 있어 in-process 에선 되던 `@PostMapping` 이
  격리되면 405 였고, route 목록도 프록시 라우트의 method 를 비워 보고했다. 이제 요청을
  그대로 포워딩하고 경로별 method 를 기록해, REST·MCP route 목록이 모든 격리 모드에서
  실제 method 를 보고한다.
- container reconcile 이 재기동 이름 충돌로 실패하던 것 수정. detached 컨테이너가 JVM
  보다 오래 살고 per-run seq 카운터가 재기동 시 리셋돼 reconcile 이 기존 컨테이너 이름을
  재생성 → `docker run --name` 이 125 충돌 → 모듈 route 404. respawn 전에 같은 이름의
  stale 컨테이너를 제거하고, `@PreDestroy` 로 정상 종료 시 이 인스턴스의 컨테이너를
  정리한다.
- process 트랙 워커 JVM 을 정상 종료 시 종료하도록 수정. `WorkerProcessIsolation` 에
  `@PreDestroy` 가 없어, `ProcessBuilder` 로 띄운 워커 JVM(부모 JVM 이 종료돼도 OS 가
  죽이지 않음)이 랜덤 포트·힙을 문 채 orphan 으로 살아남았다. 이제 `@PreDestroy` 가
  병렬로(SIGTERM → 강제 종료) 정리한다 — 위 container 수정의 process 트랙 짝. 유예는
  `protean.worker.shutdown-grace-ms`(기본 `5000`; `0`=즉시 강제)로 설정 가능. `@PreDestroy`
  가 안 도는 unclean exit(`kill -9`·크래시)도 이제 다음 startup 에 회수한다 — 워커마다
  per-spawn uuid 를 command line(`-Dprotean.worker.id`)에 달고 `<module-store>/workers`
  아래 마커 파일을 남겨, startup 이 프로세스 목록에서 남은 마커의 JVM 을 강제 종료한다.
  PID 가 아니라 uuid 로 매칭해 무관한(또는 다른 인스턴스의) 프로세스를 죽이지 않는다.
- MCP 리소스 surface 를 REST parity 로 복원. `protean://modules/{id}/routes` 가
  worker/container 모듈에서 빈 리스트(정상 모듈을 라우트 없음으로 오독)였고
  `protean://modules` 는 shared-lib generation 필드가 null 이었다. 둘 다 이제 REST 관리
  surface 와 일치한다.

### 동적 로딩 엔진

- 런타임 JSR-199 인메모리 컴파일(`RuntimeCompiler`), 모듈별 `ModuleClassLoader`,
  RequestMapping 동적 등록/해제(`DynamicEndpointRegistrar`).
- 무중단 hot-swap 업데이트, 명시 롤백, 버전 히스토리, 클린 언로드 — 언로드 경로가
  `RequestMappingHandlerAdapter` per-Class 캐시를 purge 해 모듈 ClassLoader 가 온전히
  수거된다(Metaspace 누수 없음).
- update diff: 리소스만 바뀐 업데이트는 재컴파일을 건너뛴다(소스 무변경 시 javac 스킵,
  무중단 swap 유지).

### Trust 모델 & 승격 게이트

- `install` 시 모든 모듈이 서빙 전에 승격 파이프라인을 통과한다: ①테스트(모듈 동봉
  JUnit 컴파일·실행, 무테스트=거부) → ②리뷰(ASM 바이트코드 정적 스캔 `ForbiddenApiRule`,
  `CodeRule` SPI) → ③검증(실 HTTP 프로브·동시성·타임아웃·메모리, 실패 시 자동 롤백).
- opt-in **서명** 게이트(Ed25519 trust store) · **승인** 게이트(`PENDING_APPROVAL`,
  사람이 승인해야 ACTIVE, 재기동해도 우회 불가).
- Trust 모델: 모든 소스는 신뢰 개발자 전제. 미신뢰 소스용 샌드박스는 의도적 non-goal
  (`SandboxAbsenceTest` 가 부재를 증명).

### 격리 모드

- `in-process`(전용 ClassLoader + child ApplicationContext), `worker`(별도 JVM +
  ReverseProxy 포워딩), `container`(Docker: cgroup 메모리/PID · read-only FS ·
  cap-drop · seccomp).
- 모든 모드가 무중단 hot-swap·풀·감독(crash 재기동)·전용 DB 지원. Worker 는 필요 시
  **RPC 브리지**로 메인 공유 빈 호출(`bridgedInterfaces`). `WorkerRuntimeProvider` SPI 로
  embed/sidecar 배포 모델 교체.

### 데이터 접근

- 메커니즘만 제공, 정책은 소비자 몫: 모듈은 child 컨텍스트 안에서 자기 Persistence 계층
  (JdbcTemplate·MyBatis·JPA·멀티 DataSource)을 직접 구성. 드라이버·ORM 은 호스트 번들이며,
  Protean 이 번들하는 mysql/postgres 드라이버는 발행 POM 에서 `optional`(transitive 강제 안 함).
- 모듈당 격리 DB 스코프 자동 프로비저닝(GRANT 격리, `protean.worker.db.auto-provision`),
  `DbDialect` SPI(내장 mysql/postgres).
- 리소스 채널(`ModuleDescriptor.resources`)로 mapper XML·마이그레이션 SQL 등 비-Java 파일을
  실어 모듈 ClassLoader 가 서빙. 관리형 `ProteanTaskExecutor`(per-module·lazy·bounded)는
  언로드 시 자동 shutdown, `ModuleUnloadCallback` SPI 로 컨텍스트 밖 자원 정리.

### MCP 어댑터 & Level 3 디버깅

- zero-dep MCP(Model Context Protocol) 어댑터 — Streamable HTTP(`POST /platform/mcp`) +
  stdio. 모듈 deploy/update/rollback/approve/reject/uninstall/get/list/versions 툴.
  fail-safe off(`protean.mcp.enabled=false`), 인증은 소비자 Spring Security +
  `ModuleActionAuthorizer` SPI 에 위임. MCP `2025-11-25` 스펙 완결(세션, 상시 스트림+재전송,
  `listChanged`, 취소, `_meta` passthrough, opt-in OAuth protected-resource 메타데이터).
- JDI(`jdk.jdi`, zero-dep) 기반 Level 3 디버깅: `launch`/`attach`/`frames`/`step`/
  `continue`/`evaluate`/`redefine`(fix-and-continue)/`terminate`. `evaluate` 는 전체
  표현식 문법 지원(연산자·캐스트·new·람다·메서드 레퍼런스).

### 컨트롤 surface & 설정

- 관리 REST(`/platform/modules`, `protean.admin.enabled`) 및 트레이스 REST.
- 타입 안전 설정 표면 `ProteanProperties`(`protean.*`) — configuration-processor
  메타데이터로 소비자 IDE 자동완성 지원.

### 빌드 & 문서

- plain jar(소비용, classifier 없음) + fat `-boot.jar`(embed 워커 런타임)
  + 평평한 shaded `-worker.jar`(Shadow; sidecar 워커 process 트랙, `worker`
  classifier 발행) + sidecar 워커 컨테이너 이미지(Jib) `ghcr.io/<owner>/protean-worker`.
  `publishToMavenLocal`(POM/소비성 검증). **`test` 와 `bootJar` 는 분리 실행**
  (결합 시 `LeakDiagnosisTest` OOM 위험).
- README(en/ko) 및 `docs/guide/` 사용자 가이드.
