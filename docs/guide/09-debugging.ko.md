[English](09-debugging.md) | **한국어**

# 09. 디버깅

Protean 은 배포된 모듈을 실행 중인 상태에서 인터랙티브하게 디버깅하는 Level 3 표면(`debug.*` MCP 툴)을 제공한다. 브레이크포인트·스텝·스택/변수 조회·표현식 평가·fix-and-continue(재정의)를 JDI(Java Debug Interface)로 수행한다. 이 문서는 라이브러리 소비자가 디버그 표면을 켜고 세션을 운용하는 절차를 다룬다.

## 디버그 표면 켜기 (실행 게이트)

디버깅은 배포보다 위험(`redefine`·`evaluate` = 사실상 임의 코드 실행)하므로 MCP 어댑터([08. MCP 연동](08-mcp-integration.ko.md))와 **별도 옵트인**이다. `protean.mcp.enabled=true` 면 debug 툴은 **항상 `tools/list` 에 노출**되지만, 실제 **실행**은 `protean.mcp.debug.enabled`(기본 `false`) 로 게이트된다:

```properties
protean.mcp.enabled=true         # MCP 디스패처(필수)
protean.mcp.debug.enabled=true   # debug.* 실행 게이트(기본 false = prod 태세)
```

- **게이트 OFF(기본, prod)**: debug 툴은 목록엔 있으나 호출하면 `isError`("debug surface disabled")로 **즉시 거부** — JDWP 워커 spawn 등 부수효과가 전혀 없다.
- **게이트 ON(dev)**: 호출 허용.
- **런타임 flip**: 이 게이트는 `DebugSurfaceState` 빈으로 **재기동 없이** 열고 닫을 수 있다(소비자가 자기 인가된 관리 경로에서 `setEnabled(true/false)`). 툴은 이미 목록에 있으므로 클라가 다시 받을 것이 없다 — **재연결 불필요**.
- `protean.mcp.enabled` 를 끄면 디스패처 자체가 없어 어떤 툴도 노출되지 않는다. 디버그 표면은 `!worker` 프로파일에서만 활성.

**2중 방어**: 실행 게이트(`debug.enabled`) + 모든 `debug.*` 호출의 `ModuleAction.DEBUG` 를 판정하는 `ModuleActionAuthorizer`(인가 SPI, [08](08-mcp-integration.ko.md)). prod 은 둘 중 하나로 막힌다.

## zero-dep — JDI

디버그 코어(`DebugCore`)는 JDK 표준 모듈 `jdk.jdi` 만 쓴다(별도 의존 없음). JDWP 를 켠 대상 JVM 에 `com.sun.jdi.SocketAttach` 커넥터로 소켓 attach 한다. 따라서 서버는 JRE 가 아니라 `jdk.jdi` 를 포함한 **JDK** 로 실행돼야 한다(커넥터가 없으면 attach 시 오류).

코어는 프로토콜 비종속이라 MCP 는 그 위의 얇은 세션 어댑터일 뿐이다.

## 디버그 툴 카탈로그

| 툴 이름 | 입력 | 용도 |
|---|---|---|
| `debug.launch` | `files[]` 또는 `manifest`, `id`·`version`·`controller`·`isolationMode` | 모듈을 JDWP 켠 전용 디버그 워커로 (재)배포하고 자동 attach 해 세션을 연다 |
| `debug.attach` | `host`(필수)·`port`(필수) | 이미 JDWP 로 뜬 대상 JVM 에 attach 해 세션을 연다 |
| `debug.set_breakpoint` | `sessionId`·`className`·`line` | `className:line` 에 브레이크포인트 설정 |
| `debug.await_stop` | `sessionId`·`timeoutMs`(기본 10000) | 다음 정지(브레이크포인트/스텝)를 기다려 정지 위치 반환 |
| `debug.frames` | `sessionId` | 정지된 스레드의 스택 프레임 반환 |
| `debug.get_variables` | `sessionId`·`frame`(기본 0) | 프레임의 로컬 변수(이름→값). `-g` 컴파일 필요 |
| `debug.evaluate` | `sessionId`·`expr`(필수)·`frame`(기본 0) | 정지 프레임에서 표현식 평가 |
| `debug.step` | `sessionId`·`depth`(`over`\|`into`\|`out`, 기본 `over`) | 한 스텝 실행 후 다음 라인에서 정지 |
| `debug.continue` | `sessionId` | 정지된 스레드 재개 |
| `debug.redefine` | `sessionId`·`files[]`(필수) | fix-and-continue: 고친 소스를 재컴파일해 로드된 클래스를 in-place 교체 |
| `debug.terminate` | `sessionId` | 세션 종료(대상 VM 디태치) |
| `debug.list_sessions` | (없음) | 활성 디버그 세션 목록(`sessionId`·`vmName`·`owner`·`idleMs`·`paused`·`lastStop`). 재접속·재발견용 |

`debug.launch`/`debug.attach` 는 응답에 `sessionId` 를 담아 반환한다. 이후 모든 툴은 이 `sessionId` 를 넘긴다. `debug.launch` 는 추가로 `moduleId`·`workerPort`·`jdwpPort`·`paths` 를 반환한다.

## 워크플로

### debug.launch — 원스텝 (권장)

`debug.launch` 는 대상 모듈을 JDWP 를 켠 **전용 디버그 워커**로 (재)배포하고 자동 attach 한다. 입력은 `protean.deploy_module` 과 동일한 `files[]`/`manifest` 형식이다(`ModuleInputNormalizer` 재사용). 세션 종료(`debug.terminate`) 시 워커 JVM 이 kill 되고 라우트가 일반 배포로 원복된다.

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call",
 "params":{"name":"debug.launch","arguments":{
   "id":"orders","version":"1.0.0",
   "controller":"com.acme.orders.OrderController",
   "files":[{"kind":"source","filename":"OrderController.java",
             "content":"package com.acme.orders; ... @RestController ..."}]
 }}}
```

응답의 `sessionId`(예: `dbg-1`)를 이후 호출에 사용한다.

### debug.attach — 외부 JVM

이미 JDWP(`-agentlib:jdwp=...,server=y,address=<port>`)로 떠 있는 JVM 에 붙을 때 사용한다.

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call",
 "params":{"name":"debug.attach","arguments":{"host":"127.0.0.1","port":5005}}}
```

### 실습 시나리오 — 브레이크포인트 → 조사 → 재개

`debug.launch` 로 세션 `dbg-1` 을 연 뒤:

```json
// 1) 브레이크포인트
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
  "name":"debug.set_breakpoint",
  "arguments":{"sessionId":"dbg-1","className":"com.acme.orders.OrderController","line":42}}}

// 2) 해당 엔드포인트를 HTTP 로 한 번 호출한 뒤, 정지를 대기
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
  "name":"debug.await_stop","arguments":{"sessionId":"dbg-1","timeoutMs":15000}}}
// → {"stopped":true,"className":"...OrderController","method":"create","line":42}

// 3) 스택과 로컬 변수 조사
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
  "name":"debug.frames","arguments":{"sessionId":"dbg-1"}}}
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{
  "name":"debug.get_variables","arguments":{"sessionId":"dbg-1","frame":0}}}

// 4) 표현식 평가
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{
  "name":"debug.evaluate","arguments":{"sessionId":"dbg-1","expr":"order.getItems().size()"}}}

// 5) 스텝 / 재개
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{
  "name":"debug.step","arguments":{"sessionId":"dbg-1","depth":"over"}}}
{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{
  "name":"debug.continue","arguments":{"sessionId":"dbg-1"}}}

// 6) 종료 (launch 로 띄운 워커도 함께 kill + 라우트 원복)
{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{
  "name":"debug.terminate","arguments":{"sessionId":"dbg-1"}}}
```

정지 상태에서만 `frames`/`get_variables`/`evaluate` 가 의미 있으므로, 스텝/재개로 흐름을 진행할 때는 다음 정지를 `debug.await_stop` 으로 기다린다.

### debug.evaluate — 지원 문법 범위

`debug.evaluate` 는 zero-dep 핸드롤 표현식 평가기다. 라이브러리라 소비자가 사용 시점에 확장 못 하므로 Java 표현식 문법을 최대한 완전히 지원한다.

**지원**: 식별자(로컬/`this` 필드)·리터럴·`this`·필드/게터/인덱싱·타입 인식 오버로드/생성자 해석(widening·autoboxing·상위타입·most-specific)·산술/비교/논리(단축평가)·비트/시프트·단항(`- ! ~`)·삼항(`?:`)·문자열 `+` 연결·프리미티브 및 참조타입 캐스트(FQCN)·`instanceof`·`new`·FQCN static 참조·대입(`= += …`, 로컬/필드/배열/static lvalue)·**람다·메서드 레퍼런스**(합성 클래스를 대상 VM 에 주입해 실 `stream()` 등에 전달).

**제약**:
- 람다·메서드 레퍼런스는 **함수형 인터페이스 인자 위치에서만** materialize 된다(예: `stream().filter(...)`). 단독으로는 평가할 수 없다.
- 람다 파라미터는 **타입을 명시**해야 한다 — `(java.lang.String s) -> ...` (형 추론 미지원).
- static/캐스트/`instanceof` 의 타입명은 로드된 **FQCN** 이어야 한다.

```json
// 예시들
{"expr":"user.getName()"}
{"expr":"order.items[0].price * 2"}
{"expr":"list.size() > 0 ? \"has\" : \"empty\""}
{"expr":"new java.math.BigDecimal(\"1.5\").add(total)"}
{"expr":"obj instanceof com.acme.orders.Order"}
// 람다 — 파라미터 타입 명시
{"expr":"items.stream().filter((java.lang.String s) -> s.length() > 0).count()"}
// 메서드 레퍼런스 — unbound/bound/static/생성자
{"expr":"items.stream().map(java.lang.String::length).count()"}
```

평가 오류는 tool result `isError` 로 반환된다.

### debug.redefine — fix-and-continue

`debug.redefine` 는 고친 소스를 재컴파일해 실행 중인 클래스를 in-place 교체한다(**메서드 본문만**; 스키마 변경은 JVM 제약으로 불가). 세션과 정지 상태는 유지된다. 입력 `files[]` 는 `{filename, content}` 배열이고, FQCN 은 파일명·내용에서 자동 도출된다.

```json
{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{
  "name":"debug.redefine","arguments":{"sessionId":"dbg-1","files":[
    {"filename":"OrderController.java",
     "content":"package com.acme.orders; ... // 고친 메서드 본문 ..."}]}}}
```

미로드/미지원(예: 메서드 시그니처·필드 변경) 클래스는 예외가 나 `isError` 로 매핑된다.

## `RuntimeCompiler` 의 `-g` — 왜 필요한가

Protean 의 런타임 컴파일러는 모듈 소스를 항상 `-g`(전체 디버그 정보: line + vars + source) 옵션으로 컴파일한다. 이 정보가 있어야 인터랙티브 디버깅에서:

- 브레이크포인트의 **라인 매핑**이 정확히 동작하고,
- `debug.get_variables` 가 **로컬 변수명**을 조회할 수 있다.

클래스 파일이 약간 커질 뿐 런타임 성능 영향은 없다. 즉 별도 디버그 빌드 없이 배포된 모듈을 그대로 디버깅할 수 있다.

## 브레이크포인트 정지와 요청 타임아웃

브레이크포인트에서 멈추면 그 요청을 트리거한 스레드가 정지(JDI suspend)한 채 무기한 대기한다. 이로 인해:

- **트리거 HTTP 클라이언트가 타임아웃될 수 있다.** 정지 중엔 트리거 요청의 응답이 오지 않으므로, 짧은 클라이언트 타임아웃(예: `curl --max-time`)은 끊긴다. **이는 무해하다** — 브레이크포인트 조사는 트리거 커넥션이 아니라 별도 MCP 채널(`POST /platform/mcp`)로 하기 때문이다. 트리거의 최종 응답 바디가 필요하면 클라이언트 타임아웃을 넉넉히(또는 무제한) 둔다.
- **모듈 실행 워치독은 debug 경로에 적용되지 않는다.** `protean.module.request-timeout-ms` 를 켜뒀더라도, `debug.launch` 세션이 서빙 중인 경로는 실행 타임아웃에서 예외 처리된다(인터랙티브 디버깅과 요청 타임아웃은 상호배타적). 일반(비-debug) 경로는 그대로 타임아웃 안전망이 적용된다.
- **세션은 트리거 요청과 분리돼 있다.** 클라이언트가 끊겨도 세션은 살아 있다. 세션 수명은 `debug.terminate` 또는 idle 자동 회수로만 끝난다.

## 세션 재접속 · 재발견

세션은 `sessionId` 로만 식별되고 특정 클라이언트 연결에 묶이지 않는다. 따라서 클라이언트가 끊겼다 **다시 연결해도 같은 `sessionId` 로 그대로 재접속**된다(별도 핸드셰이크 불필요 — `debug.frames`/`await_stop`/`continue` 를 그 id 로 호출하면 된다). `sessionId` 를 잃었으면 `debug.list_sessions` 로 활성 세션을 조회해 재발견한다:

```json
{"jsonrpc":"2.0","id":20,"method":"tools/call","params":{
  "name":"debug.list_sessions","arguments":{}}}
```

응답 `structuredContent.sessions[]` 의 각 항목은 `sessionId`·`vmName`·`owner`·`idleMs`·`paused`·`lastStop`(마지막 정지 위치)를 담는다. (현재는 모든 세션을 반환한다. 사용자별 스코핑은 후속 계획이다.)

## 세션 idle 자동 회수

에이전트가 `debug.terminate` 를 잊으면 `debug.launch` 로 띄운 워커 JVM 이 누수될 수 있다. 이를 막기 위해 `DebugCore` 는 idle 스위퍼를 돌린다: 마지막 활동 이후 유휴 시간이 임계치를 넘은 세션을 자동 회수(terminate → dispose 훅 → 워커 kill)한다. 활동 시각은 세션에 접근하는 모든 툴 호출에서 갱신된다.

```properties
# 기본 30분. 0 또는 음수면 자동 회수 비활성.
protean.mcp.debug.session-idle-timeout=30m
```

스위프 주기는 타임아웃의 1/4(최소 1초, 최대 60초)로 자동 결정된다. 회수 시 경고 로그가 남는다.

## 운영 주의

- 디버그 표면은 임의 코드 재정의·표현식 평가를 허용하므로, 프로덕션에서 켤 때는 반드시 `ModuleActionAuthorizer` 로 `DEBUG` 액션을 강하게 통제하고 `POST /platform/mcp` 를 인증 뒤에 둔다([12. 보안](12-security.ko.md)).
- JDK 로 실행 중인지 확인한다(`jdk.jdi` 필요). 운영 전반은 [11. 운영](11-operations.ko.md) 참고.

## 관련 문서

- [08. MCP 연동](08-mcp-integration.ko.md)
- [05. 격리 모드](05-isolation-modes.ko.md)
- [11. 운영](11-operations.ko.md)
- [12. 보안](12-security.ko.md)
- [13. 트러블슈팅](13-troubleshooting.ko.md)
- [README](../../README.ko.md)
