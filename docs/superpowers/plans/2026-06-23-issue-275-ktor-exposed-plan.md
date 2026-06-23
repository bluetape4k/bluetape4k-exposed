# Ktor Exposed Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a published `:bluetape4k-exposed-ktor` module for explicit Ktor + Exposed integration, with caller-owned JDBC/R2DBC databases, safe StatusPages mapping, health/readiness routes, transaction helpers, metrics, tests, docs, CI/Nightly wiring, and release metadata.

**Architecture:** A single `ktor/exposed` integration module depends on existing `bluetape4k-ktor-core` APIs and this repo's JDBC/R2DBC modules. It exposes Exposed-specific Ktor helpers only; it does not create pools, dispatchers, registries, global plugins, schema migration, repository scanning, or Spring-style auto-configuration.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, JetBrains Exposed 1.3.0, Ktor 3.5.0 through the shared `bt4k` catalog/BOM, Micrometer optional metrics, JUnit 5, Ktor `testApplication`, H2 JDBC/R2DBC, Kover, GitHub Actions.

---

## File Structure

Create:

- `ktor/exposed/build.gradle.kts`
- `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtorConfig.kt`
- `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtor.kt`
- `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorStatusPages.kt`
- `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorHealthRoutes.kt`
- `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorTransactions.kt`
- `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorMetrics.kt`
- `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorTestFixtures.kt`
- `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtorTest.kt`
- `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorTransactionsTest.kt`
- `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorStatusPagesTest.kt`
- `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorHealthRoutesTest.kt`
- `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorMetricsTest.kt`
- `examples/ktor-exposed-demo/build.gradle.kts`
- `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/exposed/examples/ktor/KtorExposedDemoApplication.kt`
- `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/exposed/examples/ktor/KtorExposedDemoApplicationTest.kt`
- `docs/superpowers/lessons/2026-06-23-issue-275-ktor-exposed.md`

Modify:

- `settings.gradle.kts`
- `README.md`
- `README.ko.md`
- `AGENTS.md`
- `exposed/bom/README.md`
- `exposed/bom/README.ko.md`
- `.github/workflows/ci.yml`
- `.github/workflows/nightly-tests.yml`

## Tasks

- [ ] **Task 0 - Commit planning baseline (small, no Kotlin).**
  - Files: this spec/plan and `.omx/artifacts/step-2r-spec-review-issue-275-ktor-exposed.md`.
  - Commands:
    - `git diff --check`
    - `git status --short`
  - Expected result: only planning/review artifacts are staged and committed with a Lore commit before implementation starts. If `.omx/artifacts/**` is ignored, use `git add -f` for the review artifact and verify it appears in `git status --short`.

- [ ] **Task 1 - Load implementation skills and verify source versions (small, no code edits).**
  - Required skills before code edits: `$bluetape4k-code-patterns`, `$ecc-kotlin-patterns`, `$ecc-kotlin-exposed`, `$ecc-kotlin-testing`, `$kotlin-coroutines-skill`.
  - Worktree Gradle rule: catalog-sensitive Gradle commands run with `-Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml` so the worktree does not silently fall back to the remote catalog.
  - Commands:
    - `test -f ../../../bluetape4k-dependencies/gradle/libs.versions.toml`
    - `rg -n '^exposed =|^ktor =|ktor-bom|bluetape4k-ktor-(core|testing)' gradle/libs.versions.toml ../../../bluetape4k-dependencies/gradle/libs.versions.toml`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml -q projects --no-configuration-cache --no-daemon`
  - Expected result: Exposed `1.3.0`, Ktor `3.5.0`, `ktor-bom`, `bluetape4k-ktor-core`, and `bluetape4k-ktor-testing` are visible from the shared `bt4k` catalog; current project list has no Ktor module yet.

- [ ] **Task 2 - Register the new module and dependency surface (medium, Gradle).**
  - Files: `settings.gradle.kts`, `ktor/exposed/build.gradle.kts`.
  - Add `includeMappedModule("ktor/exposed", "bluetape4k-exposed-ktor")`.
  - Build script contract:
    - Use `implementation(platform(bt4k.ktor.bom))` or the existing shared-catalog Ktor BOM accessor proven by Gradle.
    - Use `api(bt4k.bluetape4k.ktor.core)` for `ApiErrorResponse`, `HealthResponse`, `respondApiError`, path validation style, and Ktor public types.
    - Use `api(project(":bluetape4k-exposed-jdbc"))` and `api(project(":bluetape4k-exposed-r2dbc"))` because public signatures expose `Database`, `R2dbcDatabase`, `Transaction`, and `R2dbcTransaction`.
    - Use `api` or `compileOnly` for Micrometer only according to public signature needs; `MeterRegistry` in public config requires `api`.
    - Use `testImplementation(bt4k.bluetape4k.ktor.testing)`, H2 JDBC/R2DBC, JUnit 5, coroutine test, and any repo-native test helpers.
    - No direct version literals for Ktor, Exposed, Micrometer, or H2.
  - Commands:
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml -q projects --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:dependencies --configuration compileClasspath --no-configuration-cache --no-daemon`
  - Expected result: `:bluetape4k-exposed-ktor` appears in Gradle projects and compile classpath resolves through catalog/BOM-managed dependencies.

- [ ] **Task 3 - Implement config and installer shell (medium, Kotlin, use `$bluetape4k-code-patterns`).**
  - Files:
    - `Bluetape4kExposedKtorConfig.kt`
    - `Bluetape4kExposedKtor.kt`
  - Implement `Bluetape4kExposedKtorConfig` with nullable `jdbcDatabase`, nullable `jdbcBlockingDispatcher: CoroutineDispatcher?`, nullable `r2dbcDatabase`, `installStatusPages = false`, `installHealthRoutes = false`, `healthPath = "/healthz/exposed"`, `readinessPath = "/readyz/exposed"`, `readinessProbeTimeout = 1.seconds`, `jdbcQueryTimeout = 1.seconds`, and nullable `MeterRegistry`.
  - Implement `Application.installBluetape4kExposedKtor(config)` as an explicit Exposed-only installer:
    - Do not call `installBluetape4kKtorCore()`.
    - Do not install content negotiation or generic health routes.
    - If `installStatusPages = true`, install Exposed-specific `StatusPages` only when `StatusPages` is absent.
    - If `installStatusPages = true`, require caller-installed Ktor JSON/content negotiation or caller-owned response serialization setup; this module does not install content negotiation.
    - If `installStatusPages = true` and `StatusPages` is already installed, fail fast with a clear message telling callers to use one `install(StatusPages) { bluetape4kErrorResponses(); bluetape4kExposedErrors() }` block.
    - If `installHealthRoutes = true`, register Exposed-specific health/readiness routes.
    - Default `installBluetape4kExposedKtor()` may no-op and must not fail.
  - Validation:
    - `readinessProbeTimeout` and `jdbcQueryTimeout` must be positive when routes are installed.
    - All-null backend config fails only when readiness routes are requested.
    - JDBC readiness requires `jdbcBlockingDispatcher`.
  - Commands:
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:compileKotlin --no-configuration-cache --no-daemon`
  - Expected result: installer compiles and has no lifecycle side effects.

- [ ] **Task 4 - Implement transaction helpers (medium, Kotlin/coroutines, use `$bluetape4k-code-patterns`).**
  - Files: `ExposedKtorTransactions.kt`, `ExposedKtorMetrics.kt`.
  - Implement:
    - `suspend fun <T> ApplicationCall.exposedJdbcTransaction(db: Database, blockingDispatcher: CoroutineDispatcher, block: Transaction.() -> T): T`
    - `suspend fun <T> ApplicationCall.exposedR2dbcTransaction(db: R2dbcDatabase, block: suspend R2dbcTransaction.() -> T): T`
  - JDBC helper runs `transaction(db = db)` inside caller-supplied `CoroutineDispatcher` with no hidden dispatcher/executor.
  - Do not expose a `CoroutineContext` overload for JDBC; `EmptyCoroutineContext` must not be accepted.
  - R2DBC helper runs `suspendTransaction(db = db)`.
  - Both helpers preserve `CancellationException`.
  - Metrics wrapper records only allowlisted meter names/tags when registry is provided; no registry means no-op and no meters.
  - Commands:
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:compileKotlin --no-configuration-cache --no-daemon`
  - Expected result: helpers compile, expose no global state, and support cancellation-preserving control flow.

- [ ] **Task 5 - Implement safe StatusPages mapping (medium, Kotlin/security, use `$bluetape4k-code-patterns`).**
  - Files: `ExposedKtorStatusPages.kt`.
  - Implement `fun StatusPagesConfig.bluetape4kExposedErrors()`.
  - Map Exposed/SQL/R2DBC/pool/connectivity/timeout failures to `ApiErrorResponse` via `respondApiError`.
  - Use stable codes and generic messages only.
  - Rethrow `CancellationException`.
  - Use this allowlist table:
    - `CancellationException`: rethrow before broad catches, no response body, metrics outcome `cancelled`.
    - Module-internal readiness timeout: HTTP 503, error `EXPOSED_READINESS_TIMEOUT`, message `Exposed readiness probe timed out`, metrics outcome `timeout`.
    - SQL/Exposed/R2DBC/pool/connectivity failure: HTTP 503, error `EXPOSED_DATABASE_UNAVAILABLE`, message `Exposed database operation failed`, metrics outcome `error`.
    - Transaction failure from user block: HTTP 500, error `EXPOSED_TRANSACTION_FAILED`, message `Exposed transaction failed`, metrics outcome `error`.
  - Redaction denylist: `cause.message`, SQL text, bind values, SQLState, vendor code, constraint/table/column/schema/database names, URL, username, password, token, stack trace.
  - Do not log raw `Throwable`, `cause.message`, `localizedMessage`, SQL text, JDBC/R2DBC URL, SQLState, vendor code, constraint/table/column/schema/database names, username, password, token, or stack traces from status/readiness/metrics paths. If logging is added, log only stable classification fields such as `backend`, `operation`, and `outcome`.
  - Commands:
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:compileKotlin --no-configuration-cache --no-daemon`
  - Expected result: status mapping compiles and has no secret-bearing output path.

- [ ] **Task 6 - Implement health/readiness routes (medium, Kotlin/coroutines, use `$bluetape4k-code-patterns`).**
  - Files: `ExposedKtorHealthRoutes.kt`.
  - Implement `Route.bluetape4kExposedHealthRoutes(...)`.
  - `/healthz/exposed`: static liveness, no DB probe, HTTP 200, `HealthResponse.up(details = mapOf("exposed" to "UP"))`.
  - `/readyz/exposed`: probe configured backend only, omit unconfigured backend from details, return 200 when all configured probes are UP and 503 otherwise.
  - JDBC readiness:
    - Requires `jdbcBlockingDispatcher`.
    - Runs minimal `SELECT 1` on the caller-supplied dispatcher.
    - Applies statement-level `jdbcQueryTimeout`.
    - Distinguishes internal readiness timeout from external cancellation.
  - R2DBC readiness:
    - Uses `suspendTransaction(db = ...)` and minimal query.
    - Distinguishes internal readiness timeout from external cancellation.
  - Health details allowlist: keys `exposed`, `jdbc`, `r2dbc`; values `UP`, `DOWN`, `timeout`.
  - Commands:
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:compileKotlin --no-configuration-cache --no-daemon`
  - Expected result: routes compile, use `HealthResponse`, and expose only coarse readiness state.

- [ ] **Task 7 - Add focused Ktor module tests (large, tests, use `$bluetape4k-code-patterns`).**
  - Files under `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/`.
  - Use Ktor `testApplication` and `bluetape4k-ktor-testing` response helpers.
  - Add JDBC tests:
    - successful transaction route using supplied `Database` and explicit `blockingDispatcher`.
    - `EmptyCoroutineContext`/plain `CoroutineContext` cannot be used through the public API.
    - helper/readiness execution occurs on a caller-supplied named dispatcher/thread.
    - rollback/unchanged state after exception.
    - same supplied `Database` reusable after failed request.
    - cancellation is not converted to `ApiErrorResponse`.
  - Add R2DBC tests:
    - successful transaction route using supplied `R2dbcDatabase`.
    - rollback/unchanged state after exception.
    - same supplied `R2dbcDatabase` reusable after failed request.
    - cancellation is rethrown/preserved.
  - Add StatusPages tests:
    - default `installBluetape4kExposedKtor()` does not install StatusPages and does not add `/healthz/exposed` or `/readyz/exposed`.
    - Exposed/SQL/R2DBC/pool/timeout errors map to expected stable code/message.
    - secret-bearing exception messages and SQL-looking payloads do not appear in response body.
    - already installed core `StatusPages` plus `installBluetape4kExposedKtor(installStatusPages = true)` fails fast with the documented composition guidance.
    - standalone `installStatusPages = true` path is tested only with caller-installed Ktor JSON/content negotiation, and docs state that serialization setup is caller-owned.
  - Add health/readiness tests:
    - static health does not touch DB.
    - jdbc-only, r2dbc-only, both backend, DB down, internal timeout.
    - all-null readiness config fails fast.
    - JDBC readiness without `jdbcBlockingDispatcher` fails fast.
    - invalid path and non-positive timeout fail fast.
    - JDBC and R2DBC external cancellation are separate from internal timeout and propagate `CancellationException` instead of returning `timeout`, 503 readiness, or `ApiErrorResponse`; if Ktor `testApplication` cannot model this reliably, add direct helper tests with a cancelled parent job.
    - JDBC statement-level timeout cleanup is proven deterministically with a fake JDBC statement/driver that records `queryTimeout`, a reliable H2 sleep/alias test, or another bounded test proving the blocked statement exits after `jdbcQueryTimeout`; this proof is separate from route-level `readinessProbeTimeout`.
  - Add metrics tests with `SimpleMeterRegistry`:
    - exact meter names/tags.
    - no registry creates no meters.
    - repeated calls reuse meter identity.
    - cancellation uses `cancelled` and is rethrown.
  - Add concurrency/isolation smoke:
    - Use per-test unique H2 DB names for both JDBC and R2DBC, or prove repo fixture cleanup with JDBC `withTables` / `withTablesSuspending` and R2DBC `withTables` around every table lifecycle.
    - Prefer `MultithreadingTester`, `SuspendedJobTester`, or `StructuredTaskScopeTester`; if Ktor `testApplication` makes them unsuitable, add coroutine/job-based smoke and document the rationale in the lesson.
  - Commands:
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:compileTestKotlin --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:cleanTest :bluetape4k-exposed-ktor:test --no-parallel --no-build-cache --no-configuration-cache --no-daemon`
  - Expected result: module tests pass sequentially and cover rollback, cancellation, redaction, health/readiness, metrics, concurrency, and isolation.

- [ ] **Task 8 - Add runnable Ktor example (medium, Kotlin/docs, use `$bluetape4k-code-patterns`).**
  - Files under `examples/ktor-exposed-demo/`.
  - Add a minimal Ktor application that:
    - creates caller-owned local H2 JDBC/R2DBC resources for demo/test only.
    - closes demo-created `DataSource`, `ConnectionFactory`, and dispatcher resources with `try/finally` or Ktor lifecycle hooks.
    - installs `installBluetape4kKtorCore(Bluetape4kKtorCoreConfig(installStatusPages = false))`.
    - composes `install(StatusPages) { bluetape4kErrorResponses(); bluetape4kExposedErrors() }`.
    - installs `installBluetape4kExposedKtor(...)` or direct exposed health/transaction helpers.
    - shows JDBC blocking isolation with caller-selected context.
    - has no real usernames/passwords/tokens/hostnames.
  - Add `testApplication` smoke that exercises health/readiness and one transaction route.
  - Add a code-review checklist or smoke assertion proving no example-owned dispatcher/pool/resource is leaked.
  - Commands:
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :examples-ktor-exposed-demo:compileKotlin :examples-ktor-exposed-demo:compileTestKotlin --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :examples-ktor-exposed-demo:cleanTest :examples-ktor-exposed-demo:test --no-build-cache --no-configuration-cache --no-daemon`
  - Expected result: example compiles/tests and demonstrates explicit composition without Spring-style auto-magic.

- [ ] **Task 9 - Update docs and local agent guide (medium, docs).**
  - Files: `README.md`, `README.ko.md`, `AGENTS.md`, `exposed/bom/README.md`, `exposed/bom/README.ko.md`.
  - README/README.ko content:
    - module row and dependency snippet for `bluetape4k-exposed-ktor`.
    - explicit caller-owned JDBC/R2DBC configuration example.
    - default `installBluetape4kExposedKtor()` is intentionally no-op unless the caller opts into StatusPages, health routes, or direct transaction helpers.
    - StatusPages composition example with core status pages disabled and both mappings in one block.
    - standalone `installStatusPages = true` requires caller-owned Ktor JSON/content negotiation because this module does not install generic Ktor core/content negotiation.
    - JDBC blocking caution and R2DBC suspend example.
    - runbook for disabling status/readiness, rollback to raw Exposed `transaction` / `suspendTransaction`, `/readyz/exposed` DOWN/timeout triage, caller-owned resources, and non-goals.
    - readiness triage must distinguish `DOWN` vs `timeout`, explain configured backend keys, mention dispatcher saturation, distinguish `jdbcQueryTimeout` from route timeout, and state that response/log output intentionally omits secret-bearing detail.
  - AGENTS content:
    - layout row `ktor/exposed`.
    - module naming row `:bluetape4k-exposed-ktor`.
    - targeted test command.
  - BOM README content:
    - add Ktor integration to published artifact category.
  - Commands:
    - `rg -n "bluetape4k-exposed-ktor|installBluetape4kExposedKtor|bluetape4kExposedErrors|exposedJdbcTransaction|exposedR2dbcTransaction|/readyz/exposed" README.md README.ko.md AGENTS.md exposed/bom/README.md exposed/bom/README.ko.md`
    - `rg -n "password|passwd|token|secret|apikey|api_key|authorization|bearer|jdbc:postgresql://|jdbc:mysql://|r2dbc:postgresql://|r2dbc:mysql://|r2dbc:pool:|\\.env" README.md README.ko.md examples/ktor-exposed-demo`
  - Expected result: docs match actual source names and contain no real secret/connection-string examples. The secret scan is gating: every hit must be removed or explicitly documented as a safe placeholder in the lesson.

- [ ] **Task 10 - Wire CI/Nightly and coverage (medium, YAML).**
  - Files: `.github/workflows/ci.yml`, `.github/workflows/nightly-tests.yml`.
  - CI updates:
    - Add `changes.outputs.ktor`.
    - Add `dorny/paths-filter` entry for `ktor/exposed/**`, `examples/ktor-exposed-demo/**`, `settings.gradle.kts`, root build files, `gradle/**`, `buildSrc/**`, and workflow files.
    - Add `test-ktor-exposed` job with compile/test and Kover XML report for `:bluetape4k-exposed-ktor`.
    - The example is owned by the existing examples job; add `:examples-ktor-exposed-demo:test` and Kover there instead of duplicating it in `test-ktor-exposed`.
    - Add test/coverage artifact names such as `test-results-ktor-exposed` and `coverage-ktor-exposed`.
    - Add the job to `coverage-report.needs` and `ci-status.needs`.
  - Nightly updates:
    - Include the Ktor integration and example in smoke/full placement without Docker-only assumptions.
    - Upload coverage artifact and add it to `coverage-report.needs` and `nightly-status.needs`.
  - Commands:
    - `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
    - `rg -n "ktor|test-ktor-exposed|coverage-ktor-exposed|examples-ktor-exposed-demo" .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
  - Expected result: workflow syntax passes and status jobs depend on the new Ktor jobs.

- [ ] **Task 11 - Verify publish/BOM/catalog readiness (medium, Gradle/release).**
  - Files: no source edits unless Gradle metadata requires adjustment.
  - Checks:
    - `:bluetape4k-exposed-ktor` is included in BOM constraints.
    - `examples-ktor-exposed-demo` is excluded from BOM/NMCP aggregation as a non-published module.
    - Generated Maven metadata or local BOM POM contains `bluetape4k-exposed-ktor`.
    - Runtime/compile dependency scopes expose public signature types and keep test drivers in test scopes.
    - No direct unpinned version literals were added.
  - Commands:
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-bom:generatePomFileForBluetapeExposedPublication --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:generatePomFileForBluetapeExposedPublication --no-configuration-cache --no-daemon`
    - `rg -n "bluetape4k-exposed-ktor" exposed/bom/build/publications/BluetapeExposed/pom-default.xml`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml dependencies --configuration nmcpAggregation --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:dependencyInsight --dependency ktor-server-core --configuration compileClasspath --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:dependencyInsight --dependency exposed-core --configuration compileClasspath --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:dependencies --configuration runtimeClasspath --no-configuration-cache --no-daemon`
    - `rg -n 'version = "|:[0-9]+\\.[0-9]+' ktor/exposed/build.gradle.kts examples/ktor-exposed-demo/build.gradle.kts`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml dependencies --configuration nmcpAggregation --no-configuration-cache --no-daemon | tee /tmp/issue-275-nmcpAggregation.txt`
    - `rg -n "bluetape4k-exposed-ktor" /tmp/issue-275-nmcpAggregation.txt`
    - `! rg -n "examples-ktor-exposed-demo" /tmp/issue-275-nmcpAggregation.txt`
  - Shared catalog action:
    - Create a linked release-blocking issue in `bluetape4k-dependencies` for `bluetape4k-exposed-ktor` alias unless the implementation branch also updates that sibling repo in a coordinated follow-up.
    - Record the issue URL or sibling commit in the PR DoD.
  - Expected result: publish metadata is correct and the dependency-catalog release gap has explicit tracked evidence.

- [ ] **Task 12 - Final local verification (large, full gate).**
  - Commands:
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml -q projects --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:compileKotlin :bluetape4k-exposed-ktor:compileTestKotlin --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:cleanTest :bluetape4k-exposed-ktor:test --no-parallel --no-build-cache --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :examples-ktor-exposed-demo:compileKotlin :examples-ktor-exposed-demo:compileTestKotlin :examples-ktor-exposed-demo:cleanTest :examples-ktor-exposed-demo:test --no-build-cache --no-configuration-cache --no-daemon`
    - `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
    - `git diff --check`
  - Additional static checks:
    - `! rg -n "newFixedThreadPool|newSingleThreadContext|Executors\\.new|asCoroutineDispatcher|limitedParallelism|Dispatchers\\.(IO|Default|VT)|shutdownHook|GlobalScope|Metrics\\.globalRegistry|HikariDataSource\\(" ktor/exposed/src/main`
    - `! rg -n "printStackTrace|localizedMessage|cause\\.message|message\\s*\\?:|sqlState|vendorCode|SQLState|constraint|table|column|schema|jdbc:|r2dbc:|log\\.(trace|debug|info|warn|error)\\([^\\n]*(cause|throwable|exception|ex)" ktor/exposed/src/main`
    - `rg -n "bluetape4k-exposed-ktor|coverage-ktor-exposed|test-ktor-exposed" README.md README.ko.md AGENTS.md exposed/bom/README.md exposed/bom/README.ko.md .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
  - Expected result: all verification commands pass or each failure has an implemented fix before review.

- [ ] **Task 13 - Step 6-R review, lesson, PR, and issue closeout (medium, workflow).**
  - Review:
    - Run Step 6-R with independent performance, stability, security, operator, developer/API, and user/caller perspectives.
    - Fix all P0/P1 findings and rerun affected lanes until P0/P1 = 0.
    - Save `.omx/artifacts/step-6r-review-issue-275-ktor-exposed.md`.
  - Lesson:
    - Write `docs/superpowers/lessons/2026-06-23-issue-275-ktor-exposed.md` with design deltas, verification evidence, dependency/catalog decision, and any concurrency-test rationale.
  - Commit/PR:
    - Commit with Lore protocol.
    - Push `feat/issue-275-ktor-integration`.
    - Create PR with `--body-file`.
    - Verify live PR body with `gh pr view <number> --json body`.
    - Final `##` section in the PR body must be `## DoD Status`.
  - Closeout:
    - Wait for CI.
    - Merge only after checks pass and workflow policy allows it.
    - Sync local `develop`, remove worktree, delete branch, and verify issue #275 is closed.
  - Expected result: PR is merged, issue #275 is closed, root `develop` is clean and in sync.

## Stop Conditions

- Stop implementation if Gradle cannot resolve the shared `bt4k` Ktor aliases/BOM and no catalog-safe fallback can be proven.
- Stop implementation if public signatures would require hidden runtime dependencies that cannot be represented safely in `api`.
- Stop before merge if CI fails or PR body live verification does not contain the required DoD.
- Do not stop for ordinary compile/test failures; fix and rerun the relevant gate.
