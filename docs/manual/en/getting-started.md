---
manualId: "getting-started"
title: "Getting Started with Bluetape4k Exposed"
locale: "en"
releaseRef: "1.12.1"
---

# Getting Started with Bluetape4k Exposed

## Align versions with the central BOM

Applications should not pin each Exposed module independently. Import the `bluetape4k-dependencies` BOM once, then add only the data-access path the application needs.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc")
    // Choose this instead for coroutine-native reactive database access:
    // implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc")
}
```

Replace `<version>` with the released `bluetape4k-dependencies` version used by your application. The `1.12.1` label in this manual is the verified Exposed source baseline, not the consumer version to paste into this snippet.

## Choose JDBC or R2DBC first

Do not import both paths by habit. Choose the primary path from the driver model, transaction owner, blocking boundary, and framework integration.

| Decision | JDBC | R2DBC |
| --- | --- | --- |
| Driver | Blocking JDBC driver | Non-blocking R2DBC driver |
| Call chain | Allows or isolates blocking work | Remains coroutine/non-blocking end to end |
| Transaction owner | JDBC transaction manager | R2DBC transaction manager and coroutine context |
| Typical integration | Spring MVC and conventional batch work | Spring WebFlux and coroutine services |

JDBC is the production default when an existing JDBC driver and transaction manager own the boundary. Do not choose R2DBC merely because a function is marked `suspend`; use it when the driver, pool, framework, and full call chain preserve the non-blocking model.

## Continue from the foundation

1. Read [`core`](modules/bluetape4k-exposed-core.md) and [`dao`](modules/bluetape4k-exposed-dao.md) for IDs, entities, and mapping.
2. Build repository and transaction boundaries with either [`jdbc`](modules/bluetape4k-exposed-jdbc.md) or [`r2dbc`](modules/bluetape4k-exposed-r2dbc.md).
3. Add caches, database adapters, and Spring Boot or Ktor integration only after the base path is tested.
4. Use the [learning path](guides/learning-path.md) for runnable follow-up material.
