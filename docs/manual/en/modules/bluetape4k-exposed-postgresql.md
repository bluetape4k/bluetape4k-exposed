---
manualId: "bluetape4k-exposed-postgresql"
id: "bluetape4k-exposed-postgresql"
title: "Exposed PostgreSQL Extensions"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-postgresql"
sourceDir: "exposed/postgresql"
releaseRef: "1.11.0"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-postgresql
---

# Exposed PostgreSQL Extensions

`bluetape4k-exposed-postgresql` adds pgvector, PostGIS, and `tstzrange` types and operators to the ordinary Exposed JDBC PostgreSQL path. Connection and transaction ownership remain with `bluetape4k-exposed-jdbc` and the application.

## Problem {#problem}

PostgreSQL-specific values otherwise leak as raw SQL or driver objects. This module maps vectors, geometry, and timestamp ranges to typed Exposed columns and expressions.

## When to use it {#when-to-use}

Use it only when a schema uses pgvector, PostGIS, or `tstzrange`. Plain PostgreSQL CRUD needs the JDBC module and driver, not this extension module.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    // add pgvector-java or postgis-jdbc only for the feature used
}
```

## Core concepts {#concepts}

`vector(dimension)` supports cosine, L2, and inner-product expressions; PostGIS columns map JTS point/polygon/geometry with spatial predicates; `tstzRange` maps bounded timestamp ranges and overlap/contains/adjacency operators.

## Quick start {#quick-start}

```kotlin
object Embeddings : Table() { val value = vector("value", 768) }
transaction(db) {
    connection.registerVectorType()
    Embeddings.select(Embeddings.value.cosineDistance(queryVector.literal())).limit(20)
}
```

## API by task {#api-by-task}

| Task | API |
| --- | --- |
| Vector column/search | `vector`, `cosineDistance`, `l2Distance`, `innerProduct` |
| Spatial columns | `geoPoint`, `geoPolygon`, `geoGeometry` |
| Spatial predicates | `stDWithin`, `stContains`, `stIntersects`, `stArea` |
| Time range | `tstzRange`, `overlaps`, `contains`, `isAdjacentTo` |

## Recommended patterns {#patterns}

Install the matching server extension through migrations, register pgvector on every physical connection, and create workload-specific indexes. Page ordered results with a stable tie-breaker. Use JDBC batch APIs from the JDBC module; this extension does not redefine batching.

## Integrations {#integrations}

The PostgreSQL driver, pgvector, PostGIS JDBC, and Exposed JDBC/time APIs are compile-only so the application chooses runtime components. Tests use Testcontainers PostgreSQL and cover range behavior plus type conversion; feature-specific server extensions remain explicit prerequisites.

## Configuration {#configuration}

Keep SRID, vector dimension, range bounds, and extension versions in schema contracts. Configure pooling and JDBC transaction isolation in the application.

## Failure modes {#failures}

Missing extensions, mismatched dimensions/SRIDs, unregistered vector types, or absent indexes cause startup, conversion, or performance failures. A generated expression does not create the server extension or index.

## Operations {#operations}

Observe query plans, index use, distance scan size, spatial selectivity, lock time, and batch latency. Analyze representative parameter values rather than assuming an operator always uses an index.

## Testing {#testing}

Use Testcontainers PostgreSQL with the same extensions and migrations as production. Test value round trips, nullable columns, boundary inclusivity, operator SQL, paging order, rollback, and batch failure.

## Workshops and learning path {#workshops}

Start with the [adapter matrix](../guides/database-adapter-matrix.md), then add one extension at a time to a JDBC repository. Keep ordinary transaction rules from the [transaction guide](../guides/transaction-boundaries.md).

## Limitations {#limitations}

The module does not manage connections, install extensions, choose indexes, or bundle optional PostgreSQL/pgvector/PostGIS drivers at runtime. It covers the types and operators present in release 1.11 only.

## Sources {#sources}

- [pgvector extensions](../../../../exposed/postgresql/src/main/kotlin/io/bluetape4k/exposed/postgresql/pgvector/VectorExtensions.kt)
- [PostGIS extensions](../../../../exposed/postgresql/src/main/kotlin/io/bluetape4k/exposed/postgresql/postgis/GeoExtensions.kt)
- [`tstzrange` extensions](../../../../exposed/postgresql/src/main/kotlin/io/bluetape4k/exposed/postgresql/tsrange/TstzRangeExtensions.kt)
- [Vector release test](../../../../exposed/postgresql/src/test/kotlin/io/bluetape4k/exposed/postgresql/pgvector/VectorColumnTypeTest.kt)
