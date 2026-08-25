# jdbc-tests Bluetape4k assertions 정규화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `exposed/jdbc-tests`가 published test-support의 Bluetape4k assertion API를 직접 노출하고 migration drift fixture와 module-local import 정책을 같은 assertion 계약으로 유지하도록 만든다.

**Architecture:** 기존 중앙 version catalog alias를 `api` configuration에 직접 선언하고, production 또는 migration SQL 실행 로직은 건드리지 않는다. migration fixture는 equality·identity·boolean·exception 의도를 Bluetape4k matcher로 표현하며, Gradle verification task는 고정된 `src/main/kotlin`·`src/test/kotlin`의 regular Kotlin 파일만 symlink 없이 검사해 `check`에 연결한다.

**Tech Stack:** Kotlin 2.4, Gradle Kotlin DSL, JUnit 5, Exposed JDBC, `io.github.bluetape4k:bluetape4k-assertions` (`bt4k.bluetape4k.assertions` catalog alias), Testcontainers.

---

## 변경 파일 지도

- Modify: `exposed/jdbc-tests/build.gradle.kts` — 직접 `api` dependency와 고정-root assertion import guard 및 `check` 연결을 소유한다.
- Modify: `exposed/jdbc-tests/src/test/kotlin/io/bluetape4k/exposed/tests/migration/JdbcMigrationDriftTest.kt` — raw JUnit assertion import와 호출을 intent-specific Bluetape4k matcher로 교체한다.
- Create: `docs/review/2026-08-25-issue-721-jdbc-tests-assertions-review.md` — 독립 렌즈 결과, 7-Tier 최종 검토, 명령별 증거를 기록한다.
- Create: `docs/lessons/2026-08-25-issue-721-jdbc-tests-assertions.md` — Type A 필수 lesson과 재발 방지 규칙을 기록한다.
- Do not modify: `Assertions.kt`, production Exposed 실행 로직, migration SQL helper, README/KDoc, module registration, workflow YAML, central catalog.

## Task 1: RED 기준선과 테스트 계약 고정

**Files:**
- Read: `exposed/jdbc-tests/build.gradle.kts`
- Read: `exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/Assertions.kt`
- Read: `exposed/jdbc-tests/src/test/kotlin/io/bluetape4k/exposed/tests/migration/JdbcMigrationDriftTest.kt`

- [ ] **Step 1: 현재 금지 import를 수량화한다.**

Run from the repository root:

```bash
rg -n '^import (org\.junit\.jupiter\.api\.Assertions|kotlin\.test\.assert|org\.assertj\.|org\.kluent\.)' \
  exposed/jdbc-tests/src/main/kotlin exposed/jdbc-tests/src/test/kotlin
```

Expected RED baseline: `JdbcMigrationDriftTest.kt`의 `Assertions.assertEquals`, `assertFalse`, `assertSame`, `assertTrue` 4개가 출력되고 다른 raw assertion import는 출력되지 않는다.

- [ ] **Step 2: direct alias와 기존 published main source 사용을 대조한다.**

```bash
rg -n 'bt4k\.bluetape4k\.(assertions|junit5)|io\.bluetape4k\.assertions' \
  exposed/jdbc-tests/build.gradle.kts \
  exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/Assertions.kt
```

Expected result: `Assertions.kt`는 이미 `io.bluetape4k.assertions`를 import하고, `build.gradle.kts`에는 `bt4k.bluetape4k.junit5`만 있으며 `bt4k.bluetape4k.assertions` direct declaration은 없다.

- [ ] **Step 3: 기존 fixture의 의미 계약을 기록한다.**

확인할 계약은 additive migration statement의 `statement` 원문 진단, `preservingFailure`의 primary identity, cleanup-only 예외, suppressed cleanup 예외, dialect별 cleanup/drop이다. 트랜잭션 rollback은 변경 대상 fixture의 새 동작으로 추가하지 않고, 기존 `AssertionsTest.assertFailAndRollback should rollback on failure` 회귀 계약과 `withDb`의 `maxAttempts = 1` 경계를 기준선으로 삼는다. 이번 변경에서 이 동작과 `withLogs = false`를 수정하지 않는다.

## Task 2: 고정-root import guard를 먼저 작성해 RED를 확인한다

**Files:**
- Modify: `exposed/jdbc-tests/build.gradle.kts`

- [ ] **Step 1: 고정 source root와 금지 import predicate를 추가한다.**

`build.gradle.kts`의 기존 `test`/`migrationDriftTest` task 선언 뒤에 다음 블록을 추가한다. root는 Gradle property나 환경 변수로 재지정할 수 없는 표준 module 경로로 고정한다. 두 root가 없거나 directory가 아니거나 root 자체가 symlink이면 즉시 `check`를 실패시키고, `Files.walk`의 기본 동작으로 symlink directory를 따라가지 않는 데 더해 발견된 symlink path도 즉시 실패시킨다. 따라서 누락·symlink·ancestor escape가 모두 fail-closed가 된다.

```kotlin
val bluetapeAssertionSourceRoots = listOf(
    layout.projectDirectory.dir("src/main/kotlin").asFile,
    layout.projectDirectory.dir("src/test/kotlin").asFile,
)

val verifyBluetapeAssertionImports = tasks.register("verifyBluetapeAssertionImports") {
    group = "verification"
    description = "Rejects raw assertion imports in jdbc-tests Kotlin sources."
    inputs.files(bluetapeAssertionSourceRoots)

    doLast {
        fun regularKotlinFiles(root: java.io.File): List<java.io.File> {
            val rootPath = root.toPath().toAbsolutePath().normalize()
            check(java.nio.file.Files.isDirectory(rootPath)) { "Missing Kotlin source root: $root" }
            check(!java.nio.file.Files.isSymbolicLink(rootPath)) { "Kotlin source root must not be a symlink: $root" }
            val realRoot = rootPath.toRealPath()
            return java.nio.file.Files.walk(rootPath).use { paths ->
                val allPaths = paths.toList()
                check(allPaths.none(java.nio.file.Files::isSymbolicLink)) {
                    "Symlink path is forbidden below Kotlin source root: $root"
                }
                allPaths
                    .filter { path ->
                        java.nio.file.Files.isRegularFile(path) &&
                            path.toRealPath().startsWith(realRoot) &&
                            path.fileName.toString().endsWith(".kt")
                    }
                    .map { it.toFile() }
            }
        }

        fun isForbiddenAssertionImport(line: String): Boolean {
            val imported = Regex(
                "^\\s*import\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*(?:\\s*\\.\\s*\\*)?)(?:\\s+as\\s+[A-Za-z_][A-Za-z0-9_]*)?\\s*$",
            ).matchEntire(line)?.groupValues?.get(1)?.replace(Regex("\\s"), "") ?: return false
            return imported == "org.junit.jupiter.api.Assertions" ||
                imported.startsWith("org.junit.jupiter.api.Assertions.") ||
                imported.startsWith("kotlin.test.assert") ||
                imported.startsWith("org.assertj.") ||
                imported.startsWith("org.kluent.")
        }

        val violations = bluetapeAssertionSourceRoots
            .flatMap(::regularKotlinFiles)
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (line.trimStart().startsWith("import") && isForbiddenAssertionImport(line)) {
                        "${file.relativeTo(projectDir)}:${index + 1}: $line"
                    } else {
                        null
                    }
                }
            }

        check(violations.isEmpty()) {
            "Raw assertion imports are forbidden in jdbc-tests:\n${violations.joinToString("\n")}"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyBluetapeAssertionImports)
}
```

이 predicate는 공백/탭, `Assertions`, `Assertions.*`, `import ... as A` alias를 포함한 `Assertions.<member>`와 `kotlin.test.assert*`, AssertJ/Kluent package를 금지하고 JUnit `Test`·lifecycle·parameterized annotation import는 금지하지 않는다. `toRealPath().startsWith(realRoot)`로 canonical containment도 확인하며, 추가 Kotlin source set이 생기면 guard 대상 목록과 source-set inventory 검증을 함께 갱신한다.

- [ ] **Step 2: RED probe로 guard가 실제 금지 import를 잡는지 확인한다.**

고정된 probe root 아래 이번 실행 전용의 unique path인 `src/test/kotlin/io/bluetape4k/exposed/tests/migration/RawAssertionImportProbe-<run-id>.kt`에 다음 임시 파일을 `apply_patch`로 만든다. 생성 전에 `test ! -e`로 충돌을 거부하고, probe를 만든 뒤 `trap`으로 정상 종료·실패·중단 모두에서 삭제한다. 삭제 후에도 `test ! -e`로 잔류를 확인하며, 기존 파일을 덮어쓰지 않는다.

```kotlin
package io.bluetape4k.exposed.tests.migration

import org.junit.jupiter.api.Assertions.*

private object RawAssertionImportProbe
```

Run:

```bash
./gradlew :bluetape4k-exposed-jdbc-tests:verifyBluetapeAssertionImports --no-daemon --console=plain
```

Expected result: FAIL with the unique probe path and the wildcard import in the violation list. Probe file를 `apply_patch`로 삭제하고, trap cleanup과 `test ! -e`를 통과시킨 뒤 이 임시 파일은 커밋하지 않는다. 같은 RED 단계에서 tab 및 alias import probe도 각각 추가해 모두 FAIL하는지 확인한다.

- [ ] **Step 3: guard의 negative/positive 경계를 수동 확인한다.**

`org.junit.jupiter.api.Test`, `org.junit.jupiter.api.Nested`, `org.junit.jupiter.params.ParameterizedTest` import는 기존 fixture에서 허용되어야 한다. regular `.kt` 외 파일과 source root 밖 파일은 검사 대상이 아니지만, `src/main/kotlin`·`src/test/kotlin` 외 Kotlin source set directory가 발견되면 검증을 실패시키고 guard 목록을 먼저 갱신한다. root 누락, root symlink, 하위 symlink probe는 모두 FAIL이어야 하며, `git diff --check`와 probe 잔류 검사를 통과한 뒤에만 다음 단계로 진행한다.

## Task 3: direct API dependency와 migration matcher를 최소 변경으로 적용한다

**Files:**
- Modify: `exposed/jdbc-tests/build.gradle.kts:76-84`
- Modify: `exposed/jdbc-tests/src/test/kotlin/io/bluetape4k/exposed/tests/migration/JdbcMigrationDriftTest.kt:3-197`

- [ ] **Step 1: published test-support API dependency를 직접 선언한다.**

기존 Bluetape4k API declaration 앞에 catalog alias 하나만 추가한다.

```kotlin
    // Bluetape4k
    compileOnly(bt4k.bluetape4k.jdbc)
    compileOnly(bt4k.bluetape4k.io)

    api(bt4k.bluetape4k.assertions)
    api(bt4k.bluetape4k.junit5)
    api(bt4k.bluetape4k.testcontainers)
```

버전을 build script에 추가하거나 local catalog를 만들지 않는다. `bluetape4k-dependencies` 중앙 catalog/BOM의 alias만 사용한다.

- [ ] **Step 2: raw JUnit assertion import를 Bluetape4k import로 교체한다.**

`JdbcMigrationDriftTest.kt`의 assertion import 영역을 다음으로 바꾼다.

```kotlin
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
```

JUnit `Nested`, `Tag`, `Test`, `ParameterizedTest`, `MethodSource` import는 유지한다.

- [ ] **Step 3: fixture assertion 호출을 의미별 matcher로 바꾼다.**

다음 exact replacement를 적용한다.

```kotlin
JdbcMigrationBaseline.exists().shouldBeFalse()
statements.size shouldBeEqualTo 1
MigrationUtils.statementsRequiredForDatabaseMigration(
    JdbcMigrationEvolved,
    withLogs = false,
).isEmpty().shouldBeTrue()
JdbcTypeChangeBaseline.exists().shouldBeFalse()
statements.isNotEmpty().shouldBeTrue()
statements.any { isExpectedH2TypeChange(it, JdbcTypeChangeEvolved.tableName, "value") }.shouldBeTrue()
statement shouldBeEqualTo validateAdditiveStatement(
    statement = statement,
    expectedTable = "jdbc_migration_drift",
    expectedColumn = "description",
)
assertFailsWith<IllegalArgumentException>(message = statement) {
    validateAdditiveStatement(
        statement = statement,
        expectedTable = "jdbc_migration_drift",
        expectedColumn = "description",
    )
}
val thrown = assertFailsWith<IllegalStateException> {
    preservingFailure(
        block = { throw primary },
        cleanup = {},
    )
}
thrown shouldBeSameInstanceAs primary
thrown.suppressed.isEmpty().shouldBeTrue()
thrown shouldBeSameInstanceAs cleanup
thrown.suppressed.toList() shouldBeEqualTo listOf(cleanup)
```

기존 `preservingFailure` block/cleanup, dialect parameterization, SQL validation, drop/rollback 순서와 `withLogs = false`는 그대로 두며, equality와 identity를 서로 바꾸지 않는다.

- [ ] **Step 4: direct API와 matcher 변경의 compile RED/GREEN 순서를 확인한다.**

먼저 `verifyBluetapeAssertionImports`를 실행해 기존 raw import 때문에 실패하는 RED 결과를 보관한다. Step 2와 Step 3 변경 후 같은 명령이 PASS하고, `compileTestKotlin`이 `io.bluetape4k.assertions` API를 직접 해석하는지 확인한다. `api`/`implementation` 차이는 compile task만으로 판정하지 않고 다음 두 Gradle surface를 읽는다.

```bash
./gradlew :bluetape4k-exposed-jdbc-tests:outgoingVariants --no-daemon --console=plain
./gradlew :bluetape4k-exposed-jdbc-tests:generatePomFileForBluetapeExposedPublication --no-daemon --console=plain
./gradlew :bluetape4k-exposed-jdbc-tests:generateMetadataFileForBluetapeExposedPublication --no-daemon --console=plain
rg -n 'bluetape4k-assertions' \
  exposed/jdbc-tests/build/publications/BluetapeExposed/pom-default.xml \
  exposed/jdbc-tests/build/publications/BluetapeExposed/module.json
```

Expected result: `apiElements` dependency view, `exposed/jdbc-tests/build/publications/BluetapeExposed/module.json`의 `apiElements` variant, `pom-default.xml`에 `io.github.bluetape4k:bluetape4k-assertions`가 consumer-visible dependency로 나타난다.

## Task 4: 모듈 검증과 dialect 증거 수집

**Files:**
- Read: Gradle 결과와 `build/test-results` 보고서
- Modify: none

- [ ] **Step 1: guard와 test compilation을 실행한다.**

Context-mode를 통해 순차 실행한다.

```bash
./gradlew :bluetape4k-exposed-jdbc-tests:verifyBluetapeAssertionImports --no-daemon --console=plain
./gradlew :bluetape4k-exposed-jdbc-tests:compileTestKotlin --no-daemon --console=plain
```

Expected result: 두 task 모두 PASS, raw assertion import 검색 결과 0건이다.

- [ ] **Step 2: H2 migration fixture와 전체 module test를 실행한다.**

```bash
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  --tests 'io.bluetape4k.exposed.tests.migration.JdbcMigrationDriftTest' \
  --no-build-cache --no-daemon --console=plain
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-jdbc-tests:test --no-build-cache --no-daemon --console=plain
```

Expected result: migration drift parameterized cases와 helper contract cases가 모두 PASS하고, module test도 PASS한다. `preservingFailure` primary/suppressed identity와 `statement` failure message를 결과에서 확인한다. 기존 `AssertionsTest.assertFailAndRollback should rollback on failure`도 같은 H2 module run에서 PASS해야 하며, 이 issue는 새로운 rollback semantics를 주장하지 않는다.

- [ ] **Step 3: PostgreSQL과 MySQL_V8을 순차 검증한다.**

```bash
EXPOSED_TEST_DB=POSTGRESQL ./gradlew :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  --tests 'io.bluetape4k.exposed.tests.migration.JdbcMigrationDriftTest' \
  --no-build-cache --no-daemon --console=plain
EXPOSED_TEST_DB=MYSQL_V8 ./gradlew :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  --tests 'io.bluetape4k.exposed.tests.migration.JdbcMigrationDriftTest' \
  --no-build-cache --no-daemon --console=plain
```

각 실행은 `TestDB` 선택 계약상 H2와 선택 dialect를 함께 포함한다. 따라서 H2/helper 검사는 Step 2에서 기준 증거를 한 번 확보하고, PostgreSQL/MySQL 실행에서 반복되는 H2 케이스는 의도된 고정 비용으로 별도 집계한다. 각 JUnit XML의 `tests`, `failures`, `errors`, `skipped`를 읽어 H2는 7 tests/0 failures/0 errors/0 skipped, PostgreSQL과 MySQL_V8은 각각 8 tests/0 failures/0 errors/0 skipped인지 확인한다. XML이 없거나 count가 0/부분 실행이면 PASS가 아니라 `PENDING`/FAIL이다. 두 container lifecycle, dialect 문맥, cleanup/drop 경로를 각각 기록하며, 중복 실행을 단일 dialect PASS로 축약하지 않는다. Docker가 준비되지 않으면 `colima status`, `docker context show`, `docker info`를 먼저 확인한다. 세 dialect 증거가 없으면 PR DoD를 PASS로 표시하지 않고 `PENDING`으로 남긴다.

`withDb`의 `maxAttempts = 1`을 재확인하고, 실패 후 재실행이 필요하면 첫 시도의 JUnit XML·exit status를 보존한다. 재실행만 green인 경우 retry-only PASS로 표시하지 않고 원인 분석 대기 `PENDING`으로 남긴다. Docker preflight 전후 `docker ps --format '{{.Names}}\t{{.Status}}'`와 테스트 종료 로그를 읽어 container 종료·connection cleanup·shutdown-hook 잔여가 없는지 확인하며, 자격 증명·토큰·전체 JDBC URL은 증거 문서에 기록하지 않는다.

- [ ] **Step 4: 정적·diff·Kotlin checklist 검증을 실행한다.**

```bash
set -euo pipefail
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-jdbc-tests:check --no-build-cache --no-daemon --console=plain
if rg -n '^import[[:space:]]+(org\.junit\.jupiter\.api\.Assertions(\.|[[:space:]]+as[[:space:]]|$)|kotlin\.test\.assert|org\.assertj\.|org\.kluent\.)' \
  exposed/jdbc-tests/src/main/kotlin exposed/jdbc-tests/src/test/kotlin; then
  echo 'forbidden raw assertion import found' >&2
  exit 1
fi
if rg -n 'println\(|System\.(out|err)' exposed/jdbc-tests/build.gradle.kts \
  exposed/jdbc-tests/src/main/kotlin exposed/jdbc-tests/src/test/kotlin; then
  echo 'stdout/stderr call found' >&2
  exit 1
fi
test "$(find exposed/jdbc-tests/src -type d -name kotlin -print | sort)" = \
  $'exposed/jdbc-tests/src/main/kotlin\nexposed/jdbc-tests/src/test/kotlin'
git diff --check
```

Expected result: H2로 고정된 `check` PASS, raw import와 새 `println`/`System.out`/`System.err` 검색 0건, diff whitespace 오류 0건이다. `check`는 PostgreSQL/MySQL container를 다시 시작하지 않으며, 앞선 targeted dialect 실행과의 중복 비용은 Step 3의 선택 dialect 증거로 한정한다. 결과 문서에는 sanitized counts/status만 남기고 password/token/secret/private JDBC URL을 redaction한다. `$bluetape-kotlin-patterns` testing checklist KT-01..KT-11과 7-Tier P0/P1=0을 review artifact에 연결한다.

## Task 5: 독립 검토 결과와 lesson을 기록한다

**Files:**
- Create: `docs/review/2026-08-25-issue-721-jdbc-tests-assertions-review.md`
- Create: `docs/lessons/2026-08-25-issue-721-jdbc-tests-assertions.md`

- [ ] **Step 1: 최종 7-Tier review artifact를 작성한다.**

한국어 표에 다음을 포함한다: 변경 파일/비변경 경계, dependency API classpath, raw import guard의 고정 root·symlink·alias 정책, matcher 의미 보존, H2 및 가능한 PostgreSQL/MySQL 실행 결과, `println` 검색, `git diff --check`, 독립 performance/stability/security/ops/api/user 렌즈의 provenance와 P0-P3 판정. 문서 self-review는 미완성 placeholder가 없다는 검색과 writer Korean terminology audit를 통과해야 한다.

- [ ] **Step 2: Type A lesson을 작성한다.**

lesson에는 context(직접 사용하는 published API의 누락), decision(`api` direct declaration과 module-local guard), evidence(RED/GREEN 및 dialect 결과), surprise(테스트 지원 main source도 published API classpath 계약을 가진다는 점), future guard(새 raw assertion import는 `check`에서 차단)를 기록한다. `docs/lessons/`의 기존 한국어 문체와 Lore evidence를 따른다.

- [ ] **Step 3: 문서와 구현 산출물을 함께 self-review한다.**

```bash
rg -n '미완성|placeholder' \
  docs/superpowers/plans/2026-08-25-issue-721-jdbc-tests-assertions-plan.md \
  docs/review/2026-08-25-issue-721-jdbc-tests-assertions.md \
  docs/lessons/2026-08-25-issue-721-jdbc-tests-assertions.md
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/review/2026-08-25-issue-721-jdbc-tests-assertions.md \
  docs/lessons/2026-08-25-issue-721-jdbc-tests-assertions.md
git diff --check
```

증거 파일과 review/lesson에는 테스트 count, exit status, sanitized lifecycle 상태만 기록한다. `password`, `token`, `secret`, private host, credential-bearing JDBC URL과 container 환경 dump는 redaction하고 원문 로그를 커밋하지 않는다. 문서에 남기는 failure message는 assertion 계약을 검증하는 데 필요한 statement/message prefix만 보존한다.

## Task 6: 커밋·push·PR 전달

**Files:**
- Modify: none after Task 5 evidence is complete

- [ ] **Step 1: Lore 형식으로 implementation commit을 만든다.**

커밋 의도는 “published test-support의 assertion classpath를 명시하고 fixture 정책을 강제한다”로 작성한다. 메시지에는 `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, `Not-tested` trailer를 포함하고, `.issue721-workflow/` 임시 증거 JSON은 커밋에서 제외한다.

- [ ] **Step 2: branch를 push하고 exact head를 확인한다.**

```bash
git diff --check
git status --short
git rev-parse HEAD
git push -u origin refactor/jdbc-tests-bluetape-assertions
git ls-remote origin refs/heads/refactor/jdbc-tests-bluetape-assertions
```

`git diff --name-only origin/develop...HEAD`가 다음 허용 경로만 포함하는지 확인한다: `exposed/jdbc-tests/build.gradle.kts`, `exposed/jdbc-tests/src/test/kotlin/io/bluetape4k/exposed/tests/migration/JdbcMigrationDriftTest.kt`, 현재 Issue #721의 `docs/superpowers/`, `docs/review/`, `docs/lessons/` 산출물. 그 밖의 경로가 있으면 push를 중단하고 원인을 정리한다. `git diff origin/develop...HEAD --binary | rg -n '(password|token|secret|jdbc:[^[:space:]]+@)'`가 0건인지 확인하고, `git remote get-url origin`이 기대한 GitHub 저장소인지 live-read한다. 로컬 HEAD와 원격 branch HEAD가 같은지 확인한 뒤 PR을 만든다.

- [ ] **Step 3: Korean PR을 생성하고 live metadata를 맞춘다.**

PR base는 `develop`, head는 `refactor/jdbc-tests-bluetape-assertions`, 제목은 `test(jdbc-tests): bluetape4k-assertions 직접 의존성과 migration fixture를 정규화한다`로 한다. body에는 `Closes #721`과 검증 명령/결과를 포함하고 마지막 heading을 `## DoD Status`로 둔다. Issue #721의 assignee `debop`, milestone `2.0.0`, labels `test`, `refactoring`, `tech-debt`를 PR에도 반영한다.

- [ ] **Step 4: merge 전 상태를 확인하고 멈춘다.**

```bash
gh pr view --json number,url,state,headRefName,baseRefName,headRefOid,body,assignees,milestone,labels,statusCheckRollup,reviews,mergeable,mergeStateStatus
```

PR exact head, CI, review/thread, mergeability를 live-read한 뒤 merge하지 않는다. hosted CI/review가 추가로 대기하면 PR 상태를 `PENDING`으로 보고하고 다음 이슈로 넘어가기 전 사용자가 별도로 merge 승인할 수 있게 한다.

## Plan self-review

- Spec coverage: direct `api`, matcher semantics, diagnostic message, import policy, fixed roots, symlink boundary, RED probe, H2/cross-dialect validation, no-production-change boundary, 7-Tier review, lesson, Lore commit, PR DoD가 각각 Task 1–6에 매핑되어 있다.
- Placeholder scan: 계획 본문에 미완성 placeholder를 남기지 않는다.
- Type consistency: Gradle task 이름은 `verifyBluetapeAssertionImports`, matcher 이름은 `assertFailsWith`, `shouldBeEqualTo`, `shouldBeFalse`, `shouldBeSameInstanceAs`, `shouldBeTrue`로 모든 단계에서 동일하다.
