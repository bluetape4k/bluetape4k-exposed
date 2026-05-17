# exposed-bom

한국어 | [English](./README.md)

Exposed extension 생태계용 Maven BOM (Bill of Materials). 모든 `io.github.bluetape4k.exposed:*`
모듈의 버전을 중앙 관리한다.

## Architecture

```mermaid
graph TB
    Consumer[소비 프로젝트]
    BOM[exposed-bom<br/>java-platform]

    subgraph "exposed-core"
      Core[exposed-core]
      Dao[exposed-dao]
    end

    subgraph "드라이버"
      Jdbc[exposed-jdbc]
      R2dbc[exposed-r2dbc]
      Tests1[exposed-jdbc-tests]
      Tests2[exposed-r2dbc-tests]
    end

    subgraph "캐시"
      Cache[exposed-cache]
      JdbcLet[exposed-jdbc-lettuce]
      JdbcRedi[exposed-jdbc-redisson]
      JdbcCaf[exposed-jdbc-caffeine]
      R2dbcLet[exposed-r2dbc-lettuce]
      R2dbcRedi[exposed-r2dbc-redisson]
      R2dbcCaf[exposed-r2dbc-caffeine]
    end

    subgraph "직렬화 / 암호화"
      Fast[exposed-fastjson2]
      Jack2[exposed-jackson2]
      Jack3[exposed-jackson3]
      Tink[exposed-tink]
    end

    subgraph "DB 어댑터"
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
    BOM -.->|버전 constraint| Core
    BOM -.->|버전 constraint| Jdbc
    BOM -.->|버전 constraint| Cache
    BOM -.->|버전 constraint| SBJ
```

BOM은 Gradle `java-platform` 으로 `<dependencyManagement>` constraint 만 게시한다.

## 핵심 기능

- 모든 `exposed-*` 모듈 버전 중앙 관리
- JetBrains Exposed 확장 (JDBC + R2DBC) 버전 일관성 보장
- `bluetape4k-dependencies` 가 상위에서 통합

## 관리 모듈

| 그룹 | 모듈 |
|------|------|
| 코어 | `exposed-core`, `exposed-dao` |
| 드라이버 | `exposed-jdbc`, `exposed-r2dbc`, `exposed-jdbc-tests`, `exposed-r2dbc-tests` |
| 캐시 | `exposed-cache`, `exposed-jdbc-{lettuce,redisson,caffeine}`, `exposed-r2dbc-{lettuce,redisson,caffeine}` |
| 직렬화 | `exposed-fastjson2`, `exposed-jackson2`, `exposed-jackson3` |
| 암호화 | `exposed-tink` |
| DB 어댑터 | `exposed-{mysql8,postgresql,clickhouse,bigquery,duckdb,trino,measured,timefold-solver-persistence}` |
| Spring Boot | `exposed-spring-boot-{jdbc,r2dbc}`, `exposed-spring-boot-batch` |
| 유틸 | `exposed-batch` |

> 참고: `examples/*` 및 `*-demo` 모듈은 BOM constraint 에서 제외된다.

## 사용 예제

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

### 순수 Gradle

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

## 설정 옵션

BOM 자체는 별도 설정이 없다. SNAPSHOT 사용 시 Sonatype Central Snapshots 저장소 추가:

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

이 BOM은 `bluetape4k-dependencies` 에서 자동 통합된다. 여러 bluetape4k 생태계를 함께 사용한다면
`io.github.bluetape4k:bluetape4k-dependencies` import 권장.
