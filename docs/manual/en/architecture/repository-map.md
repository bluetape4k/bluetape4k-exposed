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

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are copied byte-for-byte from README assets in the `1.11.0` release tag. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG source.

### Bluetape4k Exposed module composition diagram

[![Bluetape4k Exposed module composition diagram](../../assets/readme-diagrams/root-readme-module-relationships-01.png)](../../assets/readme-diagrams/root-readme-module-relationships-01.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/0b494a5fd1e083006046764757342b68a397e4c5/README.md)_

### Bluetape4k Exposed overview diagram

[![Bluetape4k Exposed overview diagram](../../assets/readme-diagrams/root-readme-overview-01.png)](../../assets/readme-diagrams/root-readme-overview-01.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/0b494a5fd1e083006046764757342b68a397e4c5/README.md)_

<!-- release-readme-diagrams:end -->
