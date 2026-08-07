---
manualId: "bluetape4k-exposed-bom"
id: "bluetape4k-exposed-bom"
title: "Exposed Bill of Materials"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-bom"
sourceDir: "exposed/bom"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-bom
---

# Exposed Bill of Materials

> Version alignment for the published `bluetape4k-exposed` libraries. Applications should normally import the central `bluetape4k-dependencies` BOM instead of selecting this repository BOM directly.

## Problem {#problem}

An application can combine core, JDBC or R2DBC, a database adapter, and framework integration modules. Declaring every version independently permits a dependency graph that was never released or tested together. This BOM publishes constraints for the repository's publishable modules.

## When to use it {#when-to-use}

Use the central `io.github.bluetape4k:bluetape4k-dependencies:<version>` platform for an application that consumes more than one bluetape4k repository. Import this narrower BOM only when dependency management is intentionally limited to `bluetape4k-exposed`.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc")
}
```

Direct repository BOM: `io.github.bluetape4k.exposed:bluetape4k-exposed-bom:1.12.1`.

## Core concepts {#concepts}

- A BOM contributes version constraints; it adds no runtime classes.
- The repository BOM constrains published library projects and excludes examples, demos, and benchmarks.
- The documentation version records source provenance. The version an application selects should come from the central dependency catalog unless it deliberately owns lower-level alignment.

## Quick start {#quick-start}

Import one platform, omit versions from managed bluetape4k artifacts, and inspect Gradle's resolved dependency graph. Do not import both BOMs merely to repeat the same constraints.

## API by task {#api-by-task}

| Task | Gradle API |
|---|---|
| Accept managed versions | `implementation(platform(...))` |
| Enforce every constraint | `implementation(enforcedPlatform(...))` |
| Inspect one dependency | `dependencyInsight` |
| Inspect a configuration | `dependencies --configuration runtimeClasspath` |

## Recommended patterns {#patterns}

Keep the central BOM version in one version catalog or convention plugin. Upgrade it as one change, then run the application's compile and integration tests before merging.

## Integrations {#integrations}

The BOM aligns repository artifacts; it does not install a JDBC driver, R2DBC driver, connection pool, Spring starter, or Testcontainers module. Declare those according to the selected runtime path.

## Configuration {#configuration}

No runtime configuration is exposed. Gradle resolves the platform during dependency graph construction.

## Failure modes {#failures}

- Adding explicit versions beside managed artifacts can override the tested set.
- Treating a BOM as a library leaves required runtime modules absent.
- Mixing central and repository BOM versions from different release lines can produce surprising constraint selection.

## Operations {#operations}

Record the central BOM version in deployment provenance. When diagnosing a classpath problem, capture `dependencyInsight` output rather than inferring the selected version from the build file.

## Testing {#testing}

Run a dependency-resolution check, compile the chosen JDBC or R2DBC path, and execute its database integration tests. The BOM itself has no behavioral test surface.

## Workshops and learning path {#workshops}

Continue with [Getting started](../getting-started.md), then choose [JDBC or R2DBC](../guides/jdbc-vs-r2dbc.md). Workshop repositories use the same central dependency-management entry point.

## Limitations {#limitations}

The BOM cannot guarantee behavioral compatibility with an independently overridden Exposed, Kotlin, driver, or framework version. It also does not select JDBC over R2DBC.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### Exposed BOM managed artifact map

[![Exposed BOM managed artifact map](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-bom-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-bom-diagram-01.svg)

_Release README: [`exposed/bom/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/bom/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [BOM build](../../../../exposed/bom/build.gradle.kts)
- [Repository settings](../../../../settings.gradle.kts)
