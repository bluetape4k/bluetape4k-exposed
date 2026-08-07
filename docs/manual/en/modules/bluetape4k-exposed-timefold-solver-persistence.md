---
manualId: "bluetape4k-exposed-timefold-solver-persistence"
id: "bluetape4k-exposed-timefold-solver-persistence"
title: "Exposed Timefold Score Persistence"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-timefold-solver-persistence"
sourceDir: "exposed/timefold-solver-persistence"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-timefold-solver-persistence
---

# Exposed Timefold Score Persistence

> Store Timefold 2 score values in Exposed columns without turning this module into a solver repository.

## Problem {#problem}

Timefold score classes are domain values, not JDBC primitives. This module supplies Exposed column factories, column types, and transformers for the eight built-in Timefold 2 score families. It does **not** load planning facts, persist a solution graph, run `SolverManager`, or manage a solving job.

## When to use it {#when-to-use}

Use it when an Exposed table needs a typed score column—for example, to store the last accepted score beside a solution record. Keep the planning entity mapping and solver lifecycle in the application. If all you need is a numeric rank unrelated to Timefold semantics, an ordinary Exposed numeric column is simpler.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-timefold-solver-persistence")
}
```

Consumers only need the central `bluetape4k-dependencies` version. Do not add a separate version to this artifact.

## Core concepts {#concepts}

`SimpleScore` uses a `BIGINT`-compatible Exposed `LongColumnType`. The other seven families use `VARCHAR` and round-trip through Timefold's canonical `toString()` and `parseScore(...)` representation.

| Score family | Column factory | Stored form |
| --- | --- | --- |
| `SimpleScore` | `simpleScore("score")` | `Long` |
| `SimpleBigDecimalScore` | `simpleBigDecimalScore("score")` | `VARCHAR` |
| `HardSoftScore` | `hardSoftScore("score")` | `VARCHAR` |
| `HardSoftBigDecimalScore` | `hardSoftBigDecimalScore("score")` | `VARCHAR` |
| `HardMediumSoftScore` | `hardMediumSoftScore("score")` | `VARCHAR` |
| `HardMediumSoftBigDecimalScore` | `hardMediumSoftBigDecimalScore("score")` | `VARCHAR` |
| `BendableScore` | `bendableScore("score", length)` | `VARCHAR` |
| `BendableBigDecimalScore` | `bendableBigDecimalScore("score", length)` | `VARCHAR` |

The string-backed columns preserve score identity, but database lexical ordering is not Timefold score ordering. Do not implement “best score” queries with a plain `ORDER BY score` on those columns.

## Quick start {#quick-start}

```kotlin
object PlanningResults : LongIdTable("planning_results") {
    val name = varchar("name", 120)
    val score = hardSoftScore("score")
    val bendable = bendableScore("bendable_score", length = 500).nullable()
}

transaction {
    PlanningResults.insert {
        it[name] = "vehicle-routing-42"
        it[score] = HardSoftScore.of(-2, -35)
        it[bendable] = BendableScore.of(
            longArrayOf(-1, 0),
            longArrayOf(-10, -20, -5),
        )
    }
}
```

Choose `VARCHAR` length from real score strings. Bendable scores grow with the number of hard and soft levels, so the default or a copied example value may be too small for your model.

## API by task {#api-by-task}

- Define a column: call the matching `Table.*Score(...)` extension.
- Change text capacity: pass `length` to a string-backed factory.
- Understand conversion: inspect the matching `*ScoreColumnType` and `*ScoreTransformer`.
- Verify a new database: insert and select representative positive, negative, zero, uninitialized, decimal, and bendable values.

The transformers are infrastructure details. Application tables should normally use the column factories rather than constructing transformer classes directly.

## Recommended patterns {#patterns}

- Persist a score together with the solution revision or domain version that produced it.
- Treat the score column as a value snapshot, not as proof that the corresponding solution graph is present or current.
- Compare scores in Kotlin after parsing, or store additional sortable components when database-side ranking is a real requirement.
- Use schema migrations for length changes; truncating a serialized score makes it unparsable.

## Integrations {#integrations}

The module depends on Exposed Core and Timefold Solver Core, so it can be used from either an Exposed DSL or DAO table. It does not select JDBC versus R2DBC, provide a repository, or add Spring/Ktor lifecycle integration. Combine it with the database and framework modules that own those concerns.

## Configuration {#configuration}

There are no Spring properties or runtime service beans. Configuration is the table schema: SQL type, nullability, and string length. Keep the Timefold major version aligned through the central BOM so the serialized form and `parseScore(...)` implementation remain compatible.

## Failure modes {#failures}

- `Data too long` or truncation: enlarge the string-backed column with a migration.
- Parse failure on read: inspect legacy or manually edited values; they must match Timefold's canonical score text.
- Wrong database ordering: lexical `VARCHAR` order is not semantic score order.
- Missing planning data after loading a score: this module stores only the score value.
- Decimal surprises: preserve the canonical BigDecimal score string instead of inventing a lossy numeric mapping.

## Testing and operations {#operations}

The release tests run each score family through real Exposed insert/select round trips. For an application schema, add its largest bendable shape and representative decimal scale. During migrations, count parse failures and oversized values before changing the column. A stored score should be observable together with its solution ID and revision, not as an isolated operational metric.

## Testing {#testing}

```bash
./gradlew :bluetape4k-exposed-timefold-solver-persistence:test
```

The module test suite covers all eight column families. It proves conversion and database round trips; it does not exercise a Timefold solving job or a complete persisted planning model.

## Workshops and learning path {#workshops}

Start with `SimpleScoreTest`, then compare one string-backed test such as `HardSoftScoreTest` and the bendable tests. Next, define a score column in your own table and verify the longest score your model can produce. Continue with the [Exposed workshop](https://github.com/bluetape4k/exposed-workshop) for JDBC table and transaction practice, or the [R2DBC workshop](https://github.com/bluetape4k/exposed-r2dbc-workshop) when the application uses Exposed R2DBC. Timefold's own documentation remains the source for planning domain and solver lifecycle design.

## Limitations {#limitations}

This library is a score-column adapter. It does not persist planning entities, rebuild a `Solution`, coordinate solver jobs, provide optimistic locking, or define a database-portable semantic ordering for string-backed scores.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### Timefold Score column families diagram

[![Timefold Score column families diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-timefold-solver-persistence-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-timefold-solver-persistence-diagram-01.svg)

_Release README: [`exposed/timefold-solver-persistence/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/timefold-solver-persistence/README.md)_

### Score persistence round trip diagram

[![Score persistence round trip diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-timefold-solver-persistence-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-timefold-solver-persistence-flow-02.svg)

_Release README: [`exposed/timefold-solver-persistence/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/timefold-solver-persistence/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Supported score column families](../../../../exposed/timefold-solver-persistence/README.md)
- [`SimpleScore` Long mapping](../../../../exposed/timefold-solver-persistence/src/main/kotlin/io/bluetape4k/timefold/solver/exposed/api/score/buildin/SimpleScore.kt)
- [`HardSoftScore` string mapping](../../../../exposed/timefold-solver-persistence/src/main/kotlin/io/bluetape4k/timefold/solver/exposed/api/score/buildin/HardSoftScore.kt)
- [Round-trip tests](../../../../exposed/timefold-solver-persistence/src/test/kotlin/io/bluetape4k/timefold/solver/exposed/api/score/buildin/SimpleScoreTest.kt)
