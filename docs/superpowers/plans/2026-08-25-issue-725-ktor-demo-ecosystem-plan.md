# Issue #725 Ktor 예제 생태계 정리 실행 계획

## 전제와 승인 범위

- 기준 branch: `refactor/ktor-demo-ecosystem`, base `origin/develop`
  (`1242e5eb990a1f362233dba9542aa6e4d7192730`)
- 대상 module: `:examples-ktor-exposed-demo`
- 대상 issue: `#725`
- 설계: `docs/superpowers/specs/2026-08-25-issue-725-ktor-demo-ecosystem-design.md`
- 설계 검토: `docs/review/2026-08-25-issue-725-ktor-demo-ecosystem-review.md`
- 사용 규칙: `$bluetape-workflow` Type A, `$bluetape-kotlin-patterns`,
  `$bluetape-writer`

## 변경 순서

### 1. RED: 현재 계약을 고정한다

1. `KtorExposedDemoLifecycleTest.kt`의 diagnostic sink 테스트가 allowlist 한 줄
   포맷과 민감값 제거를 검증하는지 확인한다.
2. 동일 테스트에 logger collector 주입 경로를 먼저 추가해 현재 출력 계약을
   실패시키는 RED 상태를 만든다.
3. `KtorExposedDemoLifecycleTest.kt`, `OrderCommandServiceTest.kt`,
   `KtorExposedDemoPostgresIntegrationTest.kt`의 `assertSame`를
   `shouldBeSameInstanceAs`로 바꾸는 대상 목록을 고정한다.

예상 결과: 기존 동작을 설명하는 테스트가 있고, 새 sink constructor 계약이
구현 전에는 컴파일되지 않거나 실패한다.

### 2. GREEN: production helper와 logger를 적용한다

다음 파일만 수정한다.

- `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplication.kt`
  - `Uuid.V7.nextId().toString()`으로 runtime diagnostic correlation ID를 만든다.
  - `PrintStream`, `System.err`, `output.println`을 제거한다.
  - `io.bluetape4k.logging.KotlinLogging`과 lazy `error` 확장을 사용한다.
  - allowlist 문자열은 유지하고 테스트용 작은 함수형 logger를 주입한다.
- `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderRoutes.kt`
  - `503` correlation ID 생성만 `Uuid.V7.nextId().toString()`으로 바꾼다.
  - canonical parser·nil UUID·입력 예외 계약은 건드리지 않는다.
- `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoLifecycleTest.kt`
  - JUnit `assertSame` import/call을 `shouldBeSameInstanceAs`로 바꾼다.
  - logger collector로 allowlist 포맷, correlation ID, secret 비노출을 검증한다.
- `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderCommandServiceTest.kt`
  - 모든 `assertSame`를 bluetape4k matcher로 바꾼다.
- `examples/ktor-exposed-demo/src/postgresIntegrationTest/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoPostgresIntegrationTest.kt`
  - fixture ID를 `Uuid.V7.nextId()`로 만든다.
  - cached object 참조 동일성은 `shouldBeSameInstanceAs`로 검증한다.

새 dependency, module, wrapper, schema는 추가하지 않는다.

### 3. 문서와 로케일을 동기화한다

- `examples/ktor-exposed-demo/README.md`
- `examples/ktor-exposed-demo/README.ko.md`

두 파일의 `503` 설명을 “application logger 진단 레코드”로 맞추고, 생성 값이
UUIDv7의 canonical UUID 문자열임을 명시한다. correlation ID가 retry/event
republish token이 아니라는 제약과 response-diagnostic 연결 범위는 동일하게
유지한다. 변경 후 두 문서의 표·명령·source token을 비교한다.

### 4. 검증한다

Gradle은 repository 규칙에 따라 context-mode에서 실행하며, Testcontainers 작업은
동시에 실행하지 않는다.

1. 정적 검사: 대상 examples 범위에서 `org.junit.jupiter.api.Assertions`,
   `kotlin.test`, `assertSame`, `UUID.randomUUID`, `println`, `System.out`,
   `System.err` 잔여를 확인한다. parser/domain의 `java.util.UUID` 사용은 허용
   목록과 대조한다.
2. 최소 compile: `:examples-ktor-exposed-demo:compileKotlin` 및
   `:examples-ktor-exposed-demo:compileTestKotlin`.
3. 단위/HTTP H2: `:examples-ktor-exposed-demo:test --no-parallel`.
4. PostgreSQL: `:examples-ktor-exposed-demo:postgresIntegrationTest --no-parallel`.
5. 모듈 lint/Detekt task가 존재하면 해당 task를 실행하고, 없으면 task listing과
   repository lint 결과를 N/A 근거로 남긴다.
6. `git diff --check`, changed-file review, Kotlin final checklist를 수행한다.

각 결과는 실행 SHA, task, test count, failure/error/skipped 수와 함께 lesson 및
Type A receipt evidence에 기록한다. Container 또는 network 오류는 코드 실패로
분류하지 않고 raw output과 재현 조건을 남긴다.

### 5. 최종 review와 delivery

1. requirement-to-file/test traceability와 plan-task status를 verifier 표로
   작성한다.
2. T1 Performance, T2 Stability, T3 Security, T4 Operator/Ops, T5 Developer/API,
   T6 User/Caller, T7 integration을 module slice 단위로 다시 검토한다.
3. `P0 = 0`, `P1 = 0`을 확인하고 P2/P3는 수정·명시적 N/A·후속 issue 중 하나로
   처리한다.
4. `docs/lessons/2026-08-25-issue-725-ktor-demo-ecosystem.md`에 결정·검증·miss·
   재발 방지 guard를 한국어로 기록한다.
5. Lore trailer가 있는 한국어 커밋을 만들고, exact head를 push한다.
6. `develop` 대상 Korean PR을 생성하고 `Closes #725`, assignee `debop`,
   milestone `2.0.0`, Issue labels(`test`, `refactor`, `tech-debt`)를 맞춘다.
   PR body 마지막은 `## DoD Status`로 두고 hosted checks/review/merge hold를
   명시한다. merge와 auto-merge는 수행하지 않는다.

## 계획-요구사항 매핑

| 요구사항 | 구현/검증 task | 증거 |
|---|---|---|
| bluetape UUID helper | 2단계 production + PostgreSQL fixture | source scan, compile, tests |
| bluetape assertions | 1·2단계 세 test file | no legacy import/call, targeted tests |
| logger 사용 | 1·2단계 sink collector/default logger | no direct print scan, sink test |
| canonical response contract | 2·3단계 | H2/HTTP + README parity |
| lifecycle/cancellation 보존 | 2·4단계 | lifecycle tests, Kotlin checklist |
| Postgres 안정성 | 4단계 sequential task | integration XML/counts |
| 7-Tier review | 5단계 | review artifact with convergence |
| lesson/PR | 5단계 | Korean lesson, exact PR read-back |

## 롤백과 범위 통제

실패 시 커밋 전에는 변경 파일만 되돌리고, 커밋 후에는 PR branch를 보존한 채
추가 fix commit으로 수정한다. UUID helper·matcher·logger 변경과 무관한 모듈,
workflow, schema, release 파일은 추가하지 않는다. README의 logger 목적지 표현이
실행 backend와 불일치하면 문서를 다시 맞추고 해당 writer/review gate를 재실행한다.

## SPW-01~05 계획 gate

- SPW-01: 대상 독자·source·정확한 경로·토큰을 위에서 고정했다.
- SPW-02: 순서, 파일, 명령, 기대 증거, rollback, PR gate를 포함했다.
- SPW-03: 한국어 기술 문체와 code token 보존을 적용한다.
- SPW-04: spec → plan → source/test/README traceability를 verifier에서 재확인한다.
- SPW-05: 최종 read-back과 terminology audit 결과를 receipt에 첨부한다.
