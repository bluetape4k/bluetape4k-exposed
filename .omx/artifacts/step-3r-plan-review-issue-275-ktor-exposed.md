# Step 3-R Plan Review - Issue #275 Ktor Exposed

Date: 2026-06-23
Spec: `docs/superpowers/specs/2026-06-23-issue-275-ktor-exposed-design.md`
Plan: `docs/superpowers/plans/2026-06-23-issue-275-ktor-exposed-plan.md`

## Scope

Reviewed the implementation plan for Ktor Exposed module creation, public API
shape, Ktor core composition, JDBC/R2DBC transaction and readiness semantics,
security redaction, metrics, tests, example lifecycle, CI/Nightly wiring, BOM,
NMCP aggregation, and shared dependency catalog readiness.

## Final Tier Results

| Tier | Perspective | Final P0 | Final P1 | Final Verdict |
|---|---:|---:|---:|---|
| 1 | Performance | 0 | 0 | PASS |
| 2 | Stability/Test | 0 | 0 | PASS |
| 3 | Security | 0 | 0 | PASS |
| 4 | Operator/Release | 0 | 0 | PASS |
| 5 | Developer/API | 0 | 0 | PASS |
| 6 | User/Caller | 0 | 0 | PASS |
| Main | Integration | 0 | 0 | PASS |

## P1 Fixes Applied

- Changed the JDBC public API and config from generic `CoroutineContext` to
  caller-supplied `CoroutineDispatcher`; `CoroutineContext`/`EmptyCoroutineContext`
  JDBC overloads are explicitly disallowed.
- Made JDBC statement-level `jdbcQueryTimeout` verification deterministic instead
  of waivable, and kept it separate from route-level readiness timeout.
- Expanded static checks for hidden dispatchers/executors, including
  `Dispatchers.IO`, `Dispatchers.Default`, `Dispatchers.VT`, `limitedParallelism`,
  `asCoroutineDispatcher`, `newSingleThreadContext`, executor creation, global
  registries, and app-owned pool creation in main sources.
- Corrected worktree-relative shared catalog verification to use
  `../../../bluetape4k-dependencies/gradle/libs.versions.toml`, and pinned
  catalog-sensitive Gradle commands with `-Pbluetape4kDependenciesCatalogPath=...`.
- Replaced non-executable BOM/publish checks with the actual
  `generatePomFileForBluetapeExposedPublication` task and gating grep against
  `exposed/bom/build/publications/BluetapeExposed/pom-default.xml`.
- Added root `nmcpAggregation` verification proving `bluetape4k-exposed-ktor`
  is present and `examples-ktor-exposed-demo` is absent.
- Added no-raw-exception-logging constraints and static checks for status,
  readiness, and metrics paths.
- Added an explicit error classification allowlist for cancellation, internal
  readiness timeout, database/pool/connectivity failures, and transaction user
  block failures.
- Added JDBC/R2DBC external cancellation tests distinct from internal timeout.
- Required per-test unique H2 DB names or repo fixture cleanup for JDBC/R2DBC
  table lifecycles.
- Added `cleanTest` and `--no-build-cache` to targeted and final test gates.
- Changed `installStatusPages = true` semantics: it installs only when
  `StatusPages` is absent, fails fast when already installed, and does not try to
  reopen or extend an existing Ktor plugin.
- Added default no-op installer tests and documentation requirements.
- Added standalone StatusPages serialization guidance: JSON/content negotiation
  is caller-owned because this module does not install generic Ktor core.
- Added example resource lifecycle obligations for demo-created `DataSource`,
  `ConnectionFactory`, and dispatcher resources.
- Bounded readiness triage docs to cover `DOWN` vs `timeout`, configured backend
  keys, dispatcher saturation, `jdbcQueryTimeout` versus route timeout, and
  secret-free response/log behavior.

## Nonblocking Notes

- First implementation still does not require JMH/benchmark; lightweight
  regression and static checks cover hidden lifecycle and cardinality risks.
- Example tests are owned by the existing examples job to avoid duplicate CI
  cost in `test-ktor-exposed`.

## Integration Verdict

Step 3-R closed with P0 = 0 and P1 = 0. The plan is ready for a planning
baseline commit, then implementation Task 1.
