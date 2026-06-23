# Issue #275 Ktor Exposed Integration Lesson

Date: 2026-06-23
Issue: #275
Milestone: 1.11.0

## Result

Added `bluetape4k-exposed-ktor` as an explicit opt-in integration module for
Ktor applications that already own their Exposed JDBC/R2DBC resources.

The module keeps the ownership boundary narrow:

- no `Database` or `R2dbcDatabase` creation
- no dispatcher, pool, or connection factory creation in main sources
- no global Micrometer registry use
- no default StatusPages mutation when an application already owns StatusPages
- no authentication, logging, tracing, OpenAPI, or content negotiation setup

## Design Notes

- `installBluetape4kExposedKtor()` is intentionally a default no-op. Health,
  readiness, metrics, and StatusPages integration are installed only when the
  caller opts in.
- JDBC transaction helpers require a caller-supplied `CoroutineDispatcher`
  because Exposed JDBC remains blocking.
- R2DBC helpers wrap Exposed `suspendTransaction` and do not add a blocking
  dispatcher path.
- Readiness routes report only allowlisted backend keys and status categories.
  Database exception details stay out of HTTP responses.
- `bluetape4kExposedErrors()` can be composed with Ktor core error responses,
  but the module refuses to reopen an already installed `StatusPages` plugin.
- Demo resources are created in the example only and closed through Ktor
  `ApplicationStopped`.

## Follow-Up Created

The implementation needs a shared dependency catalog alias after this repository
publishes the new artifact:

- bluetape4k-dependencies issue #126:
  `Add catalog alias for bluetape4k-exposed-ktor`

## Verification Evidence

- `:bluetape4k-exposed-ktor:test` passed with 8 tests covering no-op install,
  StatusPages collision handling, redaction, JDBC/R2DBC readiness, metrics,
  commit, and rollback.
- `:examples-ktor-exposed-demo:test` passed with a smoke route covering health,
  readiness, and a transaction endpoint.
- `:bluetape4k-exposed-bom:generatePomFileForBluetapeExposedPublication` and
  `:bluetape4k-exposed-ktor:generatePomFileForBluetapeExposedPublication`
  passed, and the generated BOM POM contains `bluetape4k-exposed-ktor`.
- Root `dependencies --configuration nmcpAggregation` includes
  `project ':bluetape4k-exposed-ktor'` and does not include the demo module.
- `actionlint` passed for CI and nightly workflow updates.
- Static guards found no hidden dispatcher/executor/global registry/resource
  creation in `ktor/exposed/src/main`.
- Static guards found no raw database exception detail logging in
  `ktor/exposed/src/main`.
- Documentation secret scan found only safe text/code identifiers, not live
  credentials or concrete production connection URLs.

## Reuse Guidance

For future web-framework integrations, keep the integration module explicit and
caller-owned first. Add convenience only where it does not create lifecycle,
secret, dispatcher, or plugin ownership ambiguity.
