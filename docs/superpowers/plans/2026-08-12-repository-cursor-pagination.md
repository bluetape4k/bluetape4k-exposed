# Repository Cursor Pagination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 `findPage` 계약을 유지하면서 JDBC와 R2DBC 저장소에 typed primary-key cursor pagination을 추가한다.

**Architecture:** `ExposedCursorPage<T, C : Comparable<C>>`를 core에 두고, JDBC와 R2DBC에는 같은 이름과 인자를 가진 top-level extension을 각각 추가한다. extension은 `IdTable.id`의 underlying column을 사용해 ASC에서는 strict `>`, DESC에서는 strict `<` 경계를 만들고 `pageSize + 1` sentinel만 조회한다. repository interface는 수정하지 않아 기존 ABI를 보존하며 cursor 직렬화·서명·transport scope는 caller가 소유한다.

**Tech Stack:** Kotlin 2.x, JetBrains Exposed 1.4.x, Gradle, JUnit 5, Kluent/bluetape4k assertions, H2 기본 테스트 경로, PostgreSQL/Testcontainers nightly 경로, JDBC `transaction`, R2DBC `suspendTransaction`/coroutine cancellation.

---

## 파일 책임 지도

- Create: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ExposedCursorPage.kt` — cursor 결과 DTO와 불변 상태 invariant.
- Create: `exposed/core/src/test/kotlin/io/bluetape4k/exposed/core/ExposedCursorPageTest.kt` — DTO 생성·경계·실패 계약.
- Create: `exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/JdbcRepositoryCursorPagination.kt` — JDBC extension과 private ID boundary helper.
- Create: `exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/repository/JdbcRepositoryCursorPaginationTest.kt` — sparse ID, sort/predicate, mutation, count 부재, validation.
- Create: `exposed/r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/repository/R2dbcRepositoryCursorPagination.kt` — R2DBC suspend extension과 동일 boundary helper.
- Create: `exposed/r2dbc/src/test/kotlin/io/bluetape4k/exposed/r2dbc/repository/R2dbcRepositoryCursorPaginationTest.kt` — JDBC와 동일 결과·경계와 cancellation 회귀.
- Modify: `exposed/core/README.md`, `exposed/core/README.ko.md` — DTO와 typed cursor 공통 계약.
- Modify: `exposed/jdbc/README.md`, `exposed/jdbc/README.ko.md` — JDBC 사용 예와 count/transport 책임.
- Modify: `exposed/r2dbc/README.md`, `exposed/r2dbc/README.ko.md` — suspend 사용 예와 cancellation 경계.
- Defer: `docs/manual/**` — stable 1.12.1 landing/chapter pages are immutable release-pinned sources; promote the same contract during the 1.13.0 release-manual gate.
- Create: `docs/superpowers/lessons/2026-08-12-issue-645-repository-cursor-pagination.md` — 구현 중 발견한 Kotlin/Exposed 계약과 재발 방지 규칙.

### Task 1: DTO 실패 테스트와 최소 구현

**Files:** 위 core DTO/test 파일.

- [ ] **Step 1: DTO invariant 실패 테스트를 작성한다.**

```kotlin
class ExposedCursorPageTest {
    @Test
    fun `마지막 페이지는 cursor 없이 생성된다`() {
        val page = ExposedCursorPage(listOf("a"), nextCursor = null, hasNext = false)
        page.content shouldBeEqualTo listOf("a")
    }

    @Test
    fun `다음 페이지가 있으면 비어 있지 않은 content와 cursor가 필요하다`() {
        assertFailsWith<IllegalArgumentException> {
            ExposedCursorPage(emptyList<String>(), nextCursor = 1, hasNext = true)
        }
        assertFailsWith<IllegalArgumentException> {
            ExposedCursorPage(listOf("a"), nextCursor = null, hasNext = true)
        }
    }

    @Test
    fun `다음 페이지가 없으면 nextCursor를 허용하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            ExposedCursorPage(listOf("a"), nextCursor = 1, hasNext = false)
        }
    }
}
```

- [ ] **Step 2: core 테스트를 먼저 실행해 실패를 확인한다.**

```bash
./gradlew :bluetape4k-exposed-core:test --tests 'io.bluetape4k.exposed.core.ExposedCursorPageTest' --no-configuration-cache --no-daemon --rerun-tasks
```

Expected: `FAIL` with unresolved `ExposedCursorPage`.

- [ ] **Step 3: invariant를 가진 DTO를 구현한다.**

```kotlin
package io.bluetape4k.exposed.core

data class ExposedCursorPage<T, C : Comparable<C>>(
    val content: List<T>,
    val nextCursor: C?,
    val hasNext: Boolean,
) {
    init {
        require(hasNext || nextCursor == null) {
            "nextCursor must be null when hasNext is false"
        }
        require(!hasNext || (content.isNotEmpty() && nextCursor != null)) {
            "hasNext requires non-empty content and a nextCursor"
        }
    }
}
```

- [ ] **Step 4: DTO 테스트를 통과시킨다.**

```bash
./gradlew :bluetape4k-exposed-core:test --tests 'io.bluetape4k.exposed.core.ExposedCursorPageTest' --no-configuration-cache --no-daemon --rerun-tasks
```

Expected: `BUILD SUCCESSFUL` and all cursor DTO tests `PASSED`.

- [ ] **Step 5: DTO 단위 변경을 Lore commit으로 기록한다.**

```bash
git add exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ExposedCursorPage.kt exposed/core/src/test/kotlin/io/bluetape4k/exposed/core/ExposedCursorPageTest.kt
git commit -m "cursor 페이지 DTO가 경계 불변식을 보장한다"
```

Commit trailers must include `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, and `Not-tested`.

### Task 2: JDBC cursor extension을 TDD로 추가한다

**Files:** JDBC production/test files above.

- [ ] **Step 1: 기존 `AbstractExposedTest` 패턴으로 sparse fixture와 실패 테스트를 작성한다.**

The fixture must insert explicit IDs `[1L, 3L, 7L, 20L]`, map `ResultRow[table.id].value`, and expose a `LongJdbcRepository` implementation. Tests must cover:

```kotlin
val first = repository.findCursorPage(pageSize = 2)
first.content.map { it.id } shouldBeEqualTo listOf(1L, 3L)
first.hasNext shouldBeTrue()
first.nextCursor shouldBeEqualTo 3L

val second = repository.findCursorPage(pageSize = 2, cursor = first.nextCursor)
second.content.map { it.id } shouldBeEqualTo listOf(7L, 20L)
second.hasNext shouldBeFalse()
second.nextCursor shouldBeNull()

val descending = repository.findCursorPage(pageSize = 2, sortOrder = SortOrder.DESC)
descending.content.map { it.id } shouldBeEqualTo listOf(20L, 7L)
assertFailsWith<IllegalArgumentException> { repository.findCursorPage(pageSize = 0) }
assertFailsWith<IllegalArgumentException> { repository.findCursorPage(pageSize = -1) }
assertFailsWith<IllegalArgumentException> { repository.findCursorPage(pageSize = 10_001) }
```

Add predicate composition, active/deleted visibility (`isDeleted eq false`), deletion of ID `3L` between separate physical connections, insertion of ID `5L` after cursor `3L`, cursor-before insertion, predicate-mismatch insertion, DESC mutation, all six `SortOrder` variants, and a SQL statement counter/logger assertion that the cursor call emits one bounded `SELECT`, no `COUNT`, strict `>`, the predicate, matching order, and `LIMIT 3`. The mutation helper must expose the connection identity and commit each setup/mutation transaction before the next read.

- [ ] **Step 2: JDBC test를 먼저 실행해 unresolved extension 또는 실패 assertion을 확인한다.**

```bash
./gradlew :bluetape4k-exposed-jdbc:test --tests 'io.bluetape4k.exposed.jdbc.repository.JdbcRepositoryCursorPaginationTest' --no-configuration-cache --no-daemon --rerun-tasks
```

Expected: `FAIL` before the extension exists.

- [ ] **Step 3: JDBC extension을 구현한다.**

```kotlin
package io.bluetape4k.exposed.jdbc.repository

import io.bluetape4k.exposed.core.ExposedCursorPage
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.selectAll

fun <ID : Comparable<ID>, E : Any> JdbcRepository<ID, E>.findCursorPage(
    pageSize: Int,
    cursor: ID? = null,
    sortOrder: SortOrder = SortOrder.ASC,
    predicate: () -> Op<Boolean> = { Op.TRUE },
): ExposedCursorPage<E, ID> {
    require(pageSize in 1..10_000) { "pageSize must be between 1 and 10_000" }

    val basePredicate = predicate()
    val where = cursor?.let { basePredicate and table.cursorBoundary(it, sortOrder) } ?: basePredicate
    val rows = table.selectAll()
        .where(where)
        .orderBy(table.id to sortOrder)
        .limit(pageSize + 1)
        .toList()
    val hasNext = rows.size > pageSize
    val pageRows = if (hasNext) rows.take(pageSize) else rows
    val nextCursor = pageRows.lastOrNull()?.let { row -> if (hasNext) row[table.id].value else null }
    return ExposedCursorPage(pageRows.map { it.toEntity() }, nextCursor, hasNext)
}

private fun <ID : Comparable<ID>> IdTable<ID>.cursorBoundary(cursor: ID, sortOrder: SortOrder): Op<Boolean> {
    @Suppress("UNCHECKED_CAST")
    val idColumn = (id.columnType as EntityIDColumnType<ID>).idColumn
    return if (sortOrder.isAscending()) idColumn greater cursor else idColumn less cursor
}

private fun SortOrder.isAscending(): Boolean = this in setOf(
    SortOrder.ASC,
    SortOrder.ASC_NULLS_FIRST,
    SortOrder.ASC_NULLS_LAST,
)
```

The implementation must not call `countBy`, `offset`, or a second query. Keep the checked cast private, document the hard 10,000-row page cap, and preserve `JdbcRepository<ID : Any, E>` unchanged.

- [ ] **Step 4: JDBC targeted tests and compile pass를 확인한다.**

```bash
./gradlew :bluetape4k-exposed-jdbc:test --tests 'io.bluetape4k.exposed.jdbc.repository.JdbcRepositoryCursorPaginationTest' :bluetape4k-exposed-jdbc:compileKotlin --no-configuration-cache --no-daemon --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`, all cursor tests `PASSED`, and no existing `findPage` test changes.

- [ ] **Step 5: JDBC 변경을 Lore commit으로 기록한다.**

```bash
git add exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/JdbcRepositoryCursorPagination.kt exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/repository/JdbcRepositoryCursorPaginationTest.kt
git commit -m "JDBC 저장소에 typed cursor 페이지를 추가한다"
```

### Task 3: R2DBC cursor extension과 cancellation을 TDD로 추가한다

**Files:** R2DBC production/test files above.

- [ ] **Step 1: JDBC와 같은 fixture/result assertions를 `runSuspendIO` 기반으로 작성한다.**

The first call, cursor continuation, ASC/DESC, predicate, sparse IDs, active/deleted visibility, six `SortOrder` variants, delete/insert between physical connections, validation, and SQL/count evidence must match Task 2. Launch the cursor call in a child `Job`, block the suspend mapper at a real suspension point, cancel the child, and assert the original cancellation propagates. In the same test use a pool of size one, perform an uncommitted write before cancellation, assert rollback, then run a follow-up query to prove connection release.

- [ ] **Step 2: R2DBC test를 먼저 실행해 extension 부재 또는 실패를 확인한다.**

```bash
./gradlew :bluetape4k-exposed-r2dbc:test --tests 'io.bluetape4k.exposed.r2dbc.repository.R2dbcRepositoryCursorPaginationTest' --no-configuration-cache --no-daemon --rerun-tasks
```

Expected: `FAIL` before production extension implementation.

- [ ] **Step 3: suspend extension을 구현한다.**

```kotlin
suspend fun <ID : Comparable<ID>, E : Any> R2dbcRepository<ID, E>.findCursorPage(
    pageSize: Int,
    cursor: ID? = null,
    sortOrder: SortOrder = SortOrder.ASC,
    predicate: () -> Op<Boolean> = { Op.TRUE },
): ExposedCursorPage<E, ID> {
    require(pageSize in 1..10_000) { "pageSize must be between 1 and 10_000" }
    val basePredicate = predicate()
    val where = cursor?.let { basePredicate and table.cursorBoundary(it, sortOrder) } ?: basePredicate
    val rows = table.selectAll()
        .where(where)
        .orderBy(table.id to sortOrder)
        .limit(pageSize + 1)
        .toList()
    val hasNext = rows.size > pageSize
    val pageRows = if (hasNext) rows.take(pageSize) else rows
    val nextCursor = pageRows.lastOrNull()?.let { row -> if (hasNext) row[table.id].value else null }
    val content = mutableListOf<E>()
    for (row in pageRows) {
        content += row.toEntity()
    }
    return ExposedCursorPage(content, nextCursor, hasNext)
}
```

Use the same private `EntityIDColumnType<ID>` boundary helper and do not catch or translate `CancellationException`; collection stays inside the caller's existing suspend transaction.

- [ ] **Step 4: R2DBC targeted tests and compile pass를 확인한다.**

```bash
./gradlew :bluetape4k-exposed-r2dbc:test --tests 'io.bluetape4k.exposed.r2dbc.repository.R2dbcRepositoryCursorPaginationTest' :bluetape4k-exposed-r2dbc:compileKotlin --no-configuration-cache --no-daemon --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`, all cursor and cancellation tests `PASSED`.

- [ ] **Step 5: R2DBC 변경을 Lore commit으로 기록한다.**

```bash
git add exposed/r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/repository/R2dbcRepositoryCursorPagination.kt exposed/r2dbc/src/test/kotlin/io/bluetape4k/exposed/r2dbc/repository/R2dbcRepositoryCursorPaginationTest.kt
git commit -m "R2DBC 저장소에 typed cursor 페이지를 추가한다"
```

### Task 4: EN/KO module README를 계약에 맞게 갱신한다

**Files:** `exposed/core`, `exposed/jdbc`, and `exposed/r2dbc` README pairs. Stable `docs/manual/**` remains unchanged until 1.13.0 promotion.

- [ ] **Step 1: 각 locale에 동일한 의미의 typed cursor section을 추가한다.**

Use the same API shape in both languages:

```kotlin
val first = repository.findCursorPage(pageSize = 20, predicate = { Users.active eq true })
val next = repository.findCursorPage(
    pageSize = 20,
    cursor = first.nextCursor,
    predicate = { Users.active eq true },
)
```

Document primary-key stable position, all six `SortOrder` variants, strict boundary, no count/offset, the shared 10,000 page cap, `hasNext`/`nextCursor` invariant, caller-owned token encode/decode/signing and predicate/sort scope, no snapshot guarantee, transaction/cancellation behavior, explicit `SoftDeleted*` active predicate usage (deleted rows are otherwise visible), and `findPage`/Spring Batch reader remaining separate contracts. Keep code, API names, URLs, and commands unchanged between translations.

- [ ] **Step 2: locale parity and manual source checks를 실행한다.**

```bash
git diff --check
rg -n "findCursorPage|ExposedCursorPage|nextCursor|10,000|10_000|cursor" exposed/core/README.md exposed/core/README.ko.md exposed/jdbc/README.md exposed/jdbc/README.ko.md exposed/r2dbc/README.md exposed/r2dbc/README.ko.md
git diff --quiet -- docs/manual
```

Expected: no whitespace errors; every EN/KO pair contains the same API names, examples, exclusions, and cursor invariants.

- [ ] **Step 3: documentation 변경을 Lore commit으로 기록한다.**

```bash
git add exposed/core/README.md exposed/core/README.ko.md exposed/jdbc/README.md exposed/jdbc/README.ko.md exposed/r2dbc/README.md exposed/r2dbc/README.ko.md
git commit -m "cursor 페이지 사용 계약을 문서와 예제에 반영한다"
```

### Task 5: 통합 검증과 ABI/performance evidence를 수집한다

**Files:** no additional production files; use build outputs and reports.

- [ ] **Step 1: core/JDBC/R2DBC targeted suite를 순차 실행한다.**

```bash
./gradlew :bluetape4k-exposed-core:test :bluetape4k-exposed-jdbc:test :bluetape4k-exposed-r2dbc:test :bluetape4k-exposed-jdbc-tests:test :bluetape4k-exposed-r2dbc-tests:test --no-parallel --no-configuration-cache --no-daemon --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`; baseline comparison must show existing `findPage` tests remain green and cursor tests are additive.

- [ ] **Step 2: compile/API surface and `findPage` ABI readback을 수행한다.**

```bash
./gradlew :bluetape4k-exposed-jdbc:compileKotlin :bluetape4k-exposed-r2dbc:compileKotlin --no-configuration-cache --no-daemon --rerun-tasks
find exposed/jdbc/build/classes/kotlin/main exposed/r2dbc/build/classes/kotlin/main -name '*CursorPagination*.class' -print
javap -public -classpath exposed/jdbc/build/classes/kotlin/main io.bluetape4k.exposed.jdbc.repository.JdbcRepository
javap -public -classpath exposed/r2dbc/build/classes/kotlin/main io.bluetape4k.exposed.r2dbc.repository.R2dbcRepository
javap -public -classpath exposed/jdbc/build/classes/kotlin/main io.bluetape4k.exposed.jdbc.repository.JdbcRepositoryCursorPaginationKt
javap -public -classpath exposed/r2dbc/build/classes/kotlin/main io.bluetape4k.exposed.r2dbc.repository.R2dbcRepositoryCursorPaginationKt
```

Expected: both interfaces retain their pre-change `findPage` methods; only new static extension classes expose cursor methods. Record the exact output in the workflow evidence, not in public docs.

- [ ] **Step 3: bounded performance/SQL smoke를 확인한다.**

Use the cursor SQL logger assertions from Tasks 2–3 to verify one bounded select, zero count, no offset, and `LIMIT pageSize + 1`. Run the existing H2 PR path; schedule PostgreSQL/Testcontainers paths only through the repository's nightly topology. Do not claim snapshot isolation or benchmark improvement without a measured baseline.

- [ ] **Step 4: Kotlin pattern checklist와 static checks를 실행한다.**

```bash
./gradlew detekt --no-configuration-cache --no-daemon
git diff --check
```

Review null-safety, DTO's existing shallow-list policy (matching `ExposedPage`), narrow private helpers, structured R2DBC cancellation, no broad catches, no new dependency, and Korean KDoc/docs against `$bluetape-kotlin-patterns`. Verify that Spring Data JDBC/R2DBC and Ktor transaction files have no diff and their existing compile/tests remain green; these adapters are intentionally out of scope.

- [ ] **Step 5: lesson note와 final verification commit을 기록한다.**

Write Korean lessons with the exact tests, the `Comparable`/underlying-column invariant, and the caller-owned token boundary. Then run `git status --short`, `git diff --check`, and commit:

```bash
git add docs/superpowers/lessons/2026-08-12-issue-645-repository-cursor-pagination.md docs/superpowers/plans/2026-08-12-repository-cursor-pagination.md docs/superpowers/specs/2026-08-12-issue-645-repository-cursor-pagination-design.md
git commit -m "cursor 페이지 구현 근거와 교훈을 기록한다"
```

### Task 6: delivery gates

- [ ] **Step 1:** workflow lane completion, component evidence, and main verification receipts are recorded with exact commit, test, diff, and ABI evidence.
- [ ] **Step 2:** live issue #645 remains the source of truth; verify title, milestone, labels, assignee, and exact feature branch before PR creation.
- [ ] **Step 3:** create a Korean PR targeting `develop`, attach the DoD and test evidence, and wait for CI/review.
- [ ] **Step 4:** if CI fails, diagnose from fresh GitHub logs, repair locally, rerun targeted tests, and update the PR; do not claim success from local tests alone.
- [ ] **Step 5:** merge only after a fresh explicit user approval following green CI, review, exact-head readback, and mergeability checks; then fast-forward local `develop`, verify clean worktree and preserve unrelated worktrees.

## Self-review checklist

- [ ] Existing `findPage` interface signatures and behavior are untouched; Spring Data and Ktor adapter boundaries have no source changes.
- [ ] JDBC/R2DBC share DTO, parameter names, sort semantics, strict boundaries, page-size validation, and no-count behavior.
- [ ] Sparse IDs and separate-connection insert/delete tests prove stable-position semantics without claiming snapshots.
- [ ] `Long`, `Int`, `String`, and UUID-compatible IDs remain documented; `CompositeID` remains explicitly out of scope.
- [ ] Both adapters enforce `1..10_000`; all six `SortOrder` variants and SoftDeleted active predicates are tested.
- [ ] EN/KO module README pairs are parity-checked; stable manual pairs remain unchanged and no diagram geometry/assets are changed.
- [ ] Cancellation is rethrown unchanged and connection reuse is tested.
- [ ] Final DoD maps every #645 acceptance item to a test file, CI job, and manual evidence path.
- [ ] No placeholder, TODO, or unbounded broad catch remains in the plan or implementation.
