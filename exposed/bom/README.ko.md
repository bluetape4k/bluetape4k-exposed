# bluetape4k-exposed-bom

한국어 | [English](./README.md)

Exposed extension 생태계용 Maven BOM(Bill of Materials)입니다. 배포되는
`io.github.bluetape4k.exposed:bluetape4k-exposed-*` artifact 버전을 맞춰 주므로,
사용자는 각 모듈 dependency에 버전을 반복해서 적지 않아도 됩니다.

## 관리 Artifact Map

![Exposed BOM managed artifact map](../../docs/images/readme-diagrams/exposed-bom-diagram-01.png)

BOM은 Gradle `java-platform`으로 `<dependencyManagement>` constraint만 게시합니다. 런타임 class는 포함하지
않고, 소비자가 사용할 `bluetape4k-exposed-*` artifactId의 버전만 맞춰 줍니다.

## 핵심 기능

- 모든 `exposed-*` 모듈 버전 중앙 관리
- JetBrains Exposed 확장 (JDBC + R2DBC) 버전 일관성 보장
- `bluetape4k-dependencies` 가 상위에서 통합

## 관리 ArtifactId

| 그룹 | ArtifactId |
|------|------------|
| Core | `bluetape4k-exposed-core`, `bluetape4k-exposed-dao` |
| Repository runtime | `bluetape4k-exposed-jdbc`, `bluetape4k-exposed-r2dbc`, `bluetape4k-exposed-jdbc-tests`, `bluetape4k-exposed-r2dbc-tests` |
| Cache decorator | `bluetape4k-exposed-cache`, `bluetape4k-exposed-jdbc-caffeine`, `bluetape4k-exposed-jdbc-lettuce`, `bluetape4k-exposed-jdbc-redisson`, `bluetape4k-exposed-r2dbc-caffeine`, `bluetape4k-exposed-r2dbc-lettuce`, `bluetape4k-exposed-r2dbc-redisson` |
| Column codec | `bluetape4k-exposed-jackson2`, `bluetape4k-exposed-jackson3`, `bluetape4k-exposed-fastjson2`, `bluetape4k-exposed-tink`, `bluetape4k-exposed-measured` |
| Dialect/analytics | `bluetape4k-exposed-postgresql`, `bluetape4k-exposed-mysql8`, `bluetape4k-exposed-cockroachdb`, `bluetape4k-exposed-bigquery`, `bluetape4k-exposed-clickhouse`, `bluetape4k-exposed-trino`, `bluetape4k-exposed-starrocks`, `bluetape4k-exposed-duckdb` |
| Persistence integration | `bluetape4k-exposed-timefold-solver-persistence` |
| Ktor | `bluetape4k-exposed-ktor` |
| Spring Boot | `bluetape4k-exposed-spring-boot-jdbc`, `bluetape4k-exposed-spring-boot-r2dbc`, `bluetape4k-exposed-spring-boot-batch`, `bluetape4k-exposed-spring-modulith` |
| Utils | `bluetape4k-exposed-batch` |

> 참고: `examples/*` 및 `*-demo` 모듈은 BOM constraint에서 제외됩니다.

## 사용 예제

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
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor")
}
```

### 순수 Gradle

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
            <version>${exposed.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## 설정 옵션

BOM 자체에는 별도 설정이 없습니다. SNAPSHOT 사용 시 Sonatype Central Snapshots 저장소를 추가합니다:

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "central-snapshots"
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
    }
}
```

## 의존성

이 BOM은 `bluetape4k-dependencies`에서 자동 통합됩니다. 여러 bluetape4k 생태계를 함께 사용한다면
`io.github.bluetape4k:bluetape4k-dependencies` import를 권장합니다.
