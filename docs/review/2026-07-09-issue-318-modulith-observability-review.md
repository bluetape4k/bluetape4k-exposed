# Issue 318 Exposed Modulith Observability Review

## Scope

- Issue: #318 `feat(spring-modulith): add optional observability for Exposed event publications`
- Module: `spring-boot/spring-modulith`
- Change shape: optional Micrometer auto-configuration, focused tests, README/README.ko updates

## Evidence

- Official Spring Modulith source/docs identify `module.events.published` as the event-publishing metric family and expose customization APIs for event metrics. This PR keeps Exposed metrics separate as durable store-state gauges.
- Local source previously had `ExposedModulithAutoConfiguration`, `ExposedModulithProperties`, and `ExposedEventPublicationRepository`, but no Micrometer integration.
- Classpath and bean activation follow Spring Boot optional auto-configuration rules: Micrometer is `compileOnly`, the auto-configuration is guarded by `@ConditionalOnClass(name = [...])`, and the bean is conditional on an `ExposedEventPublicationRepository` plus `MeterRegistry`.

## Review Result

| Lens | Status | Evidence |
|---|---|---|
| Tier 4 implementation | PASS | Optional auto-configuration registers gauges only when repository and `MeterRegistry` exist; no existing repository mutation semantics changed. |
| Tier 5 tests | PASS | Focused auto-configuration tests cover enabled path, disabled property, missing Micrometer classpath, missing `MeterRegistry`, missing repository, unloadable toggle, and configured tags. Full module tests passed. |
| Tier 7 docs | PASS | `README.md` and `README.ko.md` document activation conditions, meter name, tag cardinality, and Spring Modulith metric boundary. |
| Diagram review | PASS | No diagram asset added. The change adds operational gauge activation and tag contracts, not a new topology or event lifecycle. The README bullet list is a clearer source-backed artifact than a diagram here. |
| Concurrency gate | PASS | No concurrency, thread-safety, coroutine, or virtual-thread behavior changed; `MultithreadingTester` and related stress helpers are not applicable. |

## Notes

- Gauge callbacks use `Double.NaN` when repository reads fail so metric scrape paths do not throw. Repository correctness remains covered by repository tests; this PR only adds the observability surface.
- Additional configured tags must remain low-cardinality deployment tags such as application, region, or environment.

## Validation

- `repo-test-summary -- ./gradlew :bluetape4k-exposed-spring-modulith:test --tests 'io.bluetape4k.spring.modulith.exposed.config.ExposedModulithObservabilityAutoConfigurationTest' --no-configuration-cache --no-build-cache --no-parallel --rerun-tasks --console=plain`
  - Result: `SUCCESS: Executed 7 tests in 2.6s`; `BUILD SUCCESSFUL in 6s`
- `repo-test-summary -- ./gradlew :bluetape4k-exposed-spring-modulith:test --no-configuration-cache --no-build-cache --no-parallel --rerun-tasks --console=plain`
  - Result: `SUCCESS: Executed 61 tests in 16.9s`; `BUILD SUCCESSFUL in 20s`
- `git diff --check`
  - Result: PASS
- `mcp__omx_code_intel.lsp_servers`
  - Result: Kotlin/IntelliJ diagnostics backend unavailable; Gradle `compileKotlin`, focused tests, and full module tests were used as fallback evidence.
