# Issue #255 StarRocks Exposed Module Spec

Date: 2026-06-06
Repository: `bluetape4k-exposed`
Issue: `bluetape4k/bluetape4k-exposed#255`
Workflow: `bluetape4k-full-feature`

## Step 1 Evidence

- Live issue #255 is open, assigned to `debop`, milestone `backlog`, labels
  `enhancement` and `feature`.
- Parent research #227 accepted StarRocks as the strongest local-first OLAP
  implementation candidate and delegated container/dialect proof to #255.
- Official StarRocks JDBC docs, checked on 2026-06-06, document:
  - Maven coordinate `com.starrocks:starrocks-connector-j:1.1.1`.
  - JDBC URL form `jdbc:starrocks://<fe_host>:<fe_query_port>/<catalog>.<database>`.
  - FE query port default `9030`.
  - Standard JDBC `DatabaseMetaData` support for catalog, schema, table, and
    column introspection.
- Official StarRocks Docker quickstart, checked on 2026-06-06, documents:
  - Image `starrocks/allin1-ubuntu`.
  - Ports `9030`, `8030`, and `8040`.
  - Local Docker requirement: 4 GB RAM and 10 GB free disk.
- Official StarRocks DataGrip docs, checked on 2026-06-06, identify the native
  driver class as `com.starrocks.cj.jdbc.Driver` and recommend the native driver
  for metadata discovery.
- Official StarRocks `CREATE TABLE` docs, checked on 2026-06-06, show that
  StarRocks has its own OLAP table syntax and distribution/key clauses. The
  first implementation must therefore keep generated DDL intentionally narrow.
- Maven Central StarRocks Connector/J metadata, checked on 2026-06-06, identifies
  the artifact as a JDBC Type 4 driver compatible with the MySQL protocol. The
  published POM declares `GNU General Public License, v2 with Universal FOSS
  Exception, v1.0`; implementation must record this dependency-license evidence
  in the PR and must not shade or repackage the driver.

## Goal

Add a dedicated `:bluetape4k-exposed-starrocks` module that proves a narrow,
locally testable StarRocks JDBC integration for Exposed.

The module should let users connect through the native StarRocks JDBC driver,
register a StarRocks Exposed dialect, run basic query/metadata smoke tests
against a local/container StarRocks instance, and document the supported scope
without claiming broad MySQL, PostgreSQL, Trino, or ClickHouse parity.

## Non-Goals

- Do not claim full Exposed DAO/repository parity.
- Do not claim MySQL dialect parity only because StarRocks speaks a
  MySQL-compatible protocol.
- Do not implement broad StarRocks DDL generation, partitioning, rollups,
  aggregate key variants, external catalogs, stream load, or data lake features.
- Do not add a Spring Boot starter or R2DBC module.
- Do not require SaaS credentials or external StarRocks Cloud access.
- Do not add a reusable `bluetape4k-testcontainers` StarRocks launcher in this
  issue unless the current repository already exposes one. If no launcher exists,
  keep the local container fixture test-scoped and record a follow-up if
  repeated use becomes likely.

## Public Module Contract

### Module

- Directory: `exposed/exposed-starrocks`
- Gradle project: `:bluetape4k-exposed-starrocks`
- Artifact: `io.github.bluetape4k.exposed:bluetape4k-exposed-starrocks`

`settings.gradle.kts` auto-discovers `exposed/*/build.gradle.kts`, so the module
is registered by adding the directory. Public module lists still need explicit
updates in root README files and repo-local `AGENTS.md`.

### Dependencies

- Add a local version-catalog alias for the StarRocks driver because sibling
  OLAP JDBC drivers (`clickhouse-jdbc`, `duckdb-jdbc`, `trino-jdbc`) are
  currently module-local aliases in this repository, not centrally governed
  `bluetape4k-dependencies` aliases.
- Use `api(libs.starrocks.connector.j)` or the final alias selected during
  implementation.
- Keep dependency scope public because the module exposes a connection factory
  that relies on the StarRocks JDBC driver being present at runtime.

### Packages And Public Types

Use the existing OLAP module naming style:

- `io.bluetape4k.exposed.starrocks.StarRocksDatabase`
- `io.bluetape4k.exposed.starrocks.StarRocksConnectionOptions`
- `io.bluetape4k.exposed.starrocks.StarRocksConnectionWrapper`
- `io.bluetape4k.exposed.starrocks.StarRocksTable`
- `io.bluetape4k.exposed.starrocks.dialect.StarRocksDialect`
- `io.bluetape4k.exposed.starrocks.dialect.StarRocksDialectMetadata`

Public API KDoc must be English and must state the non-atomic transaction
contract if StarRocks behaves like the existing OLAP wrappers.

### Connection Factory

`StarRocksDatabase` must:

- Register prefix `jdbc:starrocks`.
- Register dialect name `starrocks`.
- Register dialect metadata.
- Expose a host/port/catalog/database overload with defaults:
  - `host = "localhost"`
  - `port = 9030`
  - `catalog = "default_catalog"`
  - `user = "root"`
  - `password = ""`
- Require `database` explicitly. Official quickstart docs create a database
  after the container starts, and the spec must not assume that
  `default_catalog.default` exists.
- Expose a direct JDBC URL overload.
- Expose a `DataSource` overload when it can follow the Trino/ClickHouse wrapper
  leak-prevention pattern.
- Validate blank host, catalog, database, user values and invalid port values
  before calling `DriverManager`.
- Build URL as `jdbc:starrocks://{host}:{port}/{catalog}.{database}`.
- Provide an internal/test bootstrap path that can connect with a no-database
  JDBC URL (`jdbc:starrocks://{host}:{port}`) only for `CREATE DATABASE` or
  readiness checks if the driver accepts the official DataGrip URL template.
  User-facing examples should prefer the catalog/database URL after the database
  exists.

`StarRocksConnectionOptions` should remain small:

- Standard `user` and `password` properties are always supported.
- Any additional map of JDBC properties must reject blank keys and blank values.
- Avoid driver-specific options until official docs or driver source confirms
  them.

### Dialect Scope

Start from the smallest Exposed dialect that can pass real StarRocks smoke tests:

- Prefer reusing an existing Exposed vendor dialect only after source inspection
  shows the generated SQL is accepted by StarRocks.
- Disable unsupported or unproven DDL features rather than translating them
  broadly.
- Treat `CREATE TABLE` as StarRocks-specific. If Exposed default DDL is rejected,
  provide `StarRocksTable` or a narrow table override that emits only the
  minimal fixture DDL needed by tests.
- Metadata adapter must avoid unsupported `DatabaseMetaData` calls only after a
  real failure is observed. Do not preemptively mask metadata behavior.

## Tests And Local Verification Contract

The module must include:

- Unit tests for URL construction validation and dialect registration.
- Container-backed smoke tests that run serially and prove:
  - JDBC connection succeeds through `com.starrocks.cj.jdbc.Driver`.
  - `SELECT 1` executes.
  - The test database is created explicitly before connecting to
    `default_catalog.<test_database>`.
  - A minimal fixture table can be created or set up through a known-good SQL
    path.
  - A row can be inserted or otherwise loaded by a local fixture path.
  - A `SELECT` query returns expected data.
  - `DatabaseMetaData` can discover catalog/schema/table/column information.
- Test resources:
  - `src/test/resources/junit-platform.properties`
  - `src/test/resources/logback-test.xml`
- Test assertions must use `bluetape4k-assertions`.
- Testcontainers-backed Gradle verification must run serially.

If the official all-in-one image is too slow or unstable for PR CI, the
acceptance path is:

1. Keep local container smoke tests in the module.
2. Place the module in Nightly or an explicitly serial workflow lane.
3. Document Docker memory/disk requirements and the exact local command.
4. Record any remaining CI resource risk in the review and PR body.

## Documentation Contract

Update both English and Korean README files:

- Root `README.md` and `README.ko.md` module table entries.
- Module `exposed/exposed-starrocks/README.md`.
- Module `exposed/exposed-starrocks/README.ko.md`.

README content must cover:

- Supported scope: connection, narrow dialect registration, metadata smoke,
  simple query execution.
- Unsupported scope: broad DDL/DML parity, MySQL parity claims, StarRocks Cloud,
  stream load, external catalog features.
- Dependency snippet.
- Local Docker/Testcontainers requirements: image, ports, 4 GB RAM, 10 GB disk.
- Minimal usage example.

## CI And Registration Contract

Because this is a new module, implementation must update or verify:

- `settings.gradle.kts` registration through auto-discovery.
- Root README locale set.
- Repo-local `AGENTS.md` module list.
- `.github/workflows/ci.yml` path filter, job, coverage artifact, and coverage
  summary `needs`.
- `.github/workflows/nightly-tests.yml` path filter/job if the StarRocks
  container is too heavy for normal CI, or explicit confirmation that CI owns
  the smoke lane.
- `gradle/libs.versions.toml` dependency alias.
- BOM/catalog publication constraints if this repository requires explicit
  entries for new modules.
- Generated catalog/check scripts if present.
- `./gradlew projects`.
- `actionlint` after workflow edits.

## Acceptance Criteria Mapping

| Issue #255 AC | Spec Requirement |
|---|---|
| Local/container smoke proves connection | `AbstractStarRocksTest` or equivalent serial container smoke |
| Fixture/table setup | Minimal known-good StarRocks SQL fixture path |
| Metadata introspection | `DatabaseMetaData` catalog/schema/table/column smoke |
| SELECT query execution | `SELECT 1` plus fixture query test |
| No PostgreSQL/MySQL parity claims | README non-goals and dialect narrow scope |
| CI/Nightly explicit | Workflow update/verification contract |
| README updated if user-facing | Root and module README locale set |

## Risks

| Risk | Mitigation |
|---|---|
| StarRocks all-in-one image is heavy | Run tests serially, document resource requirements, prefer Nightly if CI is unstable |
| Driver is MySQL-protocol compatible but SQL is StarRocks-specific | Source-verify generated SQL and keep DDL narrow |
| `DatabaseMetaData` support differs by catalog/database URL shape | Explicitly create the test database, then test `default_catalog.<test_database>` and document the supported shape |
| No existing bluetape4k StarRocks Testcontainers launcher | Use test-scoped singleton fixture and create follow-up only if reuse is needed |
| New dependency is not centralized in `bluetape4k-dependencies` | Mirror sibling OLAP local alias pattern and record the governance choice |

## Source Links

- StarRocks JDBC Driver: https://docs.starrocks.io/docs/integrations/JDBC_driver/
- StarRocks Docker quickstart: https://docs.starrocks.io/docs/quick_start/shared-nothing/
- StarRocks DataGrip integration: https://docs.starrocks.io/docs/integrations/IDE_integrations/DataGrip/
- StarRocks CREATE TABLE: https://docs.starrocks.io/docs/sql-reference/sql-statements/table_bucket_part_index/CREATE_TABLE/
- Maven Central StarRocks Connector/J: https://central.sonatype.com/artifact/com.starrocks/starrocks-connector-j/1.1.1
