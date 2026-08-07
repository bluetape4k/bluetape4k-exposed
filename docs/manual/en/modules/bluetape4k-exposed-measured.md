---
manualId: "bluetape4k-exposed-measured"
id: "bluetape4k-exposed-measured"
title: "Exposed Measurement Support"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-measured"
sourceDir: "exposed/measured"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-measured
---

# Exposed Measurement Support

> Library module

## Problem {#problem}

This module stores typed physical measurements in Exposed columns while presenting `Measure`, `Temperature`, and `TemperatureDelta` values to Kotlin code. The database stores only a base-unit `DOUBLE`; it does not store unit metadata.

## When to use it {#when-to-use}

Use it when domain code benefits from compile-time measurement dimensions and the database can use one documented canonical unit per column. Do not use it when decimal-exact money-like values, arbitrary precision, or per-row display units must be preserved.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-measured")
}
```

## Core concepts {#concepts}

- `MeasureColumnType` converts a value to the declared base unit and stores a `Double`.
- Convenience DSL fixes canonical units: metres, kilograms, seconds, square/cubic metres, radians, pascals, bytes, hertz, joules, and watts.
- `temperature` stores Kelvin; `temperatureDelta` stores Kelvin delta.
- Unit objects and the originally entered unit are not persisted.

## Quick start {#quick-start}

```kotlin
object Sensors : LongIdTable("sensors") {
    val cableLength = length("cable_length_m")
    val ambient = temperature("ambient_kelvin")
}

transaction {
    Sensors.insert {
        it[cableLength] = 150.centimeters()
        it[ambient] = 25.celsius()
    }
}
```

The stored values are approximately `1.5` metres and `298.15` Kelvin.

## API by task {#api-by-task}

| Task | Stable 1.11 API |
| --- | --- |
| Custom dimension/base unit | `measure(name, baseUnit)` |
| Common dimensions | `length`, `mass`, `time`, `area`, `volume`, `angle`, `pressure` |
| Data/energy | `storage`, `binarySize`, `frequency`, `energy`, `power` |
| Temperature | `temperature`, `temperatureDelta` |

## Recommended patterns {#patterns}

Put the canonical unit in the column name or schema documentation. Convert only at input/output boundaries. Treat a base-unit change as a data migration: readers cannot infer whether an existing `1000.0` means metres, millimetres, joules, or another unit.

## Integrations {#integrations}

The module uses `bluetape4k-measured` domain types and Exposed core column types. JDBC and R2DBC drivers see ordinary `DOUBLE` values, so database functions and indexes operate on canonical numeric values.

## Configuration {#configuration}

There is no runtime unit registry to configure. The schema declaration selects the base unit. Define application validation for finite values, physical bounds, rounding tolerance, and whether `NaN` or infinity is allowed.

## Failure modes {#failures}

- Reading a non-`Number` driver value raises an error naming the column type.
- Changing the base unit without rewriting rows silently changes meaning.
- `DOUBLE` conversion introduces binary floating-point rounding.
- `NaN` and infinity can escape domain assumptions unless the application rejects them.
- Confusing absolute temperature with temperature delta produces semantically wrong values.

## Operations {#operations}

Document units in migrations, dashboards, exports, and alerts. Monitor impossible ranges and non-finite values before they spread. Choose tolerances from the domain rather than comparing converted doubles for exact equality.

## Testing {#testing}

Test conversions from several input units, negative and boundary values, round-trip tolerance, and the production dialect. Assert stored raw values when a unit contract is critical. Add migration tests whenever the canonical unit or precision policy changes.

```bash
./gradlew :bluetape4k-exposed-measured:test
```

## Workshops and learning path {#workshops}

Start with the [serialization and encryption guide](../guides/serialization-and-encryption.md) for the broader typed-column boundary. Then read the table DSL tests, which cover every convenience unit, and the column-type tests for error and precision behavior.

## Limitations {#limitations}

The module stores no unit metadata, provenance, uncertainty, significant figures, or arbitrary-precision decimal. It does not validate domain ranges. Changing a canonical unit or numeric representation requires an explicit schema/data migration.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### Measured column DSL coverage

[![Measured column DSL coverage](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-measured-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-measured-diagram-01.svg)

_Release README: [`exposed/measured/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/measured/README.md)_

### Measured column conversion flow

[![Measured column conversion flow](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-measured-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-measured-diagram-02.svg)

_Release README: [`exposed/measured/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/measured/README.md)_

### Measured column round trip

[![Measured column round trip](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-measured-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-measured-sequence-01.svg)

_Release README: [`exposed/measured/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/measured/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Gradle build file](../../../../exposed/measured/build.gradle.kts)
- [Measured column types](../../../../exposed/measured/src/main/kotlin/io/bluetape4k/exposed/core/measured/MeasuredColumnTypes.kt)
- [DSL coverage](../../../../exposed/measured/src/test/kotlin/io/bluetape4k/exposed/core/measured/TableDslMeasuredColumnsTest.kt)
- [Round-trip tests](../../../../exposed/measured/src/test/kotlin/io/bluetape4k/exposed/core/measured/MeasuredColumnTypesTest.kt)
