# Issue #322 Exposed Migration Drift Verification Design

## Status

- Date: 2026-07-17
- Repository: `bluetape4k/bluetape4k-exposed`
- Branch: `feat/issue-322-migration-drift`
- Base: `origin/develop@38d13d9`
- Issue: [#322](https://github.com/bluetape4k/bluetape4k-exposed/issues/322)
- Work type: Type A, verification hardening across build, JDBC, R2DBC,
  documentation, and CI
- User-approved direction: keep the migration lane opt-in, cover JDBC and
  R2DBC, exercise H2/PostgreSQL/MySQL 8, document limitations, and create a PR
  after verification

## Problem

The repository already applies JetBrains' `org.jetbrains.exposed.plugin` to the
Spring JDBC and R2DBC demos, checks in generated V1 scripts, and runs a
path-scoped `Migration Smoke` workflow. That proves that a model can generate a
script from an empty H2 database when the task receives a fixed filename. It
does not prove that a pre-existing database and a changed Exposed model produce
a detectable, reviewable schema diff.

This distinction matters because generated migration SQL is advisory. Exposed
may emit destructive statements, and its metadata comparison is dialect
sensitive. A green empty-database generation job must not be presented as proof
that a production PostgreSQL or MySQL schema matches the current model.

## Current Evidence

### Repository state

- Root `build.gradle.kts` declares the central Exposed plugin alias with
  `apply false`.
- `examples/jdbc-demo/build.gradle.kts` and
  `examples/r2dbc-demo/build.gradle.kts` apply the plugin.
- Both demo plugin configurations connect through JDBC H2 URLs in PostgreSQL
  compatibility mode. The R2DBC demo still uses JDBC for build-time migration
  generation; its application runtime is R2DBC.
- `.github/workflows/migration-smoke.yml` regenerates both checked-in V1 files
  with explicit `--filename` values and fails on a Git diff.
- `exposed/jdbc-tests` already depends on `exposed-migration-jdbc` and exposes
  H2, PostgreSQL, and MySQL 8 fixtures.
- `exposed/r2dbc-tests` already depends on `exposed-migration-r2dbc` and exposes
  matching R2DBC fixtures.
- The baseline H2 JDBC/R2DBC tests pass at `38d13d9`.

### Determinism observation

Running `generateMigrations` without `--filename` created timestamp-named SQL
files on each invocation. Running it with a fixed filename overwrites the same
path. Therefore:

- fixed filenames plus `git diff --exit-code` are suitable for a deterministic
  checked-in script smoke;
- the plugin default filename is intentionally time-dependent and must not be
  called deterministic;
- documentation and CI commands must always provide a fixed filename when the
  result is compared with Git.

### Upstream contract

The [Exposed 1.3.1 migration documentation](https://www.jetbrains.com/help/exposed/migrations.html)
describes two distinct surfaces:

1. the Gradle plugin compares Exposed table definitions with a database schema
   and writes migration scripts;
2. JDBC and R2DBC `MigrationUtils` APIs expose lower-level schema comparison,
   statement generation, and validation building blocks.

The same documentation requires manual review because generated output can
contain `CREATE`, `ALTER`, `DROP`, and other destructive operations. It also
states that full column type-change support is currently limited to H2.

Two upstream issues remain relevant:

- [JetBrains/Exposed#377](https://github.com/JetBrains/Exposed/issues/377)
  requests a convenient non-mutating schema equality assertion and remains
  open.
- [JetBrains/Exposed#2441](https://github.com/JetBrains/Exposed/issues/2441)
  reports a PostgreSQL varchar-to-text type change that produced no migration
  statement and remains open.

The repository must therefore gate only behavior that Exposed 1.3.1 reliably
supports and document unsupported or incomplete comparisons instead of
encoding false confidence.

## Goals

1. Prove that an additive schema change is detected by JDBC and R2DBC
   `MigrationUtils` on H2, PostgreSQL, and MySQL 8.
2. Prove that applying the generated additive statements converges the schema
   so the next diff is empty.
3. Preserve deterministic checked-in demo migration generation by using fixed
   filenames.
4. Keep real-database checks opt-in or scheduled rather than a mandatory daily
   gate.
5. Explain the build-time JDBC connection used by the Gradle plugin, the
   programmatic/test-time JDBC and R2DBC comparison APIs, dialect limitations,
   destructive-output review, and migration-runner ownership.

## Non-Goals

- Adding a new published module or public bluetape4k API.
- Applying generated SQL automatically in production.
- Replacing Flyway, Liquibase, or an application-owned migration process.
- Treating H2 PostgreSQL mode as PostgreSQL compatibility proof.
- Hard-gating PostgreSQL/MySQL column type changes while upstream limitations
  remain.
- Changing the central Exposed version or duplicating its version locally.
- Testing every supported Exposed dialect in this issue.

## Considered Approaches

### A. Extend only the existing demo plugin smoke

Add more generated files and workflow invocations around the two Spring demos.

Advantages:

- smallest code diff;
- keeps all behavior visible in runnable examples.

Rejected because an empty-database script does not exercise an evolved schema,
the R2DBC demo's plugin generation still uses JDBC metadata, and multiplying
checked-in SQL files would not prove runtime-specific `MigrationUtils`
behavior.

### B. Add a dedicated migration-verification Gradle module

Create a new internal module containing JDBC/R2DBC fixtures and dialect tests.

Advantages:

- isolates migration verification from reusable test infrastructure;
- provides one obvious task namespace.

Rejected because the repository already has JDBC and R2DBC test modules with
the required migration dependencies, database selectors, and Testcontainers
launchers. A new module would duplicate setup and introduce unnecessary
settings, CI, BOM, manual-inventory, and publication hazards.

### C. Add drift regression tests to the existing JDBC/R2DBC test modules

Keep the demo plugin smoke for checked-in file determinism and add schema
evolution tests to the existing database test infrastructure.

Advantages:

- exercises the exact JDBC and R2DBC migration APIs;
- reuses established H2/PostgreSQL/MySQL 8 fixtures;
- requires no new module or public API;
- separates fixed-file generation from live database comparison;
- supports fast H2 PR proof and sequential scheduled real-database proof.

This is the chosen approach.

## Architecture

### Layer 1: deterministic plugin smoke

The two demo modules remain the plugin examples. CI always invokes
`generateMigrations` with the existing fixed V1 filenames. Before invocation,
it removes only those two expected files; both tasks use the plugin-specific
`--rerun` option with `--no-build-cache` and `--no-daemon`, and CI requires both
files to be recreated. It then checks only the two migration directories. It
fails on tracked changes and on every
untracked file using a bounded `git status --porcelain --untracked-files=all`
check, so an unexpected timestamped or second SQL file cannot pass unnoticed.

This layer answers: "Did the current model and plugin version unexpectedly
change the reviewed baseline script?"

It does not answer: "Does a deployed database match the model?"

### Layer 2: JDBC and R2DBC schema evolution regression

Add one focused test class to each test-infrastructure module. Each test uses
two plain `Table` objects with the same physical table name and an intentionally
minimal, pinned schema:

- baseline model: `integer("id")`, explicit `PrimaryKey(id)`, and one required
  `varchar("name", 64)` column;
- evolved model: the baseline columns plus nullable
  `varchar("description", 255)`;
- excluded from both models: auto-increment/identity columns, defaults,
  sequences, references, secondary indexes, and generated constraint names.

JDBC and R2DBC use distinct physical table names, and the H2 type-change
characterization uses a third table. This prevents unrelated metadata such as
sequences or indexes from expanding the additive-only proof.

For each enabled dialect, the test performs this lifecycle inside the
repository's existing database fixture:

1. create the baseline table;
2. request migration statements for the evolved model;
3. require exactly one generated statement and validate the whole statement
   with a test-only additive-DDL validator before execution;
4. after case, whitespace, and H2/PostgreSQL/MySQL identifier-quote
   normalization, accept only
   `ALTER TABLE <fixture> ADD [COLUMN] <expected-column> VARCHAR(255) NULL`;
5. reject comments, multiple or trailing semicolons, compound clauses,
   additional operations, another table/column, `DROP`, `TRUNCATE`, `DELETE`,
   removal/rename/type change, `DEFAULT`, `NOT NULL`, `GENERATED`,
   `REFERENCES`, `CONSTRAINT`, `CHECK`, `UNIQUE`, `PRIMARY KEY`, `COLLATE`, a
   comma, any trailing operation, and every statement targeting another
   object;
6. execute the allowed statement inside the matching JDBC or R2DBC
   transaction;
7. request migration statements again;
8. assert that the second result is empty;
9. drop the physical table in an independent top-level cleanup and
   assert that it no longer exists.

The validator has unit cases for representative H2, PostgreSQL, and MySQL 8
forms plus negative cases containing comments, compound DDL, extra semicolons,
unexpected identifiers, and destructive verbs. Assertions otherwise avoid
requiring exact vendor SQL text and prove the observable lifecycle: drift
exists before application and disappears after application.

Both test classes import
`org.jetbrains.exposed.v1.core.ExperimentalDatabaseMigrationApi` and opt into
it. JDBC imports
`org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils`, R2DBC imports
`org.jetbrains.exposed.v1.migration.r2dbc.MigrationUtils`, and both call
`statementsRequiredForDatabaseMigration(EvolvedTable, withLogs = false)`.
Only the exact validated string reaches `JdbcTransaction.exec` or suspending
`R2dbcTransaction.exec`. If Exposed 1.3.1 emits a different additive type tail
for a selected dialect, implementation stops for review instead of silently
broadening the allowlist.

Cleanup never relies on transaction rollback because DDL rollback semantics
differ across H2, PostgreSQL, and MySQL. A private test helper captures the
primary throwable, performs cleanup through a second database-fixture call,
adds cleanup failure with `primary.addSuppressed(cleanup)`, and rethrows the
primary. Non-database unit cases prove primary-only, cleanup-only, and dual
failure behavior. Cleanup-only failure is thrown directly.

An H2-only characterization additionally changes a column from bounded varchar
to text and asserts that a type-altering statement is produced. PostgreSQL and
MySQL do not receive that hard assertion because Exposed 1.3.1 does not promise
full type-change detection there. The separate type-change table uses the same
failure-preserving top-level cleanup contract and post-cleanup absence
assertion as the additive fixture.

### Layer 3: opt-in verification workflow

Tag the focused regressions `migration-drift`. The default `test` tasks exclude
that tag so existing broad Nightly jobs, including their retry loops, cannot
hide or duplicate migration evidence. Each test module registers a dedicated
`migrationDriftTest` task that includes the tag, declares `EXPOSED_TEST_DB` as
an input, and is intrinsically live-only with both
`outputs.upToDateWhen { false }` and `outputs.cacheIf { false }`. Every
invocation therefore executes fresh migration tests while dependent compile
and resource tasks may remain up-to-date or use their normal cache.

Each dedicated task uses the normal test `SourceSet`, explicitly configures
`useJUnitPlatform { includeTags("migration-drift") }`, and assigns
`build/test-results/migrationDriftTest/binary` as its binary-results directory
and `build/test-results/migrationDriftTest` as its XML output directory. Normal
`test` explicitly excludes the tag. Dedicated JUnit XML is required, HTML is disabled, and
`reports.junitXml.includeSystemOutLog` plus `includeSystemErrLog` are both
false so workflow staging has one well-defined sanitized report source.

Refine verification into two complementary proof levels:

- `Migration Smoke` runs only for matching pull-request paths and manual
  dispatch; remove its independent weekly schedule to avoid an uncoordinated
  overlap with Nightly. Use two independent jobs: `demo-migrations` and
  `h2-drift`. The H2 job uses stable-ID JDBC and R2DBC steps with
  `continue-on-error: true` and `timeout-minutes: 10`, separate artifact
  uploads, and a final `if: always()` outcome check so one API failure does not
  suppress the other API evidence. Pin `demo-migrations` to a 15-minute job
  timeout and `h2-drift` to a 30-minute job timeout, leaving report upload and
  aggregation headroom after both bounded H2 attempts.
- Its path contract includes `exposed/jdbc-tests/**`,
  `exposed/r2dbc-tests/**`, root `README.md` and `README.ko.md`, demo
  build/model/migration paths, root `build.gradle.kts`, `settings.gradle.kts`,
  `gradle.properties`, `gradle/**`, and the workflow files themselves. These
  paths cover the local plugin declaration, catalog import/tag authority, task
  defaults, and wrapper/build configuration now that no weekly smoke exists.
- Preserve the untrusted-PR boundary: use `pull_request`, never
  `pull_request_target`; keep job permissions at `contents: read` plus
  `packages: read`; expose no secrets or production/shared endpoints; and configure
  `gradle/actions/setup-gradle` in both jobs with
  `cache-read-only: ${{ github.event_name == 'pull_request' }}`. Every checkout
  uses `persist-credentials: false`.
- H2 steps disable JUnit XML system-out/system-err capture and stage status,
  sanitized command summary, and XML only; raw HTML and raw command logs are
  not uploaded. Pre-test setup creates an API-specific `status.txt=started`
  marker, and an always-run outcome collector records both GitHub step outcomes
  after the bounded attempts, including timeout/cancelled states. A fail-closed
  sensitive-pattern scan covers staged XML and summary before upload. Jobs
  upload artifacts with `if: always()`,
  `retention-days: 14`, and `if-no-files-found: error`: JDBC uses
  `migration-drift-jdbc-h2`, R2DBC uses `migration-drift-r2dbc-h2`, each from a
  distinct `build/migration-drift-reports/h2/<api>/**` staging directory.
- The full Nightly scope adds one dedicated
  `migration-drift-real-databases` job after `build`. A single runner executes
  JDBC PostgreSQL, R2DBC PostgreSQL, JDBC MySQL 8, and R2DBC MySQL 8 in that
  order with no retry, `--no-parallel`, `--max-workers=1`, and `--no-daemon`.
  The job has `timeout-minutes: 60`; this
  budget intentionally includes the selector contract's companion H2 runs,
  container startup, compilation, and report staging.
- The job uses the existing full-scope condition exactly: Sunday schedule
  `3 19 * * 0`, or manual dispatch with `inputs.scope == 'full'`:

  ```yaml
  if: ${{ (github.event_name == 'schedule' && github.event.schedule == '3 19 * * 0') || (github.event_name == 'workflow_dispatch' && inputs.scope == 'full') }}
  ```

  It carries the
  established Testcontainers/Gradle environment:
  `TESTCONTAINERS_RYUK_DISABLED=true`,
  `DOCKER_HOST=unix:///var/run/docker.sock`, and the Nightly `GRADLE_OPTS` JVM
  memory settings. Each selection step sets its exact
  `EXPOSED_TEST_DB=POSTGRESQL` or `EXPOSED_TEST_DB=MYSQL_V8` value.
- Implement the four selections as independent steps with stable IDs and
  `continue-on-error: true`. Each step captures the Gradle status and stages
  sanitized JUnit XML, status, and `command-summary.log` under
  `build/migration-drift-reports/<api>-<database>` even after a test failure;
  HTML is never staged. Before Gradle starts, the step creates its report
  directory and records command/selection metadata. Each
  shell uses the exact failure-safe pattern: `set -o pipefail`, `set +e`, pipe
  Gradle stdout/stderr through `tee` to a runner-temporary raw log while
  suppressing `tee` output from the Actions console, capture
  `gradle_status=${PIPESTATUS[0]}`, restore `set -e`, then stage evidence under
  a separately captured `evidence_status`. Write both statuses and exit with
  the nonzero Gradle status first, otherwise the evidence status. The final
  aggregate treats either status as failure. This prevents `tee` or evidence
  assembly from replacing or hiding the Gradle result and prevents `errexit`
  from skipping evidence staging.
- Raw Gradle/driver logs are never uploaded or printed to the Actions console.
  A trap and the normal path delete the temporary raw log. Before deletion,
  produce `command-summary.log` from an allowlist of task outcomes, build
  result, test counts, and wrapper-emitted lifecycle labels. Redact JDBC/R2DBC
  URL authority/userinfo/query values, user/password properties, tokens, and
  home-directory paths before staging even allowlisted text. `status.txt` and
  the allowlisted summary therefore preserve dependency, compilation, and
  container-startup failure evidence without publishing credentials or local
  identifiers. Each selection
  has `timeout-minutes: 12`; the 60-minute job budget leaves setup, staging,
  upload, and aggregation headroom after the four bounded attempts. An
  `if: always()` outcome-collection step writes each GitHub step outcome into
  its `status.txt`, including a timeout where the command could not replace the
  initial `started` marker. After all four attempts, an
  `if: always()` upload step publishes artifact
  `migration-drift-real-databases` from
  `build/migration-drift-reports/**` with `retention-days: 14` and
  `if-no-files-found: error`; a final `if: always()` step fails when any of the
  four step outcomes is not `success`.
- The fast combined H2 task is mandatory before PR delivery. Complete
  real-database proof with either the same four local selections in order or a
  successful manually dispatched full Nightly run on the exact branch head.
  Each selection includes the existing selector's companion H2 case, and
  display names/assertions prove the actual dialect.

The companion H2 repetition is an accepted fixture constraint: adding an
exact-dialect selector only to save four short cases would widen existing test
infrastructure semantics. The 60-minute CI budget and local command guidance
account for it explicitly.

The workflow remains path-scoped and non-required. It is evidence for migration
compatibility, not a repository-wide mandatory migration policy. PR readiness
includes a live ruleset/branch-protection check confirming that the Migration
Smoke checks are not required checks. Query both `develop` branch protection
required-status contexts and every paginated repository ruleset; fetch each
ruleset detail, filter to active enforcement whose target conditions apply to
`develop`, and extract status-check rules. Treat classic branch-protection 404
as explicit absence. Record query time, endpoints, ruleset IDs/enforcement/
conditions, returned contexts, and exact PR head checks in
`docs/review/2026-07-17-issue-322-exposed-migration-drift-review.md`.

## Documentation Design

Document current-develop behavior in equivalent migration sections in
`README.md` and `README.ko.md`. Do not add a stable manual page or edit
`docs/manual/manifest.yaml`: the stable manual is pinned to release 1.11.0, and
the new 1.12-only tasks and workflow do not exist at that release ref. Manual
promotion belongs to the 1.12 release closeout after an exact release ref and
commit exist.

Both README sections have matching headings, commands, warnings, support
matrix, upstream links, and review checklist, split by audience:

1. **Application user:** owns configuration and credentials, writes output to
   an application-controlled location, never overwrites an applied migration,
   uses a new monotonically ordered filename for each change, reviews the SQL,
   then hands it to Flyway, Liquibase, or another application migration runner.
2. **Repository contributor:** runs the replaceable fixed-V1 demo baseline and
   the dedicated H2/PostgreSQL/MySQL drift regression tasks. The fixed V1 files
   are repository fixtures, not an application migration naming example.

The application-user path includes a copy-pastable `exposed.migrations`
Kotlin DSL configuration with `alias(bt4k.plugins.exposed.plugin)` (upstream
plugin ID `org.jetbrains.exposed.plugin`), `tablesPackage`, `fileDirectory`,
matching JDBC `runtimeOnly`, and providers named
`MIGRATION_JDBC_URL`, `MIGRATION_DB_USER`, and `MIGRATION_DB_PASSWORD`. It
writes into an application-controlled directory and uses a new immutable
monotonically ordered filename rather than either repository V1 fixture. The
shell example first sets `MIGRATION_FILE`, proves the target does not exist
with `test ! -e`, and only then passes `--filename="$MIGRATION_FILE"`. A
companion note for R2DBC applications states that build-time plugin comparison
still requires a JDBC URL and JDBC driver; an R2DBC URL or runtime driver is
not sufficient. Examples forbid committed credentials and production/shared
endpoints.

An adjacent availability callout distinguishes upstream Exposed 1.3.1 plugin
capability from this repository's dedicated `migrationDriftTest` tasks and CI:
the latter are available on `develop` and first ship with bluetape4k-exposed
1.12.0. The English and Korean wording must remain equivalent.

The reader-facing boundary is explicit:

| Surface | Connection and purpose | Forbidden inference |
|---|---|---|
| Gradle plugin | Build-time JDBC metadata connection and script generation | It does not connect over R2DBC or apply production migrations |
| JDBC `MigrationUtils` | Programmatic/test-time JDBC schema comparison | Do not run as startup or request-path schema management |
| R2DBC `MigrationUtils` | Programmatic/test-time R2DBC schema comparison | Do not run as startup or request-path schema management |

The support matrix labels additive columns as "proved here", H2 type changes
as "characterized only", and PostgreSQL/MySQL type changes, renames/removals,
defaults, indexes, foreign/unique/check constraints, and vendor-specific DDL as
"not guaranteed". An empty diff means only "no difference detected by this API
and version", never "the schemas are equal".

The review checklist has three categories:

- schema safety: `DROP`/`TRUNCATE`, removal/rename/type changes, `NOT NULL`,
  defaults, indexes, unique/foreign/check constraints, and statement order;
- data safety: backfill correctness, production-shaped row volume, table
  rewrite and data reinterpretation risk;
- rollout safety: lock duration, phased nullable-add/backfill/constraint
  enforcement, database transaction support, backup, rollback, and migration
  runner ownership.

Every command includes prerequisites, produced file/report location, what a
pass proves, what it does not prove, and the first diagnostic command. Raw SQL
is reviewed against a disposable or staging copy before promotion.

A focused Ruby parity validator and its self-test extract and normalize the
marked migration-section headings, shell/Kotlin fences, table row keys,
commands, and URLs from both READMEs. Any semantic mismatch fails validation.
A separate
`docs/superpowers/checklists/2026-07-17-exposed-1.12-manual-promotion-checklist.md`
is owned by the 1.12 release/publish workflow and remains pending until an
exact release ref and commit exist. That later gate promotes English and
Korean manuals together and runs manifest, inventory, parity, and
release-manual validation; the promotion itself is outside issue #322.

## Failure Handling

### Generated file drift

If fixed-filename generation changes a checked-in file, CI fails with the Git
diff. The contributor reviews the SQL and intentionally updates the file or
fixes the unintended model/plugin change.

### Additive drift is not detected

The focused regression fails before any generated SQL is applied. This is a
compatibility regression in the selected Exposed version or dialect and blocks
the migration lane.

### Generated SQL does not converge

If the generated additive statements fail the allowlist, fail to execute, or a
second comparison still reports drift, the regression fails before unsafe
continuation. Assertion output is tagged with API, selected dialect, lifecycle
stage, and generated statement count. Raw SQL is logged only for synthetic
fixture schemas; connection URLs, credentials, and production identifiers are
never logged, and unexpected statements are normalized or redacted. The test
never broadens its assertion to accept an empty or unrelated statement list.

### Container or network failure

CI uses only the repository's disposable Testcontainers databases and
test-database-scoped identities. Production/shared endpoints and repository
secrets are forbidden. A user comparing metadata outside tests should use a
read-only account where the database permits it; generated DDL is exercised
only against a disposable/staging database with narrowly scoped DDL rights.

The existing broad Nightly jobs may retry, but tagged migration-drift tests are
excluded from them. The dedicated sequential migration job never retries. If a
container or network failure occurs, its unique API/dialect artifact remains
failed evidence; rerun only after classifying the failure, and keep both run
URLs. A later pass does not erase the earlier result.

### Unsupported type change

PostgreSQL/MySQL type-change output is not used as a hard gate. The README links
the official limitation and upstream issue so users know to validate such
changes with database-native tooling and manual review.

### Destructive generated statement

Tests apply only an isolated additive fixture. Documentation forbids automatic
application of arbitrary generated output and requires review before handing a
script to the application's migration authority.

## Compatibility and Rollback

- No production API, artifact coordinate, runtime default, or dependency
  version changes.
- Existing applications are not required to use the plugin or migration APIs.
- The workflow remains opt-in/path-scoped and can be rolled back independently
  from the tests and README documentation.
- Regression tables use unique physical names and fixture cleanup; they do not
  reuse application tables.
- Rollback removes the new test classes/tasks, workflow jobs/path filters, and
  paired README sections. The lesson remains
  as historical evidence of why fixed filenames and API-specific checks
  were required. No database or consumer migration is required.

## Verification Strategy

### Fast proof

```bash
EXPOSED_TEST_DB=H2 ./gradlew \
  :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  :bluetape4k-exposed-r2dbc-tests:migrationDriftTest \
  --no-configuration-cache \
  --no-parallel --max-workers=1 --no-daemon

./gradlew \
  :exposed-spring-boot-jdbc-demo:generateMigrations \
  --filename=V1__create_products.sql \
  --rerun --no-build-cache --no-configuration-cache --no-daemon

./gradlew \
  :exposed-spring-boot-r2dbc-demo:generateMigrations \
  --filename=V1__create_webflux_products.sql \
  --rerun --no-build-cache --no-configuration-cache --no-daemon

if [[ -n "$(git status --porcelain --untracked-files=all -- \
  examples/jdbc-demo/src/main/resources/db/migration \
  examples/r2dbc-demo/src/main/resources/db/migration)" ]]; then
  git status --short --untracked-files=all -- \
    examples/jdbc-demo/src/main/resources/db/migration \
    examples/r2dbc-demo/src/main/resources/db/migration
  exit 1
fi
```

Local proof does not delete tracked fixtures; the plugin-specific `--rerun`
forces generation while preserving the worktree on failure. The stronger
remove-and-recreate assertion runs only in the ephemeral Migration Smoke job.

### Real-database proof

Run sequentially:

```bash
EXPOSED_TEST_DB=POSTGRESQL ./gradlew \
  :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  --no-configuration-cache \
  --no-parallel --max-workers=1 --no-daemon

EXPOSED_TEST_DB=POSTGRESQL ./gradlew \
  :bluetape4k-exposed-r2dbc-tests:migrationDriftTest \
  --no-configuration-cache \
  --no-parallel --max-workers=1 --no-daemon

EXPOSED_TEST_DB=MYSQL_V8 ./gradlew \
  :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  --no-configuration-cache \
  --no-parallel --max-workers=1 --no-daemon

EXPOSED_TEST_DB=MYSQL_V8 ./gradlew \
  :bluetape4k-exposed-r2dbc-tests:migrationDriftTest \
  --no-configuration-cache \
  --no-parallel --max-workers=1 --no-daemon
```

Because the existing selectors include H2 alongside the named real database,
these commands prove "H2 plus PostgreSQL" and "H2 plus MySQL 8". Parameterized
test display names and assertion messages identify API, actual dialect,
lifecycle stage, and generated statement count.

### Repository proof

- affected module compilation and tests;
- English/Korean README parity and link validation;
- workflow syntax with `actionlint`;
- an isolated shell-contract check where a deliberately failing piped command
  still writes summary/status evidence and returns the original nonzero code;
- workflow security review proving `pull_request`, `contents: read`, no secret
  use or production endpoint, and pull-request read-only Gradle caches;
- live `develop` branch-protection and active-ruleset required-status context
  queries, recorded with query time and exact PR head checks;
- `./gradlew detekt`;
- `git diff --check`;
- final six-perspective code review with P0=0 and P1=0.

## Acceptance Criteria Traceability

| Issue criterion | Design evidence |
|---|---|
| Documented task or test exercises migration/schema drift | Paired README sections and focused JDBC/R2DBC tasks |
| Output is deterministic or nondeterminism documented | Fixed filename Git diff plus timestamp-default warning |
| Regression covers a schema change producing output | Baseline-to-evolved additive-column lifecycle |
| Known upstream limitations are linked | Official 1.3.1 docs plus Exposed #377 and #2441 |
| Existing builds and consumers remain unaffected | No public API/module/version/default changes; opt-in workflow |
| JDBC and R2DBC are covered where feasible | Matching `MigrationUtils` tests; plugin/R2DBC connection boundary documented |
| H2, PostgreSQL, and another dialect are checked | H2 Migration Smoke plus dedicated/local no-retry sequential PostgreSQL/MySQL 8 proof |

## Definition of Done

- The written spec and executable plan pass all Type A review perspectives.
- JDBC and R2DBC drift tests show RED before implementation and GREEN after.
- H2, PostgreSQL, and MySQL 8 focused tests pass with real DB commands run
  sequentially.
- Fixed-filename plugin generation leaves no Git diff.
- English and Korean README sections are semantically equivalent; stable 1.11
  manual metadata is unchanged.
- Workflow syntax, affected builds, Detekt, and diff checks pass.
- A durable lesson records the deterministic-filename and plugin/programmatic
  boundary.
- The PR is created against `develop`, assigned to `debop`, mirrors issue #322
  metadata, and reaches merge-ready CI/review state.
- Merge remains blocked until a fresh exact-head approval.
