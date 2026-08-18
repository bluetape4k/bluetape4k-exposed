# Module exposed-r2dbc-lettuce

[English](./README.md) | 한국어

Exposed R2DBC와 Lettuce Redis 캐시를 결합한 코루틴 네이티브 Read-through / Write-through / Write-behind 캐시 레포지토리 모듈입니다.
데이터 접근 작업은 `suspendTransaction`과 suspend 기반 `ExposedR2dbcLettuceSuspendedLoadedMap`을 사용하며, JDBC 레포지토리 경로는 제공하지 않습니다.

## 개요

`exposed-r2dbc-lettuce`는 다음을 제공합니다:

- **Read-through 캐시**: `findById` 시 캐시 미스이면 R2DBC `suspendTransaction`으로 DB 자동 로드 후 Redis에 캐싱
- **Write-through / Write-behind**: `save` 시 Redis와 DB를 동시(또는 비동기)로 반영
- **NearCache 지원**: Caffeine 로컬 캐시(front) + Redis(back) 2-tier 캐시 (옵션)
- **코루틴 레포지토리**: `R2dbcLettuceRepository` / `AbstractR2dbcLettuceRepository`
- **MapLoader / MapWriter**: repository loaded-map 연동을 위한 R2DBC 기반 구현체
    - `loadAllKeys()`는 기존 `List` API를 유지하며, 지원되는 표준 scalar ID에는 keyset page를 사용하고 custom ID에는 기존 offset fallback을 사용
    - `loadAllKeysFlow()`는 ambient caller-owned transaction이 없으면 page마다 `suspendTransaction`을 여는 bounded streaming을 제공하고, 활성 transaction에서는 Exposed의 ambient 재사용 규칙을 따르며 downstream cancellation을 재전파
    - 열거는 weakly consistent하며, custom ID offset fallback 경로에서는 page 사이 삭제로 아직 관찰하지 않은 row를 건너뛸 수 있음
    - writer의 `chunkSize`/loader의 `batchSize`는 0보다 커야 함

## 아키텍처

아키텍처 그림은 저장소 표면, 선택적 Caffeine NearCache, Redis loaded map, R2DBC loader/writer를 분리해서 보여줍니다. read-through 로딩, 쓰기 모드 처리, DB 재시도 정책을 어느 컴포넌트가 맡는지 확인할 때 보면 됩니다.

![R2DBC Lettuce Redis cache architecture diagram](../../docs/images/readme-diagrams/exposed-r2dbc-lettuce-diagram-01.png)

시퀀스 그림은 실제 메시지 순서를 따릅니다. NearCache hit, Redis hit, Redis miss 시 R2DBC loader 경로, 설정된 쓰기 모드에 따른 save, delete/invalidate/clear의 캐시 정리 흐름을 구분해서 보여줍니다.

![R2DBC Lettuce cache sequence diagram](../../docs/images/readme-diagrams/exposed-r2dbc-lettuce-sequence-01.png)

## 의존성 추가

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc-lettuce:${version}")
}
```

## 기본 사용법

### 코루틴 레포지토리 구현 (AbstractR2dbcLettuceRepository)

```kotlin
import io.bluetape4k.exposed.r2dbc.lettuce.repository.AbstractR2dbcLettuceRepository
import io.bluetape4k.exposed.r2dbc.lettuce.repository.ExposedR2dbcLettuceCodecs
import io.bluetape4k.redis.lettuce.map.LettuceCacheConfig
import io.lettuce.core.RedisClient

data class UserRecord(val id: Long, val name: String, val email: String): java.io.Serializable

class UserR2dbcLettuceRepository(redisClient: RedisClient):
    AbstractR2dbcLettuceRepository<Long, UserRecord>(
        client = redisClient,
        config = LettuceCacheConfig.READ_WRITE_THROUGH,
        valueCodec = ExposedR2dbcLettuceCodecs.jackson3(UserRecord::class.java),
    ) {
    override val table = UserTable

    override suspend fun ResultRow.toEntity() = UserRecord(
        id = this[UserTable.id].value,
        name = this[UserTable.name],
        email = this[UserTable.email],
    )

    override fun UpdateStatement.updateEntity(entity: UserRecord) {
        this[UserTable.name] = entity.name
        this[UserTable.email] = entity.email
    }

    override fun BatchInsertStatement.insertEntity(entity: UserRecord) {
        this[UserTable.id] = entity.id
        this[UserTable.name] = entity.name
        this[UserTable.email] = entity.email
    }

    override fun extractId(entity: UserRecord) = entity.id
}

// suspend 함수로 사용
suspend fun example(repo: UserR2dbcLettuceRepository) {
    repo.save(1L, UserRecord(1L, "홍길동", "hong@example.com"))
    val user = repo.findById(1L)   // NearCache → Redis → DB 순으로 조회
    repo.delete(1L)                // Redis + DB 동시 삭제
    repo.clearCache()              // Redis 캐시 전체 삭제
}
```

## R2dbcLettuceRepository 주요 메서드

| 메서드                                   | 설명                                           |
|---------------------------------------|----------------------------------------------|
| `suspend findById(id)`                | NearCache → Redis → DB 순으로 조회 (Read-through) |
| `suspend findAll(ids)`                | 다건 조회, 미스 키만 Redis → DB Read-through         |
| `suspend findAll(limit, offset, ...)` | R2DBC DB 조회 후 결과를 Redis에 적재                  |
| `suspend findByIdFromDb(id)`          | 캐시 우회, R2DBC `suspendTransaction` 직접 조회      |
| `suspend findAllFromDb(ids)`          | 캐시 우회, R2DBC 다건 직접 조회                        |
| `suspend countFromDb()`               | R2DBC DB 전체 레코드 수                            |
| `suspend save(id, entity)`            | Redis 저장 + WriteMode에 따라 R2DBC DB 반영         |
| `suspend saveAll(entities)`           | 다건 저장                                        |
| `suspend delete(id)`                  | Redis + R2DBC DB 동시 삭제                       |
| `suspend deleteAll(ids)`              | 다건 삭제                                        |
| `suspend clearCache()`                | NearCache + Redis 키 전체 삭제 (DB 영향 없음)         |

## LettuceCacheConfig — 쓰기 모드

| WriteMode            | 동작                                  |
|----------------------|-------------------------------------|
| `READ_WRITE_THROUGH` | save 시 Redis + R2DBC DB 동시 반영 (기본값) |
| `READ_WRITE_BEHIND`  | save 시 Redis 즉시, R2DBC DB는 비동기 반영   |
| `READ_ONLY`          | Redis에만 저장, DB 쓰기 없음                |

## Redis Codec 안전성

Repository 생성자는 값 직렬화를 위한 `RedisCodec<String, E>`를 명시적으로 요구합니다. 기존 Lettuce
binary loaded-map 기본값은 LZ4/Fory 계열이므로 repository 데이터에는 자동 선택하지 않습니다.
`ExposedR2dbcLettuceCodecs.jackson3(Entity::class.java)` 또는 검토된 codec을 전달하세요. Fory/Kryo
계열 binary codec은 Redis 데이터가 완전히 신뢰되고 외부 writer와 공유되지 않는 경우에만 사용하세요.

## NearCache 설정

`LettuceCacheConfig.nearCacheEnabled = true`로 Caffeine 로컬 캐시(front)를 활성화할 수 있습니다.

```kotlin
val config = LettuceCacheConfig(
    writeMode = WriteMode.WRITE_THROUGH,
    nearCacheEnabled = true,
    nearCacheName = "user-near-cache",
    nearCacheMaxSize = 1000,
    nearCacheTtl = Duration.ofMinutes(5),
)
```

NearCache가 활성화되면 조회 순서: **Caffeine(로컬) → Redis → DB**

## JDBC 버전과의 차이점

| 항목               | exposed-jdbc-lettuce                               | exposed-r2dbc-lettuce            |
|------------------|----------------------------------------------------|----------------------------------|
| DB 드라이버          | JDBC (blocking)                                    | R2DBC (non-blocking)             |
| 트랜잭션             | `transaction {}` / `suspendedTransactionAsync(IO)` | `suspendTransaction {}`          |
| `toEntity`       | 일반 함수 (`fun`)                                      | suspend 함수 (`suspend fun`)       |
| `runBlocking` 사용 | 없음 (`ExposedLettuceSuspendedLoadedMap`)            | 없음 (`ExposedR2dbcLettuceSuspendedLoadedMap`) |
| 동기 레포지토리         | `JdbcLettuceRepository` 제공                         | 미제공 (suspend only)               |

## 주요 파일/클래스 목록

| 파일                                             | 설명                                                                  |
|------------------------------------------------|---------------------------------------------------------------------|
| `repository/R2dbcLettuceRepository.kt`         | suspend 캐시 레포지토리 인터페이스                                              |
| `repository/AbstractR2dbcLettuceRepository.kt` | 추상 구현체 (ExposedR2dbcLettuceSuspendedLoadedMap + NearCache)          |
| `repository/ExposedR2dbcLettuceCodecs.kt`      | repository Redis 값 codec 명시 헬퍼                                      |
| `map/ExposedR2dbcLettuceSuspendedLoadedMap.kt` | 호출자가 전달한 값 codec을 쓰는 코루틴 loaded map                        |
| `map/R2dbcEntityMapLoader.kt`                  | R2DBC `suspendTransaction` 기반 MapLoader 추상 클래스                      |
| `map/R2dbcEntityMapWriter.kt`                  | R2DBC `suspendTransaction` + Resilience4j Retry 기반 MapWriter 추상 클래스 |
| `map/R2dbcExposedEntityMapLoader.kt`           | Exposed R2DBC DSL 기반 MapLoader 구현체                                  |
| `map/R2dbcExposedEntityMapWriter.kt`           | Exposed R2DBC DSL 기반 MapWriter 구현체 (upsert 전략)                      |

## 테스트

```bash
./gradlew :bluetape4k-exposed-r2dbc-lettuce:test
```

## 참고

- [exposed-r2dbc](../exposed-r2dbc)
- [exposed-jdbc-lettuce](../exposed-jdbc-lettuce)
- [bluetape4k-lettuce](../../infra/lettuce)
- [Lettuce Redis Client](https://lettuce.io)
