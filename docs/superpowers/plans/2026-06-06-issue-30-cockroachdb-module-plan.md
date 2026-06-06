# Issue #30 CockroachDB Module Plan

Spec: `docs/superpowers/specs/2026-06-06-issue-30-cockroachdb-module-design.md`

## Tasks

1. Create `exposed/exposed-cockroachdb`.
   - Add `build.gradle.kts`.
   - Add `src/test/resources/junit-platform.properties`.
   - Add `src/test/resources/logback-test.xml`.
   - Rely on `settings.gradle.kts` auto-discovery for
     `:bluetape4k-exposed-cockroachdb`.

2. Implement the minimal public API.
   - Add `CockroachDatabase`.
   - Use PostgreSQL JDBC driver class name.
   - Validate `host`, `port`, `database`, `user`, and `jdbcUrl`.
   - Provide host/port/database, JDBC URL, and `DataSource` connect overloads.
   - Add English KDoc and examples.
   - Do not register or override a custom dialect.

3. Add Testcontainers smoke tests.
   - Add `AbstractCockroachDbTest` using `CockroachServer.Launcher.cockroach`.
   - Add `CockroachDatabaseTest` for `SELECT 1`, URL building, validation, and
     `SchemaUtils.create/drop` on a simple table.
   - Use `@Execution(SAME_THREAD)` and bluetape4k assertions.
   - Keep raw SQL only for readiness/diagnostic checks if needed.

4. Update user-facing documentation.
   - Add module `README.md` and `README.ko.md`.
   - Update root `README.md` and `README.ko.md` module list.
   - Update `CHANGELOG.md`.
   - Update repo-local `AGENTS.md` module list.

5. Update CI/Nightly registration.
   - Add path filter output for `cockroachdb`.
   - Add CI job for `:bluetape4k-exposed-cockroachdb:test`.
   - Add Nightly job for the same module.
   - Add coverage artifacts to CI/Nightly `needs`.
   - Run `actionlint`.

6. Verify locally.
   - `./gradlew projects --console=plain`
   - `./gradlew :bluetape4k-exposed-cockroachdb:test --no-configuration-cache --no-daemon`
   - `./gradlew :bluetape4k-exposed-cockroachdb:koverXmlReport --no-configuration-cache --no-daemon`
   - `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
   - `git diff --check`

7. Review and delivery.
   - Run local 7-tier Step 6-R review on the diff.
   - Add `docs/lessons/2026-06-06-issue-30-cockroachdb-module.md`.
   - Commit using Lore protocol.
   - Push branch and create PR assigned to `debop`.
   - Verify PR body final section is `## DoD Status`.
   - Run PR review gate with P0=0/P1=0 before final report.

## Risks And Controls

- CockroachDB is not an Exposed-supported dialect.
  - Control: do not claim custom dialect parity; test the PostgreSQL-wire smoke
    path only.
- Testcontainers may be unavailable locally or in CI.
  - Control: run serially, use `CockroachServer`, and record concrete blocker if
    Docker cannot start.
- Workflow registration can silently omit coverage aggregation.
  - Control: check path filter, job, coverage artifact, and status `needs`
    together.
