[English](README.md) | **한국어**

# Protean 사용자 가이드

Protean 을 의존성으로 쓰는 **Spring Boot 개발자**(라이브러리 소비자)를 위한 실무 how-to 모음이다.
"어떻게 하나"에 초점을 맞춘다.

읽는 순서대로 번호를 붙였다. 처음이면 [01. 시작하기](01-getting-started.ko.md)부터.

## 핵심

| # | 문서 | 내용 |
|---|------|------|
| 01 | [시작하기](01-getting-started.ko.md) | 의존성 추가 → 자동 구성 → Hello 모듈 배포 → 확인 → 해제까지 end-to-end |
| 02 | [모듈 작성](02-module-authoring.ko.md) | `ModuleDescriptor`·`module.yaml` 작성, sources/tests/resources, 컨트롤러 규약, child 컨텍스트 DI, 금지 API |
| 03 | [설정 레퍼런스](03-configuration.ko.md) | 전체 `protean.*` 프로퍼티(키·기본값·설명) |
| 04 | [REST API 레퍼런스](04-rest-api.ko.md) | 컨트롤 플레인 엔드포인트 요청/응답·상태코드·에러 |

## 기능별 가이드

| # | 문서 | 내용 |
|---|------|------|
| 05 | [격리 모드](05-isolation-modes.ko.md) | in-process / worker / container 선택·셋업, hot-swap·풀·감독·RPC 브리지 |
| 06 | [승격 게이트](06-promotion-gates.ko.md) | 테스트·리뷰·검증 게이트 운영, 서명·승인 워크플로 |
| 07 | [데이터 접근](07-data-access.ko.md) | 드라이버 번들·리소스 채널·모듈 DataSource·worker DB·트랜잭션·관리형 실행 |
| 08 | [MCP 연동](08-mcp-integration.ko.md) | 원격 서버 보안 자세, Bearer/OAuth 2.0 설정, 클라이언트 연결·구동(HTTP/stdio), 툴 카탈로그, 인증 위임 |
| 09 | [디버깅](09-debugging.ko.md) | Level 3 디버깅(launch/attach/breakpoint/step/evaluate/redefine) 실습 |
| 10 | [SPI 확장](10-spi-extension.ko.md) | 빈 등록으로 여는 확장점(`CodeRule`·`DbDialect`·`ModuleActionAuthorizer`·`WorkerRuntimeProvider`·`ModuleUnloadCallback`) |

## 운영 · 보조

| # | 문서 | 내용 |
|---|------|------|
| 11 | [운영](11-operations.ko.md) | 영속 저장소·reconcile·트레이스·타임아웃·누수 회피·빌드/발행 |
| 12 | [보안](12-security.ko.md) | Trust 모델 운영 관점, 샌드박스 non-goal, MCP/REST 인증 권고 |
| 13 | [트러블슈팅](13-troubleshooting.ko.md) | 자주 나는 오류·진단·FAQ |

## 관련 문서

- [README](../../README.ko.md) — 프로젝트 개요
