# Issue #725 Ktor 예제 생태계 정리 lesson

## 배경

`examples/ktor-exposed-demo`는 이미 `bluetape4k-assertions` dependency를 선언하고
있었지만 일부 테스트가 JUnit `assertSame`을 사용했다. production 진단과
`503` 응답·PostgreSQL fixture는 `UUID.randomUUID()`를 사용했고,
`StderrDemoDiagnosticSink`는 `System.err`에 `println`했다.

## 결정

- UUID-valued ID는 저장소 표준인 `Uuid.V7.nextId()`로 만들고 HTTP/log 계약은
  canonical `UUID.toString()`으로 유지했다.
- 동일성 검증은 `shouldBeSameInstanceAs`, 예외 검증은 기존
  `io.bluetape4k.assertions.assertFailsWith`로 통일했다.
- 진단 sink는 `io.bluetape4k.logging.KotlinLogging`과 lazy `error`를 기본값으로
  사용하고, 테스트에는 함수형 logger를 주입해 allowlist 한 줄 포맷을 직접
  검증한다. `println`, `System.out`, `System.err`는 대상 scope에서 제거했다.
- UUIDv7 correlation ID는 시간 비트를 포함할 수 있지만 인증·재시도·이벤트 재발행
  토큰이 아니다. 이 경계를 두 README에 같은 의미로 기록했다.

## 결과와 검증

- 변경 파일: production 2개, test 4개, README 2개.
- 설계·계획·review artifact에서 Type A와 Kotlin/writer gate를 고정했다.
- `compileKotlin`, `compileTestKotlin`, `compilePostgresIntegrationTestKotlin`:
  `BUILD SUCCESSFUL`.
- H2 module test: 32 tests / failures 0 / errors 0 / skipped 0.
- PostgreSQL Testcontainers: 4 tests / failures 0 / errors 0 / skipped 0.
- `:examples-ktor-exposed-demo:detekt`: `NO-SOURCE`, `BUILD SUCCESSFUL`.
- static scan에서 legacy assertion/import, random UUID 생성, direct print/logging
  경로는 0건이다.
- `git diff --check`와 Korean terminology audit(4개 artifact/README)는 통과했다.
- final T1~T7 통합 review는 P0/P1/P2/P3 모두 0이다.

## 놓친 점과 복구

첫 PostgreSQL integration compile에서 계획대로 범위 내 import를 정리하는 과정에
기존 `runSuspendIO` import를 함께 제거해 `compilePostgresIntegrationTestKotlin`
이 실패했다. 컴파일 오류의 실제 source line을 읽고 import를 복원한 뒤 compile과
PostgreSQL 4개 테스트를 다시 실행해 pass를 확인했다. 다음에는 assertion/import
정리 후 custom source set별 compile을 H2 test보다 먼저 실행한다.

설계·계획 native lane은 startup 응답이 없어 main-session fallback으로 대체했다.
이 대체는 독립 모델 증거가 아니라 liveness가 확인된 운영 선택이며 receipt와
review에 남겼다.

## 재발 방지

다음 Ktor 예제 변경에서도 아래 순서를 유지한다.

1. `rg`로 JUnit/kotlin.test assertion, `UUID.randomUUID`, direct print를 먼저
   고정한다.
2. custom source set이 있으면 `compile<PostgresIntegrationTest>Kotlin` 같은
   compile gate를 실행한다.
3. logger 변경은 raw output 대신 injectable collector와 allowlist 테스트를 둔다.
4. public behavior를 바꾸면 README.md와 localized README를 한 번에 수정하고
   canonical string·token 비사용 계약을 함께 검증한다.
5. 마지막에 H2 → PostgreSQL 순차 테스트, Detekt/task scope, `git diff --check`,
   7-Tier review, receipt, Korean PR을 완료한다.

## SPW-01~05

- SPW-01 PASS: 예제 사용자·운영자, source path, 명령·결과를 고정했다.
- SPW-02 PASS: context, decision, outcome, miss, future guard를 포함했다.
- SPW-03 PASS: 한국어 기술 문체와 정확한 code token을 유지했다.
- SPW-04 PASS: source, test XML, README parity, review artifact를 대조했다.
- SPW-05 PASS: 최종 Markdown read-back과 terminology audit을 수행했다.
