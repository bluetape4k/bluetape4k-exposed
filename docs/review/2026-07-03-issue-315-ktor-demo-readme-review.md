# Issue #315 Ktor Demo README Review

## Scope

- Issue: #315 `docs(examples): add README pair for ktor-exposed-demo`
- Files reviewed:
  - `examples/ktor-exposed-demo/README.md`
  - `examples/ktor-exposed-demo/README.ko.md`
  - `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplication.kt`
  - `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoResources.kt`
  - `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplicationTest.kt`
  - `ktor/exposed/README.md`
  - `ktor/exposed/README.ko.md`

## Findings

- P0/P1: none.
- The new README pair keeps the public contract source-grounded: the demo owns
  H2 JDBC/R2DBC resources, passes them to `installBluetape4kExposedKtor()`, and
  closes them from the Ktor lifecycle.
- The documentation does not imply that `installBluetape4kExposedKtor()` creates
  databases, pools, dispatchers, Ktor core, or content negotiation.
- English and Korean files cover equivalent sections: overview, resource
  ownership, Ktor composition, routes, running, and module README links.

## Verification

- `git diff --check`: PASS.
- Targeted source reference check for routes, `StatusPages`,
  `ApplicationStopped`, `installHealthRoutes = true`, and
  `:examples-ktor-exposed-demo:test`: PASS.
- `./gradlew :examples-ktor-exposed-demo:test`: PASS, 1 test passing,
  `BUILD SUCCESSFUL in 15s`.

## Residual Risk

- Documentation-only change. No production source or test source was modified.
