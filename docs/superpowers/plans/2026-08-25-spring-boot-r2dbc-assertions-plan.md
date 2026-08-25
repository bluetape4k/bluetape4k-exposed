# spring-boot/r2dbc assertion standardization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development when executing this plan. Keep the main session as the only writer for the declared implementation scope, run heavyweight database checks sequentially, and stop at every verification gate.

**Goal:** Issue #724의 `spring-boot/r2dbc` 테스트 10개가 `bluetape4k-assertions`를
직접 사용하도록 이관하고, 새 legacy assertion 유입을 모듈 `check`에서 fail-closed로
차단한다.

**Architecture:** `bt4k.bluetape4k.assertions`를 `spring-boot/r2dbc`의 test source
set에 직접 선언한다. 모듈 전용 Gradle verification task가 Kotlin test source를
증분 입력으로 스캔하여 legacy import·wildcard·fully-qualified 호출을 `logger`로
보고하고 실패시킨다. 테스트 assertion 표현만 바꾸며 Spring coroutine,
Exposed transaction, multi-DB parameterization, production API는 변경하지 않는다.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, Spring Boot R2DBC, Exposed, JUnit 5,
`io.bluetape4k.assertions`, `bluetape-kotlin-patterns`.

---

## 0. 실행 경계와 안전 규칙

- Type A receipt `20260825T092059Z-41fee637`의 main lane과 선언된 write scope를
  유지한다. `.issue724-workflow/`는 receipt 입력용 untracked helper이며 commit하지 않는다.
- 모든 Gradle 명령은 `functions.exec` 내부의 context-mode `ctx_execute`
  (`language: "shell"`, current worktree, `--no-daemon --console=plain`)로
  실행한다. DB/Testcontainers 명령은 `concurrency=1`로 순차 실행한다.
- 구현 전 `mutation-check`가 `build.gradle.kts`, 대상 테스트 10개, spec/plan/review/lesson
  경로를 모두 덮는지 확인한다. 변경 경로가 write scope 밖이면 즉시 중단한다.
- production source, dependency catalog, convention plugin, workflow, README/API,
  Spring auto-configuration, SQL/transaction 구현은 수정하지 않는다.
- 매 task 후 `git diff --check`와 targeted source scan을 재실행한다.

## 1. 기준선과 RED 증거 고정

**파일:** 대상 10개 Kotlin 테스트와 `spring-boot/r2dbc/build.gradle.kts`

1. `gh issue view 724 --repo bluetape4k/bluetape4k-exposed --json number,title,body,state,assignees,milestone,labels,url`로 live Issue를 다시 읽고, spec의 파일 목록·완료 조건과 대조한다.
2. 대상 10개에 대해 다음을 집계한다.
   - `import kotlin.test.assert*`, `import org.junit.jupiter.api.Assertions`와 wildcard import
   - `kotlin.test.assert*`/`org.junit.jupiter.api.Assertions.assert*` fully-qualified 호출
   - `assertEquals`, `assertTrue`, `assertFalse`, `assertNotNull`, `assertContentEquals`,
     `assertSame`, `assertNotSame`, `assertThrows` 호출
   - `!!`, `println`, `System.out`, `System.err`
3. baseline 결과를 workflow evidence와 review artifact에 기록한다. 현재 legacy import가
   10개 파일에 존재하는 것이 의도된 RED 상태이며, production 동작 테스트를 임의로
   추가하지 않는다.
4. synthetic guard probe 입력은 import·wildcard·fully-qualified 호출·missing source
   root·unreadable source를 각각 표현한다. probe는 구현 후 RED→GREEN 검증에 사용하고
   저장소 source에 남기지 않는다.

## 2. 직접 dependency와 fail-closed guard 구현

**파일:** `spring-boot/r2dbc/build.gradle.kts`

1. 기존 `bt4k.bluetape4k.junit5` 선언과 분리된
   `testImplementation(bt4k.bluetape4k.assertions)`를 추가한다. version literal이나
   catalog 구조는 만들지 않는다.
2. `checkSpringBootR2dbcAssertionStyle` verification task를 등록한다.
   - `src/test/kotlin`을 `inputs.dir` 또는 동등한 `inputs.files`로 선언하고,
     `build/reports/spring-boot-r2dbc/assertion-style.txt`를 deterministic
     `outputs.file`로 선언해 Gradle up-to-date/cache 계약을 명시한다.
   - root가 없거나 directory가 아니면 `GradleException`으로 실패한다.
   - `.kt` 파일을 읽는 중 `IOException` 또는 scan 예외가 발생하면 예외를 삼키지 않고
     `GradleException`으로 실패한다.
   - 명시적 `kotlin.test.assert*`, `kotlin.test.*`,
     `org.junit.jupiter.api.Assertions`, `org.junit.jupiter.api.Assertions.*` import와
     두 namespace의 fully-qualified assertion 호출을 모두 차단한다. `kotlin.test.Test`,
     `org.junit.jupiter.api.Test`, `io.bluetape4k.assertions.*`는 허용한다.
   - 위치·rule만 `logger.error`로 보고하고 통과 메시지는 `logger.lifecycle`로 남긴다.
     `println`, `System.out`, `System.err`는 사용하지 않는다.
   - 구현 순서는 `sourceDir.asFile.isDirectory` 확인 → sorted Kotlin file inventory
     생성 → 각 파일 `readLines()` 예외 처리 → import/wildcard/FQ call regex 검사 →
     발견 목록을 report output에 기록하고 logger로 보고 → non-empty면
     `GradleException` 순서로 고정한다. 발견 목록이 비어도 source root/file
     inventory가 비어 있으면 fail-closed한다. report에는 위치·rule만 기록하고
     source 내용/사용자 값은 기록하지 않는다.
3. `tasks.named("check") { dependsOn(checkSpringBootR2dbcAssertionStyle) }`로 모듈
   verification graph에 연결한다.
4. synthetic probe로 각 차단 규칙과 fail-closed 경로가 실제 실패하는지 확인하고,
   probe를 제거한 뒤 `checkSpringBootR2dbcAssertionStyle`가 현재 source에 대해
   GREEN인지 확인한다.

## 3. assertion API 이관

**파일:** Issue #724에 명시된 10개 테스트만

1. 각 파일에서 legacy assertion import를 제거하고 필요한
   `io.bluetape4k.assertions` extension을 최소 import한다.
2. 의미별 변환은 다음 표를 그대로 따른다.

| 기존 | 변경 | 보존해야 하는 의미 |
| --- | --- | --- |
| `assertEquals(expected, actual)` | `actual shouldBeEqualTo expected` | 값 동등성·receiver/expected 순서 |
| `assertTrue(value)` / `assertFalse(value)` | `value.shouldBeTrue()` / `value.shouldBeFalse()` | Boolean 전용 matcher |
| `assertNotNull(value)` | `value.shouldNotBeNull()` | smart-cast 반환값 사용, 새 `!!` 금지 |
| `assertEquals(null, value)` | `value.shouldBeNull()` | null 검증 |
| `assertSame(expected, actual)` | `actual shouldBeSameInstanceAs expected` | 참조 동일성 |
| `assertNotSame(expected, actual)` | `actual shouldNotBeSameInstanceAs expected` | 참조 비동일성 |
| `assertContentEquals(expected, ByteArray)` | `ByteArray shouldBeEqualTo expected` | primitive array content equality |
| legacy `assertThrows` | `io.bluetape4k.assertions.assertFailsWith` | 예외 타입·본문 계약 |

3. 파일별 경계는 다음과 같이 확인한다.
   - `R2dbcFluentQueryIntegrationTest.kt`, `R2dbcFluentQueryMultiDbTest.kt`:
     Flow 수집·projection·pagination·multi-DB parameterization과 `runSuspendIO`를
     그대로 둔다.
   - `ExposedR2dbcRepositoryAbiCompatibilityTest.kt`:
     resource stream에 `shouldNotBeNull()`을 적용하고 public ABI 문자열 비교를 유지한다.
   - `R2dbcBindValueSnapshotterTest.kt`:
     `original`/`copy`는 identity matcher, ByteArray/ByteBuffer는 content matcher,
     List/BigDecimal/ID는 value matcher를 사용한다.
   - `R2dbcDiagnosticSanitizerTest.kt`:
     control-character/separator 차단과 길이·operation label 동등성을 보존한다.
   - `R2dbcExamplePredicateCompilerTest.kt`:
     compiler 결과의 nullable 반환을 `shouldNotBeNull()`로 smart-cast한다.
   - `R2dbcFluentQueryDirectConstructionTest.kt`, `R2dbcFluentQueryPlanTest.kt`:
     immutable plan의 identity, null, projection, limit 계약을 분리해 검증한다.
   - `R2dbcPersistentPropertyResolverTest.kt`:
     resolver name/value와 `includeNull` Boolean 의미를 보존한다.
   - `R2dbcTransactionLeaseTest.kt`:
     `CancellationException` 및 일반 failure의 동일 인스턴스 검증과
     `runSuspendIO` 경계를 변경하지 않는다.
4. 변환 중 production code나 테스트의 coroutine/transaction setup을 수정하지 않는다.

## 4. TDD/compile GREEN 게이트

1. Task 1의 baseline RED가 기록된 뒤 Task 2–3을 적용한다. 이 작업은 production
   behavior가 없는 표준화이므로 guard synthetic probe가 회귀 테스트 역할을 한다.
2. context-mode로 다음 명령을 순서대로 실행한다.
   - `./gradlew :bluetape4k-exposed-spring-boot-r2dbc:checkSpringBootR2dbcAssertionStyle --no-daemon --console=plain`
   - `./gradlew :bluetape4k-exposed-spring-boot-r2dbc:compileTestKotlin --no-daemon --console=plain`
3. 실패 시 출력 원인을 읽고 matcher import/type inference를 최소 수정한다. 실패를
   무시하거나 test를 skip하지 않는다.

## 5. targeted 및 module verification

모든 명령은 context-mode로 실행하며 real DB/Testcontainers가 필요한 명령은 동시에
실행하지 않는다.

1. 대상 unit/contract 테스트를 한 번에 실행한다.
   `./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test --tests '*R2dbcFluentQueryIntegrationTest' --tests '*R2dbcFluentQueryDirectConstructionTest' --tests '*R2dbcFluentQueryPlanTest' --tests '*R2dbcPersistentPropertyResolverTest' --tests '*R2dbcTransactionLeaseTest' --tests '*R2dbcBindValueSnapshotterTest' --tests '*R2dbcDiagnosticSanitizerTest' --tests '*R2dbcExamplePredicateCompilerTest' --tests '*ExposedR2dbcRepositoryAbiCompatibilityTest' --no-daemon --console=plain --no-parallel`
2. multi-DB 테스트를 별도 순차 실행한다.
   `./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test --tests '*R2dbcFluentQueryMultiDbTest' --no-daemon --console=plain --no-parallel`
3. module 전체 test를 실행한다.
   `./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test --no-daemon --console=plain --no-parallel`
4. 가능한 경우 module detekt를 실행한다.
   `./gradlew :bluetape4k-exposed-spring-boot-r2dbc:detekt --no-daemon --console=plain`
5. XML 결과에서 tests/failures/errors/skipped를 집계하고, skipped가 있으면 원인과
   scope를 기록한다. green compile만으로 완료를 주장하지 않는다.

## 6. forbidden scan·7-Tier·writer gate

1. 대상 10개에 대해 legacy import·wildcard·fully-qualified 호출·raw JUnit assertion,
   `!!`, `println`, `System.out`, `System.err`가 0인지 확인한다. `assertFailsWith`는
   Bluetape API 사용이므로 허용한다.
2. `git diff --check`를 실행하고 changed paths를 NUL-safe로 수집해 main lane
   write scope와 대조한다.
3. review artifact에 T1–T7과 main integration을 기록한다.
   - T1 요구사항/Issue 추적성
   - T2 API·Kotlin pattern·null-safety
   - T3 coroutine/transaction/lifecycle
   - T4 security/diagnostic/logger-only guard
   - T5 performance/test-cost
   - T6 operability/rollback/reproducibility
   - T7 delivery: Lore commit, exact push, Korean PR, hosted checks/review pending
4. 각 기술 artifact에 `bluetape-writer` SPW-01..05와 Korean naturalness KO-01..07을
   기록한다. review/lesson은 한국어 prose와 exact commands/paths를 유지한다.
5. P0/P1이 0이 아니면 다음 gate로 진행하지 않는다. P2/P3는 in-scope로 바로
   고칠 수 있을 때만 수정 후 같은 렌즈를 재검증한다.

## 7. lesson, commit, push, PR

1. `docs/lessons/2026-08-25-issue-724-spring-boot-r2dbc-assertions.md`에
   직접 dependency를 Type A로 재분류한 이유, guard 설계, assertion 의미 경계,
   검증 결과, 남은 hosted review/check 상태를 기록한다.
2. spec/plan/review/lesson 및 구현 변경을 read-back하고 `git diff --check` 후
   Type A receipt의 completion-check가 missing lane/check/evidence 0인지 확인한다.
3. Lore protocol을 지키는 한국어 commit을 하나 생성한다. intent line은 결정 이유를
   쓰고 `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`,
   `Not-tested` trailer를 포함한다.
4. `git push --set-upstream origin refactor/spring-boot-r2dbc-assertions` 후 exact
   head를 다시 읽는다.
5. 기존 PR 여부를 `gh pr list --repo bluetape4k/bluetape4k-exposed --head refactor/spring-boot-r2dbc-assertions`로 확인한 뒤 없을 때만 PR을 만든다.
   - base `develop`, head `refactor/spring-boot-r2dbc-assertions`
   - 한국어 title/body, assignee `debop`, milestone `2.0.0`, labels `test,refactoring,tech-debt`
   - `Closes #724`
   - body의 마지막 `##` section은 반드시 `## DoD Status`
6. `gh pr view --json`로 body/metadata/exact head를 read-back하고 `gh pr checks`를
   확인한다. merge·auto-merge는 수행하지 않는다.

## Acceptance traceability

| Issue #724 조건 | 계획 task | 증거 |
| --- | --- | --- |
| 10개 legacy/mixed 파일 이관 | 1, 3, 6 | import/call scan, targeted tests |
| 직접 `bluetape4k-assertions` dependency | 2 | build script read-back, compile |
| nullable/`!!` 방어 | 3, 6 | `shouldNotBeNull`, target scan |
| compile + unit + multi-DB | 4, 5 | context-mode Gradle receipts/XML |
| coroutine/transaction 경계 보존 | 3, 5, 6 | diff audit, lease/multi-DB tests |
| 7-Tier + Korean artifacts | 6, 7 | review/lesson SPW gate |
| commit/PR 후 merge 보류 | 7 | exact pushed head, open PR, no merge evidence |

## Rollback and rerun points

- compile/type mismatch: 해당 테스트 파일의 matcher import만 되돌리고 direct dependency는
  유지한 채 compile을 재실행한다.
- guard false positive: rule을 import/wildcard/call 단위로 좁히되 fail-closed source
  root/read 오류 계약은 유지한다.
- DB/Testcontainers failure: 실패 로그·환경·skipped 여부를 기록하고 같은 명령을
  병렬화하지 않은 상태로 재현한다. 실패를 성공으로 표시하지 않는다.
- delivery failure: commit은 보존하고 push/PR metadata를 재확인한다. merge는 별도
  fresh explicit approval 없이는 실행하지 않는다.
