---
manualId: "bluetape4k-exposed-mysql8"
id: "bluetape4k-exposed-mysql8"
title: "Exposed MySQL 8 GIS Extensions"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-mysql8"
sourceDir: "exposed/mysql8"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-mysql8
---

# Exposed MySQL 8 GIS Extensions

`bluetape4k-exposed-mysql8` adds JTS-backed MySQL 8 geometry columns, WKB conversion, constructors, spatial predicates, measurements, and transformations to Exposed JDBC.

## Problem {#problem}

MySQL's internal geometry bytes and SRID-aware SQL do not map cleanly to generic Exposed columns. This module keeps conversion and spatial expressions type-directed.

## When to use it {#when-to-use}

Use it for MySQL 8 schemas that store or query geometry. Plain MySQL CRUD should depend on the JDBC module and Connector/J without this GIS layer.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-mysql8")
    runtimeOnly("com.mysql:mysql-connector-j")
}
```

## Core concepts {#concepts}

Geometry columns carry an SRID and map to JTS types. `MySqlWkbUtils` translates MySQL internal geometry, while expressions cover contains/within/intersects, distance, area, length, buffer, union, difference, intersection, centroid, and envelope.

## Quick start {#quick-start}

```kotlin
object Places : Table() {
    val location = geoPoint("location", srid = 4326)
    val reference = geoPoint("reference", srid = 4326)
}
transaction(db) {
    Places.selectAll().where { Places.location.stDWithin(Places.reference, 1_000.0) }
}
```

## API by task {#api-by-task}

| Task | API |
| --- | --- |
| Columns | `geoPoint`, `geoPolygon`, `geoLineString`, multi/geometry helpers |
| Relations | `stContains`, `stWithin`, `stIntersects`, `stTouches` |
| Measurements | `stDistance`, `stDistanceSphere`, `stLength`, `stArea` |
| Transformations | `stBuffer`, `stUnion`, `stDifference`, `stIntersection` |

## Recommended patterns {#patterns}

Choose and document one SRID, validate incoming geometry, and add spatial indexes through migrations. Page ordinary query results with a stable order; use JDBC batch operations from the JDBC module for bulk writes.

## Integrations {#integrations}

JTS is an API dependency; Connector/J is compile-only and must be present at runtime. Release tests run MySQL Testcontainers and cover WKB conversion, geometry types, spatial relations, measurements, and a write-path spike.

## Configuration {#configuration}

Align server character/time-zone settings separately from geometry SRID. The application owns Connector/J properties, pool size, transaction isolation, and schema migration.

## Failure modes {#failures}

Mismatched SRIDs, invalid WKB, unsupported geometry subtype, or missing spatial indexes can fail conversion or turn a bounded query into a scan. Results returned as generic expressions may require explicit conversion.

## Operations {#operations}

Observe spatial index use, rows examined, temporary tables, lock waits, batch latency, and connection pool saturation. Record SRID and unit assumptions beside every distance threshold.

## Testing {#testing}

Use MySQL 8 Testcontainers. Assert round trips for each geometry family, null handling, WKB byte order, spatial relation semantics, transaction rollback, paging order, and batch failure.

## Workshops and learning path {#workshops}

Read the [database adapter matrix](../guides/database-adapter-matrix.md), implement one point query, then add complex geometry and indexes. Follow the JDBC [transaction boundary guide](../guides/transaction-boundaries.md).

## Limitations {#limitations}

The module does not bundle Connector/J at runtime, manage schema/indexes, or make spatial functions portable to PostgreSQL/PostGIS. It is a MySQL 8 GIS extension, not a general MySQL adapter.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### MySQL8 GIS column DSL coverage

[![MySQL8 GIS column DSL coverage](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-mysql8-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-mysql8-diagram-01.svg)

_Release README: [`exposed/mysql8/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/mysql8/README.md)_

### MySQL8 GIS serialization flow

[![MySQL8 GIS serialization flow](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-mysql8-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-mysql8-diagram-02.svg)

_Release README: [`exposed/mysql8/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/mysql8/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Geometry column types](../../../../exposed/mysql8/src/main/kotlin/io/bluetape4k/exposed/mysql8/gis/GeoColumnTypes.kt)
- [Spatial functions](../../../../exposed/mysql8/src/main/kotlin/io/bluetape4k/exposed/mysql8/gis/SpatialFunctions.kt)
- [`MySqlWkbUtils`](../../../../exposed/mysql8/src/main/kotlin/io/bluetape4k/exposed/mysql8/gis/MySqlWkbUtils.kt)
- [Geometry release test](../../../../exposed/mysql8/src/test/kotlin/io/bluetape4k/exposed/mysql8/gis/GeometryColumnTypeTest.kt)
