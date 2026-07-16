# Issue #322 Exposed Migration Drift Implementation Plan

> **Execution contract:** implement in order with test-first proof. Do not widen
> this issue into a public API, dependency upgrade, stable-manual update, or
> production migration runner.

**Goal:** Complete the existing Exposed 1.3.1 migration integration with
deterministic demo generation, additive schema-drift convergence tests for JDBC
and R2DBC on H2/PostgreSQL/MySQL 8, opt-in CI evidence, and misuse-resistant
English/Korean guidance.

**Architecture:** The Gradle plugin remains the build-time JDBC metadata/script
example. Dedicated tagged `migrationDriftTest` tasks run programmatic JDBC or
R2DBC comparison against synthetic baseline/evolved tables. Pull requests use
fixed-file demo proof plus bounded H2 checks; the full Nightly/manual lane runs
real databases sequentially without retry and retains sanitized per-selection
evidence.

**Tech stack:** Kotlin 2.3, Gradle 9.6, JUnit 5 tags/parameterized tests,
JetBrains Exposed 1.3.1 `MigrationUtils`, H2, PostgreSQL, MySQL 8,
Testcontainers, GitHub Actions, `actionlint`.

---

## Locked File Structure

### Build and tests

- Modify `exposed/jdbc-tests/build.gradle.kts`
- Create
  `exposed/jdbc-tests/src/test/kotlin/io/bluetape4k/exposed/tests/migration/JdbcMigrationDriftTest.kt`
- Modify `exposed/r2dbc-tests/build.gradle.kts`
- Create
  `exposed/r2dbc-tests/src/test/kotlin/io/bluetape4k/exposed/r2dbc/tests/migration/R2dbcMigrationDriftTest.kt`

The validator and failure-preserving cleanup helper stay private in each test
file. The small duplication is intentional: the two test-support modules are
consumed by many modules, and moving the helpers into `main` would create a
published/test-support API solely for this regression.

### CI and documentation

- Modify `.github/workflows/migration-smoke.yml`
- Modify `.github/workflows/nightly-tests.yml`
- Modify `README.md`
- Modify `README.ko.md`
- Create `scripts/manual/validate_migration_readme_parity.rb`
- Create `scripts/manual/validate_migration_readme_parity_test.rb`
- Create
  `docs/superpowers/checklists/2026-07-17-exposed-1.12-manual-promotion-checklist.md`
- Create
  `docs/lessons/2026-07-17-issue-322-exposed-migration-drift.md`
- Create
  `docs/review/2026-07-17-issue-322-exposed-migration-drift-review.md`
- Create
  `docs/review/2026-07-17-issue-322-exposed-migration-drift-plan-review.md`
- Modify
  `docs/superpowers/checklists/2026-07-17-issue-322-exposed-migration-drift-checklist.md`
- Modify this plan only to record review and completion evidence

`docs/manual/**`, version catalogs, dependency locks, public library source,
and checked-in V1 SQL are expected unchanged.

## Triggered Risk Predictions

| Risk | Earliest signal | Prevention/proof | Rerun point |
|---|---|---|---|
| A generated statement widens beyond the synthetic additive column | Validator unit or drift test rejects the statement | Whole-statement allowlist, representative dialect positives, hostile compound negatives | Tasks 2 and 3 |
| DDL cleanup replaces the real failure or leaks a table | Primary failure disappears or next run sees the fixture | Separate fixture call, suppressed cleanup error, primary/cleanup/dual unit cases, existence assertion | Tasks 2 and 3 |
| Environment change reuses stale test output | Task reports `UP-TO-DATE`/`FROM-CACHE` | Env task input, non-cacheable and never-up-to-date task, two-run evidence | Task 4 |
| Default retried Nightly jobs hide a migration regression | Tagged class appears in normal `test` XML | Default tag exclusion plus dedicated no-retry task/job | Tasks 1, 4, and 6 |
| Fixed-file smoke misses a timestamped extra file | Untracked SQL remains while CI is green | Remove only expected files, force generation, require recreation, bounded porcelain check | Task 5 |
| One hung API suppresses later evidence | Later step/artifact is absent | Per-step and job timeouts, `continue-on-error`, always-run uploads and aggregate verdict | Tasks 5 and 6 |
| `tee` masks the Gradle exit or raw logs leak identifiers | Failed Gradle step is green or artifact contains a URL/home path | `PIPESTATUS[0]`, staged status, allowlisted/redacted summary, isolated shell failure test | Task 6 |
| Test reports expose driver output or runner paths | Sensitive-pattern scan matches before upload | Disable XML streams, omit HTML, fail-closed scan staged XML/summary | Tasks 1, 5, and 6 |
| README teaches overwriting applied migrations | Fixed V1 command appears without fixture warning | Audience split, immutable application filename, support matrix, three-part safety checklist | Task 7 |
| Stable manual falsely advertises 1.12 behavior at 1.11 ref | Any `docs/manual/**` diff | README-only delivery and exact no-diff check | Tasks 7 and 8 |

## Plan Review Record

This section is filled only after the six independent plan lenses and
main-session integration converge.

| Lens | P0 | P1 | P2 | P3 | Result |
|---|---:|---:|---:|---:|---|
| Performance/cost | 0 | 0 | 0 | 0 | READY |
| Stability/reliability | 0 | 0 | 0 | 0 | READY |
| Security/privacy | 0 | 0 | 0 | 0 | READY |
| Operator/Ops | 0 | 0 | 0 | 0 | READY |
| Developer/API | 0 | 0 | 0 | 0 | READY |
| User/caller | 0 | 0 | 0 | 0 | READY |
| Main-session integration | 0 | 0 | 0 | 0 | READY |

Accepted cross-repository policy risk: these two workflows follow the current
repository convention of mutable verified major Action tags. Converting every
Action reference to a full commit SHA is a repository-wide workflow-governance
change, not part of #322. No matching tracking issue existed at plan time; the
final review records `workflow governance` as owner and recommends a separate
follow-up issue without widening this PR.

---

### Task 0: Freeze Reviewed Plan Evidence

**Files:**

- Modify this plan
- Modify the issue checklist

- [x] **Step 1: Run six independent plan reviews**

Review performance, stability, security, Ops, developer/API, and user/caller
concerns separately. Repair every P0/P1 and rerun each affected lane.

- [x] **Step 2: Record risk and acceptance traceability**

Verify every design goal, issue acceptance criterion, predicted risk, file,
command, artifact, rollback point, and PR gate is owned by a task below.

- [x] **Step 3: Validate and commit the plan before implementation**

```bash
git diff --check
rg -n "pending|PENDING|P0|P1|Triggered Risk Predictions" \
  docs/superpowers/plans/2026-07-17-issue-322-exposed-migration-drift-plan.md
```

Expected: no pending review cell remains, all lanes are P0=0/P1=0, and the
plan/checklist are committed with a Lore-compliant decision message.

### Task 1: Add Live-Only Tagged Test Tasks

**Files:**

- Modify `exposed/jdbc-tests/build.gradle.kts`
- Modify `exposed/r2dbc-tests/build.gradle.kts`

- [x] **Step 1: Capture the current task baseline**

```bash
./gradlew \
  :bluetape4k-exposed-jdbc-tests:tasks \
  :bluetape4k-exposed-r2dbc-tests:tasks \
  --group verification --no-configuration-cache --no-daemon
```

Expected: `migrationDriftTest` is absent before the edit.

- [x] **Step 2: Register the dedicated task in each module**

For each module:

- make normal `test` exclude JUnit tag `migration-drift`;
- register `migrationDriftTest` over the normal `SourceSet` test output and
  runtime classpath;
- include only tag `migration-drift`;
- set verification group and a precise description;
- declare `EXPOSED_TEST_DB` from an environment provider, defaulting to `H2`,
  normalize it once, use that provider as a task input, and explicitly forward
  the same value into the test worker environment;
- set `outputs.upToDateWhen { false }` and `outputs.cacheIf { false }`;
- set `maxParallelForks = 1` and disable JUnit parallel execution;
- carry the JVM options required by repository tests, including preview;
- configure the task explicitly with
  `useJUnitPlatform { includeTags("migration-drift") }`, while normal `test`
  uses `excludeTags("migration-drift")`;
- require JUnit XML, disable HTML, disable JUnit XML system-out and system-err
  inclusion with `reports.junitXml.includeSystemOutLog = false` and
  `reports.junitXml.includeSystemErrLog = false`, set
  `binaryResultsDirectory` to
  `build/test-results/migrationDriftTest/binary`, and set
  `reports.junitXml.outputLocation` to
  `build/test-results/migrationDriftTest`, which is the workflow staging
  source;
- do not add a dependency from normal `test` to the dedicated task.

- [x] **Step 3: Prove the task shape**

```bash
./gradlew \
  :bluetape4k-exposed-jdbc-tests:tasks \
  :bluetape4k-exposed-r2dbc-tests:tasks \
  --group verification --no-configuration-cache --no-daemon

./gradlew \
  :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  :bluetape4k-exposed-r2dbc-tests:migrationDriftTest \
  --dry-run --no-configuration-cache --no-daemon
```

Expected: both tasks exist, resolve normal test classes/runtime dependencies,
and are not attached to normal `test`.

Rollback: revert only the two module build files.

### Task 2: Implement JDBC Drift Proof Test-First

**File:**

- Create
  `exposed/jdbc-tests/src/test/kotlin/io/bluetape4k/exposed/tests/migration/JdbcMigrationDriftTest.kt`

- [x] **Step 1: Write RED validator and cleanup unit cases**

Under class tag `migration-drift`, add tests for:

- representative unquoted, double-quoted, and backtick-quoted additive forms;
- optional `COLUMN`, whitespace, and case variance;
- comments, extra semicolons, multiple statements, unexpected table/column,
  destructive verbs, and compound clauses;
- `DEFAULT`, `NOT NULL`, `GENERATED`, `REFERENCES`, `CONSTRAINT`, `COLLATE`,
  extra columns, and every tail beyond the dialect-approved nullable
  varchar(255) definition;
- primary-only, cleanup-only, and dual-failure propagation/suppression.

After case/whitespace/identifier-quote normalization, accept only the complete
dialect-approved tail `VARCHAR(255) NULL` for H2, PostgreSQL, and MySQL 8. The
validator must reject comments, extra semicolons, multiple statements, commas,
trailing operations, unexpected identifiers, and every form containing
`DEFAULT`, `NOT NULL`, `GENERATED`, `REFERENCES`, `CONSTRAINT`, `CHECK`,
`UNIQUE`, `PRIMARY KEY`, or `COLLATE`. If current Exposed output differs, stop
and reopen the reviewed plan instead of broadening the allowlist silently.

Call not-yet-implemented private helpers, then run:

```bash
EXPOSED_TEST_DB=H2 ./gradlew \
  :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  --tests '*JdbcMigrationDriftTest*' \
  --no-configuration-cache --no-parallel --max-workers=1 --no-daemon
```

Expected RED: compilation or assertions fail because the helper behavior does
not exist yet. Preserve the failure excerpt in the checklist.

- [x] **Step 2: Implement the narrow private helpers**

Implement a whole-statement additive validator and a synchronous cleanup
wrapper. Do not use a broad substring allowlist. Keep unexpected SQL out of
normal logs; assertion messages may report normalized synthetic identifiers
and statement count.

- [x] **Step 3: Reach helper-only GREEN**

Keep the non-database cases in `@Nested inner class HelperContract` and run
only that nested class:

```bash
EXPOSED_TEST_DB=H2 ./gradlew \
  :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  --tests '*JdbcMigrationDriftTest*HelperContract*' \
  --no-configuration-cache --no-parallel --max-workers=1 --no-daemon
```

Expected: validator and primary/cleanup/dual failure cases pass without any
database or container lifecycle output.

- [x] **Step 4: Write the JDBC lifecycle regression**

Make `JdbcMigrationDriftTest` extend `AbstractExposedTest` and use
`@ParameterizedTest` plus `@MethodSource(ENABLE_DIALECTS_METHOD)`. Use plain
tables with physical names unique to JDBC. Add
`@OptIn(ExperimentalDatabaseMigrationApi::class)`, import
`org.jetbrains.exposed.v1.core.ExperimentalDatabaseMigrationApi` and
`org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils`, and inside the
existing `withDb` fixture:

1. create baseline `id` + `name`;
2. call
   `MigrationUtils.statementsRequiredForDatabaseMigration(EvolvedTable, withLogs = false)`
   for evolved `id` + `name` + nullable `description`;
3. require one validator-approved statement;
4. execute the exact validated string through `JdbcTransaction.exec`;
5. compare again and require empty output;
6. clean up in a second top-level fixture call and assert absence.

Add a separate H2-only varchar-to-text characterization table. It proves that
an altering statement is proposed but does not feed the additive executor.
Run it through the same failure-preserving top-level cleanup wrapper and assert
that the type-change table is absent afterward.

- [x] **Step 5: Reach GREEN**

Run the JDBC command once. Expected: GREEN; display names show the actual
dialect; the cleanup assertion passes. Task 4 owns the repeated live/cleanup
proof.

Rollback: remove the JDBC test file without touching production/test-support
main source.

### Task 3: Implement R2DBC Drift Proof Test-First

**File:**

- Create
  `exposed/r2dbc-tests/src/test/kotlin/io/bluetape4k/exposed/r2dbc/tests/migration/R2dbcMigrationDriftTest.kt`

- [x] **Step 1: Write the matching RED unit cases**

Mirror the JDBC validator matrix and use a suspending cleanup wrapper whose
cleanup runs in a second R2DBC `withDb` call. Run:

```bash
EXPOSED_TEST_DB=H2 ./gradlew \
  :bluetape4k-exposed-r2dbc-tests:migrationDriftTest \
  --tests '*R2dbcMigrationDriftTest*' \
  --no-configuration-cache --no-parallel --max-workers=1 --no-daemon
```

Expected RED, captured before helper implementation.

- [x] **Step 2: Implement private suspending helpers**

Implement the same exact `VARCHAR(255) NULL` whole-statement validator and a
suspending cleanup wrapper. Run cleanup in `NonCancellable` only when the
primary failure is cancellation or the current context is inactive, preserving
ordinary primary/cleanup throwable identity. Then run only
`@Nested inner class HelperContract` to GREEN before adding database behavior:

```bash
EXPOSED_TEST_DB=H2 ./gradlew \
  :bluetape4k-exposed-r2dbc-tests:migrationDriftTest \
  --tests '*R2dbcMigrationDriftTest*HelperContract*' \
  --no-configuration-cache --no-parallel --max-workers=1 --no-daemon
```

Expected: no database or container lifecycle output.

- [x] **Step 3: Implement the R2DBC lifecycle**

Make `R2dbcMigrationDriftTest` extend `AbstractExposedR2dbcTest`, use
`@ParameterizedTest` plus `@MethodSource(ENABLE_DIALECTS_METHOD)`, and enter
the established suspending fixture through `runSuspendIO`. Add
`@OptIn(ExperimentalDatabaseMigrationApi::class)`, import
`org.jetbrains.exposed.v1.core.ExperimentalDatabaseMigrationApi` and
`org.jetbrains.exposed.v1.migration.r2dbc.MigrationUtils`, call
`MigrationUtils.statementsRequiredForDatabaseMigration(EvolvedTable, withLogs = false)`,
and execute only the exact validated string through suspending
`R2dbcTransaction.exec`. Keep table shape and assertions equivalent to JDBC
while using a distinct R2DBC physical table and separate H2 type-change table.
Both additive and type-change fixtures use failure-preserving top-level cleanup
and post-cleanup absence assertions.

- [x] **Step 4: Reach GREEN**

Run the R2DBC command once. Expected: GREEN with actual dialect display,
convergence, and cleanup assertion. Task 4 owns the repeated proof.

Rollback: remove the R2DBC test file.

### Task 4: Prove Tag, Cache, and Environment Boundaries

**Files:**

- Test/build files from Tasks 1–3

- [x] **Step 1: Prove default tests exclude drift**

Remove only the normal-task result directories, run both normal H2 module tests,
and assert that no `MigrationDriftTest` XML exists under
`build/test-results/test`.

- [x] **Step 2: Prove the dedicated tasks execute live twice**

Run the combined H2 command twice with `--build-cache --info`, capture logs,
and verify both dedicated tasks execute on both runs and neither reports
`UP-TO-DATE` nor `FROM-CACHE`. Verify `EXPOSED_TEST_DB` is listed as an input
and explicitly forwarded to the worker, the dedicated XML files exist, and
both additive and H2 type-change fixtures remain absent between runs.

Rollback: return to the responsible build/test task; do not weaken the tag or
cache contract. Dedicated/default test execution is the compilation proof;
standalone compile is diagnostic fallback only if a test task cannot start.

### Task 5: Make Pull-Request Migration Smoke Independent and Bounded

**File:**

- Modify `.github/workflows/migration-smoke.yml`

- [ ] **Step 1: Preserve trigger and trust boundaries**

- remove only the weekly schedule;
- retain `workflow_dispatch` and `pull_request`, never
  `pull_request_target`;
- keep job permissions at `contents: read` plus `packages: read` and keep PR
  Gradle caches read-only;
- set `persist-credentials: false` on every checkout and verify no secrets or
  OIDC permission is available;
- include demo/test/README/workflow paths plus root build/settings,
  `gradle.properties`, and `gradle/**` authority paths.

- [ ] **Step 2: Harden `demo-migrations`**

Keep a 15-minute timeout. Confirm `help --task generateMigrations` exposes the
plugin-specific `--rerun` option. Remove only the two expected V1 fixture files,
run both fixed-filename tasks with `--rerun --no-build-cache --no-daemon`,
require both files to exist, then fail on any bounded tracked or untracked
migration-directory status.

- [ ] **Step 3: Add bounded `h2-drift`**

Use a 30-minute job timeout and two stable-ID, 10-minute,
`continue-on-error: true` steps. Run JDBC then R2DBC with
`EXPOSED_TEST_DB=H2` and no parallel Gradle execution. Each step keeps its raw
log runner-temporary and off the Actions console, stages status plus a
sanitized allowlisted summary and JUnit XML with system streams disabled,
deletes the raw log, and fail-closed scans staged text for credentials, URL
authority/userinfo/query, tokens, and home paths. Do not upload HTML. Use
separate always-run artifact uploads:

- `migration-drift-jdbc-h2`
- `migration-drift-r2dbc-h2`

Pre-test setup creates both API staging directories with
`status.txt=started`. After both bounded attempts, an `if: always()` outcome
collector records the JDBC/R2DBC GitHub step outcomes, including timeout or
cancelled states, before upload and aggregate evaluation.

Each keeps safe staged evidence for 14 days with `if-no-files-found: error`.
Finish
with an always-run aggregate step that fails if either API outcome failed.

- [ ] **Step 4: Validate YAML and generated file proof locally**

Run `actionlint`, both plugin-specific `--rerun` generation tasks without
deleting local fixtures, file existence assertions, and the bounded porcelain
assertion. Expected: no workflow diagnostic and no migration-directory status.
The remove-and-recreate proof runs only inside the ephemeral CI checkout, so a
local plugin failure cannot leave tracked deletions or require restoration.

Rollback: restore the prior workflow; tracked V1 files must remain byte-equal
to `origin/develop` unless the forced proof demonstrates an intentional plugin
change, which is outside this issue.

### Task 6: Add Sequential No-Retry Real-Database Evidence

**File:**

- Modify `.github/workflows/nightly-tests.yml`

- [ ] **Step 1: Add the exact full-only job boundary**

Add `migration-drift-real-databases` after `build` with job-level
`permissions` containing `contents: read` and `packages: read`, the exact
existing Sunday-full/manual-full
condition, `timeout-minutes: 60`, repository Testcontainers environment, and
no retry loop.

- [ ] **Step 2: Implement four bounded selection steps**

Run JDBC PostgreSQL, R2DBC PostgreSQL, JDBC MySQL 8, R2DBC MySQL 8 in order.
Each step:

- has a stable ID, `continue-on-error: true`, and 12-minute timeout;
- sets the exact `EXPOSED_TEST_DB` value;
- precreates `build/migration-drift-reports/<api>-<database>` metadata;
- uses `set -o pipefail`, `set +e`, `tee` to a runner-temporary raw log with
  console output suppressed, `gradle_status=${PIPESTATUS[0]}`, then restores
  `set -e`;
- stages JUnit XML with system streams disabled and an allowlisted/redacted
  `command-summary.log` while
  separately capturing `evidence_status`;
- fail-closed scans every staged text artifact, omits HTML, deletes the raw log
  through both trap and normal paths, and prints only the sanitized summary;
- writes both statuses, exits with nonzero Gradle status first and otherwise
  evidence status.

The staged JUnit/display evidence for every PostgreSQL or MySQL 8 selection
must contain the requested real dialect in addition to the selector's companion
H2 case; absence of the requested dialect is a failed selection.

After all steps, write GitHub step outcomes into each status record, upload one
14-day `migration-drift-real-databases` artifact with `if: always()` and
`if-no-files-found: error`, then fail on any non-success outcome or recorded
Gradle/evidence status.

- [ ] **Step 3: Validate shell failure preservation**

Run an isolated local shell harness using a deliberately failing command piped
through `tee`. Assert original nonzero exit, summary/status creation, and no raw
log in the upload directory. Add a second case where Gradle succeeds but
evidence staging fails; assert the evidence failure becomes the step result.
Add sensitive-pattern fixtures proving staged upload is rejected, raw output is
absent from console capture, and raw temp files are deleted. Run `actionlint`.

- [ ] **Step 4: Review privacy and schedule conditions**

Prove the job is absent from weekday smoke schedules, present for Sunday/full
dispatch, uses no secret/production endpoint, and cannot upload URL authority,
userinfo, query strings, passwords, tokens, or home paths.

Rollback: remove only the dedicated job; existing broad Nightly jobs and retry
behavior remain unchanged.

### Task 7: Replace Ambiguous README Guidance in English and Korean

**Files:**

- Modify `README.md`
- Modify `README.ko.md`
- Create `scripts/manual/validate_migration_readme_parity.rb`
- Create `scripts/manual/validate_migration_readme_parity_test.rb`
- Create
  `docs/superpowers/checklists/2026-07-17-exposed-1.12-manual-promotion-checklist.md`

- [ ] **Step 1: Write matching application-user guidance**

Add equivalent 1.12 availability callouts, a three-surface boundary table, and
a copy-pastable Kotlin DSL example with
`alias(bt4k.plugins.exposed.plugin)` (upstream plugin ID
`org.jetbrains.exposed.plugin`),
`tablesPackage`, `fileDirectory`, matching JDBC `runtimeOnly`, and providers
for `MIGRATION_JDBC_URL`, `MIGRATION_DB_USER`, and
`MIGRATION_DB_PASSWORD`. Use an application-controlled output directory and a
new immutable versioned filename, with a collision preflight before generation:

```bash
MIGRATION_FILE=V202607170001__add_description.sql
test ! -e "src/main/resources/db/migration/$MIGRATION_FILE"
./gradlew generateMigrations --filename="$MIGRATION_FILE"
```

State that R2DBC applications still need a build-time JDBC URL and driver.
Forbid committed credentials, shared/production endpoints, startup or
request-path comparison, and overwriting an applied migration.

- [ ] **Step 2: Write matching contributor guidance**

Explain that checked-in V1 files are replaceable repository fixtures, not an
application convention. Document combined H2 and sequential real-database
commands, prerequisites, report locations, pass/non-proof meaning, and the
exact diagnostics table below.

| Failure surface | First diagnostic and evidence order |
|---|---|
| Gradle plugin | Rerun the fixed-filename command with `--stacktrace --info`; inspect bounded migration-directory status |
| H2 JDBC drift | Run `:bluetape4k-exposed-jdbc-tests:migrationDriftTest --tests '*JdbcMigrationDriftTest*' --stacktrace --info`; inspect staged status, then sanitized XML |
| H2 R2DBC drift | Run `:bluetape4k-exposed-r2dbc-tests:migrationDriftTest --tests '*R2dbcMigrationDriftTest*' --stacktrace --info`; inspect staged status, then sanitized XML |
| PostgreSQL/MySQL 8 | Verify Docker first; inspect `command-summary.log`, then `status.txt`, then sanitized JUnit XML |

- [ ] **Step 3: Add support and review matrices**

Match headings, commands, warnings, upstream links, support rows, and
schema/data/rollout safety checks across both languages. State that empty diff
means only "no difference detected".

- [ ] **Step 4: Validate parity and stable-manual ownership**

Create a focused Ruby validator and self-test that extract and normalize the
marked migration section headings, shell/Kotlin fences, table row keys,
commands, and URLs from both READMEs and fail on semantic parity drift. Create
a separate 1.12 manual-promotion checklist owned by the release/publish
workflow; it remains pending until an exact 1.12 release ref and commit exist,
then requires English/Korean manual promotion plus manifest, inventory,
parity, and release-manual validation. The promotion itself is outside #322.

Run:

```bash
ruby scripts/manual/validate_migration_readme_parity_test.rb
ruby scripts/manual/validate_migration_readme_parity.rb README.md README.ko.md
git diff --exit-code origin/develop -- docs/manual
git diff --check
```

Expected: no stable-manual diff.

Rollback: remove both README sections together; never leave one locale ahead.

### Task 8: Converge the Candidate Final Head

**Files:** all changed implementation files

- [ ] **Step 1: Run preliminary fast proof**

Run one fresh combined H2 drift pass, default H2 tests, forced fixed-file
generation without local fixture deletion, bounded status, Detekt,
`actionlint`, shell-contract test, link/parity checks, and `git diff --check`.

- [ ] **Step 2: Run six implemented-diff reviews**

Review performance, stability, security, Ops, developer/API, and user/docs.
Repair all P0/P1 findings and rerun affected tests. Record final counts in
`docs/review/2026-07-17-issue-322-exposed-migration-drift-review.md`.

- [ ] **Step 3: Record the lesson and reconcile evidence**

Create `docs/lessons/2026-07-17-issue-322-exposed-migration-drift.md` explaining
fixed filenames versus timestamp defaults, why fixed V1 is only a repo fixture,
plugin JDBC metadata versus JDBC/R2DBC programmatic comparison, and why an
empty diff is not schema equality. Map each issue acceptance criterion and
design DoD to evidence, and update plan/checklist/review artifacts through the
pre-PR gate.

- [ ] **Step 4: Commit and push the candidate final head**

Confirm no public API, dependency version, checked-in SQL, or stable-manual
drift. Use Lore-compliant commits, push the candidate SHA, verify upstream
equality, and make no further repository change before exact-head proof.

### Task 9: Prove and Deliver the Exact Head

- [ ] **Step 1: Run exact-head fast proof**

At the clean pushed candidate SHA, rerun combined H2 drift, default H2 tests,
plugin-specific fixed-file generation without local deletion, bounded status,
Detekt, `actionlint`, shell/privacy checks, parity/link checks, and diff checks.
The commands must leave the worktree byte-identical to the candidate head.

- [ ] **Step 2: Create the PR against `develop`**

Assign `debop`, mirror issue #322 metadata, include `Fixes #322`, summarize
tests and known Exposed limitations, and link the exact workflow evidence.

- [ ] **Step 3: Complete exact-head real-database proof**

Choose one path against the same candidate SHA:

1. run all four PostgreSQL/MySQL selections locally in repository-required
   order with `--no-parallel --max-workers=1 --no-daemon`; or
2. manually dispatch Nightly with `scope=full` on the exact branch head and
   require the dedicated sequential job/artifact to pass.

Do not run Testcontainers selections in parallel. Record actual command exit
codes or exact workflow run/head/artifact evidence outside the Git tree.

- [ ] **Step 4: Verify live PR state**

Wait for CI, reviews, and unresolved threads. Query `develop` branch
protection and every active ruleset required-status context. Paginate the
ruleset list, fetch every ruleset detail by ID, filter to active enforcement
whose target conditions apply to `develop`, and extract required-status-check
rules. Treat a classic branch-protection 404 as explicit absence. Record query
time, commands/API endpoints, ruleset IDs, enforcement states, target
conditions, required contexts, exact PR head/checks, and prove Migration Smoke
is not a required check.

- [ ] **Step 5: Stop at merge readiness**

Report the exact PR number, head SHA, CI conclusions, review/thread state, and
remaining risks. Do not merge until the user gives fresh approval for that
exact head. Any later code, build, test, workflow, README, lesson, plan,
checklist, or review-file commit invalidates exact-head evidence; rerun every
affected proof before reporting merge readiness.

## Acceptance Traceability

| Issue/design requirement | Owning tasks |
|---|---|
| Documented migration/schema drift task | 1, 7 |
| Deterministic output or documented nondeterminism | 5, 7, 9 |
| Schema change generates and converges output | 2, 3 |
| JDBC and R2DBC proof | 2, 3, 5, 6 |
| H2, PostgreSQL, MySQL 8 proof | 2, 3, 5, 6, 8 |
| Upstream limitations linked | 7, 9 |
| No mandatory consumer workflow | 5, 6, 7, 9 |
| Existing builds unaffected | 1, 4, 8 |
| Failure evidence preserved without secret leakage | 5, 6, 8 |
| Stable 1.11 manual unchanged | 7, 8 |
| PR created but merge separately approved | 9 |

## Stop Condition

Stop only when the exact PR head has complete H2 and real-database evidence,
all required CI/reviews/threads pass, all Type A checklist rows through PR
readiness reconcile, and a merge-ready report is delivered. Merge and local
closeout remain blocked on a fresh exact-head user approval.
