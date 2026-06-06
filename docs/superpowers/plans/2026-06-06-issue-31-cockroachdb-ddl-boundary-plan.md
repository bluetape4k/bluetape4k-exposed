# Issue #31 CockroachDB DDL Boundary Plan

Spec: `docs/superpowers/specs/2026-06-06-issue-31-cockroachdb-ddl-boundary-design.md`

## Decision

Start from the current helper-only `exposed-cockroachdb` module. Add a
compatibility matrix and executable CockroachDB tests first. Add a custom
`CockroachDbDialect` only if the accepted DDL subset fails because Exposed's
default PostgreSQL dialect advertises or emits an unsafe CockroachDB capability.

## Tasks

1. Add an internal compatibility matrix.
   - Add an internal/test-visible source file under `exposed-cockroachdb`.
   - Represent categories as `Supported`, `Deferred`, or `OutOfScope`.
   - Include evidence notes for primary key DDL, unique constraint/index DDL,
     explicit index DDL, generated IDs, `RETURNING`, metadata discovery,
     migration diff boundary, PostgreSQL range types, `CREATE DOMAIN`, XML
     behavior, drop-primary-key workflows, transaction retry, and R2DBC.
   - Keep the matrix out of the public stable API unless implementation evidence
     requires otherwise.

2. Expand CockroachDB DDL tests.
   - Add `CockroachDdlCompatibilityTest`.
   - Reuse `AbstractCockroachDbTest` and `CockroachServer.Launcher.cockroach`.
   - Use `SchemaUtils.create/drop` for accepted Exposed DDL paths.
   - Use unique table names and cleanup guards.
   - Cover:
     - primary key create/drop;
     - unique constraint duplicate failure;
     - explicit index create/drop;
     - generated ID retrieval with `insertAndGetId`;
     - raw `INSERT ... RETURNING` through PostgreSQL JDBC;
     - JDBC metadata table/column discovery;
     - observed migration diff output after accepted schema creation.
   - Use bluetape4k JDBC/HikariCP helpers for direct JDBC evidence instead of
     ad hoc `DriverManager` test connections.

3. Add unsupported/deferred smoke checks.
   - Add direct SQL checks for `CREATE DOMAIN` and PostgreSQL range type usage
     only if CockroachDB reports stable SQLSTATEs in local verification.
   - Otherwise document them as out of scope and record why no direct test was
     added.
   - Do not add retry helper tests; #32 owns retry behavior.

4. Decide on dialect.
   - Run the expanded suite with the helper-only module.
   - If accepted DDL passes, keep no custom dialect for #31.
   - If accepted DDL fails due to Exposed dialect capability, add the smallest
     `CockroachDbDialect` override and registration path required by evidence.
   - If only migration diff remains noisy after accepted DDL succeeds, keep it
     deferred and document the sequence ownership diff instead of adding a
     premature dialect.
   - If dialect is added, add `db.dialect` assertion and KDoc.

5. Update documentation.
   - Update `exposed/exposed-cockroachdb/README.md`.
   - Update `exposed/exposed-cockroachdb/README.ko.md`.
   - Add the matrix, official-doc caveats, Exposed supported-database caveat,
     and verification command.
   - Update `CHANGELOG.md`.

6. Preserve research evidence.
   - Add a concise durable research note for the official CockroachDB/Exposed
     documentation used by this issue.
   - Keep source quotes short and link to the official pages.

7. Verify locally.
   - `./gradlew :bluetape4k-exposed-cockroachdb:test --rerun-tasks --no-configuration-cache --no-daemon`
   - `./gradlew :bluetape4k-exposed-cockroachdb:koverXmlReport --no-configuration-cache --no-daemon`
   - `./gradlew :bluetape4k-exposed-cockroachdb:compileKotlin :bluetape4k-exposed-cockroachdb:compileTestKotlin --no-configuration-cache --no-daemon`
   - `git diff --check`

8. Review and delivery.
   - Run Step 6-R local 7-tier review on the final diff.
   - Add `docs/lessons/2026-06-06-issue-31-cockroachdb-ddl-boundary.md`.
   - Commit using the Lore protocol.
   - Push branch and create PR assigned to `debop`.
   - Set milestone `1.11.0` and labels matching #31 when available.
   - Verify live PR body and final `##` heading is `## DoD Status`.
   - Add PR comment and formal review with Step 6-R/Step 7-R gate evidence.
   - Monitor PR CI and report final Step 9 DoD. Do not merge without user
     request.

## Validation Expectations

- The accepted DDL subset must pass against a real CockroachDB Testcontainers
  instance, not PostgreSQL.
- The README matrix must be traceable to tests or explicit official-doc
  evidence.
- The PR must not claim full PostgreSQL dialect parity.
- Testcontainers verification must remain serial.

## Risks And Controls

| Risk | Control |
|---|---|
| CockroachDB accepts many PostgreSQL paths but differs in edge cases | Keep the matrix evidence-based and limited to tested paths. |
| Custom dialect adds public surface prematurely | Helper-only remains default; dialect addition is evidence-gated. |
| Unsupported SQL error messages vary by version | Prefer SQLSTATE or stable exception class; otherwise document unsupported paths without brittle tests. |
| Schema diff API differs across Exposed versions | Compile/test the exact API; if the helper-only dialect produces noisy but non-destructive sequence diffs, document it as deferred and record the evidence. |
| Testcontainers flakiness | Use existing singleton `CockroachServer`, serial execution, bounded readiness, and `--rerun-tasks` proof. |
