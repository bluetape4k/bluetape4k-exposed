---
manualId: "repository-overview"
title: "Bluetape4k Exposed Manual"
locale: "en"
releaseRef: "1.11.0"
---

# Bluetape4k Exposed Manual

`bluetape4k-exposed` adds repository patterns, transaction boundaries, caching, database-specific extensions, and application integrations to JetBrains Exposed. This manual starts with decisions rather than a feature catalog: choose JDBC or R2DBC, decide when caching is justified, and place database adapters and Spring Boot or Ktor integrations on the correct data-access path.

![Exposed repository overview](../assets/overview/repository-overview.png)

## Version baseline

Consumers select the central `io.github.bluetape4k:bluetape4k-dependencies:<version>` BOM version, not the repository release documented here. The technical baseline for this manual is `bluetape4k-exposed 1.11.0`, limited to the 40 Gradle projects present in that stable release.

- Release tag: [`1.11.0`](https://github.com/bluetape4k/bluetape4k-exposed/tree/1.11.0)
- Release commit: [`0b494a5fd1e083006046764757342b68a397e4c5`](https://github.com/bluetape4k/bluetape4k-exposed/commit/0b494a5fd1e083006046764757342b68a397e4c5)
- Primary paths: JDBC, R2DBC, cache, database adapters, and application integrations

## Where to start

- Use [Getting started](getting-started.md) for the central BOM and JDBC/R2DBC selection rules.
- Read the [Repository map](architecture/repository-map.md) to see how the stable modules fit together.
- Follow the [Learning path](guides/learning-path.md) for a goal-oriented sequence of examples and workshops.
- Open the [module catalog](modules/bluetape4k-exposed-bom.md) when you need coordinates and release-backed source locations.

## Responsibility boundary

This repository owns the application data path. For object history, change comparison, or JaVers commit metadata, move to [`bluetape4k-javers`](https://github.com/bluetape4k/bluetape4k-javers) instead of overloading persistence repositories. JaVers complements Exposed repositories and caches; it does not replace them.
