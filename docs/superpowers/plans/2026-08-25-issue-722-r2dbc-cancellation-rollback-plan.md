# 이슈 #722 R2DBC cancellation·rollback implementation plan

> 실행 규칙: Type A workflow의 dependency order를 따른다. 사용자가
> “이슈들을 순서대로 작업하자”라고 승인했으며, PR 생성까지 자동 진행하되
> merge는 별도 승인을 기다린다. 구현 전 설계·계획의 P0/P1을 0으로 수렴한다.

## 목표와 변경 지도

`exposed/r2dbc-tests`의 published test-support가 bluetape4k assertion
dependency를 직접 노출하고, coroutine cancellation과 transaction rollback의
primary/suppressed 계약을 회귀 테스트로 증명하도록 한다.

변경 대상:

- `exposed/r2dbc-tests/build.gradle.kts` — direct API dependency.
- `exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/Assertions.kt`
  — cleanup failure 보존, cancellation 전파, KDoc.
- `exposed/r2dbc-tests/src/test/kotlin/io/bluetape4k/exposed/r2dbc/tests/AssertionsTest.kt`
  — lifecycle regression matrix.
- `exposed/r2dbc-tests/src/test/kotlin/io/bluetape4k/exposed/r2dbc/tests/migration/R2dbcMigrationDriftTest.kt`
  — bluetape4k matcher migration.
- `docs/review/2026-08-25-issue-722-r2dbc-cancellation-rollback-review.md` — final
  7-Tier evidence.
- `docs/lessons/2026-08-25-issue-722-r2dbc-cancellation-rollback.md` — reusable
  lesson.

변경하지 않을 대상: production `exposed/r2dbc`, `withDb`/`withTables`, central
catalog/BOM, README/manual, workflow YAML, publishing/ABI baseline.

## Task 1 — RED와 설계 추적성

1. live #722 metadata, branch/base, current diff와 관련 source/caller를 다시
   읽는다.
2. 현재 `AssertionsTest`와 migration fixture의 baseline을 H2 targeted compile/test로
   기록한다.
3. 기존 `catch (Throwable)` 경로가 cancellation을 삼킬 수 있다는 negative
   regression을 설계하고, direct API dependency 부재와 raw assertion import를
   수량화한다.
4. design spec과 본 plan의 SPW-01~05를 읽고, cancellation·rollback·consumer
   compatibility·testability·operator 관점의 독립 lens를 별도 read-only pass로
   검토한다. 구현 source를 이 단계에서 바꾸지 않는다.

### 계획 단계 7-Tier 자체 리뷰

구현 전에 계획을 일곱 렌즈로 재검토했다. 이 리뷰는 현재 owner가 수행했으며,
각 렌즈의 질문과 결정 근거를 남겨 후속 리뷰에서 재현할 수 있게 했다.

| Tier | 검토 초점 | 결과 | 근거 |
| --- | --- | --- | --- |
| 1. 계약 | 공개 helper의 성공·실패·취소 의미가 명확한가 | PASS | 정상 성공은 실패로 보고하고, 예상하지 않은 `CancellationException`은 원본을 유지한다 |
| 2. 경계 | Exposed transaction과 coroutine 취소 경계를 지키는가 | PASS | savepoint rollback/release는 `NonCancellable`에서 실행하고 production R2DBC API는 건드리지 않는다 |
| 3. 실패 | primary와 cleanup 실패가 모두 보존되는가 | PASS | cleanup 예외를 primary의 suppressed 예외로 연결한다 |
| 4. 테스트 | 회귀 경로와 negative path가 모두 고정되는가 | PASS | 취소 전파, 롤백, assertion 실패, 예상된 취소를 각각 고정한다 |
| 5. 의존성 | bluetape4k assertions를 직접·일관되게 사용하는가 | PASS | 모듈에 직접 `api` 의존성을 추가하고 fixture의 JUnit assertion을 matcher로 전환한다 |
| 6. 유지보수 | 복사한 helper가 아닌 공통 conformance contract인가 | PASS | migration fixture도 공유 `preserveFailure` 경로를 사용하며 중복 helper를 제거한다 |
| 7. 전달 | 검증 범위와 미검증 범위가 PR에서 추적 가능한가 | PASS | compile, H2 targeted test, detekt와 hosted CI/merge 보류를 DoD에 분리 기록한다 |

계획 시점의 P0/P1 위험은 0건이며, 구현 후 실제 테스트와 정적 분석 출력으로
이 판단을 다시 확인한다.

## Task 2 — assertion helper 계약 구현

1. `api(bt4k.bluetape4k.assertions)`를 기존 Bluetape4k test-support API
   declarations와 함께 추가한다.
2. `preserveFailure` 내부 helper를 추가한다. block failure를 먼저 캡처하고
   `withContext(NonCancellable)`에서 cleanup을 한 번 실행하며, cleanup failure를
   primary의 `suppressed`에 연결하거나 primary가 없으면 그대로 던진다.
3. `assertFailAndRollback`은 savepoint를 만든 뒤 block의 일반 실패는 기대된
   실패로 소비하고, 성공 시 assertion failure를 primary로 만든다. cleanup은
   savepoint rollback/release를 수행하며 cancellation은 cleanup 뒤 원래 instance로
   재전파한다. R2DBC commit의 auto-commit 전환 때문에 initial `commit()`은 복사하지
   않는다.
4. `expectExceptionSuspending`은 `CancellationException`이
   `IllegalStateException`의 하위 타입이라는 JVM 계층을 먼저 분기해, expected
   cancellation만 허용하고 다른 cancellation은 즉시 재전파한다. 일반 예외의
   타입 검증과 dialect failure context를 유지한다.
5. KDoc는 cleanup과 cancellation의 실제 contract를 한국어로 명시한다.

## Task 3 — 회귀 테스트와 fixture 정규화

1. `AssertionsTest`에 다음 케이스를 추가한다.
   - block이 `CancellationException`을 던져도 savepoint rollback cleanup이
     실행되고 같은 exception instance가 재전파되는지;
   - cleanup-only failure가 그대로 던져지는지;
   - primary failure가 cleanup failure를 suppressed로 보존하는지;
   - block 성공 시 assertion failure가 발생하고 rollback은 여전히 실행되는지;
   - `expectExceptionSuspending<CancellationException>`은 명시적 기대일 때만
     통과하고 일반 expected type에는 cancellation이 재전파되는지.
2. DB-backed H2 테스트는 existing `withTables`/`withDb` fixture와
   `runSuspendIO`를 재사용하고, pure cleanup contract는 DB/mock 없이
   deterministic suspend test로 검증한다.
3. migration fixture의 JUnit assertion 호출을 intent-specific matcher로 교체하고
   lifecycle/parameterized imports는 유지한다.
4. touched module에서 `println`, `System.out`, `System.err`, raw
   JUnit/kotlin.test/AssertJ/Kluent assertion import를 검색한다.

## Task 4 — 순차 검증과 7-Tier 수렴

검증은 dependency order로 실행한다.

1. targeted `compileTestKotlin`과 `AssertionsTest`를 먼저 실행한다.
2. `R2dbcMigrationDriftTest` H2를 실행하고, 이어 PostgreSQL/MySQL_V8 migration
   smoke를 각각 순차 실행한다. Docker/Colima 실패는 테스트 PASS로 합치지 않는다.
3. affected module `test --no-build-cache --no-parallel`, `detekt`, API metadata
   (`outgoingVariants`/POM/module JSON), `git diff --check`를 실행한다.
4. final diff와 source/docs를 다시 읽고 7-Tier를 다음 lens로 판정한다.
   - 요구사항·범위·이슈 추적성
   - API/ABI·published dependency·Kotlin 사용성
   - transaction correctness·rollback·exception identity
   - coroutine cancellation·structured concurrency·resource lifecycle
   - security·side effect·logging/no-`println`
   - test strength·dialect matrix·deterministic failure proof
   - docs·operator diagnostics·delivery/rollback readiness
5. P0/P1은 0건으로 수렴하고, non-blocking P2/P3는 review/lesson에 명시한다.

## Task 5 — 문서와 delivery

1. review artifact에 exact head, changed paths, 7-Tier table, P0/P1/P2/P3,
   command evidence, hosted gap을 기록한다.
2. lesson에 `CancellationException` 보존과 cleanup suppressed policy를 재발
   방지 규칙으로 기록한다.
3. Lore trailer가 포함된 한국어 commit을 만들고, `git status`, `git diff --check`,
   commit SHA를 확인한다.
4. exact head를 push한 뒤 Issue #722와 같은 assignee `debop`, milestone `2.0.0`,
   labels `bug/test/tech-debt`인 한국어 PR을 만들고 body 마지막 heading을
   `## DoD Status`로 고정한다. CI/review는 pending이면 정확히 보고하고 merge하지
   않는다.

## 실패·rollback 지점

- direct dependency compile/API metadata가 실패하면 build script만 되돌리고
  source migration을 진행하지 않는다.
- cancellation/rollback regression이 실패하면 helper 변경을 되돌리고 baseline
  테스트로 원인을 분리한다.
- dialect smoke가 infrastructure failure면 해당 backend evidence를 PENDING으로
  남기고 H2 PASS를 전체 matrix PASS로 일반화하지 않는다.
- PR head mismatch나 metadata drift가 발견되면 push/PR creation을 중단하고
  current branch와 issue를 다시 읽는다.

## 계획 DoD

- [ ] 설계·계획·writer gate와 workflow topology가 최신 source/issue를 반영한다.
- [ ] helper가 cancellation·rollback·assertion failure를 보존하고 expected
  cancellation만 허용한다.
- [ ] migration fixture가 `bluetape4k-assertions` matcher로 통일된다.
- [ ] targeted/module/dialect/static/API metadata 검증과 7-Tier review가 fresh하다.
- [ ] Lore commit·exact-head push·한국어 PR이 완료된다. merge/sync/cleanup은
  별도 승인 및 live evidence가 필요하다.
