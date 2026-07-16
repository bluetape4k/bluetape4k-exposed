# Issue #322 Exposed Migration Drift Final Review

## Scope

- Issue: #322 `feat(migration): integrate Exposed Gradle migration plugin and schema drift checks`
- Branch: `feat/issue-322-migration-drift`
- Base: `origin/develop@38d13d9`
- Review type: Type A implemented-diff review
- Exclusions: production migration runner, public API, dependency/catalog
  upgrade, checked-in migration content change, and stable 1.11 manual change

## Gate Verdict

The pre-PR gate requires every final lens to report P0=0 and P1=0, all repaired
findings to have fresh evidence, and the exact candidate head to pass H2 plus
sequential PostgreSQL/MySQL 8 verification.

| Lens | P0 | P1 | P2 | P3 | Result |
|---|---:|---:|---:|---:|---|
| Performance/cost | 0 | 0 | 0 | 0 | READY |
| Stability/reliability | 0 | 0 | 0 | 0 | READY |
| Security/privacy | 0 | 0 | 0 | 0 | READY |
| Operator/Ops | 0 | 0 | 0 | 0 | READY |
| Developer/API | 0 | 0 | 0 | 0 | READY |
| User/docs | 0 | 0 | 0 | 0 | READY |
| Main-session integration | 0 | 0 | 0 | 0 | READY for candidate commit |

## Repaired Findings

| Severity | Finding | Repair and proof |
|---|---|---|
| P1 | Real-database migration job was absent from `Nightly Status` | Added the job to `nightly-status.needs`; weekday skipped results remain allowed. |
| P1 | Evidence commands could replace the captured Gradle exit under `errexit` | Kept evidence assembly guarded after `PIPESTATUS[0]`; shell contract and stability re-review pass. |
| P2 | Quote stripping allowed whitespace-bearing quoted identifiers | Added RED hostile cases and exact quoted/unquoted identifier-token matching; helper and full H2 tasks pass. |
| P2 | Pull-request workflow granted unused `packages: read` | Reduced Migration Smoke workflow and job permissions to `contents: read` only. |
| P2 | Sanitization missed scheme-less host-port authorities | Added DNS, IPv4, and bracketed IPv6 host-port redaction plus fail-closed scanning; fixtures and re-review pass. |
| P2 | Invalid database selector widened to the default real-DB matrix | Restricted the dedicated tasks to `H2`, `POSTGRESQL`, and `MYSQL_V8`; `TYPO` now fails during task creation. |
| P2 | Dedicated tasks did not participate in the repository Test mutex | Applied the existing shared mutex to every `Test` task; combined execution remains serialized. |
| P2 | Cancellation helper test threw an exception without cancelling a coroutine | Cancelled an actual async child and made cleanup suspend; cleanup completes only through `NonCancellable`. |
| P2 | H2 type-change characterization accepted any `ALTER` | Required the expected table, column, and text/clob type semantics. |
| P2 | Focused CI declared an excessive aggregate heap ceiling | Reduced focused test workers to 2 GiB and bounded CI Gradle/Kotlin daemon heaps to 2 GiB/1 GiB. |

## Fresh Validation Evidence

- JDBC/R2DBC H2 migration tasks: PASS after all Kotlin hardening.
- Focused H2 count: JDBC 7/7 and R2DBC 8/8.
- JDBC and R2DBC normal module tests: PASS with the repository Testcontainers
  environment; normal XML contains no `MigrationDriftTest` suite.
- Invalid selector: `EXPOSED_TEST_DB=TYPO` fails with the supported-value
  message.
- Fixed JDBC/R2DBC V1 regeneration: both files recreated; bounded migration
  directory status clean.
- README parity self-test: 6 runs, 16 assertions, 0 failures/errors.
- Live README parity: PASS.
- Stable manual diff against `origin/develop`: empty.
- `actionlint`: PASS for Migration Smoke and Nightly.
- Detekt: `BUILD SUCCESSFUL`.
- `git diff --check`: PASS.
- Six final implemented-diff lenses: P0=0, P1=0, P2=0, P3=0.

Preliminary local real-database evidence passed all four selections in required
order before final review repairs: JDBC PostgreSQL, R2DBC PostgreSQL, JDBC
MySQL 8, and R2DBC MySQL 8. The same four selections must run again against the
clean pushed candidate SHA before merge readiness is reported.

## Accepted Non-Blocking Risks

- Touched workflows retain the repository's existing mutable verified major
  Action tags. Pinning every Action to a commit SHA is a separate repository
  governance change.
- Dedicated CI steps intentionally use fresh Gradle invocations and broad
  migration-related PR paths. This spends additional runner time to preserve
  API-specific evidence and to keep README/workflow promises exercised.
- Exposed 1.3.1 migration APIs remain experimental and do not prove complete
  schema, data, or rollout compatibility.

## Stop Condition

PR creation is allowed only after pending final lens cells are resolved and the
candidate commit is clean and pushed. Merge remains blocked until the exact PR
head, CI, reviews, unresolved threads, branch protection, and active rulesets
are reported and the user gives fresh approval for that exact state.
