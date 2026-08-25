# jdbc-tests Bluetape4k assertions 정규화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `exposed/jdbc-tests`가 published test-support의 Bluetape4k assertion API를 직접 노출하고 migration drift fixture와 module-local import 정책을 같은 assertion 계약으로 유지하도록 만든다.

**Architecture:** 기존 중앙 version catalog alias를 `api` configuration에 직접 선언하고, production 또는 migration SQL 실행 로직은 건드리지 않는다. migration fixture는 equality·identity·boolean·exception 의도를 Bluetape4k matcher로 표현하며, Gradle verification task는 고정된 `src/main/kotlin`·`src/test/kotlin`의 regular Kotlin 파일만 symlink 없이 검사해 `check`에 연결한다.

**Tech Stack:** Kotlin 2.4, Gradle Kotlin DSL, JUnit 5, Exposed JDBC, `io.github.bluetape4k:bluetape4k-assertions` (`bt4k.bluetape4k.assertions` catalog alias), Testcontainers.

## 실행 승인 게이트

- 계획·독립 렌즈 검토가 모두 끝나고 P0/P1/P2가 0건이 된 뒤, source
  mutation 전에 Issue #721의 구현 계획 승인 증거를 workflow receipt에 기록한다.
  현재 실행의 사용자 지시인 “이슈들을 순서대로 작업하자”는 이슈 단위 작업
  권한이며, 계획 승인 receipt가 없는 상태에서는 Task 2 이후의 파일 변경을
  시작하지 않는다.
- ABI baseline 변경 승인과 PR merge 승인은 별도다. baseline이 바뀌거나 merge가
  필요한 경우 이 계획의 PASS를 근거로 자동 진행하지 않고 별도 승인 게이트에서
  멈춘다.

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
rg -n '^import (org\.junit\.jupiter\.api\.Assertions|org\.junit\.jupiter\.api\.assertThrows|kotlin\.test\.assert|org\.assertj\.|org\.kluent\.)' \
  exposed/jdbc-tests/src/main/kotlin exposed/jdbc-tests/src/test/kotlin
```

Expected RED baseline: `JdbcMigrationDriftTest.kt`의 `Assertions.assertEquals`, `assertFalse`, `assertSame`, `assertTrue`와 `org.junit.jupiter.api.assertThrows` 등 5개 raw assertion import가 출력되고 다른 raw assertion import는 출력되지 않는다.

- [ ] **Step 2: direct alias와 기존 published main source 사용을 대조한다.**

```bash
rg -n 'bt4k\.bluetape4k\.(assertions|junit5)|io\.bluetape4k\.assertions' \
  exposed/jdbc-tests/build.gradle.kts \
  exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/Assertions.kt
```

Expected result: `Assertions.kt`는 이미 `io.bluetape4k.assertions`를 import하고, `build.gradle.kts`에는 `bt4k.bluetape4k.junit5`만 있으며 `bt4k.bluetape4k.assertions` direct declaration은 없다.

- [ ] **Step 3: 기존 fixture의 의미 계약을 기록한다.**

확인할 계약은 additive migration statement의 `statement` 원문 진단, `preservingFailure`의 primary identity, cleanup-only 예외, suppressed cleanup 예외, dialect별 cleanup/drop이다. 트랜잭션 rollback 상태 검증은 이번 assertion migration 범위 밖으로 명시하고, 기존 `AssertionsTest.assertFailAndRollback` 구현과 `withDb`의 `maxAttempts = 1` 경계를 변경하지 않는다. 이번 변경에서 이 동작과 `withLogs = false`를 수정하지 않는다.

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
            val projectPath = projectDir.toPath().toRealPath()
            val realRoot = rootPath.toRealPath()
            check(realRoot.startsWith(projectPath)) {
                "Kotlin source root must stay inside project directory: $root"
            }
            var ancestor = rootPath
            while (ancestor != projectPath) {
                check(!java.nio.file.Files.isSymbolicLink(ancestor)) {
                    "Kotlin source root ancestor must not be a symlink: $ancestor"
                }
                ancestor = checkNotNull(ancestor.parent) { "Source root escaped project directory: $root" }
            }
            return java.nio.file.Files.walk(rootPath).use { paths ->
                val allPaths = paths.toList()
                check(allPaths.none { path -> java.nio.file.Files.isSymbolicLink(path) }) {
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
            val importLine = line.trim()
            val importIndex = line.indexOf("import")
            val leading = if (importIndex < 0) "" else line.substring(0, importIndex).trim()
            check(importIndex < 0 || leading.isEmpty() || leading.endsWith("*/")) {
                "Import hidden behind a leading comment is rejected by the guard: $line"
            }
            check(!importLine.endsWith(".") && !importLine.endsWith(" as")) {
                "Incomplete or continued import declaration is rejected by the guard: $line"
            }
            check(';' !in importLine) {
                "Semicolon-terminated import declarations are rejected by the guard: $line"
            }
            check("/*" !in importLine && "*/" !in importLine && "//" !in importLine) {
                "Comments in import declarations are rejected by the guard: $line"
            }
            val imported = importLine
                .removePrefix("import")
                .trim()
                .replace(Regex("\\s+as\\s+[A-Za-z_][A-Za-z0-9_]*\\s*$"), "")
                .replace("`", "")
                .replace(Regex("\\s*\\.\\s*"), ".")
            check(imported.isNotBlank() && !Regex("\\s").containsMatchIn(imported)) {
                "Unparseable import declaration is rejected by the guard: $line"
            }
            return imported == "org.junit.jupiter.api.Assertions" ||
                imported.startsWith("org.junit.jupiter.api.Assertions.") ||
                imported == "org.junit.jupiter.api.assertThrows" ||
                imported.startsWith("kotlin.test.assert") ||
                imported.startsWith("org.assertj.") ||
                imported.startsWith("org.kluent.")
        }

        val expectedKotlinRoots = bluetapeAssertionSourceRoots
            .map { it.toPath().toAbsolutePath().normalize().toRealPath() }
            .toSet()
        val unexpectedKotlinSources = tasks
            .withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>()
            .flatMap { it.source.files }
            .filter { it.extension == "kt" }
            .filter { file ->
                val sourcePath = file.toPath().toAbsolutePath().normalize().toRealPath()
                expectedKotlinRoots.none { root -> sourcePath.startsWith(root) }
            }
        check(unexpectedKotlinSources.isEmpty()) {
            "Kotlin compile source is outside the fixed guard roots: $unexpectedKotlinSources"
        }

        val violations = bluetapeAssertionSourceRoots
            .flatMap(::regularKotlinFiles)
            .flatMap { file ->
                var blockCommentOpen = false
                file.readLines().mapIndexedNotNull { index, line ->
                    val original = line.trimStart()
                    var remainder = original
                    var strippedComment = false
                    if (blockCommentOpen) {
                        val closes = remainder.indexOf("*/")
                        if (closes < 0) return@mapIndexedNotNull null
                        blockCommentOpen = false
                        remainder = remainder.substring(closes + 2).trimStart()
                        strippedComment = true
                    }
                    while (remainder.startsWith("/*")) {
                        val closes = remainder.indexOf("*/", 2)
                        if (closes < 0) {
                            blockCommentOpen = true
                            return@mapIndexedNotNull null
                        }
                        remainder = remainder.substring(closes + 2).trimStart()
                        strippedComment = true
                    }
                    if (strippedComment && (remainder.startsWith("import") || remainder.startsWith("."))) {
                        check(false) { "Import hidden after a block comment is rejected by the guard: ${file.relativeTo(projectDir)}:${index + 1}" }
                    }
                    if (remainder.startsWith(".") || (remainder.startsWith("//") && "import" in remainder)) {
                        check(false) { "Import continuation/comment bypass is rejected by the guard: ${file.relativeTo(projectDir)}:${index + 1}" }
                    }
                    if (remainder.startsWith("import") && isForbiddenAssertionImport(remainder)) {
                        "${file.relativeTo(projectDir)}:${index + 1}: $remainder"
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

tasks.named("test") {
    dependsOn(verifyBluetapeAssertionImports)
}
tasks.named("migrationDriftTest") {
    dependsOn(verifyBluetapeAssertionImports)
}
tasks.named("check") {
    dependsOn(verifyBluetapeAssertionImports)
}
```

이 predicate는 선행 공백/탭, `Assertions`, `Assertions.*`, `import ... as A` alias를 포함한 `Assertions.<member>`, top-level `org.junit.jupiter.api.assertThrows`, `kotlin.test.assert*`, AssertJ/Kluent package를 금지하고 JUnit `Test`·lifecycle·parameterized annotation import는 금지하지 않는다. `toRealPath().startsWith(realRoot)`로 canonical containment도 확인하며, 추가 Kotlin source set이 생기면 guard 대상 목록과 source-set inventory 검증을 함께 갱신한다.

- [ ] **Step 2: RED probe로 guard가 실제 금지 import를 잡는지 확인한다.**

고정된 probe root 아래 이번 실행 전용의 unique path인 `src/test/kotlin/io/bluetape4k/exposed/tests/migration/RawAssertionImportProbe-<run-id>.kt`에 다음 임시 파일을 `apply_patch`로 만든다. 생성 전에 `test ! -e`로 충돌을 거부하고, probe를 만든 뒤 `trap`으로 정상 종료·실패·중단 모두에서 삭제한다. 삭제 후에도 `test ! -e`로 잔류를 확인하며, 기존 파일을 덮어쓰지 않는다.

```kotlin
package io.bluetape4k.exposed.tests.migration

import org.junit.jupiter.api.Assertions.*

private object RawAssertionImportProbe
```

Run:

```bash
./gradlew :bluetape4k-exposed-jdbc-tests:verifyBluetapeAssertionImports --no-build-cache --no-configuration-cache --no-parallel --max-workers=1 --rerun-tasks --no-daemon --console=plain
```

Expected result: FAIL with the unique probe path and the wildcard import in the violation list. Probe file를 `apply_patch`로 삭제하고, trap cleanup과 `test ! -e`를 통과시킨 뒤 이 임시 파일은 커밋하지 않는다. 같은 RED 단계에서 tab, alias, backtick-escaped segment, dotted-segment spacing, semicolon-terminated import, line-comment/block-comment import probe도 각각 추가해 모두 FAIL 또는 fail-closed rejection인지 확인한다.

- [ ] **Step 3: guard의 negative/positive 경계를 수동 확인한다.**

`org.junit.jupiter.api.Test`, `org.junit.jupiter.api.Nested`, `org.junit.jupiter.params.ParameterizedTest` import는 기존 fixture에서 허용되어야 한다. regular `.kt` 외 파일과 source root 밖 파일은 검사 대상이 아니지만, `compileKotlin`/`compileTestKotlin`의 실제 `.kt` source file이 고정 root 밖에서 발견되면 검증을 실패시키고 guard 목록을 먼저 갱신한다. root 누락, root symlink, 하위 symlink, 불완전 continuation import probe는 모두 FAIL이어야 하며, `git diff --check`와 probe 잔류 검사를 통과한 뒤에만 다음 단계로 진행한다.

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

먼저 `verifyBluetapeAssertionImports`를 실행해 기존 raw import 때문에 실패하는 RED 결과를 보관한다. Step 2와 Step 3 변경 후 같은 명령이 PASS하고, `compileTestKotlin`이 `io.bluetape4k.assertions` API를 직접 해석하는지 확인한다. `api`/`implementation` 차이는 compile task만으로 판정하지 않고 다음 Gradle surface와 module ABI baseline을 함께 읽는다.

```bash
./gradlew :bluetape4k-exposed-jdbc-tests:outgoingVariants --no-build-cache --no-configuration-cache --no-parallel --max-workers=1 --rerun-tasks --no-daemon --console=plain
./gradlew :bluetape4k-exposed-jdbc-tests:generatePomFileForBluetapeExposedPublication --no-build-cache --no-configuration-cache --no-parallel --max-workers=1 --rerun-tasks --no-daemon --console=plain
./gradlew :bluetape4k-exposed-jdbc-tests:generateMetadataFileForBluetapeExposedPublication --no-build-cache --no-configuration-cache --no-parallel --max-workers=1 --rerun-tasks --no-daemon --console=plain
rg -n 'bluetape4k-assertions' \
  exposed/jdbc-tests/build/publications/BluetapeExposed/pom-default.xml \
  exposed/jdbc-tests/build/publications/BluetapeExposed/module.json
```

Expected result: `apiElements` dependency view, `exposed/jdbc-tests/build/publications/BluetapeExposed/module.json`의 `apiElements` variant, `pom-default.xml`에 `io.github.bluetape4k:bluetape4k-assertions`가 consumer-visible dependency로 나타난다.

추가로 기존 ABI baseline의 digest를 먼저 저장하고 module-local ABI 검사를 실행한다.
baseline 파일이 없거나 비어 있거나 검사 중 변경되면 PASS로 처리하지 않는다.

```bash
set -euo pipefail
baseline='api/bluetape4k-exposed-jdbc-tests.api'
test -s "$baseline"
baseline_sha256=$(shasum -a 256 "$baseline" | awk '{print $1}')
./gradlew :bluetape4k-exposed-jdbc-tests:checkKotlinAbi \
  --no-build-cache --no-configuration-cache --no-parallel --max-workers=1 \
  --rerun-tasks --no-daemon --console=plain
test "$baseline_sha256" = "$(shasum -a 256 "$baseline" | awk '{print $1}')"
rg -n 'bluetape4k-exposed-jdbc-tests|bluetape4k-assertions' \
  api/bluetape4k-exposed-jdbc-tests.api \
  exposed/jdbc-tests/build/publications/BluetapeExposed/module.json \
  exposed/jdbc-tests/build/publications/BluetapeExposed/pom-default.xml
```

`checkKotlinAbi`가 새 public symbol을 발견하거나 baseline digest가 바뀌면 기준선
변경 승인이 없는 한 FAIL이다. `checkProductionAbi` 전체 aggregate는 이 issue의
module-local gate를 대체하지 않으며, 실행한 경우에도 해당 module 결과와 baseline
digest를 별도 receipt로 남긴다.

`api`가 실제 소비자에게 보이는지 확인하기 위해 게시 산출물만 선언하는 임시
consumer fixture도 만든다. fixture와 Maven repository는 repository 밖의 서로
다른 unique `mktemp -d` 경로에 만들고, Gradle init script로 현재 publication을
그 임시 repository에만 publish한다. global `mavenLocal()`은 사용하지 않는다.
`settings.gradle.kts`, `build.gradle.kts`, `src/main/kotlin/Consumer.kt`는
`apply_patch`로 생성하고, consumer는 정확한 `io.github.bluetape4k:
bluetape4k-exposed-jdbc-tests:<version>` 좌표와 임시 repository만 선언하고,
`bluetape4k-junit5` dependency를 exclude해 assertion API가 junit5의
transitive edge로 우연히 해결되지 않도록 한다. consumer는
`io.bluetape4k.assertions.shouldBeEqualTo`를 호출한 뒤 `:compileKotlin`을
실행한다. publish 전후 POM/module JSON와 임시 repository의 artifact/POM
checksum을 대조해 stale/poisoned artifact를 배제한다. fixture와 init script는
종료 시 `trap`으로 삭제하고 두 경로가 남지 않았는지 확인한다. module
publication 또는 direct assertion API가 누락되면 compile이 실패한다. 동시에
`jq`로 `module.json`의 `apiElements.dependencies`에
`io.github.bluetape4k:bluetape4k-assertions`가 직접 존재하는지 확인하고,
성공한 경우에만 `api` consumer visibility를 PASS로 기록한다. fixture에는
credential, private URL, 전체 환경 dump를 남기지 않는다.

아래 두 명령은 다음의 완전한 orchestration에서 `init_script`,
`ISSUE721_CONSUMER_REPO`, `ISSUE721_MODULE_VERSION`을 초기화하고 publish가
성공한 뒤에만 실행하는 subordinate assertion이다. 이 블록만 독립 실행하지
않는다.

```bash
set -euo pipefail
./gradlew -I "$init_script" \
  :bluetape4k-exposed-jdbc-tests:publishBluetapeExposedPublicationToIssue721ConsumerRepository \
  --no-build-cache --no-configuration-cache --no-parallel --max-workers=1 \
  --rerun-tasks --no-daemon --console=plain
jq -e '.variants[] | select(.name == "apiElements") | .dependencies[] |
  select(.group == "io.github.bluetape4k" and .module == "bluetape4k-assertions")' \
  exposed/jdbc-tests/build/publications/BluetapeExposed/module.json
```

`init_script`는 `maven-publish` 프로젝트에만 `Issue721Consumer` repository를
추가하고, URL은 `ISSUE721_CONSUMER_REPO` 환경 변수의 unique temporary path로
고정한다. consumer build는 그 repository 외의 dependency source를 선언하지
않으며, publish 결과의 group/artifact/version과 SHA-256을 fixture resolution
전에 확인한다.

init script의 최소 내용은 다음과 같다.

```groovy
allprojects {
    plugins.withId('maven-publish') {
        publishing {
            repositories {
                maven {
                    name = 'Issue721Consumer'
                    url = uri(System.getenv('ISSUE721_CONSUMER_REPO'))
                }
            }
        }
    }
}
```

consumer `settings.gradle.kts`는 `repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)`와 임시 repository 및 `mavenCentral()`만 선언하고 `mavenLocal()`은 선언하지 않는다. `build.gradle.kts`의 target dependency에는 `exclude(group = "io.github.bluetape4k", module = "bluetape4k-junit5")`를 넣고, `Consumer.kt`는 `1 shouldBeEqualTo 1`만 컴파일한다. `<version>`은 publish 직후 `module.json`의 `.component.version`에서 읽어 같은 값으로 치환한다.

fixture 파일은 다음 전문으로 생성한다.

`settings.gradle.kts`

```kotlin
import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(System.getenv("ISSUE721_CONSUMER_REPO")) }
        mavenCentral()
    }
}
rootProject.name = "issue721-consumer"
```

`build.gradle.kts`

```kotlin
plugins { kotlin("jvm") version "2.4.10" }

val moduleVersion = providers.environmentVariable("ISSUE721_MODULE_VERSION").get()
dependencies {
    implementation("io.github.bluetape4k:bluetape4k-exposed-jdbc-tests:$moduleVersion") {
        exclude(group = "io.github.bluetape4k", module = "bluetape4k-junit5")
    }
}
```

`src/main/kotlin/Consumer.kt`

```kotlin
package issue721.consumer

import io.bluetape4k.assertions.shouldBeEqualTo

fun compileProbe() {
    1 shouldBeEqualTo 1
}
```

실행 orchestration은 다음처럼 `set -euo pipefail`, unique temp 경로, init-script
경로, version export, 실패 시에도 정리되는 `trap`, publish exit status, fixture
`:compileKotlin` exit status와 잔류 경로 검사를 모두 포함한다.

```bash
set -euo pipefail
repo_worktree=$(pwd)
consumer_tmp=$(mktemp -d "${TMPDIR:-/tmp}/issue721-consumer.XXXXXX")
repo_tmp=$(mktemp -d "${TMPDIR:-/tmp}/issue721-repo.XXXXXX")
init_script="$consumer_tmp/issue721-publish.init.gradle"
export ISSUE721_CONSUMER_REPO="$repo_tmp"
trap 'rm -rf -- "$consumer_tmp" "$repo_tmp"; test ! -e "$consumer_tmp"; test ! -e "$repo_tmp"' EXIT HUP INT TERM
test ! -e "$consumer_tmp/settings.gradle.kts"
test ! -e "$consumer_tmp/build.gradle.kts"
test ! -e "$consumer_tmp/src/main/kotlin/Consumer.kt"
# 위 fixture/init 파일은 각각 apply_patch로 생성한다.
module_version=$(jq -er '.component.version' exposed/jdbc-tests/build/publications/BluetapeExposed/module.json)
export ISSUE721_MODULE_VERSION="$module_version"
./gradlew -I "$init_script" \
  :bluetape4k-exposed-jdbc-tests:publishBluetapeExposedPublicationToIssue721ConsumerRepository \
  --no-build-cache --no-configuration-cache --no-parallel --max-workers=1 \
  --rerun-tasks --no-daemon --console=plain
test -s "$repo_tmp/io/github/bluetape4k/bluetape4k-exposed-jdbc-tests/$module_version/bluetape4k-exposed-jdbc-tests-$module_version.pom"
test -s "$repo_tmp/io/github/bluetape4k/bluetape4k-exposed-jdbc-tests/$module_version/bluetape4k-exposed-jdbc-tests-$module_version.jar"
published_jar="$repo_tmp/io/github/bluetape4k/bluetape4k-exposed-jdbc-tests/$module_version/bluetape4k-exposed-jdbc-tests-$module_version.jar"
published_pom="$repo_tmp/io/github/bluetape4k/bluetape4k-exposed-jdbc-tests/$module_version/bluetape4k-exposed-jdbc-tests-$module_version.pom"
local_jar="exposed/jdbc-tests/build/libs/bluetape4k-exposed-jdbc-tests-$module_version.jar"
local_pom="exposed/jdbc-tests/build/publications/BluetapeExposed/pom-default.xml"
test "$(shasum -a 256 "$local_jar" | awk '{print $1}')" = "$(shasum -a 256 "$published_jar" | awk '{print $1}')"
test "$(shasum -a 256 "$local_pom" | awk '{print $1}')" = "$(shasum -a 256 "$published_pom" | awk '{print $1}')"
(cd "$consumer_tmp" && "$repo_worktree/gradlew" --no-daemon --console=plain --no-configuration-cache --no-build-cache --no-parallel --max-workers=1 --rerun-tasks compileKotlin)
test ! -e "$consumer_tmp"; test ! -e "$repo_tmp"
```

마지막으로 matcher 진단 의미를 성공 사례만으로 추론하지 않는다. 고정된 임시
Kotlin probe에서 `assertFailsWith<IllegalArgumentException>(message = statement) {
}`처럼 예외가 발생하지 않는 경로를 `assertFailsWith<AssertionError>`로 감싼
뒤, 실패 진단이 `statement` prefix와 `Expected IllegalArgumentException`을
포함하는지 확인한다. 별도 probe에서는 `val original =
IllegalArgumentException("original")`을 던지고 반환된 예외의 `message`가
`original.message`와 같은지 확인해 `message = statement`가 원래 예외 message를
변경하지 않는다는 점을 직접 검증한다. probe는 unique path를 사용하고
정상·실패·중단 모두 삭제하며, 결과는 sanitized prefix와 exit status만 기록한다.

## Task 4: 모듈 검증과 dialect 증거 수집

**Files:**
- Read: Gradle 결과와 `build/test-results` 보고서
- Modify: none

- [ ] **Step 1: guard와 test compilation을 실행한다.**

각 명령은 `mcp__context_mode__ctx_execute`의 `language: "shell"`, 해당
worktree `cwd`, bounded `timeout`으로 한 번에 하나씩 순차 실행한다. Gradle
출력은 context-mode 안에서 요약·검색하고, raw build log를 대화 맥락이나
커밋에 복사하지 않는다.

```bash
./gradlew :bluetape4k-exposed-jdbc-tests:verifyBluetapeAssertionImports --no-build-cache --no-configuration-cache --no-parallel --max-workers=1 --rerun-tasks --no-daemon --console=plain
./gradlew :bluetape4k-exposed-jdbc-tests:compileTestKotlin --no-build-cache --no-configuration-cache --no-parallel --max-workers=1 --rerun-tasks --no-daemon --console=plain
```

Expected result: 두 task 모두 PASS, raw assertion import 검색 결과 0건이다.

- [ ] **Step 2: H2 migration fixture와 전체 module test를 실행한다.**

```bash
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  --tests 'io.bluetape4k.exposed.tests.migration.JdbcMigrationDriftTest' \
  --no-build-cache --no-configuration-cache --no-parallel --max-workers=1 --rerun-tasks --no-daemon --console=plain
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-jdbc-tests:test \
  --no-build-cache --no-configuration-cache --no-parallel --max-workers=1 --rerun-tasks --no-daemon --console=plain
```

Expected result: migration drift parameterized cases와 helper contract cases가 모두 PASS하고, module test도 PASS한다. `preservingFailure` primary/suppressed identity와 assertion diagnostic prefix 보존은 source-to-test mapping과 sanitized failure log로 확인한다. 성공 XML이 예외 message를 출력한다고 주장하지 않는다. 기존 `AssertionsTest.assertFailAndRollback should rollback on failure`는 변경되지 않은 회귀 범위로 유지하되, 이 issue는 DB write/read rollback proof나 새로운 rollback semantics를 주장하지 않는다.

- [ ] **Step 3: PostgreSQL과 MySQL_V8을 순차 검증한다.**

```bash
EXPOSED_TEST_DB=POSTGRESQL ./gradlew :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  --tests 'io.bluetape4k.exposed.tests.migration.JdbcMigrationDriftTest' \
  --no-build-cache --no-configuration-cache --no-parallel --max-workers=1 --rerun-tasks --no-daemon --console=plain
EXPOSED_TEST_DB=MYSQL_V8 ./gradlew :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  --tests 'io.bluetape4k.exposed.tests.migration.JdbcMigrationDriftTest' \
  --no-build-cache --no-configuration-cache --no-parallel --max-workers=1 --rerun-tasks --no-daemon --console=plain
```

각 실행은 `TestDB` 선택 계약상 H2와 선택 dialect를 함께 포함한다. 따라서 H2/helper 검사는 Step 2에서 기준 증거를 한 번 확보하고, PostgreSQL/MySQL 실행에서 반복되는 H2 케이스는 의도된 고정 비용으로 별도 집계한다. 각 JUnit XML의 `tests`, `failures`, `errors`, `skipped`를 읽어 H2는 7 tests/0 failures/0 errors/0 skipped, PostgreSQL과 MySQL_V8은 각각 8 tests/0 failures/0 errors/0 skipped인지 확인한다. XML이 없거나 count가 0/부분 실행이면 PASS가 아니라 `PENDING`/FAIL이다. 두 container lifecycle, dialect 문맥, cleanup/drop 경로를 각각 기록하며, 중복 실행을 단일 dialect PASS로 축약하지 않는다. Docker가 준비되지 않으면 `colima status`, `docker context show`, `docker info`를 먼저 확인한다. 세 dialect 증거가 없으면 PR DoD를 PASS로 표시하지 않고 `PENDING`으로 남긴다.

`withDb`의 `maxAttempts = 1`을 재확인하고, 실패 후 재실행이 필요하면 첫 시도의 JUnit XML·exit status를 보존한다. 재실행만 green인 경우 retry-only PASS로 표시하지 않고 원인 분석 대기 `PENDING`으로 남긴다. Docker preflight 전후 `docker ps -a --filter label=org.testcontainers=true --format '{{.Names}}\t{{.Status}}'`와 테스트 종료 로그를 읽어 Testcontainers가 만든 known container의 종료 상태·shutdown-hook 잔여만 확인한다. JDBC connection cleanup 자체는 이 issue의 직접 증거라고 주장하지 않고 테스트 process exit와 `withDb` finally 경계만 기록한다. macOS에서 비대화형 Gradle가 socket override를 상속하지 않은 경우에만 `colima status`, `docker context show`, `docker info`로 active Colima를 확인한 뒤 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`를 해당 명령에 적용한다. `operation not supported` bind-mount 오류가 나도 healthy Colima를 재시작하지 않고 원문과 조건부 적용을 분리한다. 자격 증명·토큰·전체 JDBC URL은 증거 문서에 기록하지 않는다.

- [ ] **Step 4: 정적·diff·Kotlin checklist 검증을 실행한다.**

```bash
set -euo pipefail
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-jdbc-tests:check --no-build-cache --no-configuration-cache --no-parallel --max-workers=1 --rerun-tasks --no-daemon --console=plain
if rg -n '^import[[:space:]]+(org\.junit\.jupiter\.api\.Assertions(\.|[[:space:]]+as[[:space:]]|$)|org\.junit\.jupiter\.api\.assertThrows([[:space:]]+as[[:space:]]|$)|kotlin\.test\.assert|org\.assertj\.|org\.kluent\.)' \
  exposed/jdbc-tests/src/main/kotlin exposed/jdbc-tests/src/test/kotlin; then
  echo 'forbidden raw assertion import found' >&2
  exit 1
fi
if rg -n '^import.*(\.|[[:space:]]as)[[:space:]]*$' \
  exposed/jdbc-tests/src/main/kotlin exposed/jdbc-tests/src/test/kotlin; then
  echo 'incomplete or continued import declaration found' >&2
  exit 1
fi
if rg -n 'println\(|System\.(out|err)' exposed/jdbc-tests/build.gradle.kts \
  exposed/jdbc-tests/src/main/kotlin exposed/jdbc-tests/src/test/kotlin; then
  echo 'stdout/stderr call found' >&2
  exit 1
fi
# The Gradle guard's KotlinCompile source inventory is authoritative; do not infer
# the source set from directory basenames alone.
git diff --check
```

Expected result: H2로 고정된 `check` PASS, raw import와 새 `println`/`System.out`/`System.err` 검색 0건, diff whitespace 오류 0건이다. `check`는 PostgreSQL/MySQL container를 다시 시작하지 않으며, 앞선 targeted dialect 실행과의 중복 비용은 Step 3의 선택 dialect 증거로 한정한다. 결과 문서에는 sanitized counts/status만 남기고 password/token/secret/private JDBC URL을 redaction한다. `$bluetape-kotlin-patterns` testing checklist KT-01..KT-11과 7-Tier P0/P1=0을 review artifact에 연결한다.

## Task 5: 독립 검토 결과와 lesson을 기록한다

**Files:**
- Create: `docs/review/2026-08-25-issue-721-jdbc-tests-assertions-review.md`
- Create: `docs/lessons/2026-08-25-issue-721-jdbc-tests-assertions.md`

- [ ] **Step 1: 최종 7-Tier review artifact를 작성한다.**

한국어 표에 다음을 포함한다: 변경 파일/비변경 경계, dependency API classpath, raw import guard의 고정 root·symlink·alias 정책, matcher 의미 보존, H2 및 가능한 PostgreSQL/MySQL 실행 결과, `println` 검색, `git diff --check`, 독립 performance/stability/security/ops/api/user 렌즈의 provenance와 P0-P3 판정. 문서 self-review는 미완성 표식이 없다는 검색과 writer Korean terminology audit를 통과해야 한다. `SPW-01`부터 `SPW-05`와 `KO-01`부터 `KO-07`을 각각 PASS/증거 경로와 함께 기록한다. KO gate는 evidence 고정, 빈 주장 제거, 번역투 제거, 기술 register·용어 확인, 과도한 voice 제거, reader-facing surface 전수 확인, contextual terminology audit를 포함하며, 자연스러움 검토자가 source 사실을 바꾸지 않았음을 확인한다.

- [ ] **Step 2: Type A lesson을 작성한다.**

lesson에는 context(직접 사용하는 published API의 누락), decision(`api` direct declaration과 module-local guard), evidence(RED/GREEN 및 dialect 결과), surprise(테스트 지원 main source도 published API classpath 계약을 가진다는 점), future guard(새 raw assertion import는 `check`에서 차단)를 기록한다. `docs/lessons/`의 기존 한국어 문체와 Lore evidence를 따른다.

- [ ] **Step 3: 문서와 구현 산출물을 함께 self-review한다.**

```bash
set -euo pipefail
marker_word='place''holder'
marker_todo='TO''DO'
marker_tbd='T''BD'
marker_fixme='FI''XME'
marker_pattern="$marker_word|$marker_todo|$marker_tbd|$marker_fixme"
if rg -n "$marker_pattern" \
  docs/superpowers/plans/2026-08-25-issue-721-jdbc-tests-assertions-plan.md \
  docs/review/2026-08-25-issue-721-jdbc-tests-assertions-review.md \
  docs/lessons/2026-08-25-issue-721-jdbc-tests-assertions.md; then
  echo '문서에 미완성 표식이 남아 있다' >&2
  exit 1
fi
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/review/2026-08-25-issue-721-jdbc-tests-assertions-review.md \
  docs/lessons/2026-08-25-issue-721-jdbc-tests-assertions.md
test "$(rg -n '^[-*] \[x\] \*\*SPW-0[1-5]' docs/review/2026-08-25-issue-721-jdbc-tests-assertions-review.md | wc -l | tr -d ' ')" -eq 5
test "$(rg -n '^[-*] \[x\] \*\*KO-0[1-7]' docs/review/2026-08-25-issue-721-jdbc-tests-assertions-review.md | wc -l | tr -d ' ')" -eq 7
if rg -n '^[-*] \[ \] \*\*(SPW|KO)-0[1-7]' docs/review/2026-08-25-issue-721-jdbc-tests-assertions-review.md; then
  echo 'SPW/KO writer gate is not complete' >&2
  exit 1
fi
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
set -euo pipefail
git diff --check
git status --short
git rev-parse HEAD
origin_url=$(git remote get-url origin)
case "$origin_url" in
  https://github.com/bluetape4k/bluetape4k-exposed.git|git@github.com:bluetape4k/bluetape4k-exposed.git) ;;
  *) echo "unexpected origin: $origin_url" >&2; exit 1 ;;
esac
while IFS= read -r push_url; do
  case "$push_url" in
    https://github.com/bluetape4k/bluetape4k-exposed.git|git@github.com:bluetape4k/bluetape4k-exposed.git) ;;
    *) echo "unexpected origin push URL: $push_url" >&2; exit 1 ;;
  esac
done < <(git remote get-url --all --push origin)
unexpected_paths=$(git diff --name-only origin/develop...HEAD | rg -n -v '^(exposed/jdbc-tests/build.gradle.kts|exposed/jdbc-tests/src/test/kotlin/io/bluetape4k/exposed/tests/migration/JdbcMigrationDriftTest.kt|docs/superpowers/specs/2026-08-25-issue-721-jdbc-tests-assertions-design.md|docs/superpowers/plans/2026-08-25-issue-721-jdbc-tests-assertions-plan.md|docs/review/2026-08-25-issue-721-jdbc-tests-assertions-review.md|docs/lessons/2026-08-25-issue-721-jdbc-tests-assertions.md)$' || true)
test -z "$unexpected_paths"
credential_url_prefix='jdbc:'
credential_url_body='[^[:space:]]+'
credential_url_suffix='@'
credential_field_pattern='\b(password|token|secret)\b[[:space:]]*[:=]'
credential_pattern="${credential_url_prefix}${credential_url_body}${credential_url_suffix}|${credential_field_pattern}"
secret_matches=$(git diff origin/develop...HEAD --binary -- \
  exposed/jdbc-tests/build.gradle.kts \
  exposed/jdbc-tests/src/test/kotlin/io/bluetape4k/exposed/tests/migration/JdbcMigrationDriftTest.kt \
  docs/superpowers/specs/2026-08-25-issue-721-jdbc-tests-assertions-design.md \
  docs/superpowers/plans/2026-08-25-issue-721-jdbc-tests-assertions-plan.md \
  docs/review/2026-08-25-issue-721-jdbc-tests-assertions-review.md \
  docs/lessons/2026-08-25-issue-721-jdbc-tests-assertions.md | \
  rg -ni "$credential_pattern" || true)
test -z "$secret_matches"
synthetic_secret_hits=$(for field_key in 'pass''word' 'to''ken' 'sec''ret'; do
  printf '%s = %s\n' "$field_key" '"redacted"'
done | rg -ni "$credential_field_pattern" | wc -l | tr -d ' ')
test "$synthetic_secret_hits" -eq 3
test "$(gh repo view --json nameWithOwner --jq .nameWithOwner)" = "bluetape4k/bluetape4k-exposed"
git push -u origin refactor/jdbc-tests-bluetape-assertions
git ls-remote origin refs/heads/refactor/jdbc-tests-bluetape-assertions
```

allowlist·대소문자 무시 secret scan·origin live-read가 모두 push 전에 통과해야 한다. 허용 목록 밖의 경로, credential-bearing 문자열, 기대 저장소가 아닌 origin이 발견되면 push를 중단하고 원인을 정리한다. 로컬 HEAD와 원격 branch HEAD가 같은지 확인한 뒤 PR을 만든다.

- [ ] **Step 3: Korean PR을 생성하고 live metadata를 맞춘다.**

PR base는 `develop`, head는 `refactor/jdbc-tests-bluetape-assertions`, 제목은 `test(jdbc-tests): bluetape4k-assertions 직접 의존성과 migration fixture를 정규화한다`로 한다. body에는 `Closes #721`과 검증 명령/결과를 포함하고 마지막 heading을 `## DoD Status`로 둔다. Issue #721의 assignee `debop`, milestone `2.0.0`, labels `test`, `refactoring`, `tech-debt`를 PR에도 반영한다.

`.issue721-workflow/pr-body.md`를 `apply_patch`로 만들고 다음 내용과 명령으로 생성·메타데이터를 적용한다. body 파일은 생성 후 read-back하고 PR 이후 helper 상태에서 삭제한다.

```markdown
## 변경 내용
- `bluetape4k-assertions` direct `api` dependency와 migration fixture matcher를 정규화했다.
- module-local raw assertion import guard와 7-Tier/Kotlin evidence를 추가했다.

## 검증
- `verifyBluetapeAssertionImports`, `compileTestKotlin`, migration drift/full test 및 publication/ABI 검증 결과를 기록한다.
- dialect별 JUnit XML count와 Docker lifecycle 범위를 기록한다.

Closes #721

## DoD Status
- 구현과 로컬 검증: PASS/PENDING (실제 receipt로 갱신)
- hosted CI·review·merge: PR 생성 후 별도 대기
```

위 body 예시의 `PASS/PENDING (실제 receipt로 갱신)`은 템플릿일 뿐이다. 실제
implementation/verification/lesson receipt를 읽어 `PASS` 또는 `PENDING`과
구체적 count/exit status로 치환한 뒤 `apply_patch`로 저장한다. 생성 직전에
다음 fail-closed 검사를 실행해 미완성 표식이 남아 있으면 PR을 만들지 않는다.

```bash
set -euo pipefail
test -s .issue721-workflow/pr-body.md
pr_marker_tbd='T''BD'
pr_marker_todo='TO''DO'
if rg -n "PASS/PENDING|실제 receipt로 갱신|$pr_marker_tbd|$pr_marker_todo" .issue721-workflow/pr-body.md; then
  echo 'PR body contains an unresolved marker' >&2
  exit 1
fi
```

```bash
set -euo pipefail
pr_url=$(gh pr create \
  --repo bluetape4k/bluetape4k-exposed \
  --base develop \
  --head refactor/jdbc-tests-bluetape-assertions \
  --title 'test(jdbc-tests): bluetape4k-assertions 직접 의존성과 migration fixture를 정규화한다' \
  --body-file .issue721-workflow/pr-body.md)
pr_number=${pr_url##*/}
gh pr edit "$pr_number" --repo bluetape4k/bluetape4k-exposed \
  --add-assignee debop --milestone '2.0.0' \
  --add-label test --add-label refactoring --add-label tech-debt
gh pr view "$pr_number" --repo bluetape4k/bluetape4k-exposed \
  --json body,assignees,milestone,labels,headRefName,baseRefName,headRefOid \
  --jq '{body,assignees,milestone,labels,headRefName,baseRefName,headRefOid}'
```

PR body의 마지막 heading은 정확히 `## DoD Status`여야 하며 `Closes #721`을
포함해야 한다. `gh pr edit`가 label/milestone/assignee를 반영하지 못하면
PASS가 아니라 PENDING으로 남기고 원인을 기록한다.

- [ ] **Step 4: merge 전 상태를 확인하고 멈춘다.**

```bash
set -euo pipefail
gh pr view "$pr_number" --repo bluetape4k/bluetape4k-exposed \
  --json number,url,state,headRefName,baseRefName,headRefOid,body,assignees,milestone,labels,statusCheckRollup,reviews,reviewDecision,mergeable,mergeStateStatus
gh issue view 721 --repo bluetape4k/bluetape4k-exposed \
  --json title,state,assignees,milestone,labels,comments
gh api graphql -f query='query($number:Int!){repository(owner:"bluetape4k",name:"bluetape4k-exposed"){pullRequest(number:$number){reviewDecision reviewThreads(first:100){nodes{isResolved} pageInfo{hasNextPage endCursor}} comments(first:100){totalCount pageInfo{hasNextPage endCursor}}}}}' \
  -F number="$pr_number"
pagination_has_next=$(gh api graphql -f query='query($number:Int!){repository(owner:"bluetape4k",name:"bluetape4k-exposed"){pullRequest(number:$number){reviewThreads(first:1){pageInfo{hasNextPage}} comments(first:1){pageInfo{hasNextPage}}}}}' -F number="$pr_number" --jq '.data.repository.pullRequest.reviewThreads.pageInfo.hasNextPage, .data.repository.pullRequest.comments.pageInfo.hasNextPage' | awk '$0 == "true" { count++ } END { print count + 0 }')
test "$pagination_has_next" -eq 0
gh pr view "$pr_number" --repo bluetape4k/bluetape4k-exposed --json body --jq .body | \
  awk '/^## / { last = $0 } END { exit(last != "## DoD Status") }'
```

PR exact head, CI, review decision, issue comments, unresolved review threads,
mergeability를 live-read한 뒤 merge하지 않는다. hosted CI/review가 추가로
대기하거나 unresolved thread가 있으면 PR 상태를 `PENDING`으로 보고하고
다음 이슈로 넘어가기 전 사용자가 별도로 merge 승인할 수 있게 한다.

## Plan self-review

- Spec coverage: direct `api`, matcher semantics, diagnostic message, import policy, fixed roots, symlink boundary, RED probe, H2/cross-dialect validation, no-production-change boundary, 7-Tier review, lesson, Lore commit, PR DoD가 각각 Task 1–6에 매핑되어 있다.
- 미완성 표식 검사: 계획 본문에 미완성 표식을 남기지 않는다.
- Type consistency: Gradle task 이름은 `verifyBluetapeAssertionImports`, matcher 이름은 `assertFailsWith`, `shouldBeEqualTo`, `shouldBeFalse`, `shouldBeSameInstanceAs`, `shouldBeTrue`로 모든 단계에서 동일하다.
