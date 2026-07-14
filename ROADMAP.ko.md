[English](ROADMAP.md) | **한국어**

# Protean 로드맵

Protean 플랫폼의 향후 항목들. Protean 은 **라이브러리**이므로 — 소비자가 사용 시점에 내부를
확장할 수 없다 — 단기 ROI 보다 기능 완전성을 우선한다. 따라서 아래 일부 항목은 **채택 여부를
아직 결정 중인 지원 후보**이며, 결정이 암묵적으로 남지 않도록 여기에 명시해 둔다.

살아있는 문서다. 오늘 시점에 구체적이고 근거 있는 항목이 있는 트랙부터 씨앗으로 담았고, 새
항목이 생기면 자란다. 릴리스 일정표가 아니다.

## 범례

| 상태 | 의미 |
|---|---|
| ✅ Shipped | 구현되어 `main` 에 반영됨. 맥락용으로 기재. |
| 🛠 Planned | 제공하기로 확정됨. 아직 일정·구현 전. |
| 🔎 Candidate | 검토 중 — **지원 여부 미결정**. 트레이드오프를 기록으로 남김. |
| 🚫 Not planned | 검토 후 의도적으로 하지 않기로 함(사유 포함). |

---

## 플랫폼 호환성 (Spring Boot / Java)

Protean 은 소비자의 Spring Boot 애플리케이션 *안에서* 도는 라이브러리이며, Spring MVC·컨텍스트
내부(동적 매핑 등록, 자식 애플리케이션 컨텍스트, 핸들러-어댑터 캐시 purge)를 조작한다. 이런
내부 접점 때문에 지원 Spring Boot 라인은 부수적 세부사항이 아니라 **명시적 계약**이다.

### ✅ 현재 baseline

- **Spring Boot 3.5.x / Java 21.** 지원·검증되는 라인 — Protean 이 이 라인으로 컴파일되고 CI 가
  전체 스위트를 이 라인에서 실행한다.

### 🛠 Planned — Spring Boot 4.x 지원

Protean 은 라이브러리이므로(완전성 > ROI — 소비자가 사용 시점에 내부를 못 고침), **Spring Boot
4.x 지원은 열린 질문이 아니라 확정된 방침(commitment)이다.** 다만 아직 구현되지 않았을 뿐이다:
Boot 4(Spring Framework 7)는 파괴 변경을 동반하는 메이저 릴리스이고, Protean 의 깊은 MVC·컨텍스트
결합 때문에 지금 4.x 소비자는 런타임 링크 실패(예: `NoSuchMethodError`)를 만나며, 특정 경로(예:
모듈 언로드)를 밟을 때에야 드러나는 **잠복형**일 수 있다. 마이그레이션이 도착하기 전까지 Protean
은 **3.5.x 에 고정**된다.

이 마이그레이션은 별도 트랙으로 설계·논의한다 — 호환성 표면 감사, 그리고 지원되지 않는 라인에서
난해한 크래시 대신 명확한 메시지로 빠르게 실패하도록 하는 기동 시 버전 가드가 유력하다. 의도를
명시하기 위해 여기 기록하며, 설계는 그 트랙으로 이월한다.

### 🚫 Not planned

- Dependabot 의 **메이저** Spring Boot 범프 자동 수용. 메이저 버전 이동은 자동 의존성 범프가
  아니라 의도적 마이그레이션이다(위 참조).

---

## 관측성 (Observability)

런타임 요청 트레이싱과 모듈별 메트릭. 현재 표면은 운영 가이드
([docs/guide/11-operations.ko.md](docs/guide/11-operations.ko.md#요청-트레이스--모니터링))
참고.

### ✅ Shipped

- 런타임 요청 트레이스 링 버퍼 + 조회 API (`GET /platform/traces`, 필터
  `limit`/`moduleId`/`errorsOnly`/`status`/`minLatencyMs`/`since`/`beforeSeq`).
- opt-in 모듈별 집계 메트릭 (`GET /platform/traces/metrics`,
  `protean.trace.metrics.enabled`).
- 상관 id(`traceId`)를 트레이스·로그·RFC 9457 오류 본문에 공유.
- **worker/container 라우트의 모듈별 귀속.** 프록시(worker/container) 라우트가 이제 main 측
  트레이스에 자신의 `moduleId` 를 기록하고 모듈별 메트릭 행을 갖는다 — 이전에는 귀속되지 않고
  `(platform)` 버킷에 뭉뚱그려졌다.

### 🔎 Candidate — 지원 미결정

worker/container 모듈의 main 측 트레이스는 모듈의 **내부 실행 시간**이 아니라 **프록시-홉
(클라이언트 관측) 지연**을 기록한다. 각 worker/container 는 자체 `TraceStore` 를 가진 Protean 앱
이지만 `@Profile("!worker")` 로 돌아 `/platform/traces` 를 노출하지 않아 내부 트레이스가 main
플랫폼에서 도달 불가하다. 아래는 그 갭을 메우는 안이며, Protean 이 지원할지는 **평가 대상**이다
(운영 가치 vs 결합도·보안 표면·오버헤드를 저울질):

- **B2 — Pull-back 집계.** main 플랫폼이 주기적으로 각 worker/container 의 내부 트레이스·메트릭을
  (내부 admin 엔드포인트로) 당겨와 병합, 단일 조회 표면 유지. 트레이드오프: worker admin 평면에
  엔드포인트 추가, 폴링 오버헤드, 병합 시맨틱 정의 필요.
- **B3 — 멀티소스 콘솔.** 클라이언트(예: 트레이스 콘솔)가 각 worker 엔드포인트를 직접 조회해
  뷰를 이어붙임. 트레이드오프: worker 프로필을 완화해 트레이스 엔드포인트를 노출해야 하고,
  콘솔의 worker 토폴로지 결합도가 올라감. *(현재 비선호 — B2 보다 결합도 높음.)*

### 🚫 Not planned (현재로선)

- 장기 트레이스 영속화 / 외부 저장소. 링 버퍼는 의도적으로 인메모리·유계이며, 내구성 보존과
  집계는 로그/APM 에 위임한다.

---

*새 트랙을 제안하거나 후보를 shipped/declined 로 옮기려면, 변경과 함께 이 파일을 편집하는 풀
리퀘스트를 열어 주세요.*
