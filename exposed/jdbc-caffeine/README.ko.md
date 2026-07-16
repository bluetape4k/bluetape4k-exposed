# exposed-jdbc-caffeine

[English](./README.md) | 한국어

[![Maven Central](https://img.shields.io/maven-central/v/io.github.bluetape4k.exposed/exposed-jdbc-caffeine)](https://central.sonatype.com/artifact/io.github.bluetape4k.exposed/exposed-jdbc-caffeine)

Caffeine 로컬(인프로세스) 캐시를 사용하는 Exposed JDBC 저장소입니다. Redis 의존 없이 `exposed-cache` 인터페이스만 사용합니다.

> **참고**: [exposed-cache — 전체 모듈 생태계 및 인터페이스 계층 구조](../exposed-cache/README.ko.md)

## 아키텍처

Caffeine 인프로세스 캐시가 JDBC Repository 계약을 감싸는 위치와, 동기/Suspend Repository가 Exposed 트랜잭션으로 들어가는 경계를 보여줍니다.

![JDBC Caffeine local cache architecture diagram](../../docs/images/readme-diagrams/exposed-jdbc-caffeine-diagram-01.png)

## 쓰기 전략 흐름

시퀀스 다이어그램은 동기 Repository 기준의 흐름을 보여줍니다. read-through miss, write-through 내구성, write-behind 큐잉, 캐시 전용 eviction을 구분합니다.

![JDBC Caffeine write strategy flow diagram](../../docs/images/readme-diagrams/exposed-jdbc-caffeine-sequence-01.png)

## 주요 기능

- **Read-Through**: 캐시 미스 시 `transaction { selectAll }`로 DB 로드, 결과를 Caffeine에 저장
- **Write-Through**: `put()` 호출 시 Caffeine과 DB를 단일 JDBC 트랜잭션 안에서 동기 반영
- **Write-Behind**: `put()` 호출 시 Caffeine 즉시 갱신, DB 쓰기는 Kotlin `Channel`을 통해 비동기 배치 처리
- **동기 레포지토리**: `AbstractJdbcCaffeineRepository` — 모든 메서드가 블로킹 `transaction {}` 사용
- **Suspend 레포지토리**: `AbstractSuspendedJdbcCaffeineRepository` — 모든 DB 호출이 `suspendedTransactionAsync` 사용
- **Redis 의존 없음**: 순수 인프로세스 Caffeine, 단일 인스턴스 배포에 적합
- **AutoIncrement 안전**: Write-Through/Write-Behind 시 AutoInc 테이블 신규 엔티티의 INSERT 건너뜀 (DB가 ID 할당)
- **안전한 종료**: `close()` 호출 시 Write-Behind 큐를 모두 처리 후 코루틴 스코프 취소

<!-- JDBC-SNAPSHOT-CACHE -->
## 커밋에 맞춰 공개하는 JDBC 스냅샷 캐시 (opt-in)

`JdbcCaffeineSnapshotCache`는 위 Repository 캐시와 별개입니다. 분리된 불변 DTO만 저장하며, 현재
최상위 `JdbcTransaction`일 때 커밋 뒤 준비한 `CacheSnapshot`을 공개합니다. 기존 Repository 캐시의
데이터를 옮기지 않습니다. 롤백하면 아무것도 공개하지 않고, 같은 key를 여러 번 바꾸면 마지막 변경만 반영합니다.
로컬 fence는 더 새로운 로컬 무효화보다 먼저 시작된 fill을 거부합니다.

DB를 읽기 전에 `lookup`을 호출하세요. 그러면 용량 소진도 DB 작업 전에 확인할 수 있습니다. 반환된
`SnapshotCacheMiss`는 값 변환이나 준비 작업이 실패한 경우까지 포함해 한 번만 쓸 수 있습니다. `stageSnapshot`은
현재 최상위 트랜잭션에서 값을 변환하고 중첩 트랜잭션과 savepoint를 쓰는 트랜잭션을 거부합니다. 스냅샷 적재에는
`maxAttempts = 1`이 필요합니다. 애플리케이션 재시도는 `lookup`, 트랜잭션, DB 읽기 전체를 감싸고, 바깥쪽
시도마다 새로 `lookup`해야 합니다. `stageInvalidation`도 시도별로 분리되므로 실패한 Exposed 시도에서는 무효화가
새지 않고, 재시도가 성공한 한 번만 공개됩니다.

트랜잭션 후처리는 캐시만 다루며 Repository `put`이나 DB writer를 호출하지 않습니다. 앞서 등록된
`StatementInterceptor` 후처리가 예외를 던지면 캐시 후처리가 실행되지 않아 이전 캐시 값이 남을 수 있습니다.
용량이 제한된 `SnapshotCacheFailureBuffer`를 관찰하고, 커밋 뒤 실패를 복구할 outbox나 repair path는
애플리케이션이 따로 운영해야 합니다. commit-safe가 DB/캐시 원자성이나 장애 후 내구성을 보장하지는 않습니다.

### Canonical JDBC 예제

아래 코드는 English README의 블록과 byte-for-byte로 같으며 source-usage fixture로 실제 컴파일합니다.

<!-- README-CANONICAL-JDBC-BEGIN -->
```kotlin
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshot
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshotMapper
import io.bluetape4k.exposed.cache.snapshot.CaffeineSnapshotCacheConfig
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheConfig
import io.bluetape4k.exposed.jdbc.caffeine.snapshot.JdbcCaffeineSnapshotCache
import io.bluetape4k.exposed.jdbc.caffeine.snapshot.jdbcCaffeineSnapshotCache
import io.bluetape4k.exposed.jdbc.caffeine.snapshot.stageInvalidation
import io.bluetape4k.exposed.jdbc.caffeine.snapshot.stageSnapshot
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.io.Serializable

data class JdbcOrderRow(val id: Long, val description: String)

data class JdbcOrderSnapshot(val id: Long, val description: String) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private val jdbcOrderSnapshotCache = jdbcCaffeineSnapshotCache<Long, JdbcOrderSnapshot>(
    CaffeineSnapshotCacheConfig(
        snapshot = SnapshotCacheConfig(namespace = "orders:v1", schemaVersion = "order-dto-v1"),
    ),
)

fun JdbcTransaction.cacheOrderSnapshot(
    id: Long,
    loadFromDatabase: JdbcTransaction.(Long) -> JdbcOrderRow,
): CacheSnapshot<JdbcOrderSnapshot> {
    val lookup = jdbcOrderSnapshotCache.lookup(id)
    lookup.snapshot?.let { return it }
    val row = loadFromDatabase(id)
    return stageSnapshot(
        cache = jdbcOrderSnapshotCache,
        miss = requireNotNull(lookup.miss),
        source = row,
        mapper = CacheSnapshotMapper { CacheSnapshot(JdbcOrderSnapshot(it.id, it.description)) },
    )
}

fun JdbcTransaction.invalidateOrderSnapshot(id: Long) {
    stageInvalidation(jdbcOrderSnapshotCache, id)
}
```
<!-- README-CANONICAL-JDBC-END -->

## 사용 예시

### 동기 레포지토리 (AbstractJdbcCaffeineRepository)

```kotlin
import io.bluetape4k.exposed.cache.LocalCacheConfig
import io.bluetape4k.exposed.jdbc.caffeine.repository.AbstractJdbcCaffeineRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement

data class ActorRecord(val id: Long, val firstName: String, val lastName: String) : java.io.Serializable {
    companion object { private const val serialVersionUID = 1L }
}

class ActorCaffeineRepository(
    config: LocalCacheConfig = LocalCacheConfig.WRITE_THROUGH,
) : AbstractJdbcCaffeineRepository<Long, ActorRecord>(config) {

    override val table = ActorTable

    override fun ResultRow.toEntity() = ActorRecord(
        id = this[ActorTable.id].value,
        firstName = this[ActorTable.firstName],
        lastName = this[ActorTable.lastName],
    )

    override fun UpdateStatement.updateEntity(entity: ActorRecord) {
        this[ActorTable.firstName] = entity.firstName
        this[ActorTable.lastName] = entity.lastName
    }

    override fun BatchInsertStatement.insertEntity(entity: ActorRecord) {
        this[ActorTable.firstName] = entity.firstName
        this[ActorTable.lastName] = entity.lastName
    }

    override fun extractId(entity: ActorRecord) = entity.id
}

// Read-Through (캐시 미스 → DB 로드)
val actor = repo.get(1L)

// Write-Through (캐시 + DB 동기 반영)
repo.put(1L, ActorRecord(1L, "홍", "길동"))

// 다건 저장
repo.putAll(mapOf(1L to actor1, 2L to actor2))

// 캐시 항목 제거 (DB 영향 없음)
repo.invalidate(1L)
```

### Suspend 레포지토리 (AbstractSuspendedJdbcCaffeineRepository)

```kotlin
import io.bluetape4k.exposed.cache.LocalCacheConfig
import io.bluetape4k.exposed.jdbc.caffeine.repository.AbstractSuspendedJdbcCaffeineRepository

class ActorSuspendedRepository(
    config: LocalCacheConfig = LocalCacheConfig.WRITE_THROUGH,
) : AbstractSuspendedJdbcCaffeineRepository<Long, ActorRecord>(config) {

    override val table = ActorTable

    override fun ResultRow.toEntity() = ActorRecord(
        id = this[ActorTable.id].value,
        firstName = this[ActorTable.firstName],
        lastName = this[ActorTable.lastName],
    )

    override fun UpdateStatement.updateEntity(entity: ActorRecord) {
        this[ActorTable.firstName] = entity.firstName
        this[ActorTable.lastName] = entity.lastName
    }

    override fun BatchInsertStatement.insertEntity(entity: ActorRecord) {
        this[ActorTable.firstName] = entity.firstName
        this[ActorTable.lastName] = entity.lastName
    }

    override fun extractId(entity: ActorRecord) = entity.id
}

// 모든 연산이 suspend 함수
suspend fun example(repo: ActorSuspendedRepository) {
    val actor = repo.get(1L)                           // Read-Through
    repo.put(1L, ActorRecord(1L, "홍", "길동"))         // Write-Through
    repo.invalidate(1L)                                // 캐시 항목만 제거
    repo.clear()                                       // 전체 캐시 항목 제거
}
```

### Write-Behind 설정

```kotlin
val behindConfig = LocalCacheConfig(
    keyPrefix = "actor",
    maximumSize = 5_000L,
    writeMode = CacheWriteMode.WRITE_BEHIND,
    writeBehindBatchSize = 200,
    writeBehindQueueCapacity = 5_000,
)
val repo = ActorCaffeineRepository(behindConfig)

// put()은 즉시 반환, DB flush는 비동기 배치로 처리
repo.put(1L, actor)
```

## LocalCacheConfig 설정 참조

```kotlin
val config = LocalCacheConfig(
    keyPrefix = "actor",                          // 캐시 키 접두사
    maximumSize = 10_000L,                        // Caffeine 최대 항목 수
    expireAfterWrite = Duration.ofMinutes(30),    // 마지막 쓰기 이후 TTL
    expireAfterAccess = null,                     // 마지막 접근 이후 TTL (선택)
    writeMode = CacheWriteMode.WRITE_THROUGH,     // READ_ONLY | WRITE_THROUGH | WRITE_BEHIND
    writeBehindBatchSize = 100,                   // flush 배치 크기
    writeBehindQueueCapacity = 10_000,            // 큐 용량 (무제한 금지)
)
```

## 테스트 데이터베이스

테스트는 다음 환경에서 실행됩니다:

- **H2 (MySQL 모드)** — 인메모리, 로컬 빠른 실행용
- **PostgreSQL** — Testcontainers 사용
- **MySQL 8** — Testcontainers 사용

## 의존성

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-caffeine")
}
```

애플리케이션이 `bluetape4k-dependencies` BOM 버전을 소유하므로 모듈 좌표에는 버전을 쓰지 않습니다.

## 참고

- [exposed-cache — 허브 모듈](../exposed-cache/README.ko.md)
- [exposed-jdbc](../exposed-jdbc)
- [Caffeine Cache](https://github.com/ben-manes/caffeine)
