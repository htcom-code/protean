[English](11-operations.md) | **한국어**

# 11. 운영

프로덕션에서 Protean 을 운영할 때 알아야 할 영속 저장소, 재기동 복구, 모니터링, 요청 타임아웃, 리소스 누수 회피, 빌드/발행 절차를 다룬다.

## 디스크립터 영속 저장소

모듈 디스크립터(선언적 메타)는 `ModuleStore` 로 durable 하게 저장된다. 백엔드는 `protean.module-store.backend` 로 선택한다.

### filesystem 백엔드(기본)

`protean.module-store.backend=filesystem`(미설정 시 기본).

```yaml
protean:
  module-store:
    backend: filesystem
    dir: /var/lib/protean/modules   # 기본값: ${java.io.tmpdir}/protean-modules
```

- 현재 상태: `dir/<id>.json`. 쓰기는 임시 파일 → atomic move 로 부분 쓰기(crash)를 방지한다(write-ahead 내구성).
- 버전 히스토리: `dir/<id>.history/<seq>.json`(append-only 스냅샷, 감사/롤백용).
- **주의**: 기본 `dir` 이 `java.io.tmpdir` 하위다. 프로덕션에서는 재부팅 시 지워지지 않는 영속 경로로 반드시 바꿔야 한다.

### jdbc 백엔드

`protean.module-store.backend=jdbc`. 파일시스템 대비 내구성·조회성, 그리고 여러 메인 인스턴스가 같은 store 를 공유하는 다중 인스턴스 이점이 있다.

```yaml
protean:
  module-store:
    backend: jdbc
```

- 기동 시(`@PostConstruct`) 스키마를 자동 생성한다(`CREATE TABLE IF NOT EXISTS`): `module`(현재 상태), `module_version`(append-only 히스토리). 컬럼은 `descriptor_json CLOB`.
- 애플리케이션의 `JdbcTemplate`(즉 소비자가 구성한 `DataSource`)을 그대로 쓴다. 별도 DataSource 를 주입하지 않는다.
- upsert 는 DB 이식성을 위해 MERGE 대신 `UPDATE→(0건)→INSERT` 방식이다.

### 백엔드 전환

두 백엔드는 같은 `ModuleDescriptor` JSON 을 저장하지만 저장 위치가 다르다. 전환 시 기존 디스크립터가 자동 마이그레이션되지 않는다. 무중단이 필요하면 기존 store 의 디스크립터를 새 백엔드로 옮기거나, ACTIVE 모듈을 새 백엔드에 다시 배포해야 한다.

## 서버 재기동 시 reconcile

`ModuleReconciler`(`ApplicationRunner`, `@Profile("!worker")`)가 기동 시 `ModulePlatform.reconcile()` 을 호출해 저장소의 모듈을 재배포한다.

- **ACTIVE 만 복구한다.** `store.listActive()` 로 `desiredState=ACTIVE` 인 것만 각 모듈의 격리 모드로 다시 배포한다.
- `PENDING_APPROVAL` 모듈은 복구되지 않는다 — 미승인 모듈이 재기동으로 서빙되는 우회를 차단한다.
- 개별 모듈 복구 실패는 로그만 남기고 건너뛴다(다른 모듈 복구를 막지 않는다). `reconcile: {복구}/{전체} 모듈 복구` 로그로 결과를 확인한다.
- reconcile 은 게이트를 다시 태우지 않고 저장된 디스크립터를 재배포한다(설치 시 이미 통과). 저장소가 비어 있으면 no-op.

## 요청 트레이스 / 모니터링

런타임 관찰용 인메모리 trace 링버퍼(`TraceStore`)를 제공한다.

```yaml
protean:
  trace:
    enabled: true      # 기본 true
    capacity: 200      # 기본 200 — 최근 N개 요청(초과 시 오래된 것부터 폐기)
```

- `RequestTraceFilter` 가 가장 바깥(최우선) 필터로 모든 요청의 진입~응답 소요·상태·예외를 기록하고, 매칭된 핸들러 패턴으로 동적 모듈에 귀속한다.
- 조회는 `GET /platform/traces?limit=&moduleId=`(→ [04. REST API 레퍼런스](04-rest-api.ko.md)).
- 링버퍼는 바운디드 인메모리다 — 재기동 시 사라지며, 영속/외부 노출은 별도 과제다. 장기 보관·집계가 필요하면 로그/APM 으로 별도 수집해야 한다.
- capacity 는 메모리 사용과 trade-off 다. 트래픽이 많으면 최근 창이 짧아지므로, 진단이 목적이면 필요 시 늘린다.

### 관측성 표면 한눈에

trace 기능은 설정·REST·MCP 로 나뉘어 있다. 아래는 전체 표면을 모은 참조 허브이며, 각 상세는 링크한 문서를 본다. trace 기록은 기본 on(경량)이고, **모듈별 집계 메트릭은 opt-in**(꺼져 있으면 기록 핫패스는 boolean 체크 한 번뿐)이다.

**설정 (`protean.trace.*`)** — 상세 [03. 설정 레퍼런스](03-configuration.ko.md#trace--런타임-trace-기록)

| 키 | 기본값 | 뜻 |
|---|---|---|
| `enabled` | `true` | 요청 trace 기록 on/off |
| `capacity` | `200` | 링버퍼 용량(최근 N개, 초과 시 오래된 것부터 폐기) |
| `metrics.enabled` | `false` | 모듈별 집계 메트릭(opt-in) |
| `metrics.latency-buckets` | `20` | 지연 히스토그램 버킷 수(정밀도↔메모리) |
| `metrics.max-modules` | `512` | 추적 최대 모듈 수(초과 시 LRU 축출) |

**조회 표면** — REST 상세 [04. REST API 레퍼런스](04-rest-api.ko.md#런타임-trace--베이스-경로-platformtraces), MCP 상세 [08. MCP 연동](08-mcp-integration.ko.md)

| 종류 | 엔드포인트 / 툴 | 반환 | 필터 |
|---|---|---|---|
| REST | `GET /platform/traces` | `RequestTrace[]` | `limit`(기본50,최소1)·`moduleId`·`errorsOnly`·`status`·`minLatencyMs`·`since`·`beforeSeq` (AND, 최신순) |
| REST | `GET /platform/traces/metrics` | `ModuleMetricsSnapshot[]` | `moduleId`(생략 시 전 모듈); metrics off 면 빈 목록 |
| MCP 툴 | `protean.query_traces` | 위 traces 와 동일 | 위 필터와 동일 |
| MCP 툴 | `protean.module_metrics` | `{enabled, metrics[]}` | `moduleId` |
| MCP 리소스 | `protean://traces` | 최근 trace(고정 50건) | — |

**데이터 모델**

- `RequestTrace`(요청 1건): `seq`·`epochMillis`·`method`·`uri`·`pattern`(미매칭 `null`)·`moduleId`(플랫폼/정적 `null`)·`status`·`latencyMs`·`error`(예외 FQCN, 없으면 `null`)·`traceId`(로그·RFC 9457 오류와 공유하는 상관 ID).
- `ModuleMetricsSnapshot`(모듈 집계): `moduleId`(플랫폼 `"(platform)"`)·`count`·`errorCount`(예외 또는 status≥500)·`errorRate`·`p50/p95/p99LatencyMs`·`maxLatencyMs`·`lastSeenEpochMillis`.

**기록 파이프라인 (내부)**

| 컴포넌트 | 역할 |
|---|---|
| `RequestTraceFilter` | 최우선 필터로 진입~응답 소요·상태·예외 기록, 핸들러 패턴으로 모듈 귀속 |
| `CorrelationIdFilter` | `X-Request-Id`/MDC `traceId` 상관 ID 부여 → trace·로그·오류 응답 연결 |
| `TraceStore` | 바운디드 링버퍼, 자기 조회 경로(`/platform/traces`)는 미기록 |
| `TraceMetrics` / `LatencyHistogram` | metrics opt-in 시 모듈별 카운터·로그-선형 지연 히스토그램 집계 |

**격리모드별 귀속 범위 (알아둘 것)**

메인 플랫폼의 trace/메트릭이 기록하는 **지연의 의미가 격리모드에 따라 다르다.**

- **in-process** — 핸들러가 메인 JVM에서 직접 실행되므로 trace 의 `latencyMs` = 모듈 실행 시간 그대로.
- **worker / container** — 요청이 리버스 프록시로 워커/컨테이너에 포워딩된다. 메인의 trace/메트릭은 그 모듈에 정확히 귀속되지만(`moduleId` 채워짐), 기록되는 지연은 **프록시 홉(=클라이언트가 체감하는) 지연**이다(네트워크 왕복 포함). 모듈 **내부 실행 시간**과 워커 자체의 상세 trace 는 그 워커/컨테이너 JVM 안의 `TraceStore` 에 있으며, 워커는 `@Profile("!worker")` 로 인해 `/platform/traces` 를 노출하지 않으므로 **메인에서 직접 조회되지 않는다**. 필요하면 워커의 로그/APM 으로 수집한다.

> 워커/컨테이너 모듈의 내부 실행 관측성을 단일 창구로 노출하는 방안(집계 pull-back / 콘솔 멀티소스)은 지원 여부 판별 대상이다 — [ROADMAP](../../ROADMAP.ko.md#관측성-observability) 참고.

## 모듈 요청 타임아웃

`ModuleExecutionWatchdog` 가 모듈 요청 실행에 데드라인을 건다.

```yaml
protean:
  module:
    request-timeout-ms: 0   # 기본 0 = 무제한
```

- **협조적 한계**: 데드라인 초과 시 실행 스레드를 `interrupt` 한다. `Thread.sleep`·interruptible I/O 같은 블로킹은 끊지만, CPU 스핀(`while(true){}`)은 interrupt 를 무시하므로 못 막는다.
- 하드 캡(폭주 CPU·메모리)은 OS/프로세스 격리(worker/container 모드)의 몫이다. in-process 모드에서 timeout 은 방어선일 뿐 하드 격리가 아니다.

## ClassLoader 누수 회피 원칙

동적 모듈은 자기 `ModuleClassLoader` 로 로드된다. 언로드 후 그 ClassLoader 가 GC 되지 않으면 Metaspace 가 회수되지 않아 결국 Metaspace OOM 에 이른다. 플랫폼과 모듈이 함께 지켜야 한다.

- **플랫폼이 하는 일**: `DynamicEndpointRegistrar` 가 `unregister`/`swap` 시 매핑 해제뿐 아니라, 호출로 채워진 MVC 인프라의 per-Class 캐시(`RequestMappingHandlerAdapter` 의 `sessionAttributesHandlerCache`·`initBinderCache`·`modelAttributeCache`, argument-resolver 캐시, `ExceptionHandlerExceptionResolver` 캐시, 등록한 `@ControllerAdvice` 캐시)를 컨트롤러 Class 키로 evict 한다. 매핑만 해제하면 캐시가 ClassLoader 를 붙잡아 하드 누수가 난다.
- **모듈이 해야 할 일**:
  - 관리형 실행기 `ProteanTaskExecutor`(per-module·데몬·bounded)를 주입받아 async/scheduled 작업을 돌린다. raw `new Thread` 대신 이걸 쓰면 언로드 시 child 컨텍스트 close 로 `close()`(→ `shutdownNow`) 되어 스레드·잡이 정리된다. 죽은 ClassLoader 를 물고 있는 공유 스레드 누수를 막는다.
  - child 컨텍스트가 못 닿는 컨텍스트 밖 자원(공유 스레드의 ThreadLocal, static 캐시 등록, JMX MBean, 커스텀 클라이언트)은 `ModuleUnloadCallback` 빈으로 스스로 청소한다. 플랫폼이 child 컨텍스트 close 직전에 호출한다.

## 빌드 / 발행 운영

이 프로젝트는 Gradle 로 빌드한다. IDE 통합 실행이 아니라 gradle 태스크를 직접 호출한다.

### 산출물

`build/libs` 에 두 종류가 나온다.

- **plain jar**(classifier 없음, `protean-<ver>.jar`): 다른 프로젝트가 의존성으로 쓰는 일반 라이브러리 레이아웃(`BOOT-INF` 없음). 소비자가 의존하는 산출물.
- **bootJar**(`-boot` classifier, `protean-<ver>-boot.jar`): embed 워커가 explode 해 쓰는 실행형 fat jar(`BOOT-INF/classes,lib`). `main()` 이 둘이라 `springBoot.mainClass` 를 `ProteanApplication` 으로 고정해 둔다.

### bootJar 와 test 는 분리 실행(필수)

`test` 태스크는 누수 카나리에서 soft reference 를 강제로 비우려 힙을 `maxHeapSize=512m` 로 제한한다. `bootJar`(fat jar 조립, 메모리 압박)와 `test` 를 한 gradle 호출에 묶으면 `LeakDiagnosisTest` 등이 collateral OOM 을 낼 수 있다. **반드시 분리 호출한다.**

```bash
# 나쁜 예 — 결합하면 OOM 위험
./gradlew clean bootJar test

# 좋은 예 — 분리
./gradlew clean test
./gradlew bootJar
```

### 발행

```bash
./gradlew publishToMavenLocal   # ~/.m2 로 발행(POM/소비성 검증용)
```

- 발행 publication 은 `components.java`(plain jar) + sources jar + javadoc jar + 생성 POM.
- JDBC 드라이버(`mysql-connector-j`, `postgresql`)는 protean 자기 bootJar·테스트엔 필요하지만 소비자에게 transitive 로 강제하지 않으려고 발행 POM 에서 `optional=true` 로 표시된다. 소비자가 worker DB 프로비저닝을 쓰면 자기 드라이버를 명시 추가해야 한다.
- 원격 registry 는 **GitHub Packages**(이관 대상)로, URL·인증을 `build.gradle` 에 하드코딩하지 않고 `gradle.properties`/환경변수로 외부화한다. `githubOwner`/`githubRepo`/`githubActor`/`githubToken`(또는 `GITHUB_OWNER`/`GITHUB_REPO`/`GITHUB_ACTOR`/`GITHUB_TOKEN` env)이 **모두 주어졌을 때만** 원격 repository 가 등록되고(없으면 mavenLocal 만), 그때 `publishLibraryPublicationToGitHubPackagesRepository` 태스크로 발행한다. 자격 템플릿은 `gradle.properties.example` 참고.

## 관련 문서

- [03. 설정 레퍼런스](03-configuration.ko.md)
- [04. REST API 레퍼런스](04-rest-api.ko.md)
- [05. 격리 모드](05-isolation-modes.ko.md)
- [07. 데이터 접근](07-data-access.ko.md)
- [13. 트러블슈팅](13-troubleshooting.ko.md)
- [README](../../README.ko.md)
