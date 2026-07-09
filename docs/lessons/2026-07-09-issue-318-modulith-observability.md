# Issue 318 Exposed Modulith Observability Lesson

## Context

Issue #318 asked for optional observability over the Exposed-backed Spring Modulith event publication store.

## Decision

Add an optional Micrometer auto-configuration that exposes durable store-state gauges under `bluetape4k.exposed.modulith.publications` instead of trying to customize or rename Spring Modulith's own `module.events.published` metric family.

## Outcome

- Applications without Micrometer or a `MeterRegistry` remain unaffected.
- Applications with Micrometer can inspect incomplete, completed, failed, and unloadable publication counts.
- README and Korean README now document activation conditions, meter tags, and tag-cardinality constraints.
- No diagram was added because the new behavior is an operational metric contract, not a new architecture or event sequence. A compact meter/tag section is clearer and cheaper to maintain.

## Verification

- Focused auto-configuration test: 7 tests passed.
- Full `:bluetape4k-exposed-spring-modulith:test`: 61 tests passed.
- `git diff --check`: PASS.

## Future Guard

If more Modulith metrics are added, keep Spring Modulith event emission metrics and Exposed durable store-state metrics separate. Prefer low-cardinality gauges for store truth and document every new tag.
