# bluetape4k-exposed-bom

[한국어](./README.ko.md) | English

Maven BOM (Bill of Materials) for the **bluetape4k-exposed** ecosystem. Manages versions of all
`io.github.bluetape4k.exposed:*` modules so consumers can declare dependencies without specifying
individual versions.

## Architecture

```mermaid
graph TB
    Consumer[Consumer Project]
    BOM[bluetape4k-exposed-bom<br/>java-platform]

    subgraph "exposed-core"
      Core[exposed-core]
      Dao[exposed-dao]
    end

    subgraph "Drivers"
      Jdbc[exposed-jdbc]
      R2dbc[exposed-r2dbc]
      Tests1[exposed-jdbc-tests]
      Tests2[exposed-r2dbc-tests]
    end

    subgraph "Cache"
      Cache[exposed-cache]
      JdbcLet[exposed-jdbc-lettuce]
      JdbcRedi[exposed-jdbc-redisson]
      JdbcCaf[exposed-jdbc-caffeine]
      R2dbcLet[exposed-r2dbc-lettuce]
      R2dbcRedi[exposed-r2dbc-redisson]
      R2dbcCaf[exposed-r2dbc-caffeine]
    end

    subgraph "Serialization / Crypto"
      Fast[exposed-fastjson2]
      Jack2[exposed-jackson2]
      Jack3[exposed-jackson3]
      Tink[exposed-tink]
    end

    subgraph "Database adapters"
      Mysql[exposed-mysql8]
      Pg[exposed-postgresql]
      Click[exposed-clickhouse]
      Bq[exposed-bigquery]
      Duck[exposed-duckdb]
      Trino[exposed-trino]
    end

    subgraph "Spring Boot"
      SB3J[spring-boot3 / exposed-jdbc]
      SB3R[spring-boot3 / exposed-r2dbc]
      SB4J[spring-boot4 / exposed-jdbc]
      SB4R[spring-boot4 / exposed-r2dbc]
    end

    Consumer -->|platform import| BOM
    BOM -.->|version constraints| Core
    BOM -.->|version constraints| Jdbc
    BOM -.->|version constraints| Cache
    BOM -.->|version constraints| SB3J
```

The BOM is a Gradle `java-platform` that publishes only `<dependencyManagement>` constraints — no runtime classes.

## Core Features

- Centralized version management for all `bluetape4k-exposed` modules
- Single source of truth for JetBrains Exposed extensions (JDBC + R2DBC)
- Aggregated by `bluetape4k-dependencies` for cross-ecosystem version coordination

## Modules Managed

| Group | Modules |
|-------|---------|
| Core | `bluetape4k-exposed-core`, `bluetape4k-exposed-dao` |
| Drivers | `bluetape4k-exposed-jdbc`, `bluetape4k-exposed-r2dbc`, `bluetape4k-exposed-jdbc-tests`, `bluetape4k-exposed-r2dbc-tests` |
| Cache | `bluetape4k-exposed-cache`, `bluetape4k-exposed-jdbc-{lettuce,redisson,caffeine}`, `bluetape4k-exposed-r2dbc-{lettuce,redisson,caffeine}` |
| Serialization | `bluetape4k-exposed-fastjson2`, `bluetape4k-exposed-jackson2`, `bluetape4k-exposed-jackson3` |
| Crypto | `bluetape4k-exposed-tink` |
| DB adapters | `bluetape4k-exposed-{mysql8,postgresql,clickhouse,bigquery,duckdb,trino,measured,timefold-solver-persistence}` |
| Spring Boot | `bluetape4k-spring-boot3-exposed-{jdbc,r2dbc}`, `bluetape4k-spring-boot4-exposed-{jdbc,r2dbc}`, `bluetape4k-spring-boot{3,4}-batch-exposed` |
| Utils | `bluetape4k-utils-batch` |

> Note: `examples/*` and `*-demo` modules are excluded from the BOM constraints.

## Usage Examples

### Gradle Kotlin DSL

```kotlin
plugins {
    id("io.spring.dependency-management") version "1.1.x"
}

dependencyManagement {
    imports {
        mavenBom("io.github.bluetape4k.exposed:bluetape4k-exposed-bom:<version>")
    }
}

dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc")
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-cache")
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-redisson")
}
```

### Plain Gradle

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k.exposed:bluetape4k-exposed-bom:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc")
}
```

### Maven

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.bluetape4k.exposed</groupId>
            <artifactId>bluetape4k-exposed-bom</artifactId>
            <version>${bluetape4k-exposed.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## Configuration Options

The BOM itself has no configuration. For SNAPSHOT builds, add the Sonatype Central Snapshots repository:

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "central-snapshots"
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
    }
}
```

## Dependency

This BOM is automatically aggregated by `bluetape4k-dependencies`. Prefer importing
`io.github.bluetape4k:bluetape4k-dependencies` when consuming multiple bluetape4k ecosystems.
