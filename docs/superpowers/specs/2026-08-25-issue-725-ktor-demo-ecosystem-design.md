# Issue #725 Ktor 예제 생태계 정리 설계

## 목적

`examples/ktor-exposed-demo`가 bluetape4k의 UUID 생성기와 assertion을 일관되게
사용하고, 운영 진단을 직접 표준 출력에 쓰지 않도록 정리한다. 이 변경은 예제의
기존 HTTP 계약과 정제된 진단 필드 계약을 유지하면서 테스트·운영 코드의 선택지를
생태계 기본값으로 통일하는 것을 목표로 한다.

## 현재 근거와 범위

- `KtorExposedDemoApplication.kt`는 런타임 실패 correlation ID를
  `UUID.randomUUID().toString()`으로 만들고 `StderrDemoDiagnosticSink`가
  `output.println(...)`으로 진단을 출력한다.
- `OrderRoutes.kt`는 `503` 응답 correlation ID를 같은 방식으로 만든다.
- `KtorExposedDemoLifecycleTest.kt`, `OrderCommandServiceTest.kt`,
  `KtorExposedDemoPostgresIntegrationTest.kt`는 JUnit `assertSame`을 사용한다.
- PostgreSQL 통합 테스트의 임시 주문 ID도 `UUID.randomUUID()`를 사용한다.
- 테스트 의존성에는 이미 `bt4k.bluetape4k.assertions`가 있고, 저장소의
  `io.bluetape4k.idgenerators.uuid.Uuid.V7.nextId()`가 UUID 생성 표준으로
  사용된다.
- README 두 로케일은 `503`의 canonical UUID `correlationId`와 logger 진단 연결
  계약을 설명한다.

이번 변경의 코드 범위는 위 세 테스트 파일, 두 production Kotlin 파일, 그리고
동일 계약을 설명하는 `README.md`·`README.ko.md`다. 모듈 등록·CI·공개 artifact
구조는 바꾸지 않는다.

## 선택한 설계

### 1. UUID 생성

UUID 값이 필요한 새 진단·테스트 fixture에는 `Uuid.V7.nextId()`를 사용한다.
응답과 로그에 노출되는 값은 기존처럼 `UUID.toString()`의 36자 canonical 문자열을
유지한다. `OrderRoutes`의 입력 파싱과 nil UUID 검사는 사용자 입력 계약이므로
`java.util.UUID`를 그대로 둔다.

### 2. assertion

직접 객체 동일성을 검증하는 JUnit `assertSame(expected, actual)` 호출을
`actual shouldBeSameInstanceAs expected`로 바꾼다. 예외 타입·원인·cleanup
순서에 대한 기존 검증은 유지하고, `assertFailsWith`와 `shouldBeEqualTo` 등 이미
사용 중인 bluetape4k matcher와 같은 문맥으로 정리한다.

### 3. 진단 sink와 로깅

`StderrDemoDiagnosticSink`의 진단 필드 경계를 유지하되, `PrintStream`과
`output.println`을 제거하고 `io.bluetape4k.logging.KotlinLogging`의 lazy
`error` 로거를 기본 구현으로 사용한다. 테스트 가능성을 잃지 않도록 sink는
allowlist 문자열을 전달하는 작은 함수형 logger를 주입받을 수 있게 한다. 기본
구성은 실제 logger를 사용하고, 테스트는 메모리 collector를 주입해 기존의 한 줄
정확성 검증을 유지한다. 진단 필드 조립·allowlist·민감 정보 제거 규칙은 그대로
두며 새로운 raw payload나 예외 stacktrace를 로그에 추가하지 않는다.

이 선택은 직접 `System.err`를 호출하는 대안보다 운영 로깅 정책과 일치하고,
SLF4J backend가 없는 예제 테스트에서도 deterministic한 단위 검증을 가능하게
한다. 별도 진단 프레임워크나 새로운 dependency는 추가하지 않는다.

### 4. 문서

두 README의 로케일 구조와 canonical UUID 설명을 맞춘다. 생성 값이 UUIDv7임을
명시하되, `correlationId`가 재시도 토큰이나 이벤트 재발행 토큰이 아니라는 기존
제약과 정제된 응답-진단 연결 범위는 변경하지 않는다.

## 실패 모드와 호환성

| 위험 | 보호 조치 |
|---|---|
| UUID 문자열 형식 변경 | `Uuid.V7.nextId().toString()`을 사용하고 36자 canonical 응답을 검증한다. |
| caller 입력 검증 회귀 | 파싱·nil UUID·대소문자 검사는 수정하지 않는다. |
| 진단 필드 또는 비밀값 노출 | 기존 allowlist 조립을 재사용하고 출력은 logger 한 경로로 제한한다. |
| logger backend 미설정 | 운영 기본값은 lazy logger, 테스트는 주입 collector로 분리한다. |
| 객체 동일성 검증 약화 | `shouldBeSameInstanceAs`로 참조 동일성을 직접 검증한다. |
| PostgreSQL fixture 충돌 | UUIDv7 fixture와 기존 순차 Testcontainers 실행을 유지한다. |
| 취소·cleanup 동작 변화 | sink/ID 생성만 바꾸고 lifecycle 및 coroutine 제어 흐름은 건드리지 않는다. |

기존 public 예제 호출자는 `installKtorExposedDemo(..., DemoDiagnosticSink)`와
HTTP 계약을 계속 사용할 수 있다. `StderrDemoDiagnosticSink`의 출력 주입 방식은
테스트 전용 구현 세부였으므로 logger 함수 주입으로 바뀌며, 저장소 밖에서 해당
생성자를 직접 사용한다면 PR 본문에 마이그레이션 메모를 남긴다.

## 검증 계약

1. 대상 examples 테스트에 JUnit assertion import/call, `println`, `System.out`,
   `System.err`, `UUID.randomUUID`가 남지 않는다(입력 파서용 `UUID` 사용은 허용).
2. 대상 테스트는 `bluetape4k-assertions` matcher와 `assertFailsWith`를 사용하고,
   sink 테스트는 allowlist 문자열과 logger 호출 결과를 검증한다.
3. `:examples-ktor-exposed-demo:compileTestKotlin`, H2 단위/HTTP 테스트,
   PostgreSQL 통합 테스트를 순차 실행하고 실패·skip 수를 기록한다.
4. 모듈 lint/Detekt, Kotlin final checklist, 7-Tier(T1–T7), README 로케일 parity,
   `git diff --check`를 통과한다.

## 완료 정의

- production 진단은 application logger만 사용하고 직접 `println`을 호출하지 않는다.
- UUID 생성과 객체 동일성 assertion이 bluetape4k helper로 통일된다.
- 기존 응답·진단·lifecycle·PostgreSQL 동작이 fresh 테스트로 입증된다.
- 설계/계획/리뷰/lesson이 한국어 SPW-01~05를 충족한다.
- Lore 형식 커밋, `develop` 대상 Korean PR, Issue #725 연결까지 완료한다.
- PR hosted check/review/merge는 별도 gate로 남기며 이 작업에서 merge하지 않는다.

## 비목표

- 모듈 외부의 UUID 생성 호출 일괄 변경
- 새 logging dependency·진단 저장소·trace 시스템 도입
- Ktor library API 또는 DB schema 변경
- GitHub Issue close 또는 PR merge
