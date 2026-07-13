# Protean Quickstart 예제

하나의 소비자 앱을 **환경설정(Spring 프로필)** 으로 세 시나리오로 구동하는 예제다.
Protean 을 `project(':')` 로 소비하므로 발행·mavenLocal 없이 현재 소스로 바로 빌드된다.

> 모듈을 런타임에 `javac` 로 컴파일하므로 **JDK 21**(JRE 아님)로 실행해야 한다.

## 세 가지 실행 모드

| 프로필 | 시나리오 | 자동 배포 | 확인 |
|--------|----------|-----------|------|
| (없음) | in-process 데이터 접근 | 데이터 접근 모듈 | `GET /items/add` |
| `worker-demo` | 워커 격리(별도 JVM) | 계산 모듈 | `GET /compute/square` |
| `mcp` | MCP 배포 입구 | 없음(에이전트가 배포) | `POST /platform/mcp` |

> 워커 격리는 프로필이 아니라 `protean.isolation.mode=worker` **프로퍼티**로 켠다. 예제 프로필을
> `worker` 가 아니라 `worker-demo` 로 둔 이유는 `worker` 가 Protean 이 spawn 하는 워커 JVM 표시용
> **예약 Spring 프로필**이기 때문이다(메인에서 켜면 오케스트레이션 빈이 사라진다).

### ① in-process 데이터 접근 (기본)

```bash
gradle :examples:quickstart:bootRun
```

기동 시 데이터 접근 모듈이 in-process 로 배포된다. H2(인메모리)에 행을 넣고 개수를 돌려준다:

```bash
curl 'localhost:8080/items/add?name=widget'
# → items=1   (호출할 때마다 1씩 증가)

# 관리 REST 로 배포 상태 확인
curl localhost:8080/platform/modules
```

### ② 워커 격리 (별도 JVM)

```bash
gradle :examples:quickstart:bootRun --args='--spring.profiles.active=worker-demo'
```

계산 모듈이 **별도 JVM 워커**에서 서빙된다(메인이 워커를 spawn → `ReverseProxy` 로 포워딩).
호출부는 in-process 와 동일하다:

```bash
curl 'localhost:8080/compute/square?n=7'
# → square=49
```

### ③ MCP 배포 입구

```bash
gradle :examples:quickstart:bootRun --args='--spring.profiles.active=mcp'
```

MCP 어댑터가 켜지고(`POST /platform/mcp`) 에이전트가 모듈을 배포할 수 있다. 자동 배포는 없다.
먼저 툴 목록 확인:

```bash
curl -s localhost:8080/platform/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

**모듈 배포 실호출** — `deploy_module` 은 `files[]`(source/test/resource)로 소스를 실어 보낸다.
FQCN 은 package+파일명에서 자동 도출된다. 아래를 `deploy.json` 으로 저장:

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
  "name":"protean.deploy_module",
  "arguments":{
    "id":"mcp-hello","version":"1","controller":"gen.HelloController",
    "files":[
      {"kind":"source","filename":"HelloController.java","content":"package gen;\nimport org.springframework.web.bind.annotation.GetMapping;\nimport org.springframework.web.bind.annotation.RequestParam;\nimport org.springframework.web.bind.annotation.RestController;\n@RestController\npublic class HelloController {\n  public static String up(String s){ return s.toUpperCase(); }\n  @GetMapping(\"/mcp/hello\")\n  public String hello(@RequestParam(defaultValue=\"world\") String name){ return \"hello \"+up(name); }\n}\n"},
      {"kind":"test","filename":"HelloTest.java","content":"package gen;\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\nclass HelloTest {\n  @Test void up_uppercases(){ assertEquals(\"HI\", HelloController.up(\"hi\")); }\n}\n"}
    ]
  }
}}
```

배포하고(게이트 ①테스트·②리뷰 통과) 곧바로 호출:

```bash
curl -s localhost:8080/platform/mcp -H 'Content-Type: application/json' -d @deploy.json
# → ... "모듈 mcp-hello 배포됨(ACTIVE)" ...

curl 'localhost:8080/mcp/hello?name=abc'
# → hello ABC
```

툴 카탈로그·입력 형식 상세는 [08-mcp-integration](../../docs/guide/08-mcp-integration.ko.md) 참고.

### ④ 조합 — 런타임 프로퍼티 오버라이드

프로필에 `--<protean.*>` 프로퍼티를 덧붙여 시나리오를 조합한다. 격리는 **프로퍼티**로 켠다
(Spring 프로필 `worker` 는 Protean 예약 프로필이라 메인 앱에서 켜면 안 됨 — 위 주의 참고).

**MCP + 워커 격리** — MCP 로 배포한 모듈이 별도 JVM 워커에서 격리 실행:

```bash
gradle :examples:quickstart:bootRun \
  --args='--spring.profiles.active=mcp --protean.isolation.mode=worker'
```

배포 후 `list_modules` 의 `mode` 가 `worker` 로 뜨고, 호출부는 in-process 와 동일하다
(메인이 `ReverseProxy` 로 워커 포트에 포워딩). 워커 spawn 때문에 첫 호출까지 수 초 걸린다.

**MCP + 승인 게이트** — 배포가 `PENDING_APPROVAL` 로 멈추고 승인해야 서빙:

```bash
gradle :examples:quickstart:bootRun \
  --args='--spring.profiles.active=mcp --protean.gate.approval.required=true'
```

`deploy_module` → `PENDING_APPROVAL`(서빙 안 됨) → `approve_module {id, approver}` → `ACTIVE`(서빙),
또는 `reject_module {id, approver}` → 제거. (두 툴 모두 `id`·`approver` 필수)

### ⑤ 컨테이너(Docker) 격리 — OS 레벨 격리

모듈을 **Docker 컨테이너 워커**로 띄워 cgroup 메모리·read-only rootfs·`cap-drop=ALL`·`no-new-privileges`·
pids-limit 로 가둔다(강한 격리, 미신뢰 코드 기준선). `container` 프로필이 `protean.isolation.mode=container` 와
컨테이너 튜닝(`protean.worker.container.*`)을 켠다.

> **전제**
> 1. **Docker 데몬**이 실행 중이어야 한다(container 트랙의 유일한 외부 요건).
> 2. embed 런타임이 호스트 bootJar 를 exploded 로 풀어 컨테이너에 read-only 마운트하므로 **먼저 `gradle bootJar`** 를 실행한다.
> 3. container 트랙은 **self-contained 모듈만** 지원한다 — 호스트 `JdbcTemplate` 을 parent-first 로 받는
>    shared-bean 모듈(기본 데이터접근 모듈)은 RPC 브리지 미지원이라 못 띄운다. 그래서 계산/MCP 모듈로 데모한다.

`container` 프로필은 격리 모드만 켜므로, 실제 모듈을 배포하는 `worker-demo`(계산 모듈) 또는 `mcp` 프로필과
**조합**한다. 프로필을 뒤에 두면(`...,container`) `mode=container` 가 앞 프로필의 모드를 덮어쓴다.

**계산 모듈을 컨테이너로:**

```bash
gradle bootJar                                    # 컨테이너에 마운트할 exploded bootJar 준비(1회)
gradle :examples:quickstart:bootRun \
  --args='--spring.profiles.active=worker-demo,container'
```

배포되면 호스트에서 컨테이너가 뜬 것을 확인하고, 호출부는 in-process 와 동일하다(메인이 `ReverseProxy` 로
컨테이너 published 포트에 포워딩):

```bash
docker ps                                         # protean-worker-compute-... 컨테이너 확인
curl 'localhost:8080/compute/square?n=7'          # → square=49  (컨테이너 워커가 서빙)
```

**MCP 로 배포한 모듈을 컨테이너로** — `mcp,container` 로 띄운 뒤 위 ③의 `deploy_module` 로 self-contained
모듈(예: `mcp-hello`)을 배포하면 그 모듈이 전용 컨테이너에서 실행된다:

```bash
gradle bootJar
gradle :examples:quickstart:bootRun \
  --args='--spring.profiles.active=mcp,container'
# deploy.json(③) 배포 후:
docker ps                                         # protean-worker-mcp-hello-... 컨테이너 확인
curl 'localhost:8080/mcp/hello?name=abc'          # → hello ABC
```

기본 하드닝(`--read-only`·`--tmpfs /tmp`·`--cap-drop=ALL`·`no-new-privileges`·`--memory`·`--pids-limit`)은
항상 적용된다. egress 차단(`network`)·seccomp 프로파일(`seccomp: bundled`)은 `application-container.yml`
주석의 옵션으로 추가한다. 격리 트레이드오프 상세는 [05-isolation-modes](../../docs/guide/05-isolation-modes.ko.md) 참고.

### ⑥ MCP + 워커 격리 + Level 3 디버깅

MCP 배포 입구 + `debug.*` 인터랙티브 디버깅(JDI) + 워커 격리를 한 번에 켠 조합이다. `debug` 프로필이
`application-debug.yml` 로 셋을 함께 활성화한다.

```bash
gradle :examples:quickstart:runMcpDebug
# 또는
gradle :examples:quickstart:bootRun --args='--spring.profiles.active=debug'
```

> **활성 키 주의**: 디버그 표면은 **`protean.mcp.debug.enabled`(점)** 로 켠다. `protean.mcp.debug-enabled`
> (하이픈)는 `DebugMcpConfiguration` 의 `@ConditionalOnProperty` 와 매칭되지 않아 **debug 툴이 노출되지 않는다.**

`tools/list` 에 `debug.*` 툴(attach·set_breakpoint·await_stop·frames·get_variables·evaluate·step·continue·redefine·terminate)이 뜨는지로 확인한다:

```bash
curl -s localhost:8080/platform/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | grep -o 'debug\.[a-z_]*' | sort -u
```

전형적 흐름: `debug.launch`(모듈을 JDWP 워커로 배포+자동 attach) → `debug.set_breakpoint` →
`debug.await_stop` → `debug.frames`/`debug.get_variables`/`debug.evaluate` → `debug.step`/`debug.continue` →
`debug.terminate`. 유휴 세션은 `protean.mcp.debug.session-idle-timeout`(기본 30m) 초과 시 자동 회수된다.
디버깅 실습 상세는 [09-debugging](../../docs/guide/09-debugging.ko.md) 참고.

## 실행 명령 요약

각 시나리오는 이름 있는 gradle 태스크로 등록돼 있어(`build.gradle`) 긴 `--args` 없이 바로 실행할 수 있다.
아래 표의 태스크 이름을 쓰면 되고, 원시 `--args` 명령도 그대로 유효하다.

| 시나리오 | 태스크 | 동등한 원시 명령 |
|----------|--------|------------------|
| in-process 데이터 접근(기본) | `runInProcess` | `bootRun` |
| 워커 격리 + 자동배포 | `runWorkerDemo` | `bootRun --args='--spring.profiles.active=worker-demo'` |
| MCP 배포 입구 | `runMcp` | `bootRun --args='--spring.profiles.active=mcp'` |
| MCP + 워커 격리 | `runMcpWorker` | `bootRun --args='--spring.profiles.active=mcp --protean.isolation.mode=worker'` |
| MCP + 승인 게이트 | `runMcpApproval` | `bootRun --args='--spring.profiles.active=mcp --protean.gate.approval.required=true'` |
| MCP + 워커 격리 + 디버깅(Level 3) | `runMcpDebug` | `bootRun --args='--spring.profiles.active=debug'` |
| 컨테이너 격리(계산 모듈) · Docker 필요 | `runContainerCompute` | `bootJar && bootRun --args='--spring.profiles.active=worker-demo,container'` |
| 컨테이너 격리(MCP 배포) · Docker 필요 | `runContainerMcp` | `bootJar && bootRun --args='--spring.profiles.active=mcp,container'` |

> 태스크 이름 앞에는 프로젝트 경로를 붙인다: `gradle :examples:quickstart:runMcpWorker`.
> `run*` 은 모두 `bootRun` 의 짧은 별칭이고, 원시 명령 열의 `bootRun`/`bootJar` 도 같은 프로젝트 경로를 붙여 쓴다.
> container 태스크는 `bootJar` 를 자동으로 먼저 빌드하므로 별도 `gradle bootJar` 가 필요 없다.
> 여러 오버라이드를 직접 주려면 한 `--args` 안에 공백으로 나열한다(예: `--spring.profiles.active=mcp --protean.isolation.mode=worker --protean.gate.approval.required=true`).
> 서버가 뜬 뒤 멈추려면 `Ctrl-C`(포그라운드) 또는 `pkill -f QuickstartApplication`(+ 워커: `pkill -f ProteanWorkerLauncher`).

## 이 예제가 보여주는 것

- 라이브러리 소비자 앱에 Protean 이 **auto-configuration** 으로 자동 배선되는 것
- 모듈을 **소스 문자열**로 실어 `ModulePlatform.install` 로 배포(승격 게이트 ①테스트·②리뷰 통과)
- 같은 앱을 설정만으로 **in-process / worker / MCP** 로 전환
- 모듈이 호스트의 `JdbcTemplate` 을 parent-first 로 주입받아 데이터에 접근

## 관련 문서

- [사용자 가이드](../../docs/guide) — 01 시작하기 · 05 격리 모드 · 07 데이터 접근 · 08 MCP 연동
- [README](../../README.md) — 프로젝트 개요
