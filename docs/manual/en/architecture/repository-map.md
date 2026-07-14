---
manualId: "repository-map"
title: "Exposed Repository Map"
locale: "en"
releaseRef: "1.11.0"
---

# Exposed Repository Map

The 40 Gradle projects in `bluetape4k-exposed 1.11.0` do not all play the same role. Start from the shared foundation and choose either JDBC or R2DBC. Add caches, codecs, and database adapters only where needed, then select application integrations that match the framework's resource and transaction ownership.

![Exposed module map](../../assets/overview/module-map.png)

## Read the repository in four layers

1. **Foundation** — `core`, `dao`, `cache`, and `bom` define shared types and module alignment.
2. **Data access** — `jdbc` and `r2dbc` implement different driver and transaction models. An application chooses one as its primary path.
3. **Optional capabilities** — Caffeine, Lettuce, and Redisson caches; JSON, Tink, and measured columns; and database adapters refine the base path.
4. **Application integrations** — Spring Boot, Ktor, Batch, and Spring Modulith connect Exposed to framework-owned resources.

A database adapter does not replace JDBC or R2DBC; it adds dialect or backend-specific behavior after the base path is selected. Caching has the same ordering constraint. Adding it before repository and transaction semantics are clear leaves stale reads and invalidation ownership undefined.

## Release scope

This map contains only modules present in tag `1.11.0`. Develop-only modules stay out until a later stable minor establishes a new manual baseline. Use the [module manual](../modules/bluetape4k-exposed-bom.md) for the exact project inventory and source locations.
