[English](05-isolation-modes.md) | **한국어**

# 05. 격리 모드

모듈을 어디서 어떻게 실행할지 고르는 축이다. Protean 은 `IsolationStrategy` SPI 뒤에 세 모드를 제공한다 — 같은 JVM(`in-process`), 별도 JVM 워커(`worker`), Docker 컨테이너 워커(`container`). 격리가 강해질수록 blast-radius 는 줄고 기동 비용·운영 요건은 커진다.

## 모드 비교·선택 기준

| 축 | `in-process` | `worker` | `container` |
| --- | --- | --- | --- |
| 실행 위치 | 메인 JVM(전용 ClassLoader + child context) | 별도 JVM 프로세스 | Docker 컨테이너 |
| 격리 강도 | 약(ClassLoader) | 강(프로세스) | 최강(OS/cgroup) |
| 공유 빈 직접 접근 | O | X(RPC 브리지로만) | X |
| 크래시 격리 | X(메인 운명 공유) | O | O |
| 기동 비용 | 최저 | JVM 기동 | 이미지+컨테이너 |
| 외부 요건 | 없음 | 없음 | Docker 데몬 |
| 전략 클래스 | `InProcessIsolation` | `WorkerProcessIsolation` | `ContainerWorkerIsolation` |

선택 기준:
- 신뢰된 사내 모듈, 공유 서비스 빈을 직접 DI 로 써야 함 → `in-process`.
- 크래시·메모리 폭주가 메인을 죽이면 안 되는 미검증 모듈 → `worker`.
- 미신뢰(멀티테넌트/외부 제출) 코드 — 호스트 파일·네트워크·시스템콜까지 가둬야 함 → `container`.

각 전략은 모듈 능력 양립성을 `supports(ModuleDescriptor)` 로 미리 판정해, 양립 불가 조합(예: 공유 빈이 필요한데 브리지 꺼진 워커)은 배포 시점에 fail-fast 시킨다.

## 모드 지정: 전역 vs 모듈별

전역 기본은 `protean.isolation.mode` 로 정한다(미설정 시 `in-process`).

```yaml
protean:
  isolation:
    mode: worker   # in-process | worker | container
```

개별 모듈은 `ModuleDescriptor.isolationMode` 로 전역 기본을 덮어쓸 수 있다(`"in-process"` | `"worker"` | `"container"`, `null` 이면 전역 기본을 따른다).

```java
new ModuleDescriptor(
    "orders", "1.0.0",
    ModuleDescriptor.TrustTier.TRUSTED,
    ModuleDescriptor.DesiredState.ACTIVE,
    "com.acme.OrdersController",
    List.of("com.acme.OrdersController"),
    sources, tests,
    /* needsSharedBeans */ false,
    /* verification    */ null,
    /* isolationMode   */ "worker");   // 이 모듈만 워커로
```

## in-process (기본)

같은 JVM 안에서 전용 `ModuleClassLoader` + child `ApplicationContext`(parent = 루트) 로 모듈을 실행한다. child context 가 루트를 부모로 삼으므로 공유 in-process 빈을 그대로 주입받는다 — `supports()` 가 항상 `true` 라 모든 모듈을 받는다.

- 리소스 live-reload 지원: `reloadResources()` 가 컴파일·컨텍스트 재빌드 없이 리소스 바이트만 제자리 교체한다(다른 모드는 미지원 → 전체 update 로 폴백).
- 셋업이 필요 없다(기본값). 별도 프로세스/이미지가 없다.

## worker (별도 JVM)

`WorkerProcessIsolation` 이 별도 JVM 을 띄워 그 안에서 모듈을 `in-process` 로 실행하고, 메인은 `ReverseProxy` 로 그 워커 포트에 포워딩한다. 워커가 죽어도 메인은 502 를 낼 뿐 살아있다.

### 워커 기동·핸드셰이크

1. 메인이 `WorkerRuntimeProvider.processLaunchPrefix()` 로 JVM 실행 명령을 만들고 `--spring.profiles.active=worker --protean.isolation.mode=in-process --server.port=0` 을 덧붙여 프로세스를 띄운다.
2. 워커는 기동 후 실제 바인딩 포트를 stdout 에 `WORKER_PORT=<port>` 한 줄로 출력한다(`WorkerPortAnnouncer`). 메인이 이 마커를 파싱해 포트를 얻는다(핸드셰이크).
3. 메인이 `GET /__admin/health` 로 준비를 기다린 뒤 `POST /__admin/deploy` 로 디스크립터를 보낸다. 워커(`WorkerAdminController`)가 자기 안에서 소스를 컴파일·서빙하고 등록된 경로 목록을 반환하면, 메인이 그 경로를 프록시에 등록한다.

### 워커 풀

한 워커가 여러 모듈을 호스팅해 JVM 수·기동 비용을 줄인다.

```yaml
protean:
  worker:
    modules-per-worker: 4   # 워커당 최대 모듈 수(1 = 모듈당 전용 JVM=완전 격리). 기본 4
    min-warm: 0             # 빈 워커를 따뜻하게 유지할 수(재사용). 기본 0
```

`modules-per-worker=1` 이면 모듈마다 전용 JVM 이라 완전 격리되지만 JVM 수가 늘고, `>1` 이면 같은 워커 내 모듈은 크래시 시 운명을 공유한다. 빈 워커는 `min-warm` 개까지 유지해 재사용하고 초과분은 정리한다.

### 감독(auto-restart)

```yaml
protean:
  worker:
    auto-restart: true   # 기본 false
```

켜면 워커의 예기치 않은 종료(크래시)를 `Process.onExit()` 로 감지해, 그 워커가 호스팅하던 모듈을 새 워커에 재배포하고 프록시를 새 포트로 `repoint` 한다. 의도적 종료(retire/hot-swap 드레이닝)는 크래시로 오인하지 않는다.

### RPC 브리지(공유 빈 호출)

워커는 메인의 공유 빈에 직접 접근할 수 없다. 필요하면 브리지를 켠다.

```yaml
protean:
  worker:
    rpc-bridge: true   # 기본 false
```

켜지면 `supports()` 가 `needsSharedBeans` 모듈도 허용하고, 워커에 `--protean.worker.rpc-bridge=true` 와 `--protean.bridge.url=http://localhost:<메인 포트>` 를 주입한다. 워커 측 `WorkerBridgeRegistrar` 가 `ModuleDescriptor.bridgedInterfaces` 에 선언된 인터페이스마다 동적 프록시 빈을 워커 루트 컨텍스트에 등록하고, 모듈이 그 타입을 주입받아 호출하면 메인 `BridgeController`(`/__bridge/invoke`)가 실제 빈을 리플렉션으로 실행한다. 복합 DTO·제네릭 컬렉션 인자/반환과 비즈니스 예외 전파(같은 타입 재구성)를 지원하며, 메인 빈이 `@Transactional` 프록시면 호출이 메인 트랜잭션 경계 안에서 실행된다. 반환 타입이 `InputStream` 이면 JSON 봉투에 통째로 담지 않고 `application/octet-stream` 으로 청크 스트리밍하며(메인이 지연 생성 가능), 워커는 지연 소비되는 스트림으로 받는다 — 대용량 반환에 메모리 버퍼링을 피한다. 자세한 인터페이스 선언·주입은 [10. SPI 확장](10-spi-extension.ko.md)을 참고.

### 워커 DB

기본은 워커마다 자체 H2(별도 JVM = DB 격리). 수동 전역 스코프를 주려면:

```yaml
protean:
  worker:
    datasource:
      url: jdbc:mysql://db:3306/app
```

모듈당 전용 격리 DB 를 자동 프로비저닝하려면 `protean.worker.db.auto-provision=true`. 이때는 격리 보장을 위해 워커당 1모듈(`capacity=1`)·워밍 재사용 없음(`min-warm=0`)으로 강제되며, 프로비저닝된 스코프 creds 로 전용 워커를 띄운다. 벤더별 프로비저닝은 [07. 데이터 접근](07-data-access.ko.md), dialect 확장은 [10. SPI 확장](10-spi-extension.ko.md).

### 격리 모드 간 타입 공유(LIBRARY 모듈)

`LIBRARY` 모듈의 타입 공유(`uses`/`exports`, [02. 모듈 작성 §8](02-module-authoring.ko.md) 참고)는 in-process 뿐 아니라 세 격리 모드 모두에서 동작한다. 워커는 그 자체가 Protean 앱이다: 메인이 의존 모듈의 `uses` 폐포(라이브러리 descriptor 와 소스)를 워커에 push 하면, 워커는 그 라이브러리들을 **자기 `SharedModuleRegistry` 에 독립적으로 컴파일·발행**하고 의존 모듈을 그에 링크한다 — 바이트를 중계하는 게 아니라 공유 타입 정체성을 로컬에서 재도출한다. (`SharedModuleRegistry` 가 `worker` 를 포함한 모든 프로파일에 존재하는 이유가 바로 이것이다; eager 전파 빈 `SharedModuleInvalidator`/`SharedModuleUsageIndex` 는 메인 전용이다 — 메인이 워커 rebind 를 구동하므로.)

라이브러리 업데이트 시 메인 쪽 `WorkerSharedModulePropagator` 가 반응해(스토어 커밋 이후라 새 소스가 준비됨), 라이브러리를 호스팅하는 각 워커에 `POST /__admin/redeploy` 로 그 라이브러리를 재발행하고, 이어서 그것을 transitively `uses` 하는 같은 워커의 각 의존 모듈에 `POST /__admin/redeploy`(새 generation 에 재컴파일)한다. 이는 범용 모듈 redeploy 엔드포인트를 재사용한다 — 자체 `POST /__admin/shared-libs` push 를 갖는 네이티브 shared-lib jar([07. 데이터 접근](07-data-access.ko.md) 참고)와 다르다. `protean.module.eager-shared-module-invalidation`(기본 `true`)로 제어된다. 컨테이너 트랙도 같은 `WorkerParentTierTarget` 계약으로 동일 프로토콜을 따른다.

## container (Docker 컨테이너 워커)

`ContainerWorkerIsolation` 이 워커를 Docker 컨테이너로 띄워 cgroup·read-only·cap-drop·seccomp 로 가둔다. 워커 프로세스만으론 못 막는 호스트 자원·파일·시스템콜 침해를 OS 레벨에서 차단하는 미신뢰 tier 기준선이다. 풀은 두지 않는다 — "모듈당 1컨테이너"가 OS 격리의 본질이라 패킹은 격리를 약화한다. RPC 브리지는 미지원(`supports()` 가 `needsSharedBeans` 모듈을 거부).

### 요건

- Docker 데몬이 설치·실행 중이어야 한다(없으면 명령 실패로 fail-fast).
- embed 런타임(기본)은 호스트 bootJar 를 exploded 레이아웃으로 풀어 read-only 마운트하므로 **`gradle bootJar` 를 먼저 실행**해 `build/libs/*-boot.jar` 이 있어야 한다(명시 경로는 `protean.worker.container.jar`).

### 하드닝 설정

```yaml
protean:
  worker:
    container:
      image: eclipse-temurin:21-jdk   # 기본
      memory: 256m                     # cgroup 메모리 cap. 기본 256m
      pids-limit: 512                  # fork-bomb 방어 PID 한도. 기본 512
      network: ""                      # egress 격리용 네트워크(예: internal). 비면 docker 기본
      seccomp: ""                      # 프로파일 경로 | "bundled" | 비면 docker 기본
      auto-restart: false              # 컨테이너 크래시 감지 → 재배포
      db-host: host.docker.internal    # 컨테이너에서 호스트 DB 로 닿을 호스트명 재작성 대상
```

컨테이너는 다음 옵션이 항상 적용된다: `--memory` + `--read-only` rootfs + `--tmpfs /tmp` + `--cap-drop=ALL` + `--security-opt no-new-privileges` + `--pids-limit`. `network` 가 비지 않으면 `--network`, `seccomp` 가 비지 않으면 `--security-opt seccomp=<프로파일>` 을 추가한다.

`seccomp: bundled` 로 두면 클래스패스의 번들 기본 프로파일(`/seccomp/protean-default.json`, defaultAction=ALLOW + 위험 syscall EPERM 거부)을 임시 파일로 추출해 적용한다. 그 외 값은 사용자 프로파일 파일 경로로 통과시킨다.

컨테이너는 내부 `8080` 포트를 `-p 0:8080` 로 랜덤 호스트 포트에 published 하고, 메인이 `docker port` 로 호스트 포트를 찾아 health(`/__admin/health`)를 기다린 뒤 배포한다. `db-host` 는 컨테이너 안 `localhost` 가 자기 자신이 되는 문제를 풀기 위해 JDBC URL 의 `localhost`/`127.0.0.1` 호스트를 재작성하는 대상이다(Docker Desktop = `host.docker.internal`).

## 워커 런타임: embed vs sidecar

`worker`/`container` 두 트랙 공통으로, "워커 JVM 을 무엇으로 띄우나"는 `WorkerRuntimeProvider` SPI 가 결정한다. `protean.worker.runtime` 으로 고른다.

```yaml
protean:
  worker:
    runtime: embed   # embed(기본) | sidecar
```

- **embed**(`EmbeddedWorkerRuntime`, 기본): 워커 = 호스트 아티팩트. process 트랙은 호스트 classpath(`java.class.path`), container 트랙은 호스트 bootJar explode 를 재실행한다. 워커가 소스를 런타임 컴파일하므로 클래스패스 패리티가 공짜인 것이 핵심 이점이다.
- **sidecar**(`SidecarWorkerRuntime`, 옵트인): 워커 = Protean 이 발행한 전용 슬림 jar/이미지. 격리·공격면 최소화에 유리하나 모듈이 참조하는 공유 타입을 shared-api jar 로 별도 주입해야 한다.

```yaml
protean:
  worker:
    runtime: sidecar
    sidecar:
      jar: /opt/protean/worker.jar          # process 트랙
      image: registry/protean-worker:1.0     # container 트랙
      shared-api: /opt/protean/shared-api.jar # 워커 컴파일용 공유 타입
```

미설정 시 명확한 오류로 fail-fast 한다(process 는 `sidecar.jar`, container 는 `sidecar.image` 필요). 커스텀 런타임 제공자를 직접 꽂는 법은 [10. SPI 확장](10-spi-extension.ko.md).

## 무중단 hot-swap

세 모드 모두 hot-swap 은 새 인스턴스를 완전히 준비한 뒤 프록시 라우트를 **원자적으로** 새 타깃으로 전환한다 — 404/502 윈도우가 없다. `ReverseProxy.repoint(path, port)` 가 매핑 unregister/register 없이 타깃 포트만 단일 교체하기 때문이다.

- `in-process`: child context 를 새 ClassLoader 로 재빌드해 원자 swap.
- `worker`: 이 모듈을 안 가진 워커에 새 버전을 배포 → 각 경로를 `repoint` → 옛 워커를 드레이닝 후 정리(v2 준비 실패 시 옛 워커 유지 = 롤백).
- `container`: 새 컨테이너를 health+배포까지 완전 기동 → `repoint` → 옛 컨테이너 retire.

## 관련 문서

- [02. 모듈 작성](02-module-authoring.ko.md)
- [03. 설정 레퍼런스](03-configuration.ko.md)
- [07. 데이터 접근](07-data-access.ko.md)
- [10. SPI 확장](10-spi-extension.ko.md)
- [12. 보안](12-security.ko.md)
- [README](../../README.ko.md)
