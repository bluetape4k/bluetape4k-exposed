# Issue #255 StarRocks Exposed Module Plan

Date: 2026-06-06
Repository: `bluetape4k-exposed`
Spec: `docs/superpowers/specs/2026-06-06-issue-255-starrocks-module-design.md`
Issue: `bluetape4k/bluetape4k-exposed#255`

## Execution Principles

- Keep the first implementation narrow: connection, dialect registration,
  metadata smoke, fixture table, insert/select, docs, and workflow visibility.
- Prove the StarRocks container/bootstrap path before adding broad public API.
- Mirror existing `exposed-trino` and `exposed-clickhouse` structure where it
  fits; do not infer MySQL parity.
- Keep Testcontainers-backed execution serial.
- Use `bluetape4k-assertions` only in new tests.
- Public KDoc, PR body, commit messages, and GitHub artifacts stay English.

## Task Plan

| ID | Task | Inputs | Output | DoD |
|---|---|---|---|---|
| T1 | Container and driver bootstrap proof | Official docs, Maven metadata, Docker availability | Test-scoped bootstrap fixture design | Local command or targeted test proves `com.starrocks.cj.jdbc.Driver`, `jdbc:starrocks://host:port`, explicit `CREATE DATABASE`, and `SELECT 1`, or records environment blocker with evidence. |
| T2 | Module scaffold | Trino/ClickHouse/DuckDB module layout | `exposed/exposed-starrocks` with build file, source/test resources, README locale files | `./gradlew projects` lists `:bluetape4k-exposed-starrocks`; module compiles after code is added. |
| T3 | Dependency/catalog update | `gradle/libs.versions.toml`, Maven Central evidence | `starrocks-connector-j` alias and module dependency | Dependency resolves with `dependencyInsight`; license evidence remains in spec/PR; no central catalog claim is made. |
| T4 | Public connection API | Spec connection contract, existing OLAP wrappers | `StarRocksDatabase`, connection options, connection wrapper | Host/port/catalog/database and URL overloads validate inputs; raw connection closes if wrapper creation fails; KDoc states supported URL shape and transaction caveats. |
| T5 | Dialect and metadata | Existing dialect patterns, StarRocks docs | `StarRocksDialect`, metadata adapter, optional `StarRocksTable` | Dialect registers as `starrocks`; unsupported/unproven DDL features are disabled or bypassed only with evidence; metadata smoke passes. |
| T6 | Test fixture and smoke tests | T1 bootstrap proof | Serial container base test and focused tests | Tests prove DB bootstrap, connection, `SELECT 1`, fixture table setup, insert/select, metadata catalog/schema/table/column discovery, validation failure paths, and DataSource path if implemented. |
| T7 | Documentation | Spec documentation contract | Root/module `README.md` and `README.ko.md` updates | Locale files list StarRocks, include dependency snippet, supported/unsupported scope, Docker resource requirements, and usage example. |
| T8 | Registration and workflows | AGENTS, CI, Nightly, coverage aggregation | Module list and workflow visibility updates | `AGENTS.md`, CI path filters/jobs/artifacts/summary needs, Nightly placement, BOM/check-script verification are complete; `actionlint` passes after workflow edits. |
| T9 | Verification and review | Implemented diff | Local evidence and Step 6-R review | IDE diagnostics or recorded fallback, targeted Gradle commands, workflow lint, diff checks, and code review gate all pass with `P0=0/P1=0`. |
| T10 | Lessons, wiki evidence, commit, PR | Verified branch | Lesson artifact, research-preservation decision, Lore commit, PR body | Lesson includes context/decision/outcome/evidence; wiki note is updated only if #255 adds decision-relevant research beyond the existing #227 note; PR body ends with Step DoD table; PR assigned to `debop` and linked to #255. |

## Detailed Implementation Sequence

### T1. Container And Driver Bootstrap

1. Add the StarRocks driver alias first in `gradle/libs.versions.toml` so a
   minimal test can compile.
2. Create a temporary/test-scoped singleton fixture, not a production launcher:
   - image: `starrocks/allin1-ubuntu`
   - exposed ports: `9030`, `8030`, `8040`
   - username: `root`
   - password: empty string
   - readiness: poll `SELECT 1`
3. Verify whether the driver accepts `jdbc:starrocks://{host}:{port}` for
   bootstrap. If not, use the smallest officially accepted URL and raw SQL
   sequence that can create the test database.
4. Create a deterministic test database name with a stable prefix and short
   suffix; avoid shared mutable names when reusable containers are enabled.
5. Record actual bootstrap behavior in tests and README.

### T2-T5. Module And API

1. Scaffold files from the closest existing module pattern:
   - build file from `exposed-trino` plus StarRocks driver dependency.
   - wrapper/factory lifecycle from `exposed-clickhouse`/`exposed-trino`.
   - dialect minimalism from `exposed-duckdb`.
2. Implement public API:
   - `StarRocksConnectionOptions` as a `Serializable` data class.
   - `StarRocksDatabase` registration and connect overloads.
   - `StarRocksConnectionWrapper` only if needed to enforce Exposed-compatible
     autocommit/rollback behavior.
   - `StarRocksTable` only if real Exposed DDL needs StarRocks-specific SQL.
3. Implement dialect with the smallest proven changes:
   - start from source-inspected Exposed vendor dialect.
   - disable unproven ALTER/type/generated-key/sequence behavior.
   - keep `DatabaseMetaData` calls enabled until a real unsupported call fails.
4. Use English KDoc for public classes and examples.

### T6. Tests

Required test files:

- `AbstractStarRocksTest`
- `StarRocksDatabaseTest`
- `StarRocksDatabaseValidationTest`
- `StarRocksConnectionWrapperTest` if wrapper exists
- `StarRocksDialectTest`
- `StarRocksMetadataTest`
- `SchemaUtilsTest` or fixture setup test if Exposed DDL is supported
- `insert/InsertTest`
- `query/SelectTest`
- `domain/Events.kt` or equivalent fixture table

Required assertions:

- Driver/dialect registration:
  - `db.dialect.name == "starrocks"`
  - `db.dialect` is `StarRocksDialect`
- Connection:
  - host/port/catalog/database overload succeeds after explicit DB bootstrap.
  - direct JDBC URL overload succeeds.
  - DataSource overload succeeds if implemented.
- Validation:
  - blank host/catalog/database/user rejected.
  - invalid port rejected.
  - wrong URL prefix rejected.
  - blank option key/value rejected.
- Backend proof:
  - `SELECT 1`.
  - fixture table setup.
  - insert one or more rows.
  - select rows by direct condition.
  - metadata discovers the created table and at least one column.
- Transaction caveat:
  - if wrapper forces autocommit/no-op rollback, add a test matching the
    established ClickHouse/Trino behavior.

### T7. Documentation

Update:

- root `README.md`
- root `README.ko.md`
- `exposed/exposed-starrocks/README.md`
- `exposed/exposed-starrocks/README.ko.md`

README checks:

- Language switch is `English | 한국어`.
- Dependency snippet uses the actual artifact id.
- Examples use real public API names.
- Docker requirements list image, ports, 4 GB RAM, 10 GB disk.
- Unsupported claims are explicit.

### T8. Registration And Workflows

Verify/update:

- `AGENTS.md` module list includes `bluetape4k-exposed-starrocks/`.
- `settings.gradle.kts` auto-discovery works through `./gradlew projects`.
- `gradle/libs.versions.toml` has StarRocks alias near sibling JDBC drivers.
- `.github/workflows/ci.yml`:
  - path filter for `exposed/exposed-starrocks/**`
  - job or explicit compile/test lane
  - test-result artifact
  - coverage artifact
  - coverage summary `needs`
- `.github/workflows/nightly-tests.yml`:
  - serial StarRocks Testcontainers lane if normal CI is too heavy, otherwise
    explicit coverage parity with CI.
  - coverage summary `needs`
- BOM/catalog:
  - inspect `exposed/bluetape4k-exposed-bom` for explicit module constraints.
  - update if new modules are listed explicitly.
- Generated check scripts:
  - search for module/catalog validation scripts and update only if they
    enumerate modules explicitly.

## Verification Commands

Run from the #255 worktree.

```bash
./gradlew projects --no-configuration-cache --no-daemon
./gradlew :bluetape4k-exposed-starrocks:dependencyInsight --dependency starrocks-connector-j --configuration runtimeClasspath --no-configuration-cache --no-daemon
./gradlew :bluetape4k-exposed-starrocks:compileKotlin --no-configuration-cache --no-daemon
./gradlew :bluetape4k-exposed-starrocks:cleanTest :bluetape4k-exposed-starrocks:test --no-build-cache --no-configuration-cache --no-daemon
./gradlew :bluetape4k-exposed-starrocks:koverXmlReport --no-configuration-cache --no-daemon
actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml
git diff --check
```

Also run IDE diagnostics for touched Kotlin files when IntelliJ MCP tools are
available. If not available, record the fallback and use Gradle compile/test
evidence.

If Docker or StarRocks startup fails locally, rerun once after confirming Docker
resources, then classify the failure as environment, image, or implementation
with the exact log excerpt.

## Review And PR Steps

1. Run Step 6-R code review after implementation and verification.
2. Fix every P0/P1 finding and rerun affected checks.
3. Add `docs/lessons/2026-06-06-issue-255-starrocks-module.md`.
4. Check the existing wiki note
   `bluetape4k-wiki/research/2026-06-06-exposed-olap-local-testability.md`.
   Update wiki only if implementation discovers new source-backed decisions not
   already preserved there.
5. Commit with Lore trailers.
6. Push branch and create a PR assigned to `debop`, milestone `backlog`, linked
   with `Closes #255`.
7. Verify live PR body with `gh pr view --json body`; last `##` section must be
   `## DoD Status`.

## Stop Conditions

- Do not implement beyond the spec without opening a follow-up issue.
- Do not merge the PR without explicit user merge approval.
- If the native driver or all-in-one image cannot provide the required local
  proof, stop after recording evidence and update #255/plan with the blocker.
