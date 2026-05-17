# exposed-bom

[한국어](./README.ko.md) | English

Maven BOM (Bill of Materials) for the Exposed extension ecosystem. Manages versions of all
`io.github.bluetape4k.exposed:*` modules so consumers can declare dependencies without specifying
individual versions.

## Architecture

```mermaid
graph TB
    Consumer[Consumer Project]
    BOM[exposed-bom<br/>java-platform]

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
      SBJ[spring-boot / exposed-jdbc]
      SBR[spring-boot / exposed-r2dbc]
      SBB[spring-boot / batch-exposed]
    end

    Consumer -->|platform import| BOM
    BOM -.->|version constraints| Core
    BOM -.->|version constraints| Jdbc
    BOM -.->|version constraints| Cache
    BOM -.->|version constraints| SBJ
```

The BOM is a Gradle `java-platform` that publishes only `<dependencyManagement>` constraints — no runtime classes.

## Core Features

- Centralized version management for all `exposed-*` modules
- Single source of truth for JetBrains Exposed extensions (JDBC + R2DBC)
- Aggregated by `bluetape4k-dependencies` for cross-ecosystem version coordination

## Modules Managed

| Group | Modules |
|-------|---------|
| Core | `exposed-core`, `exposed-dao` |
| Drivers | `exposed-jdbc`, `exposed-r2dbc`, `exposed-jdbc-tests`, `exposed-r2dbc-tests` |
| Cache | `exposed-cache`, `exposed-jdbc-{lettuce,redisson,caffeine}`, `exposed-r2dbc-{lettuce,redisson,caffeine}` |
| Serialization | `exposed-fastjson2`, `exposed-jackson2`, `exposed-jackson3` |
| Crypto | `exposed-tink` |
| DB adapters | `exposed-{mysql8,postgresql,clickhouse,bigquery,duckdb,trino,measured,timefold-solver-persistence}` |
| Spring Boot | `exposed-spring-boot-{jdbc,r2dbc}`, `exposed-spring-boot-batch` |
| Utils | `exposed-batch` |

> Note: `examples/*` and `*-demo` modules are excluded from the BOM constraints.

## Usage Examples

### Gradle Kotlin DSL

```kotlin
plugins {
    id("io.spring.dependency-management") version "1.1.x"
}

dependencyManagement {
    imports {
        mavenBom("io.github.bluetape4k.exposed:exposed-bom:<version>")
    }
}

dependencies {
    implementation("io.github.bluetape4k.exposed:exposed-jdbc")
    implementation("io.github.bluetape4k.exposed:exposed-cache")
    implementation("io.github.bluetape4k.exposed:exposed-jdbc-redisson")
}
```

### Plain Gradle

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k.exposed:exposed-bom:<version>"))
    implementation("io.github.bluetape4k.exposed:exposed-jdbc")
}
```

### Maven

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.bluetape4k.exposed</groupId>
            <artifactId>exposed-bom</artifactId>
            <version>${exposed.version}</version>
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
