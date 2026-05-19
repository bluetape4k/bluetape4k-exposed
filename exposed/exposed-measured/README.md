# Module exposed-measured

English | [한국어](./README.ko.md)

A custom ColumnType module for storing and retrieving `bluetape4k-measured` types (`Measure<T>`, `Temperature`,
`TemperatureDelta`) as `DOUBLE` columns in Exposed.

## Supported Columns

- `measure(name, baseUnit)`
- `length(name)`, `mass(name)`, `area(name)`, `volume(name)`
- `angle(name)`, `pressure(name)`, `storage(name)`, `frequency(name)`
- `energy(name)`, `power(name)`
- `temperature(name)`, `temperatureDelta(name)`

## Example

```kotlin
object ProductTable: Table("products") {
    val width = length("width")
    val weight = mass("weight")
    val storage = storage("storage")
    val temp = temperature("temp")
}
```

## Class Diagram

![Class Diagram 1](../../docs/images/readme-diagrams/exposed-exposed-measured-diagram-01.svg)

## Column Conversion Flow

![Column Conversion Flow 2](../../docs/images/readme-diagrams/exposed-exposed-measured-diagram-02.svg)

## Storage / Retrieval Sequence Diagram

```mermaid
sequenceDiagram
        participant App as Application
        participant Col as MeasureColumnType~Length~
        participant DB as Database

    Note over App,DB: Store — converts to base unit (meters) and saves as DOUBLE
    App->>Col: insert { it[width] = 1500.millimeters() }
    Col->>Col: notNullValueToDB(value in Length.meters)
    Note over Col: Measure(1500mm) → 1.5 (in meters)
    Col->>DB: INSERT ... VALUES (1.5)

    Note over App,DB: Retrieve — restores DOUBLE back to Measure type
    App->>DB: SELECT width FROM products WHERE id = 1
    DB-->>Col: 1.5 (Double)
    Col->>Col: fromBaseValue(1.5) → Measure(1.5, meters)
    Col-->>App: Measure(1.5, Length.meters)
    Note over App: 1.5.meters().inMillimeters() == 1500.0
```
