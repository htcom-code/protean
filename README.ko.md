[English](README.md) | **한국어**

# Protean

[![License: MPL 2.0](https://img.shields.io/badge/License-MPL_2.0-brightgreen.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-21-orange.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.x-green.svg)

**Spring Boot 를 런타임 플랫폼으로 쓰는 동적 API 서버 라이브러리.** 서버가 실행 중에 Java 소스를
받아 컴파일 → 전용 ClassLoader 로 로드 → REST 엔드포인트로 등록하고, 무중단 교체·롤백·해제까지 한다.
재기동·재배포 없이 API 를 붙였다 뗄 수 있다.

**REST** 와 **MCP**(Model Context Protocol) 두 컨트롤 표면으로 모듈을 배포·디버깅한다. **MCP 로는
AI 에이전트가 직접 연결(HTTP/stdio)** 해 실행 중인 서버에 대고 재기동 없이 개발 전 과정을 돈다 — 소스
작성 → 테스트·바이트코드·검증 게이트를 통과한 배포 → 라이브 디버그(JDI 브레이크포인트·`evaluate`·hot-swap)
→ 트레이스·메트릭 관측 → 롤백. 이 표면은 강력한(RCE 등급) 만큼 로컬 밖에서는 인증을 전제로 한다
— [08. MCP 연동](docs/guide/08-mcp-integration.ko.md) 참고. 좌표: `org.htcom:protean` (Spring Boot 3.5.x / Java 21).

**호환성:** Protean 은 현재 **Spring Boot 3.5.x / Java 21** 을 지원한다. 소비자 앱 안에서 돌며
Spring MVC·컨텍스트 내부를 조작하므로 지원 라인은 계약에 가깝다 — Spring Boot 4.x 소비자는
**아직 지원되지 않는다**(런타임 링크 오류가 날 수 있다). 4.x 지원은 확정된 계획 트랙이다 —
[로드맵](ROADMAP.ko.md#플랫폼-호환성-spring-boot--java) 참고.

## 핵심 기능

- **동적 로딩 엔진** — 실행 중 Java 소스를 컴파일→전용 ClassLoader 로드→REST 매핑 등록하고, 무중단
  hot-swap·롤백·해제까지. 재기동/재배포 없음. 해제 시 Spring/ClassLoader 캐시 누수까지 purge 한다.
- **승격 파이프라인 (Trust 모델)** — `install` 시 ①테스트 ②바이트코드 리뷰 ③라이브 검증 게이트를 통과해야
  서빙된다. Ed25519 서명·사람 승인은 opt-in. "모든 소스 = 신뢰 개발자" 전제.
- **버전 관리 & 롤백** — 배포마다 버전 히스토리를 쌓고 특정 버전으로 명시 롤백한다(`/rollback?version=`).
  카나리 업데이트가 ③검증에 실패하면 이전 버전으로 자동 롤백.
- **재기동 복구** — 디스크립터를 write-ahead 저장소(파일시스템 또는 JDBC — 벤더 적응형 H2/MySQL/PostgreSQL,
  확장 가능)에 영속화하고, 서버 재기동 시 `reconcile` 이 ACTIVE 모듈을 자동 복원한다. 인메모리 상태에 의존하지 않는다.
- **격리 모드 3종** — `in-process` / `worker`(별도 JVM) / `container`(Docker·cgroup·seccomp·read-only FS).
  모듈별로 고르며, 전부 무중단 hot-swap·워커 풀·크래시 감독·전용 DB 를 지원한다.
- **RPC 브리지** — 워커가 메인의 공유 빈을 호출한다(임의 인터페이스 동적 프록시). opt-in 공유 시크릿 인증
  (token/HMAC)·`InputStream` 반환 스트리밍.
- **런타임 실행 가드** — 모듈 요청 타임아웃 워치독(`protean.module.request-timeout-ms`)이 폭주·블로킹 핸들러를
  끊는다(초과 시 503). 협조적 인터럽트라 블로킹 호출만 해제.
- **MCP 어댑터** — MCP 에이전트가 HTTP/stdio 로 모듈을 배포·조회·디버깅한다. zero-dep JSON-RPC, open-core
  (소비자가 커스텀 툴 빈을 기여할 수 있음).
- **Level 3 디버깅** — JDI 기반 브레이크포인트·스텝·변수 검사·표현식 평가·fix-and-continue(redefine). zero-dep.
- **모듈 데이터 접근 계약** — 엔진 무관 메커니즘(리소스 채널·멀티 DataSource·scope 단위 DB 자동 프로비저닝 + tenant 스코핑)만
  제공하고, ORM·풀·정책은 소비자 몫으로 남긴다.
- **구조화 오류 (RFC 9457)** — MCP·HTTP·Admin 오류를 RFC 9457 problem+확장 멤버로 표면화하고 `traceId` 로
  상관한다 → 에이전트가 문구 파싱 없이 결정론적으로 자기교정·재시도.
- **런타임 관측성** — 요청 트레이스(모듈 귀속 + `traceId` 로그 상관) + opt-in 모듈별 집계 메트릭(요청수·에러율·
  지연 p50/p95/p99). REST(`/platform/traces`)·MCP 로 조회.

각 기능의 동작 원리는 아래 [핵심 개념](#핵심-개념), 컨트롤 방법은 [컨트롤 surface](#컨트롤-surface) 참조.
라이브러리라 소비자가 사용 시점에 확장할 수 있도록 확장이 필요한 축은 빈 등록으로 여는 **open-core** —
자세한 목록은 [확장점(SPI)](#확장점spi) 참조.

---

## 핵심 개념

### 모듈(Module)
배포 단위. `ModuleDescriptor`(record)로 선언한다 — id/version, 컨트롤러·컴포넌트 FQCN,
`sources`(FQCN→Java 소스), `tests`(승격 게이트 입력), `resources`(경로→비-Java 리소스, mapper XML 등),
격리 모드, 검증 계획, 서명 등. `module.yaml` 매니페스트로도 선언할 수 있다.

### 생명주기와 승격 게이트
모듈은 `install` 시 **승격 파이프라인**을 통과해야 서빙된다. 각 단계는 `protean.gate.*` 로 토글:

```
(서명 opt-in) → ①테스트 → ②리뷰(바이트코드) → (승인 opt-in) → ③검증(라이브) → ACTIVE
```

- **① 테스트** — 모듈에 동봉된 JUnit 테스트를 런타임 컴파일·실행해 통과해야 함(무테스트=거부).
- **② 리뷰** — ASM 바이트코드 정적 스캔(`ForbiddenApiRule`: `System.exit`/`Runtime.exec`/`Runtime.addShutdownHook` 등 차단).
  `CodeRule` 빈을 등록하면 룰이 추가된다(확장점).
- **③ 검증** — 실 포트로 HTTP 프로브·동시성·타임아웃·메모리 검증. 실패 시 자동 롤백.
- **서명 게이트**(opt-in) — Ed25519 서명 검증(trust store).
- **승인 게이트**(opt-in) — 자동 게이트 통과분을 `PENDING_APPROVAL` 로 두고, 사람이 `approve`
  해야 ③검증+배포되어 `ACTIVE`. 미승인 모듈은 재기동해도 서빙되지 않음(우회 차단).

`DesiredState = { ACTIVE, INACTIVE, PENDING_APPROVAL }`. `ACTIVE` 만 기동 시 `reconcile` 대상.

### 격리 전략(IsolationStrategy)
모듈마다 격리 모드를 고를 수 있다(`ModuleDescriptor.isolationMode`, null=전역 기본 `protean.isolation.mode`):

| 모드 | 구현 | 격리 수준 |
|------|------|-----------|
| `in-process` | `InProcessIsolation` | 같은 JVM · 전용 ClassLoader + child ApplicationContext (기본) |
| `worker` | `WorkerProcessIsolation` | 별도 JVM 프로세스 · ReverseProxy 포워딩 |
| `container` | `ContainerWorkerIsolation` | Docker 컨테이너 · cgroup 메모리/PID · read-only FS · cap-drop · seccomp |

모든 모드가 무중단 hot-swap·풀·감독(crash 재기동)·전용 DB 를 지원한다. Worker 는 필요 시
**RPC 브리지**로 메인의 공유 빈을 호출한다(`bridgedInterfaces` 선언).

### Trust 모델
모든 소스 = 신뢰 개발자 전제(운영계 in-process). 미신뢰 소스용 보안 샌드박스는 **의도적 non-goal**
(`SandboxAbsenceTest` 가 부재를 증명).

### 데이터 접근 (SQL · DataSource)
특정 데이터 접근 엔진을 고르지 않는다 — **메커니즘만 제공하고 정책은 소비자 몫**이다. 모듈은 child 컨텍스트
안에서 임의의 `@Configuration` 으로 자기 Persistence 계층(JdbcTemplate·MyBatis·JPA·멀티 DataSource)을 직접 구성한다.

- **드라이버·ORM = 호스트 번들** — 모듈은 소스-온리. 드라이버·ORM 은 소비자가 호스트에 넣고 parent-first 로 쓴다.
  `protean.module.shared-lib-dir` 에 jar 드롭인(앱-수명 CL, `DriverManager` 누수 없음). 번들 mysql/postgres 드라이버는
  발행 POM 에서 **optional**(transitive 강제 없음).
- **리소스 채널** — `ModuleDescriptor.resources`(경로→내용, 바이너리 base64)로 mapper XML·`persistence.xml`·마이그레이션
  SQL 등 비-Java 파일을 배송, 모듈 ClassLoader 가 owned-child-first 로 서빙한다.
- **scope 단위 DB 프로비저닝** — `protean.worker.db.auto-provision` 은 배포를 "scope 선택"(tenant/업무 도메인)으로
  바꾼다: scope 마다 전용 DB/스키마+유저가 생기고, 같은 scope 모듈은 이를 공유하며 그 scope 의 워커/컨테이너에 패킹된다.
  운영자 주도 scope 라이프사이클(create/close/detach/destroy)은 `/platform/scopes` + MCP `scope_*`; MySQL/Postgres 내장,
  벤더 확장은 `DbDialect` 빈.
- **트랜잭션** — DataSource 선택에서 따라 나온다: in-process+공유 DataSource+부모 tx매니저=호스트 tx 참여, 자체
  DataSource=격리, worker/container=항상 격리(교차는 RPC 브리지).
- **관리형 실행 & 언로드** — `ProteanTaskExecutor`(per-module·lazy·bounded, 언로드 시 자동 shutdown) + `ModuleUnloadCallback` SPI.
- **update diff** — 리소스만 바뀐 업데이트는 재컴파일을 건너뛴다(무중단 swap 유지).

풀 사이징·샤딩·라우팅·멀티테넌시·XA·ORM 선택은 **소비자 정책**(범위 밖).

---

## 빠른 시작 (라이브러리 소비)

Protean 은 plain jar 로 발행된다(현재 mavenLocal, 원격 registry 는 GitHub 이관 후 GitHub Packages).

```gradle
dependencies {
    implementation 'org.htcom:protean:0.0.1-SNAPSHOT'
    // 소비자 앱은 Spring Boot 앱이므로 이미 보유(Protean 은 spring 을 runtime scope 로만 전이):
    implementation 'org.springframework.boot:spring-boot-starter-web'
}
```

auto-configuration(`ProteanAutoConfiguration`)이 자동 로딩된다. 관리 REST(`/platform/modules`)는
기본 on, MCP·디버깅은 fail-safe 로 기본 off. 모듈 배포 예(REST):

```bash
curl -X POST localhost:8080/platform/modules \
  -H 'Content-Type: application/json' \
  -d '{ "id": "hello", "version": "1", "controllerFqcn": "gen.HelloController",
        "sources": { "gen.HelloController": "...java source..." },
        "tests":   { "gen.HelloTest": "...junit source..." } }'
```

---

## 컨트롤 surface

### 관리 REST — `/platform/modules` (`ModuleAdminController`, `protean.admin.enabled`)
| 메서드 | 경로 | 동작 |
|--------|------|------|
| `GET` | `/platform/modules` | ACTIVE 모듈 목록 |
| `GET` | `/platform/modules/{id}` | 단일 상태 |
| `POST` | `/platform/modules` | 배포(게이트/검증 통과 시 201) |
| `POST` | `/platform/modules/from-manifest` | `module.yaml` 로 배포 |
| `PUT` | `/platform/modules/{id}` | 카나리 업데이트(hot-swap) |
| `PATCH` | `/platform/modules/{id}` | 델타/패치 업데이트(파일 overlay) |
| `POST` | `/platform/modules/{id}/reload-resources` | 리소스 무중단 교체(재컴파일 없음) |
| `GET` | `/platform/modules/{id}/versions` | 버전 히스토리 |
| `GET` | `/platform/modules/{id}/routes` | 라이브 등록 라우트(ACTIVE 의 실체) |
| `POST` | `/platform/modules/{id}/rollback?version=` | 명시 롤백 |
| `POST` | `/platform/modules/{id}/approve?approver=` | 승인 → ACTIVE |
| `POST` | `/platform/modules/{id}/reject?approver=` | 거부 |
| `DELETE` | `/platform/modules/{id}` | 해제 |

### 관측성 REST — `/platform/traces` (`TraceAdminController`, `protean.admin.enabled`)
| 메서드 | 경로 | 동작 |
|--------|------|------|
| `GET` | `/platform/traces` | 요청 트레이스(최신순). 필터 `moduleId`·`errorsOnly`·`status`·`minLatencyMs`·`since`·`beforeSeq`·`limit` |
| `GET` | `/platform/traces/metrics` | 모듈별 집계 메트릭(요청수·에러율·지연 p50/p95/p99). `protean.trace.metrics.enabled` 필요 |
| `GET` | `/platform/traces/stream` | 라이브 SSE 푸시(`trace`/`metrics`/`modules`/`summary` 이벤트) |

### 설정 REST — `/platform/config` (`ConfigAdminController`, `protean.admin.enabled`)
| 메서드 | 경로 | 동작 |
|--------|------|------|
| `GET` | `/platform/config` | 모든 키와 현재 값·tier |
| `GET` | `/platform/config/{key}` | 단일 키 |
| `PATCH` | `/platform/config` | `{key: value}` 패치 적용(원자적; 알 수 없는/유효하지 않은 키 있으면 전체 중단) |

### Shared-lib REST — `/platform/shared-libs` (`SharedLibAdminController`, `protean.admin.enabled`)
| 메서드 | 경로 | 동작 |
|--------|------|------|
| `GET` | `/platform/shared-libs` | 현재 generation + 저장 라이브러리 |
| `GET` | `/platform/shared-libs/{name}` | 단일 저장 라이브러리 메타데이터 |
| `POST` | `/platform/shared-libs` | jar 묶음을 새 generation 으로 업로드(멀티파트) |
| `DELETE` | `/platform/shared-libs/{name}` | 스토어에서 제거(미래 generation 만) |

### MCP 어댑터 (`protean.mcp.enabled`, 기본 off)
Streamable HTTP(`POST /platform/mcp`) + stdio(`protean.mcp.stdio`). zero-dep JSON-RPC(SDK 미사용). 소비자가
커스텀 `McpTool` 빈을 등록하면 함께 노출된다(open-core). 인증은 라이브러리가 구현하지 않고 소비자 Spring
Security + `ModuleActionAuthorizer` SPI 에 위임한다. 내장 툴(전부 `protean.*` 네임스페이스):

| 분류 | 툴 | 동작 |
|------|-----|------|
| 라이프사이클 | `deploy_module` | 배포(게이트 ①②③ 통과 시 ACTIVE) |
| | `update_module` | 카나리 업데이트(hot-swap, 실패 시 자동 롤백) |
| | `patch_module` | 델타 업데이트(파일 overlay, 바뀐 것만) |
| | `rollback_module` | 특정 버전으로 롤백 |
| | `approve_module` / `reject_module` | 승인 게이트: 승인→ACTIVE / 거부 |
| | `uninstall_module` | 해제 |
| | `reload_module_resources` | 리소스 무중단 교체(재컴파일 없음) |
| 조회 | `get_module` / `list_modules` | 단일 상태 / 목록 |
| | `get_module_source` | 모듈 소스 조회 |
| | `module_versions` | 버전 히스토리 |
| 관측성 | `query_traces` | 요청 트레이스(필터·페이징) |
| | `module_metrics` | 모듈별 집계 메트릭 |

### Level 3 디버깅 (`protean.mcp.debug.enabled`, 기본 off)
JDI(`jdk.jdi`, zero-dep) 기반의 인터랙티브 스텝 디버깅. `evaluate` 는 전체 표현식 문법을 지원한다
(연산자·캐스트·new·람다·메서드 레퍼런스 포함). 툴(전부 `debug.*` 네임스페이스):

| 분류 | 툴 | 동작 |
|------|-----|------|
| 세션 | `launch` | JDWP 켠 워커로 배포+자동 attach(종료 시 일반 배포로 원복) |
| | `attach` | 이미 떠 있는 JDWP JVM 에 attach |
| | `list_sessions` | 열린 디버그 세션 조회(재접속) |
| | `terminate` | 세션 종료(launch 워커면 정리) |
| 실행 제어 | `set_breakpoint` | `className:line` 중단점 |
| | `continue` / `step` | 재개 / over·into·out 스텝 |
| | `await_stop` | 다음 정지(브레이크포인트·스텝 완료)까지 대기 |
| 상태 검사 | `frames` | 정지 스레드 스택 프레임 |
| | `get_variables` | 프레임 로컬 변수(`-g` 컴파일 필요) |
| | `evaluate` | 정지 프레임에서 표현식 평가 |
| 핫스왑 | `redefine` | 메서드 본문 in-place 교체(fix-and-continue) |

---

## 패키지 구조

```
org.htcom.protean
├── ProteanApplication            앱 진입점(라이브러리 소비 시엔 auto-config 가 대신)
├── autoconfigure/                ProteanAutoConfiguration · ProteanProperties(protean.* 설정)
├── compiler/                     JSR-199 인메모리 컴파일(RuntimeCompiler) · ModuleClassLoader · ModuleSharedLibs
├── dynamic/                      RequestMapping 동적 등록/해제(DynamicEndpointRegistrar)
├── proxy/                        ReverseProxy(워커 라우팅)
├── module/                       ModulePlatform(파사드) · ModuleDescriptor · ModuleResource · Store(FS/JDBC) · Reconciler
│                                  · ProteanTaskExecutor · ModuleUnloadCallback(언로드 SPI)
├── gate/                         승격 게이트 ①②③ · 서명 · rules/(CodeRule SPI)
├── isolation/                    IsolationStrategy SPI + InProcess/Worker/Container + WorkerRuntimeProvider
├── worker/                       워커 관리(admin·포트 핸드셰이크)
├── bridge/                       워커→메인 RPC 브리지(임의 인터페이스 동적 프록시)
├── db/                           DB 스코프 자동 프로비저닝 · DbDialect SPI(내장 MySQL/Postgres)
├── runtime/                      실행 타임아웃 워치독 · 상관ID · 요청 트레이스·모듈별 메트릭(TraceStore · TraceMetrics)
├── error/                        구조화 오류(RFC 9457): ErrorCode · ProblemDetail · ProteanException
├── web/                          컨트롤 플레인 REST(ModuleAdminController · TraceAdminController)
├── mcp/                          MCP 어댑터(dispatcher · tools/ · transport/ · debug/)
└── boot/                         워커/stdio 런처(ProteanWorkerLauncher · ProteanMcpStdioLauncher)
```

핵심 파사드는 `module/ModulePlatform` — install/update/rollback/approve/reject/uninstall/reconcile 이
모두 여기로 위임되고, 게이트·검증·격리 라우팅이 이 안에서 일어난다.

---

## 확장점(SPI)

라이브러리 소비자는 사용 시점에 코드를 확장할 수 없으므로, Protean 은 확장이 필요한 축을 빈 등록으로 연다:

| SPI | 등록 방법 | 용도 |
|-----|-----------|------|
| `gate.rules.CodeRule` | `CodeRule` 빈 등록 | 승격 게이트 ② 에 커스텀 바이트코드 룰 추가 |
| `db.DbDialect` | `DbDialect` 빈 등록 | DB 스코프 프로비저닝에 벤더 추가/오버라이드(내장 mysql·postgresql 폴백) |
| `module.ModuleStoreDialect` | `ModuleStoreDialect` 빈 등록 | JDBC module-store 백엔드에 벤더 추가/오버라이드(내장 h2·mysql·postgresql) |
| `mcp.ModuleActionAuthorizer` | `ModuleActionAuthorizer` 빈 등록 | MCP 모듈 액션 인가(기본 permissive) |
| `isolation.WorkerRuntimeProvider` | 빈 등록 | 워커 배포 모델(embed/sidecar) 교체 |
| `module.ModuleUnloadCallback` | 모듈/소비자 빈 등록 | 언로드 시 컨텍스트 밖 자원(ThreadLocal·MBean 등) 정리 훅 |
| `ModuleDescriptor.bridgedInterfaces` | 디스크립터 선언 | 워커가 RPC 로 호출할 메인 공유 인터페이스 |

모듈 코드에 주입되는 것: `module.ProteanTaskExecutor`(per-module·lazy·bounded 관리형 실행기, 언로드 시 자동 shutdown).

예 — 커스텀 DB dialect 추가:

```java
@Bean
DbDialect oracleDialect() {
    return new DbDialect() {
        public String id() { return "oracle"; }
        // ... createScope / dropScope / scopedUrl / maxNameLength
    };
}
// 설정: protean.worker.db.dialect=oracle
```

---

## 주요 설정 (`protean.*`)

`ProteanProperties` 가 타입 안전 설정 표면(IDE 자동완성 메타데이터 포함). 발췌:

| 키 | 기본값 | 설명 |
|----|--------|------|
| `protean.isolation.mode` | `in-process` | 전역 격리 모드: in-process\|worker\|container |
| `protean.admin.enabled` | `true` | 관리 REST 노출 |
| `protean.mcp.enabled` | `false` | MCP 서버(배포 입구, RCE 표면 → fail-safe off) |
| `protean.mcp.stdio` / `.debug.enabled` | `false` | stdio 전송 / 디버그 툴 |
| `protean.gate.tests-enabled` / `.review-enabled` | `true` | 승격 게이트 ①/② |
| `protean.gate.signature.required` / `.approval.required` | `false` | 서명 / 승인 게이트 |
| `protean.module.request-timeout-ms` | `0` | 모듈 요청 타임아웃(0=무제한) |
| `protean.module.shared-lib-dir` | (빈값) | 공유 lib 디렉터리 — 그 jar 를 모듈 CL 부모+컴파일 클래스패스에 반영(드롭인) |
| `protean.module.executor.pool-size` | `2` | 모듈 관리형 executor(`ProteanTaskExecutor`) 풀 크기 |
| `protean.module-store.backend` / `.dir` / `.dialect` | `filesystem` | 디스크립터 저장소: filesystem\|jdbc (jdbc dialect 자동 감지; h2/mysql/postgresql 내장) |
| `protean.trace.enabled` / `.capacity` | `true` / `200` | 요청 트레이스 링버퍼 |
| `protean.trace.metrics.enabled` | `false` | 모듈별 집계 메트릭(요청수·에러율·지연 백분위, opt-in) |
| `protean.trace.metrics.latency-buckets` / `.max-modules` | `20` / `512` | 지연 히스토그램 버킷 수 / 추적 모듈 상한 |
| `protean.worker.modules-per-worker` / `.min-warm` | `4` / `0` | 워커 적재/워밍 |
| `protean.worker.auto-restart` / `.rpc-bridge` | `false` | 크래시 재기동 / RPC 브리지 |
| `protean.worker.runtime` | `embed` | 워커 런타임: embed\|sidecar |
| `protean.worker.container.*` | — | image·memory·pids-limit·network·seccomp·db-host |
| `protean.worker.db.auto-provision` | `false` | scope(tenant) 단위 격리 DB 자동 프로비저닝; 배포가 scope 선택(dialect·admin 자격 필요) |
| `protean.worker.db.scopes` / `.allow-destroy` | (비어 있음) / `false` | 시작 시 scope 허용목록(비우면 `default`) / 비가역 scope `destroy` 가드 |

전체는 `autoconfigure/ProteanProperties.java` 참조.

---

## 빌드 & 테스트

`gradlew` 래퍼는 없다 — 시스템 `gradle` 직접 사용(Java 21 toolchain).

```bash
gradle build                              # 컴파일 + plain jar + bootJar('-boot')
gradle test                               # 전체 테스트(maxHeap 512m — 누수 카나리용)
gradle :test --tests 'org.htcom.protean.XxxTest'  # 단일 클래스(루트 프로젝트로 한정)
gradle publishToMavenLocal                # ~/.m2 로 발행(POM/소비성 검증)
```

주의:
- **bootJar 와 test 는 분리 실행**한다. `clean bootJar test` 결합은 `LeakDiagnosisTest` 에서 collateral OOM 을 유발할 수 있다.
- 컨테이너·Testcontainers·OS 격리·seccomp 테스트는 **Docker 필요**하며, 실행 전 `gradle bootJar` 로 exploded 레이아웃이 있어야 한다. Docker 없으면 해당 테스트는 skip.
- 산출물: plain jar(소비용, classifier 없음) + `-boot.jar`(embed 워커 실행형 fat jar) + `-worker.jar`(sidecar 워커 process 트랙용 평평한 shaded jar). sidecar 워커 컨테이너 이미지는 `./gradlew jib` 로 `ghcr.io/<owner>/protean-worker` 에 발행.

기여 방법은 [CONTRIBUTING.md](CONTRIBUTING.md), 보안 신고는 [SECURITY.md](SECURITY.md) 참조.

---

## 라이선스

[Mozilla Public License 2.0](LICENSE).
