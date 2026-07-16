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

- worker DB admin 자격증명(`protean.worker.db.admin-url` / `username` /
  `password`)을 재기동 없이 런타임 rotation 가능. `DbScopeProvisioner` 가
  provision/deprovision 마다 `AdminCreds` 스냅샷을 읽어 자격증명이 바뀔 때만 admin
  `JdbcTemplate` 을 재구성한다(`REQUIRES_RESTART` → `APPLIED_FUTURE`). rotation 은
  먼저 검증한다 — 후보 자격증명으로 커넥션 1개를 열어 `Connection.isValid` 를 통과해야
  swap 하며, 잘못된 rotation 은 명확히 실패하고 기존 커넥션을 유지한다. (dialect 는
  재기동 필요 유지.)

### 수정

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
