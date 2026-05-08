# CLAUDE.md — bluetape4k-exposed

JetBrains Exposed ORM Kotlin 확장 라이브러리. JDBC/R2DBC 기반 Repository 패턴, 캐시 통합(Caffeine, Lettuce, Redisson), JSON 직렬화(Jackson2/3, Fastjson2), 암호화(Tink), Spring Boot 3.x/4.x 자동 설정.

- **Group**: `io.bluetape4k.exposed` · **Publishing**: Maven Central (NMCP)

## Repository Layout

```
exposed/
├── exposed-core/           # Column 타입 확장, 공통 DSL 헬퍼
├── exposed-dao/            # DAO Entity 확장, 이벤트 후킹
├── exposed-jdbc/           # JDBC Repository 패턴, 트랜잭션 DSL
├── exposed-r2dbc/          # R2DBC 코루틴 지원, suspend Repository
├── exposed-jdbc-tests/     # JDBC 통합 테스트 픽스처
├── exposed-r2dbc-tests/    # R2DBC 통합 테스트 픽스처
├── exposed-cache/          # 캐시 추상화 인터페이스
├── exposed-jdbc-caffeine/  # JDBC + Caffeine 캐시 백엔드
├── exposed-jdbc-lettuce/   # JDBC + Lettuce Redis 캐시
├── exposed-jdbc-redisson/  # JDBC + Redisson Redis 캐시
├── exposed-r2dbc-caffeine/ # R2DBC + Caffeine 캐시 백엔드
├── exposed-r2dbc-lettuce/  # R2DBC + Lettuce Redis 캐시
├── exposed-r2dbc-redisson/ # R2DBC + Redisson Redis 캐시
├── exposed-jackson2/       # Jackson 2.x Column 직렬화
├── exposed-jackson3/       # Jackson 3.x Column 직렬화
├── exposed-fastjson2/      # Fastjson2 Column 직렬화
├── exposed-tink/           # Google Tink 암호화 Column
├── exposed-measured/       # Micrometer 메트릭 통합
├── exposed-mysql8/         # MySQL 8 전용 확장
├── exposed-postgresql/     # PostgreSQL 전용 확장
├── exposed-bigquery/       # BigQuery 지원 (SaaS 계정 필요)
├── exposed-clickhouse/     # ClickHouse 지원 (SaaS 계정 필요)
├── exposed-trino/          # Trino 지원 (SaaS 계정 필요)
├── exposed-duckdb/         # DuckDB 지원
└── exposed-timefold-solver-persistence/ # Timefold Solver 영속성
utils/
spring-boot3/
├── exposed-jdbc/           # Spring Boot 3.x JDBC 자동 설정
├── exposed-jdbc-demo/      # JDBC 데모 앱
├── exposed-r2dbc/          # Spring Boot 3.x R2DBC 자동 설정
├── exposed-r2dbc-demo/     # R2DBC 데모 앱
└── batch-exposed/          # Spring Batch + Exposed 통합
spring-boot4/               # (spring-boot3 와 동일 구조, Boot 4.x 대상)
buildSrc/                   # Versions, plugins, dependency catalog
```

## Module Naming (settings.gradle.kts)

`exposed/` 디렉토리는 `withBaseDir=false`로 include되어 아래와 같이 매핑:

| 디렉토리 | Gradle 모듈명 |
|---------|-------------|
| `exposed/exposed-core` | `:bluetape4k-exposed-core` |
| `exposed/exposed-jdbc` | `:bluetape4k-exposed-jdbc` |
| `exposed/exposed-r2dbc` | `:bluetape4k-exposed-r2dbc` |
| `spring-boot3/exposed-jdbc` | `:bluetape4k-spring-boot3-exposed-jdbc` |
| `spring-boot4/exposed-r2dbc` | `:bluetape4k-spring-boot4-exposed-r2dbc` |
| `utils/batch` | `:bluetape4k-batch` |

## Build Commands

```bash
./gradlew clean build
./gradlew build -x test -x koverVerify --parallel
./gradlew :bluetape4k-exposed-core:build
./gradlew :bluetape4k-exposed-jdbc:test
./gradlew :bluetape4k-exposed-r2dbc:test
./gradlew :bluetape4k-exposed-jdbc-lettuce:test
./gradlew test --tests "io.bluetape4k.exposed.jdbc.ExposedJdbcRepositoryTest"
./gradlew :bluetape4k-spring-boot3-exposed-jdbc:test
./gradlew detekt
./gradlew publishAggregationToCentralSnapshots          # SNAPSHOT 배포
./gradlew publishAggregationToCentralPortal             # RELEASE 배포
```

## Key Design Patterns

### JDBC Repository

```kotlin
abstract class JdbcExposedRepository<T : LongIdTable>(val table: T) {
    fun findById(id: Long): ResultRow? = transaction {
        table.selectAll().where { table.id eq id }.singleOrNull()
    }
    fun save(entity: T): Unit = transaction { /* upsert */ }
}
```

### R2DBC Coroutine Repository

R2DBC 모듈은 `suspendTransaction {}` DSL로 코루틴 친화적 트랜잭션 지원:

```kotlin
suspend fun findById(id: Long): ResultRow? = suspendTransaction {
    table.selectAll().where { table.id eq id }.singleOrNull()
}
```

### Cache-backed Repositories

캐시 통합 모듈은 데코레이터 패턴 사용:

```kotlin
val repo = CaffeineBackedJdbcRepository(
    delegate = MyJdbcRepository(),
    cache = Caffeine.newBuilder().expireAfterWrite(5, MINUTES).build()
)
```

Redis 기반 캐시(Lettuce, Redisson)는 분산 캐시로 동일 인터페이스 제공.

### Column 직렬화/암호화

```kotlin
// Jackson JSON column
object UserTable : Table() {
    val profile = json<UserProfile>("profile", jacksonMapper)
}

// Tink 암호화 column
object SecretTable : Table() {
    val sensitiveData = encrypted("data", tinkAead)
}
```

### Spring Boot Auto-configuration

`spring-boot3/` 및 `spring-boot4/` 모듈은 `@EnableExposedJdbc` / `@EnableExposedR2dbc` 어노테이션과 조건부 자동 설정 제공.

## Test Environment Variables

| 변수 | 값 | 설명 |
|------|-----|------|
| `EXPOSED_TEST_DB` | `H2` / `POSTGRESQL` / `MYSQL_V8` | 테스트 DB 선택 |
| `TESTCONTAINERS_RYUK_DISABLED` | `true` | Testcontainers Ryuk 비활성화 (CI) |
| `DOCKER_HOST` | `unix:///var/run/docker.sock` | Docker 소켓 (CI) |

## Publishing

```properties
# gradle.properties
projectGroup=io.github.bluetape4k.exposed
baseVersion=1.8.0
snapshotVersion=-SNAPSHOT
```

- Snapshot: `./gradlew publishAggregationToCentralSnapshots`
- Release: `./gradlew publishAggregationToCentralPortal`
- Release 시 `snapshotVersion=` 를 빈 값으로 설정 후 태그 푸시

## CI/CD

- **CI** (`.github/workflows/ci.yml`): PR/push — Docker 불필요 모듈만 빠르게 테스트
- **Nightly** (`.github/workflows/nightly.yml`): PostgreSQL, MySQL, Redis Testcontainers 포함 전체 테스트
- **Publish Snapshot**: Nightly 성공 후 자동 또는 수동 dispatch
- **Publish Release**: 태그 푸시 또는 수동 dispatch
