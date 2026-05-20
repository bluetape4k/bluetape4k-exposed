# exposed-bom

[한국어](./README.ko.md) | English

Maven BOM (Bill of Materials) for the Exposed extension ecosystem. Manages versions of all
`io.github.bluetape4k.exposed:*` modules so consumers can declare dependencies without specifying
individual versions.

## Architecture

![exposed bom Architecture diagram](../../docs/images/readme-diagrams/exposed-bluetape4k-exposed-bom-diagram-01.png)

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
