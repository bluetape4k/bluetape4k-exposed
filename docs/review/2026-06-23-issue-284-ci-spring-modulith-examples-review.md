# Issue 284 CI Spring Modulith and Examples Review

Date: 2026-06-23
Scope: `.github/workflows/ci.yml`, `.github/workflows/nightly-tests.yml`
Issue: #284

## Verdict

P0 findings: 0
P1 findings: 0

The workflow diff adds first-class CI and Nightly coverage for the spring-modulith module plus documented example and demo test suites. The new jobs are wired into coverage aggregation and final status gates, and their artifacts use dedicated names so missing uploads remain visible in the existing coverage summary flow.

## Review Notes

- CI path filtering now exposes `spring-modulith` and `examples` outputs, which trigger targeted jobs for `spring-boot/spring-modulith/**` and `examples/**`.
- The `spring-modulith` CI filter includes its backing JDBC/core modules and workflow/build files so this PR and future dependency-path changes actually exercise the new lane.
- The `examples` CI filter also includes underlying example dependencies and workflow/build files: `exposed/bigquery/**`, `exposed/clickhouse/**`, `spring-boot/jdbc/**`, `spring-boot/r2dbc/**`, workflow YAML, root Gradle scripts, `gradle/**`, and `buildSrc/**`.
- `test-spring-modulith` runs `:bluetape4k-exposed-spring-modulith:test` and uploads `test-results-spring-modulith` plus `coverage-spring-modulith`.
- `test-examples` runs the BigQuery dry-run example, ClickHouse OLTP/OLAP example, and both Spring Boot demo tests with Testcontainers environment variables for the Docker-backed example.
- Nightly `test-examples` follows the existing Docker-heavy full-scope guard so daily smoke runs do not inherit the ClickHouse Testcontainers example load.
- CI and Nightly `coverage-report` plus final status jobs include the new job names in `needs`, so coverage/status cannot finish without observing the added lanes.
- GitHub Actions expression quoting uses the normal `${{ needs.changes.outputs['spring-modulith'] == 'true' }}` style. No escaped quote sequences were found.

## Validation

- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
  - Result: success.
- Ruby YAML structure check for both workflow files
  - Result: success, confirmed `test-spring-modulith` and `test-examples` jobs plus coverage/status `needs` entries.
- `git diff --check`
  - Result: success.
- `./gradlew :bluetape4k-exposed-spring-modulith:test :examples-exposed-bigquery-dry-run:test :exposed-spring-boot-jdbc-demo:test :exposed-spring-boot-r2dbc-demo:test --no-build-cache --console=plain --no-configuration-cache --no-daemon`
  - Result: success.
  - Evidence: spring-modulith 12 tests, JDBC demo 26 tests, R2DBC demo 25 tests, and BigQuery dry-run example passed.
- `./gradlew :examples-exposed-clickhouse-oltp-olap:testClasses --no-build-cache --console=plain --no-configuration-cache --no-daemon`
  - Result: success.

## Residual Risk

- `:examples-exposed-clickhouse-oltp-olap:test` could not complete locally because the current machine did not expose a valid Docker environment to Testcontainers. The workflow job is still intentionally Docker-enabled and must be proven by GitHub Actions CI/Nightly runners.
