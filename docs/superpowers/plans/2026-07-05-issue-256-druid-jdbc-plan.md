# Plan — Issue #256 Druid JDBC query-only experiment

## Tasks

1. Register module and dependency aliases.
   - Add `exposed/druid/build.gradle.kts`.
   - Add Avatica catalog alias.
   - Rely on existing `settings.gradle.kts` auto-discovery for `:bluetape4k-exposed-druid`.
2. Implement query-only API.
   - `DruidConnectionOptions` builds official Avatica JDBC URLs and Properties.
   - `DruidJdbc` exposes `connection`, `query`, `queryList`, and `listColumns` helpers.
   - No Exposed dialect, DDL, DML, DAO, repository, or migration APIs.
3. Add tests and smoke contract.
   - Unit tests cover option validation, properties, and query-only SQL shape.
   - Environment-gated smoke test covers connection, metadata discovery, and SELECT
     when `EXPOSED_DRUID_SMOKE=true` and a fixture datasource is available.
4. Add user docs.
   - Update `exposed/druid/README.md` and `README.ko.md`.
   - Update root README module tables and AGENTS module list.
5. Register CI/Nightly.
   - Add path-filter output and dedicated serial `test-druid` jobs.
   - Add coverage/CI status needs.
6. Verify.
   - `./gradlew --no-parallel :bluetape4k-exposed-druid:compileTestKotlin :bluetape4k-exposed-druid:test`.
   - `./gradlew projects` confirms module discovery.
   - `actionlint` for workflow edits.
   - `git diff --check`, `gno update`.
7. Review, lessons, commit, PR, CI, merge.
