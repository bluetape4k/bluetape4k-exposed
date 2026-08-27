---
manualId: "bluetape4k-exposed-ktor-cache"
id: "bluetape4k-exposed-ktor-cache"
title: "Exposed Ktor Cache Adapter"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-ktor-cache"
sourceDir: "ktor/cache"
releaseRef: "develop"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-cache
---

# Exposed Ktor Cache Adapter

Backend-neutral Ktor readiness contributors for caller-owned Exposed cache repositories and snapshot failure buffers.

## Problem {#problem}

Cache health must be observable without turning a readiness request into database, network, or blocking work.

## When to use it {#when-to-use}

Use this adapter when a Ktor service wants an O(1) in-memory view of JDBC, R2DBC, snapshot, or custom cache health.

## Coordinates {#coordinates}

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-cache")
```

## Core concepts {#concepts}

`ExposedKtorCacheContributor` sanitizes finite status and measurements. A contributor is caller-owned, side-effect-free, cancellation-cooperative, and bounded to a low-cardinality component name.

## Quick start {#quick-start}

```kotlin
val config = ExposedKtorCacheReadinessConfig(listOf(
    ExposedKtorCacheContributor.custom("local-cache") { ExposedKtorCacheStatus.UP },
))
val probes = exposedKtorCacheReadinessProbes(config)
route.bluetape4kExposedHealthRoutes(probes)
```

## API by task {#api-by-task}

- Use `jdbcRepository`, `r2dbcRepository`, `snapshot`, or `custom` contributors.
- Wrap contributors with `ExposedKtorCacheReadinessConfig`.
- Convert the immutable configuration with `exposedKtorCacheReadinessProbes`.

## Recommended patterns {#patterns}

Read an existing health snapshot; do not perform I/O in a supplier. Use stable component names and let the application own cache invalidation and shutdown.

## Integrations {#integrations}

The adapter depends on core and the Exposed cache foundation. JDBC Caffeine and R2DBC Caffeine persistence implementations remain optional.

## Configuration {#configuration}

Configure one to sixteen unique contributors. Queue and snapshot measurements are non-negative; unavailable values use `NaN` and are never coerced to zero.

## Failure modes {#failures}

Supplier exceptions become `DOWN`, cancellation follows the request context, and invalid component names or measurements fail at configuration time. Raw cache keys, URLs, SQL, and causes are not returned.

## Operations {#operations}

Cache probes run sequentially under the core shared deadline. Export core readiness metrics and monitor the cache repository's own lifecycle report separately.

## Testing {#testing}

Test O(1) snapshot conversion, negative-value rejection, supplier cancellation, deadline expiry, duplicate components, and sanitized responses.

## Workshops {#workshops}

No selective cache workshop is published in this develop-only release line.

## Limitations {#limitations}

The contributor contract does not make blocking or backend-I/O suppliers safe. Applications must supply a true in-memory observer.

## Sources {#sources}

- [Micrometer meter concepts](https://docs.micrometer.io/micrometer/reference/concepts.html)
- [Caffeine cache](https://github.com/ben-manes/caffeine)
