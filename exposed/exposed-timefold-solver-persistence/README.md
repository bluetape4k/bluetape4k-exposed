# Module exposed-timefold-solver-persistence

English | [한국어](./README.ko.md)

A Kotlin library for persisting and loading [Timefold Solver](https://github.com/timefold/timefold-solver) Score data to and from relational databases using [Exposed](https://github.com/JetBrains/Exposed).

## Overview

This module provides seamless integration between Timefold Solver's scoring system and JetBrains Exposed ORM, enabling transparent persistence and retrieval of various Score types in relational databases.

### Key Features

- **All Timefold 2 Score types supported**: 8 score types including SimpleScore, HardSoftScore, and BendableScore
- **Exposed integration**: Natural integration using Exposed's `ColumnType` and `ColumnTransformer`
- **Type safety**: Score type validation at compile time
- **Database independence**: Supports H2, MySQL, MariaDB, PostgreSQL, and more

## Supported Score Types

| Score Type                        | Description                         | DB Storage Format             |
|---------------------------------|-------------------------------------|-------------------------------|
| `SimpleScore`                   | Single score value                  | BigInt                        |
| `SimpleBigDecimalScore`         | Single score as BigDecimal          | VarChar                       |
| `HardSoftScore`                 | Two-level Hard/Soft score           | VarChar (e.g., "100/-50")     |
| `HardSoftBigDecimalScore`       | Hard/Soft score as BigDecimal       | VarChar                       |
| `HardMediumSoftScore`           | Three-level Hard/Medium/Soft score  | VarChar (e.g., "100/50/-30")  |
| `HardMediumSoftBigDecimalScore` | Three-level score as BigDecimal     | VarChar                       |
| `BendableScore`                 | Flexible Hard/Soft level score      | VarChar                       |
| `BendableBigDecimalScore`       | Bendable score as BigDecimal        | VarChar                       |

## Class Diagram

![exposed timefold solver persistence Class Structure diagram](../../docs/images/readme-diagrams/exposed-exposed-timefold-solver-persistence-diagram-01.png)

## Architecture Diagram

![Architecture Diagram diagram](../../docs/images/readme-diagrams/exposed-exposed-timefold-solver-persistence-diagram-02.png)

## Installation

### Gradle

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-timefold-solver-persistence:${bluetape4kVersion}")
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.bluetape4k</groupId>
    <artifactId>exposed-timefold-solver-persistence</artifactId>
    <version>${bluetape4kVersion}</version>
</dependency>
```

## Usage

### 1. Define Tables

```kotlin
import io.bluetape4k.timefold.solver.exposed.api.score.buildin.*
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

// Table using HardSoftScore
object PlanningSolutions : IntIdTable("planning_solution") {
    val name = varchar("name", 255)
    val score = hardSoftScore("score")
    val createdAt = datetime("created_at")
}

// Table using BendableScore
object BendablePlanningSolutions : IntIdTable("bendable_solution") {
    val name = varchar("name", 255)
    val score = bendableScore("score", length = 500)
    val createdAt = datetime("created_at")
}
```

### 2. Define Entity Classes

```kotlin
import ai.timefold.solver.core.api.score.HardSoftScore
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

class PlanningSolution(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PlanningSolution>(PlanningSolutions)
    
    var name by PlanningSolutions.name
    var score by PlanningSolutions.score
    var createdAt by PlanningSolutions.createdAt
}
```

### 3. Insert Data

```kotlin
import ai.timefold.solver.core.api.score.HardSoftScore

transaction {
    // DSL style
    PlanningSolutions.insert {
        it[name] = "Vehicle Routing Solution"
        it[score] = HardSoftScore.of(100, -50)
        it[createdAt] = DateTime.now()
    }
    
    // DAO style
    PlanningSolution.new {
        name = "Employee Scheduling Solution"
        score = HardSoftScore.of(200, -20)
        createdAt = DateTime.now()
    }
}
```

### 4. Query Data

```kotlin
transaction {
    // Single record
    val solution = PlanningSolution.findById(1)
    println("Score: ${solution?.score}")  // Output: Score: 100hard/-50soft
    
    // Conditional query
    val highScoreSolutions = PlanningSolution
        .find { PlanningSolutions.score greater HardSoftScore.of(50, 0) }
        .toList()
}
```

### 5. SimpleScore Example

```kotlin
import io.bluetape4k.timefold.solver.exposed.api.score.buildin.simpleScore
import ai.timefold.solver.core.api.score.SimpleScore

object SimpleScoreTable : IntIdTable() {
    val name = varchar("name", 255)
    val score = simpleScore("score")  // Stored as a BIGINT column
}

transaction {
    SimpleScoreTable.insert {
        it[name] = "Simple Solution"
        it[score] = SimpleScore.of(100)
    }
}
```

### 6. BendableScore Example

```kotlin
import io.bluetape4k.timefold.solver.exposed.api.score.buildin.bendableScore
import ai.timefold.solver.core.api.score.BendableScore

object BendableScoreTable : IntIdTable() {
    val name = varchar("name", 255)
    val score = bendableScore("score")
}

transaction {
    // BendableScore with 2 hard levels and 3 soft levels
    val bendableScore = BendableScore.of(
        longArrayOf(100, 50),      // hard levels
        longArrayOf(-30, -20, -10) // soft levels
    )
    
    BendableScoreTable.insert {
        it[name] = "Bendable Solution"
        it[score] = bendableScore
    }
}
```

## Database Schema

Recommended column types for each Score type:

```sql
-- SimpleScore: BIGINT
CREATE TABLE planning_solution (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(255),
    score BIGINT
);

-- HardSoftScore: VARCHAR(255)
CREATE TABLE planning_solution (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(255),
    score VARCHAR(255)  -- e.g., "100/-50"
);

-- BendableScore: VARCHAR(500) or larger recommended
CREATE TABLE planning_solution (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(255),
    score VARCHAR(500)  -- e.g., "[100/50]hard/[-30/-20/-10]soft"
);
```

## Score Serialization Format

### SimpleScore

```
100
```

### HardSoftScore

```
100hard/-50soft
```

### HardMediumSoftScore

```
100hard/50medium/-30soft
```

### BendableScore

```
[100/50]hard/[-30/-20]soft
```

## Dependencies

### Required

- `timefold-solver-core`: Timefold Solver core library
- `exposed-core`: JetBrains Exposed ORM

### Test

- `exposed-jdbc-tests`: Exposed test support
- Testcontainers (H2, MariaDB, MySQL, PostgreSQL)

## Running Tests

```bash
# Run all tests
./gradlew :exposed-timefold-solver-persistence:test

# Run a specific test class
./gradlew :exposed-timefold-solver-persistence:test --tests "HardSoftScoreTest"
```

## Score Serialization Flow

![Score Serialization Flow diagram](../../docs/images/readme-diagrams/exposed-exposed-timefold-solver-persistence-sequence-01.png)

## References

- [Timefold Solver Official Docs](https://timefold.ai/docs)
- [Exposed Official Docs](https://github.com/JetBrains/Exposed/wiki)

## License

MIT License
