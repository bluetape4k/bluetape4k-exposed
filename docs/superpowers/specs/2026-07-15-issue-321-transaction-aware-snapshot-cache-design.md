# 이슈 #321 트랜잭션 인식 스냅숏 Near Cache 설계

## 배경

이슈 #321은 Exposed DAO `Entity` 객체나 트랜잭션 범위 내부
`EntityCache`를 분산하지 않으면서 Exposed 리포지토리 결과를 저장하는
트랜잭션 인식 Near Cache를 요구한다.

현재 리포지토리의 사실은 다음과 같다.

- `JdbcCacheRepository`, `SuspendedJdbcCacheRepository`, 그리고
  `R2dbcCacheRepository`는 이미 직렬화 가능한 레코드를 캐시하며
  read-through, write-through, write-behind 동작을 제공한다.
- Caffeine, Lettuce, Redisson 구현은 각자의 캐시 및 데이터베이스 쓰기
  의미를 소유한다. 트랜잭션 후 일반 `put` 메서드를 호출하면 데이터베이스를
  다시 쓸 수 있으므로 이 메서드는 안전한 커밋 콜백이 아니다.
- `CachePersistedWrite`와 `afterPersisted`는 이미 성공한 Caffeine 영속화
  경계를 표시한다. 그러나 호출자가 소유하는 범용 Exposed 트랜잭션 버퍼를
  제공하지는 않는다.
- Exposed 1.3.1은 `StatementInterceptor.afterCommit`과
  `StatementInterceptor.afterRollback`을 제공한다. `JdbcTransaction`과
  `R2dbcTransaction` 모두 core `StatementInterceptor`를 등록할 수 있다.
- Spring의 `TransactionSynchronization`은 이미 JDBC DDD 이벤트 게시자가
  사용하지만, 이슈 #321은 Spring 중립성을 유지해야 한다.

이 설계를 작성하기 전에 다음 기준 명령이 통과했다.

```bash
./gradlew \
  :bluetape4k-exposed-cache:test \
  :bluetape4k-exposed-jdbc-caffeine:test \
  :bluetape4k-exposed-r2dbc-caffeine:test \
  --no-daemon
```

결과: 1m 28s 만에 `BUILD SUCCESSFUL`. R2DBC Caffeine 모듈은 통과한 테스트
66개와 기존 보류 사례 1개를 보고했다.

## 목표

Exposed JDBC 또는 R2DBC 트랜잭션 안에서 불변 스냅숏 캐시 변경을 스테이징하고
해당 트랜잭션이 커밋된 뒤에만 적용하는 선택형 Spring 중립 코디네이터를
추가한다.

설계는 다음을 보장해야 한다.

- 롤백은 오염된 스냅숏을 절대 노출하지 않는다.
- 공개 API는 Exposed DAO `Entity` 직접 값을 거부하고, 문서화한 값 계약은
  중첩 `Entity`, `EntityCache`, 지연 상태 또는 변경 가능한 상태를 금지한다.
- 캐시 콜백은 데이터베이스 쓰기를 반복하지 않는다.
- 커밋 후 캐시 변경 실패는 데이터베이스 결과를 바꿀 수 없다.
- 같은 저장소와 식별자에 대한 반복 변경은 결정론적인 마지막 변경 우선
  동작을 따른다.
- 로컬 Caffeine 저장소는 즉시 동작하며, 분산 구현은 무효화 SPI와 기존
  무효화 프로토콜을 재사용할 수 있다.

## 목표가 아닌 사항

- Exposed DAO의 트랜잭션 범위 identity map 대체.
- Caffeine 또는 Redis write-behind 큐를 영속 아웃박스로 전환.
- 캐시 상태를 데이터베이스 트랜잭션의 원자적 커밋에 포함.
- 임의의 리포지토리 빈을 자동 래핑.
- Spring Boot 자동 구성 추가.
- Ktor 상태 확인 경로나 메트릭 추가. 이 표면은 이슈 #325가 담당한다.
- 마이그레이션 또는 스키마 드리프트 도구 추가. 이 표면은 이슈 #322가 담당한다.

## 검토한 접근 방식

### A. Exposed 트랜잭션 인터셉터와 캐시 전용 저장소 SPI

캐시 전용 변경을 트랜잭션 로컬 상태에 스테이징하고 트랜잭션마다 인터셉터
하나를 등록한다. 최종 스테이징 변경은 `afterCommit`에서 적용하고
`afterRollback`에서 폐기한다.

장점:

- 일반 Exposed, Spring 관리 Exposed JDBC, R2DBC 트랜잭션에서 동작한다.
- 리포지토리의 Spring 중립 DDD 경계를 보존한다.
- 재시도와 동시 트랜잭션을 자연스럽게 격리한다.
- 각 캐시 백엔드가 자체 Near Cache 동기화 규칙을 보존할 수 있다.

비용:

- 구체 트랜잭션 클래스가 인터셉터 등록을 소유하므로 명시적인 JDBC 및 R2DBC
  트랜잭션 진입점이 필요하다.
- 커밋 후 캐시 실패는 관찰할 수 있지만 롤백할 수 없다.

이 접근 방식을 선택한다.

### B. Spring `TransactionSynchronization` 어댑터

이 방식은 `ExposedAggregateEventPublisher`의 패턴을 재사용하므로 Spring JDBC
애플리케이션에서 커밋 후 동작을 간단히 구현할 수 있다.

일반 Exposed와 R2DBC 호출자를 제외하고 core 캐시 계약을 Spring에 의존하게
만드므로 기각한다.

### C. 캐시를 즉시 갱신하고 롤백 시 무효화

구현은 더 작지만 다른 스레드나 노드가 커밋되지 않은 데이터를 읽을 수 있는
가시성 구간이 생긴다. 롤백 콜백은 호출자가 이미 관찰한 데이터를 회수할 수
없다.

핵심 이슈 요구 사항을 위반하므로 기각한다.

## 아키텍처

### 불변 스냅숏 envelope

`exposed/cache`에 직렬화 가능한 불변 값 envelope를 추가한다.

```kotlin
data class CacheSnapshot<V : Serializable>(
    val value: V,
    val revision: String? = null,
) : Serializable

fun interface CacheSnapshotMapper<S, V : Serializable> {
    fun toSnapshot(source: S): CacheSnapshot<V>
}

fun interface CacheSnapshotValueValidator<V : Serializable> {
    fun validate(value: V)
}

fun interface SnapshotValueSizer<V : Serializable> {
    fun estimatedRetainedBytes(value: V): Long
}
```

`value`는 애플리케이션이 소유하는 불변 레코드 또는 DTO다. `revision`은 행
버전, 정규화된 갱신 타임스탬프, 안정적인 콘텐츠 해시 같은 선택적 메타데이터다.
core 코디네이터는 이를 불투명 토큰으로 취급하며 애플리케이션 revision 형식
사이의 순서를 추측하지 않는다.

`Entity`, `ResultRow` 또는 도메인 상태의 매핑은 호출자의 활성 루트 트랜잭션
안에서 `CacheSnapshotMapper`를 통해 동기식으로 수행하며 mapper 결과만
스테이징한다. 공개 스테이징 진입점은 트랜잭션 상태를 바꾸기 전에 최상위
Exposed DAO `Entity`를 런타임에 거부한다. 공개 예제와 컴파일 테스트는
`Serializable`을 구현하는 불변 Kotlin data class를 사용한다.

`Serializable`과 최상위 런타임 검사만으로 깊은 불변성을 증명할 수는 없다.
호출자는 중첩 컬렉션, 관계, 지연 참조가 분리되어 있으며 불변인지 보장해야
한다. 선택형 애플리케이션 값 validator는 도메인별 변경 가능 그래프나 과대
그래프를 스테이징 전에 거부할 수 있다. 기본 validator는 최상위 DAO entity를
거부하고 그 밖의 값은 허용하며, 리플렉션 기반의 깊은 그래프 순회는 수행하지
않는다.

### 스냅숏 전용 구성

스냅숏 어댑터는 `LocalCacheConfig` 대신 전용 구성을 사용한다.

```kotlin
data class SnapshotCacheConfig(
    val namespace: String,
    val schemaVersion: String,
    val maxStagedMutations: Int = 10_000,
    val maxParticipatingStores: Int = 8,
)

data class CaffeineSnapshotCacheConfig(
    val snapshot: SnapshotCacheConfig,
    val maximumSize: Long = 10_000,
    val maximumWeight: Long? = null,
    val expireAfterWrite: Duration = Duration.ofMinutes(10),
    val expireAfterAccess: Duration? = null,
    val maxStagedWeight: Long? = null,
    val localDrainBudget: Duration = Duration.ofMillis(250),
    val fenceStripes: Int = 1_024,
    val maxOutstandingMissTokens: Int = 10_000,
)
```

`SnapshotCacheConfig`에는 의도적으로 `CacheWriteMode`가 없다. 스냅숏 저장소는
항상 캐시 전용이며 read-through, write-through, write-behind를 의미할 수 없다.
`LocalCacheConfig` 전달을 지원하지 않으므로 기존 쓰기 모드 의미가 조용히
무시될 수 없다. 모든 양수 경계와 기간은 생성 시 검증한다. 공통 객체에는 모든
백엔드가 사용하는 namespace와 트랜잭션 전체 제한만 둔다.
`CaffeineSnapshotCacheConfig`는 용량, 만료, 스테이징 가중치, 로컬 drain,
로컬 순서 fence 설정을 소유하며 Redisson은 이를 받거나 조용히 무시하지 않는다.
`fenceStripes`는 64에서 65,536 사이의 2의 거듭제곱이어야 하고
`maxOutstandingMissTokens`는 양수여야 한다.
`maxStagedMutations`는 직렬화된 바이트나 전체 heap 사용량이 아니라 엔트리 수와
콜백 작업량을 제한한다. 큰 DTO를 사용하는 애플리케이션은 값 validator에서
자체 페이로드 제한을 적용해야 한다.
`localDrainBudget`은 프로세스 내부 Caffeine 작업에 적용하는 트랜잭션 전체의
협력적 예산이지, 강제 선점 경계나 저장소별 허용량이 아니다. 프로덕션 권장
상한은 1초다.
`schemaVersion`은 애플리케이션이 소유하는 비어 있지 않은 형식 토큰이며,
직렬화된 필드 의미나 중첩 generic 형태가 바뀔 때마다 변경한다.
`maximumWeight` 또는 `maxStagedWeight`를 설정하면 Caffeine factory는 보수적인
보유 바이트 추정치를 반환하는 `SnapshotValueSizer<V>`를 요구한다. 이 정확한
음이 아닌 추정치를 Caffeine 가중 용량에 전달하고, 버퍼 변경 전에 스테이징
바이트 상한을 적용한다. 가중 모드는 Caffeine 유지 관리 후 가장 차가운 초과
엔트리를 무효화하여 `maximumSize`를 독립적으로 적용하며, 엔트리 제한을
근사하려고 값 가중치를 부풀리지 않는다. sizer가 없으면 선택적 가중치 제한은
null이어야 하며, 문서는 엔트리 수만으로 heap 경계를 보장할 수 없음을 분명히
밝힌다. 값별 거부가 필요한 애플리케이션을 위해 재사용 가능한
`maximumEstimatedPayloadBytes(sizer, limit)` validator를 제공한다.

JDBC Redisson invalidator는 다음과 같은 정확한 추가 구성을 사용한다.

```kotlin
data class JdbcRedissonSnapshotInvalidatorConfig(
    val snapshot: SnapshotCacheConfig,
    val nearCacheMaximumSize: Int = 10_000,
    val maxEncodedKeyBytes: Int = 4 * 1024,
    val maxBatchEncodedKeyBytes: Int = 64 * 1024,
    val maxCommitEncodedKeyBytes: Int = 256 * 1024,
    val maxOutstandingChunks: Int = 64,
    val maxOutstandingEncodedBytes: Long = 4L * 1024 * 1024,
    val namespaceVerificationTimeout: Duration = Duration.ofSeconds(2),
    val multiNode: Boolean = true,
    val synchronizationStrategy: LocalCachedMapOptions.SyncStrategy =
        LocalCachedMapOptions.SyncStrategy.INVALIDATE,
    val reconnectionStrategy: LocalCachedMapOptions.ReconnectionStrategy =
        LocalCachedMapOptions.ReconnectionStrategy.CLEAR,
    val trustedBinaryCache: Boolean = false,
)
```

생성 시 양수가 아닌 크기/바이트/미결 제한 또는 검증 timeout,
`maxBatchEncodedKeyBytes > maxCommitEncodedKeyBytes`, 그리고
`CLEAR`가 아닌 reconnect 전략, multi-node 모드의 `NONE` 동기화를 거부한다.
`UPDATE`는 무효화 전용 동작에 필요하지 않으므로 거부한다.

초기 범위의 분산 식별자는 라이브러리가 소유하는 scalar policy만 사용한다.

```kotlin
sealed interface SnapshotIdentifierPolicy<ID : Any>

fun longSnapshotIdentifierPolicy(): SnapshotIdentifierPolicy<Long>
fun uuidSnapshotIdentifierPolicy(): SnapshotIdentifierPolicy<UUID>

sealed interface SnapshotRedissonCodec<ID : Any> : Codec {
    val codecVersion: String
}

fun <ID : Any> snapshotRedissonCodec(
    delegate: Codec,
    codecVersion: String,
    identifierPolicy: SnapshotIdentifierPolicy<ID>,
): SnapshotRedissonCodec<ID>
```

wrapper는 `codecVersion`이 `[A-Za-z0-9._-]{1,64}`와 일치하도록 요구하며 선택한
scalar policy의 map-key encoder를 소유한다. delegate는 값 및 map-key가 아닌
codec 동작만 제공한다. 정규 Long 및 UUID encoder는 순수하고 결정론적이며
길이가 고정되어 있다. wrapper는 인코딩 전에 scalar policy를 적용하고, 구조상
DAO entity, 트랜잭션, 복합 그래프, 지연 참조를 거부한다.
분산 식별자는 Redis에 보이는 인프라 키이므로 secret, credential, PII가 아닌
대체 행 식별자여야 한다. 구문만으로 공개 식별자와 bearer token, credential,
개인 데이터를 구분할 수 없으므로 초기 범위에는 의도적으로 String policy를
제공하지 않는다. 애플리케이션은 `EntityID`, 복합 ID 또는 민감한 도메인 ID를
민감하지 않은 Long/UUID 대체 값으로 매핑한다. 복합 ID와 String ID는 로컬
Caffeine에서만 계속 지원한다.

정규 map-key wire encoding은 규범적이며 버전을 갖는다.

- Long은 정확히 8바이트를 사용한다. 부호 있는 2의 보수이며 최상위 바이트가
  먼저 온다. Golden vector는 `0 -> 0000000000000000`,
  `1 -> 0000000000000001`, `-1 -> ffffffffffffffff`이다.
- UUID는 정확히 16바이트를 사용한다. `mostSignificantBits`를 첫 번째 부호
  있는 big-endian Long으로 두고 같은 형식의 `leastSignificantBits`를 잇는다.
  Golden vector `00112233-4455-6677-8899-aabbccddeeff`는
  `00112233445566778899aabbccddeeff`로 매핑된다.
- decoder는 정확한 길이를 요구하며 해당 encoder의 역함수다. 플랫폼 고유
  바이트 순서, 텍스트 렌더링, delegate key codec은 관여하지 않는다.

정규 key-encoding 식별자(`bt4k-long-be-v1` 또는 `bt4k-uuid-be-v1`)는 원격
fingerprint에 들어간다. 바이트 규칙 하나라도 바꾸려면 새로운 encoding
식별자, `codecVersion`, namespace rollout이 필요하다.

### 캐시 전용 저장소

`exposed/cache`에 동기식 캐시 전용 SPI를 추가한다.

```kotlin
@InternalSnapshotCacheApi
interface SnapshotCacheStore<ID : Any, V : Serializable> {
    val storeId: SnapshotStoreId
    val storeInstanceToken: Any
    val compatibilityFingerprint: String
    val limits: SnapshotCacheLimits
    val failureBuffer: SnapshotCacheFailureBuffer

    @InternalSnapshotCacheApi
    fun claimMiss(
        miss: SnapshotCacheMiss<ID, V>,
    ): ClaimedSnapshotMiss<ID, V>

    fun applySnapshots(
        snapshots: List<SnapshotCacheMutation.Put<ID, V>>,
        deadline: SnapshotCacheDeadline,
    ): SnapshotCacheApplyReport

    fun applyInvalidations(
        ids: List<ID>,
        deadline: SnapshotCacheDeadline,
    ): SnapshotCacheApplyReport
}

@InternalSnapshotCacheApi
fun interface ClaimedSnapshotMiss<ID : Any, V : Serializable> {
    fun prepare(snapshot: CacheSnapshot<V>): SnapshotCacheMutation.Put<ID, V>
}

@InternalSnapshotCacheApi
interface AsyncSnapshotInvalidationStore<ID : Any> {
    val storeId: SnapshotStoreId
    val storeInstanceToken: Any
    val compatibilityFingerprint: String
    val limits: SnapshotCacheLimits
    val failureBuffer: SnapshotCacheFailureBuffer

    fun measure(id: ID): MeasuredInvalidation<ID>

    fun submitInvalidation(
        batch: List<MeasuredInvalidation<ID>>,
    ): CompletionStage<SnapshotCacheApplyReport>
}

data class MeasuredInvalidation<ID : Any>(
    val id: ID,
    val encodedBytes: Int,
    val encodedSha256: String,
)

interface SnapshotCacheDeadline {
    fun remaining(): Duration
    val isExpired: Boolean
}

data class SnapshotCacheLimits(
    val maxStagedMutations: Int,
    val maxParticipatingStores: Int,
    val maxStagedWeight: Long? = null,
    val localDrainBudget: Duration? = null,
)

data class SnapshotStoreId(
    val backend: String,
    val namespace: String,
)

sealed interface SnapshotCacheMutation<ID : Any, V : Serializable> {
    val id: ID

    data class Put<ID : Any, V : Serializable>(
        override val id: ID,
        val snapshot: CacheSnapshot<V>,
        @InternalSnapshotCacheApi
        val localFence: SnapshotLocalFence<ID>? = null,
        @InternalSnapshotCacheApi
        val estimatedWeight: Long? = null,
    ) : SnapshotCacheMutation<ID, V>

    data class Invalidate<ID : Any, V : Serializable>(
        override val id: ID,
    ) : SnapshotCacheMutation<ID, V>
}

@InternalSnapshotCacheApi
class SnapshotLocalFence<ID : Any> internal constructor()

class SnapshotCacheLookup<ID : Any, V : Serializable> private constructor(
    val snapshot: CacheSnapshot<V>?,
    val miss: SnapshotCacheMiss<ID, V>?,
) {
    companion object {
        @InternalSnapshotCacheApi
        fun <ID : Any, V : Serializable> hit(
            snapshot: CacheSnapshot<V>,
        ): SnapshotCacheLookup<ID, V>

        @InternalSnapshotCacheApi
        fun <ID : Any, V : Serializable> miss(): SnapshotCacheLookup<ID, V>
    }
}

class SnapshotCacheMiss<ID : Any, V : Serializable> internal constructor() {
    override fun toString(): String = "SnapshotCacheMiss(opaque)"
}

data class SnapshotCacheApplyReport(
    val results: List<SnapshotCacheOperationResult>,
) {
    @InternalSnapshotCacheApi
    fun requireReconciled(
        operation: SnapshotCacheOperation,
        expectedCount: Int,
    ): SnapshotCacheApplyReport
}

data class SnapshotCacheOperationResult(
    val operation: SnapshotCacheOperation,
    val outcome: SnapshotCacheOutcome,
    val affectedCount: Int,
    val exceptionType: String? = null,
)

enum class SnapshotCacheOperation { GET, PUT, INVALIDATE }
enum class SnapshotCacheOutcome {
    SUCCESS,
    OVERRUN,
    FAILED,
    NOT_ATTEMPTED,
    REJECTED,
}

sealed interface SnapshotCacheFailureBuffer {
    val capacity: Int
    val size: Int
    val droppedCount: Long
    val observerFailureCount: Long

    fun poll(): SnapshotCacheFailure?
    fun drainTo(
        observer: SnapshotCacheFailureObserver,
        maxElements: Int = capacity,
    ): SnapshotCacheDrainResult
}

data class SnapshotCacheDrainResult(
    val deliveredCount: Int,
    val observerFailedCount: Int,
    val remainingCount: Int,
    val observerExceptionType: String? = null,
)

fun snapshotCacheFailureBuffer(
    capacity: Int = 1_024,
): SnapshotCacheFailureBuffer
```

스냅숏 저장소 SPI는 명시적으로 선택해서 사용하는 동기식 인프로세스 내부 API다.
일반 소비자는 miss 토큰/트랜잭션 스테이징을 우회하도록 원시 적용 메서드를 호출할 수
없다. 분산 무효화 SPI는 completion stage를 반환하며, 검토를 거친 어댑터 모듈
내부에서만 사용한다. 구현체는 캐시 작업만 수행해야 한다. Exposed 저장소의 `put`을
호출하거나, map writer를 실행하거나, 새 데이터베이스 트랜잭션을 시작하거나,
데이터베이스 행을 삭제해서는 안 된다.

`ID` 값은 불변이어야 하며 트랜잭션과 캐시의 전체 수명 동안 안정적인 `equals` 및
`hashCode` 의미를 유지해야 한다. 지원하는 복합 식별자는 구성 요소도 같은 규칙을
만족하는 불변 데이터 클래스다. 문서에서는 가변 식별자 fixture를 제외하고,
복합 ID 테스트로 이 계약을 고정한다.

레지스트리는 SPI를 호출하기 전에 저장소/ID마다 최종 변경 하나로 병합한다. 협력형
예산이 만료되기 전에 시작하지 못한 로컬 작업은 영향받은 개수와 함께
`NOT_ATTEMPTED`가 된다. 기한이 지난 뒤 완료된 연산은 정상적인 1건 결과를 보고한
다음, 영향받은 개수가 0인 `OVERRUN`을 보고하므로 단계별 개수 합계는 계속 입력과
일치한다. 로컬 저장소는 인프로세스에서 순회하며 항목별 실패를 격리해 보고할 수
있다. 버퍼가 이미 각 키를 병합하므로 그룹화 과정에서 같은 식별자의 두 변경 순서가
바뀔 수 없다.

공개 Caffeine `lookup(id)` 연산은 스트라이프 잠금을 획득하고 정확히 하나의 결과,
즉 `snapshot` 또는 라이브러리가 생성한 불투명 `miss`를 반환한다. facade는 명시적
잠금으로 보호되고 miss 객체를 키로 사용하는 약한 참조 동일성 capability
레지스트리에 ID와 ID에 결합된 불투명 `SnapshotLocalFence`를 저장한다. 두
capability 모두 ID/fence/token getter가 없고 `Serializable`이 아니며, 정제된 상수
`toString`을 사용한다. 스냅숏을 스테이징하려면 이 miss 토큰이 필요하며, 공개
bare-ID 스냅숏 PUT 오버로드는 없다. 내장 Caffeine 저장소는 모든 PUT에 자신이
소유한 유효한 불투명 miss capability를 요구하고, 토큰이 없거나 다른 저장소
소유이거나 재사용되었거나 범위를 벗어나면 레지스트리/캐시를 변경하기 전에
거부한다. 다른 저장소도 자신이 소유하지 않은 miss capability를 거부해야 한다.
비공개 lookup 생성자와 opt-in 팩터리는 XOR을 강제하여 `snapshot`과 `miss` 중
정확히 하나만 null이 아니게 한다. claim은 약한 참조 레지스트리 항목을 원자적으로
제거하고, PUT 변경을 만들 때까지 ID/fence를 비공개로 소유하는 불투명
`ClaimedSnapshotMiss` preparer를 반환한다. 공통 mapped-staging 경로는 먼저
claim하고 mapper/validator를 실행한 다음 `prepare(snapshot)`을 호출한다.
preparer 자체는 호출을 정확히 한 번만 받는다. mapper가 실패하면 claim된
preparer를 그대로 폐기한다. 따라서 원래 트랜잭션이 롤백되어도 모든 miss 토큰은
일회용이다. claim되지 않은 토큰이 가비지 컬렉션되면 값도 사라진다.
`maxOutstandingMissTokens`는 의도적으로 보존한 미claim 토큰 수를 제한한다. 오래된
약한 참조 항목을 제거한 뒤에도 한도가 가득 차 있으면 데이터베이스 트랜잭션을
시작하기 전에 lookup이 실패한다.

초기 구현은 기존 JDBC 및 R2DBC Caffeine 모듈에 Caffeine 어댑터를 제공한다. 두
어댑터 모두 Caffeine을 직접 변경하므로 저장소의 데이터베이스 writer를 호출하지
않는다. `CaffeineSnapshotCacheConfig`를 받고 `maximumSize`,
`expireAfterWrite`, `expireAfterAccess`를 준수하므로 스냅숏 namespace의 항목 수는
항상 제한된다.

이 이슈에서는 분산 스냅숏 게시를 구현하지 않는다. 백엔드에 원자적으로 비교할 수
있는 revision fence가 없으면, timeout된 이전 Redis PUT이 더 새로운 commit 또는
무효화 뒤에 완료되어 오래된 데이터를 되살릴 수 있다. 코어 revision은 의도적으로
불투명하며 기존 Redisson/Lettuce 계층은 이 계약을 제공하지 않는다. 따라서 JDBC
Redisson 통합은 writer가 없는 무효화 전용 저장소로서 `Put` 변경을 거부하고,
인코딩 바이트 한도를 지키는 다중 무효화 청크를 제출한다. 기존 저장소 read-through
경로는 캐시에 안전한 DTO를 채울 수 있으며, #321은 해당 항목을 commit-safe하게
무효화한다. Lettuce 지원은 동일한 비차단 캐시 전용 기존 프로토콜 무효화 경계를
입증하는 조건으로 남겨 둔다. 분산 스냅숏 PUT은 별도의 검토를 거친 fencing 설계가
나올 때까지 연기한다.

### 모듈 경계와 공개 진입점

`exposed/cache`는 엔진 중립 계약만 소유한다. 여기에는 스냅숏 envelope와 mapper,
스냅숏 구성, 저장소 동일성, 변경/보고 모델, 캐시 전용 저장소 SPI, 실패 관찰,
공유 트랜잭션 레지스트리 알고리즘이 포함된다. main source set에는 R2DBC 의존성을
추가하지 않으며 공개 `JdbcTransaction` 또는 `R2dbcTransaction` 시그니처도
노출하지 않는다.

기존 백엔드 모듈 세 개는 이미 `exposed/cache`와 각자 대응하는 Exposed 엔진에
의존한다. 이 모듈들이 구체 facade와 정확한 공개 팩터리를 소유한다. 호환성
fingerprint를 생략할 수 없도록 facade 생성자는 계속 내부에 둔다. 공개 non-inline
팩터리는 명시적인 `KClass` 토큰을 받는다. reified 편의 함수는 이 공개 함수에
위임하므로 `@PublishedApi` 생성 경로가 필요 없다.

```kotlin
fun <ID : Any, V : Serializable> jdbcCaffeineSnapshotCache(
        idType: KClass<ID>,
        valueType: KClass<V>,
        config: CaffeineSnapshotCacheConfig,
        valueSizer: SnapshotValueSizer<V>? = null,
        validator: CacheSnapshotValueValidator<V> =
            rejectDirectEntitySnapshotValues(),
        failureBuffer: SnapshotCacheFailureBuffer =
            snapshotCacheFailureBuffer(),
    ): JdbcCaffeineSnapshotCache<ID, V>

fun <ID : Any, V : Serializable> r2dbcCaffeineSnapshotCache(
        idType: KClass<ID>,
        valueType: KClass<V>,
        config: CaffeineSnapshotCacheConfig,
        valueSizer: SnapshotValueSizer<V>? = null,
        validator: CacheSnapshotValueValidator<V> =
            rejectDirectEntitySnapshotValues(),
        failureBuffer: SnapshotCacheFailureBuffer =
            snapshotCacheFailureBuffer(),
    ): R2dbcCaffeineSnapshotCache<ID, V>

fun <ID : Any, V : Serializable> jdbcRedissonSnapshotInvalidator(
        redissonClient: RedissonClient,
        codec: SnapshotRedissonCodec<ID>,
        idType: KClass<ID>,
        valueType: KClass<V>,
        config: JdbcRedissonSnapshotInvalidatorConfig,
        failureBuffer: SnapshotCacheFailureBuffer =
            snapshotCacheFailureBuffer(),
    ): JdbcRedissonSnapshotInvalidator<ID>

data class SnapshotInvalidationQuotaHealth(
    val maxOutstandingChunks: Int,
    val outstandingChunks: Int,
    val maxOutstandingEncodedBytes: Long,
    val outstandingEncodedBytes: Long,
    val rejectedChunks: Long,
    val saturated: Boolean,
)

fun JdbcRedissonSnapshotInvalidator<*>.quotaHealth(): SnapshotInvalidationQuotaHealth
```

제공된 `SnapshotRedissonCodec` 인스턴스는 기존 저장소 map을 정확히 구성해야 하므로,
저장소 접근과 무효화가 라이브러리 소유의 동일한 map-key 바이트를 사용한다. delegate
타입, `codecVersion`, `ID`/`V` 토큰은 원격 호환성 fingerprint에 포함된다. 클래스는
같지만 구성 토큰이 다른 두 codec은 호환되지 않는다. `ExposedRedissonCodecSafety`가
trusted-binary gate를 적용한다.
Redisson client와 codec은 호출자가 소유한다. invalidator는 thread, executor,
scheduler, coroutine을 시작하지 않으며 `AutoCloseable`도 아니다. 라이브러리가
소유한 제한된 실패 버퍼는 비차단 `offer`만 수행한다. 호출자 코드는 트랜잭션과
Redisson event-loop callback 밖에서 버퍼를 poll하거나 drain한다. Caffeine
facade도 닫아야 할 리소스를 소유하지 않는다. Caffeine facade는 `storeId`,
`failureBuffer`, `lookup(id): SnapshotCacheLookup<ID, V>`를 노출한다. Redisson
invalidator는 `storeId`, `failureBuffer`, 구조적인 공유 quota 상태만 노출하고
읽기 또는 스냅숏 게시 메서드는 노출하지 않는다. 호출자가 제공한 공유 버퍼는
노출된 동일 인스턴스로 유지된다.

`rejectDirectEntitySnapshotValues()`는 classpath-safe하다. DAO가 있을 때만 Exposed
DAO 기반 클래스를 이름으로 해석하고 정적 DAO 타입 참조 없이 할당 가능 여부를
검사한다. 따라서 DAO가 없는 소비자에게 `NoClassDefFoundError`가 발생하지 않는다.
중첩 그래프는 위에서 설명한 호출자 계약으로 남는다.
Redisson 팩터리는 wrapper의 스칼라 식별자 정책만 받는다. 각 스테이징에서 wrapper는
ID를 인코딩하고 바이트 수와 SHA-256을 기록한 뒤 임시 버퍼를 해제한다.
`maxEncodedKeyBytes`를 초과하는 키는 거부하고, 레지스트리를 변경하기 전에 저장소별
`maxCommitEncodedKeyBytes` 합계를 원자적으로 강제한다. 측정 크기는 ID와 함께
보존하여 commit 시점 batch가 `maxBatchEncodedKeyBytes` 안에 머물게 한다.

실제 Redisson 호출 시 동일 wrapper의 라이브러리 소유 map-key encoder가 각 불변
스칼라 ID를 다시 인코딩하고 길이/hash를 스테이징 시점 측정값과 비교한 뒤, 바이트를
Redisson에 반환하기 전에 활성 청크의 실제 바이트 예산을 강제한다. 불일치는 내부
불변식 실패다. 새 버퍼를 해제하고 해당 청크는 네트워크 제출 전에 실패하지만
coordinator는 이후 청크를 계속 처리한다. 호출자 codec은 비결정적 map-key encoder를
주입할 수 없다. 따라서 구성된 상한은 추정치뿐 아니라 Redisson에 실제로 전달된
바이트까지 제한한다.

JDBC Caffeine 모듈은 다음 트랜잭션 확장 함수를 제공한다.

```kotlin
fun <ID : Any, V : Serializable> JdbcTransaction.stageSnapshot(
    cache: JdbcCaffeineSnapshotCache<ID, V>,
    miss: SnapshotCacheMiss<ID, V>,
    snapshot: CacheSnapshot<V>,
): CacheSnapshot<V>

fun <ID : Any, S, V : Serializable> JdbcTransaction.stageSnapshot(
    cache: JdbcCaffeineSnapshotCache<ID, V>,
    miss: SnapshotCacheMiss<ID, V>,
    source: S,
    mapper: CacheSnapshotMapper<S, V>,
): CacheSnapshot<V>

fun <ID : Any, V : Serializable> JdbcTransaction.stageInvalidation(
    cache: JdbcCaffeineSnapshotCache<ID, V>,
    id: ID,
)
```

`r2dbc-caffeine`은 `R2dbcTransaction`/`R2dbcCaffeineSnapshotCache`를 사용하는
동일한 시그니처 세 개를 선언한다.

```kotlin
fun <ID : Any, V : Serializable> R2dbcTransaction.stageSnapshot(
    cache: R2dbcCaffeineSnapshotCache<ID, V>,
    miss: SnapshotCacheMiss<ID, V>,
    snapshot: CacheSnapshot<V>,
): CacheSnapshot<V>

fun <ID : Any, S, V : Serializable> R2dbcTransaction.stageSnapshot(
    cache: R2dbcCaffeineSnapshotCache<ID, V>,
    miss: SnapshotCacheMiss<ID, V>,
    source: S,
    mapper: CacheSnapshotMapper<S, V>,
): CacheSnapshot<V>

fun <ID : Any, V : Serializable> R2dbcTransaction.stageInvalidation(
    cache: R2dbcCaffeineSnapshotCache<ID, V>,
    id: ID,
)
```

`jdbc-redisson`은 다음 함수만 노출한다.

```kotlin
fun <ID : Any> JdbcTransaction.stageInvalidation(
    invalidator: JdbcRedissonSnapshotInvalidator<ID>,
    id: ID,
)
```

스테이징에 성공하면 승인한 스냅숏을 반환하므로 miss 경로에서 mapping을 두 번 하지
않는다. 컴파일로 검증한 영문/한국어 예제는 이 최종 이름을 사용하며 선택적 API
형태는 없다. 스냅숏 확장 함수는 현재/root 트랜잭션, `maxAttempts == 1`, facade
소유권을 검증한 다음 mapping 또는 레지스트리 변경 전에 miss를 원자적으로
claim한다. mapping/스테이징 실패도 해당 토큰을 소비한다. 재시도는 애플리케이션
소유 외부 루프에서 전체 lookup과 단일 시도 트랜잭션을 반복하므로, 서로 다른
트랜잭션 시도 사이에서 게시할 수 없다.

공통 모듈은 어댑터 모듈이 Kotlin `internal` 누출 없이 호출할 수 있도록 다음 공개
opt-in 경계를 선언한다.

```kotlin
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
annotation class InternalSnapshotCacheApi

@InternalSnapshotCacheApi
interface SnapshotTransactionBridge<TX : Transaction> {
    fun isRoot(transaction: TX): Boolean
    fun isCurrent(transaction: TX): Boolean
    fun maxAttempts(transaction: TX): Int
    fun registerInterceptor(
        transaction: TX,
        interceptor: StatementInterceptor,
    )
}

@InternalSnapshotCacheApi
fun <TX : Transaction, ID : Any, V : Serializable> stageSnapshotMutation(
    transaction: TX,
    bridge: SnapshotTransactionBridge<TX>,
    store: SnapshotCacheStore<ID, V>,
    miss: SnapshotCacheMiss<ID, V>,
    snapshot: CacheSnapshot<V>,
    validator: CacheSnapshotValueValidator<V>,
): CacheSnapshot<V>

@InternalSnapshotCacheApi
fun <TX : Transaction, ID : Any, S, V : Serializable> stageMappedSnapshotMutation(
    transaction: TX,
    bridge: SnapshotTransactionBridge<TX>,
    store: SnapshotCacheStore<ID, V>,
    miss: SnapshotCacheMiss<ID, V>,
    source: S,
    mapper: CacheSnapshotMapper<S, V>,
    validator: CacheSnapshotValueValidator<V>,
): CacheSnapshot<V>

@InternalSnapshotCacheApi
fun <TX : Transaction, ID : Any> stageInvalidationMutation(
    transaction: TX,
    bridge: SnapshotTransactionBridge<TX>,
    store: AsyncSnapshotInvalidationStore<ID>,
    id: ID,
)
```

JDBC와 R2DBC singleton bridge는 `outerTransaction == null`로 `isRoot`를
구현하고, 구체 트랜잭션의 `registerInterceptor`에 등록을 위임한다. `isCurrent`는
receiver를 엔진의 현재 JDBC 트랜잭션 또는 R2DBC coroutine-context 트랜잭션과
비교한다. 모든 공개 스냅숏 확장 함수는 일회용 miss 토큰을 claim하기 전에
`maxAttempts == 1`도 요구한다. 무효화 스테이징은 miss 토큰을 전달하지 않으며 각
시도에 자체 트랜잭션 레지스트리가 있으므로 일반 Exposed 재시도에 참여할 수 있다.
공통 coordinator의 공개 opt-in 스테이징 진입점은 이 bridge, 트랜잭션 객체,
저장소, miss capability, 스냅숏 또는 source/mapper, validator를 받는다. mapping
전에 claim하며 어댑터 모듈 확장 함수만 이를 호출한다. 소비자는 bridge를 구현하거나
호출하지 않는다. 새 Gradle 모듈, annotation 의존성, 엔진 간 의존성을 도입하지
않는다.

### 트랜잭션 레지스트리와 coordinator

공유 coordinator는 비공개 Exposed 트랜잭션 user-data 키 아래에 레지스트리 하나를
저장한다. facade 인스턴스를 키로 사용하지 않는다. 레지스트리는 `SnapshotStoreId`와
엔티티 식별자를 키로 사용하는 삽입 순서 map이다. `SnapshotStoreId`에 처음 등록된
저장소가 drain 대상이 되며, 불투명 인스턴스 토큰과 비기밀 호환성 fingerprint를
기록한다. 같은 프로세스에서 동일한 논리적 동일성을 사용하는 다른 facade는 참조
동일성(`===`) 기준으로 같은 토큰과 호출자 제공 실패 버퍼를 사용하고 fingerprint도
같아야 한다. 그렇지 않으면 버퍼를 변경하기 전에 스테이징이 실패한다. 각 저장소는
비공개 토큰 객체 하나를 만들며 값이 같은 토큰으로는 저장소 동일성이 성립하지
않는다. 이 규칙은 같은 namespace를 사용하는 서로 다른 두 로컬 Caffeine 인스턴스
중 첫 번째 캐시만 조용히 갱신되는 일을 막는다. 등록된 저장소 하나 안에서는
last-mutation-wins 의미를 유지한다.

- put 후 put: 최신 스냅숏을 게시한다.
- put 후 invalidate: 무효화한다.
- invalidate 후 put: 스냅숏을 게시한다.

root 트랜잭션에서 처음 스테이징한 변경만 interceptor를 등록한다. 해당 트랜잭션의
모든 저장소는 같은 레지스트리에서 drain한다. JDBC와 R2DBC bridge는
`exposed/cache`에서 엔진 타입을 누출하지 않고 변경 및 오류 처리 로직을 공유한다.

트랜잭션 user-data 키는 유일한 상태 객체를 가리킨다. 명시적 잠금으로 보호되는
별도의 약한 참조 동일성 terminal set은 트랜잭션이 coordinator 경계를 지났는지만
기억하며 상태나 페이로드 역참조를 보유하지 않는다. 예외를 던지지 않는
interceptor의 `beforeCommit`은 `OPEN`을 `BOUNDARY_STARTED`로 바꾸고 Exposed가
user data를 지우기 전에 병합된 버퍼를 비공개 pending 필드로 옮긴다.
`beforeRollback`은 상태를 terminal로 표시하고 active/pending 페이로드를 지운다.
`afterCommit`은 캐시 호출 전에 pending 데이터를 로컬 drain 값으로 옮기고 상태를
지우며, `afterRollback`은 방어적으로 다시 지운다. 약한 참조 terminal guard는
트랜잭션이 가비지 컬렉션될 때까지 남지만 정상 callback 순서가 끝난 뒤에는 스냅숏
페이로드를 포함하지 않는다.

이 설계는 참여하는 트랜잭션 객체마다 하나의 물리적 경계만 의도적으로 지원한다.
bridge는 receiver가 현재 Exposed 트랜잭션인지도 검증한다. `BOUNDARY_STARTED`
이후의 스테이징, 캡처한 비현재 트랜잭션에서의 스테이징, coordinator 자체의
commit/rollback callback에서 수행하는 스테이징은 mapping이나 버퍼 변경 전에
실패한다. Exposed 1.3.1은 로컬 interceptor를 등록 순서대로 호출하는 동안
트랜잭션을 current로 유지하며 공통 bridge는 더 이른 callback 진입 상태를 노출하지
않는다. 따라서 coordinator보다 먼저 등록된 interceptor는 coordinator가 자체
`beforeCommit`/`beforeRollback`을 받기 전에 작업을 스테이징할 수 있다. commit은
그 작업을 캡처하고 rollback은 폐기한다. 실제 엔진을 사용하는 JDBC/R2DBC 검증
또는 어댑터 소유의 조기 guard는 어댑터 작업에 속한다. 참여 트랜잭션에서
`commit()`/`rollback()`을 수동으로 반복하는 사용은 지원하지 않는다. 테스트는
commit 후 stage, rollback 후 stage, coordinator callback 스테이징, 더 이른
callback 순서, interceptor 누적을 다룬다. 여전히 current인 재사용 가능 Exposed
객체에서 이전 수동 commit 뒤 처음 스냅숏을 호출하는 경우는 지원 계약 밖이다.
따라서 문서는 일반적인 단일 경계 `transaction {}`/`suspendTransaction {}`
scope 안에서만 스냅숏 API를 사용하도록 요구한다.

`SnapshotCacheConfig.maxStagedMutations`는 양수여야 한다. 트랜잭션 전체의 유효
한도는 참여 저장소 한도 중 최솟값이다. 한도에 도달해도 기존 identity/ID 변경을
교체할 수 있다. 한도를 넘어 새 identity/ID를 추가하면 commit 전에 예외가 발생하며
기존 레지스트리는 바뀌지 않는다. 이 규칙은 일관성 작업을 조용히 버리지 않으면서
보존 항목 수와 commit 후 fan-out 작업을 제한한다. 선택적 weight 한도를 구성하면
별도의 바이트/heap guard를 제공한다.

`maxParticipatingStores`도 양수이며 기본값은 8이다. 새 identity를 등록하기 전에
참여 저장소 값 중 최솟값을 강제하여 트랜잭션별 단계 fan-out과 async 제출 작업
합계를 제한한다.

참여자 등록, 유효 count/weight 한도 재계산, 후보 변경은 사전 검사를 거친 하나의
원자적 상태 전이를 이룬다. 새로 도입한 저장소 때문에 한도가
`existing + candidate`보다 작아지면 coordinator는 해당 저장소를 등록하거나 이전
유효 한도 또는 버퍼를 바꾸지 않고 후보를 거부한다. 교체 weight는
`totalWeight - oldMutationWeight + newMutationWeight`로 계산하며, 거부 시 이전
변경과 합계를 정확히 보존한다.

`afterCommit`에 도달하면 등록 순서와 무관하게 세 단계로 drain한다.

1. 측정한 모든 Redisson 무효화를 인코딩 바이트 한도의 청크로 나누고, 결과를
   기다리지 않은 채 모든 invalidator의 모든 청크에 admission/submission을
   시도한다.
2. 최종 인프로세스 Caffeine 무효화를 적용한다.
3. 최종 인프로세스 Caffeine 스냅숏 PUT을 적용한다.

로컬 작업보다 모든 Redisson future를 먼저 제출하므로 Exposed
트랜잭션/커넥션이 callback에 남아 있는 동안 어떤 백엔드도 기다리지 않는다. 같은
`RedissonClient`의 invalidator가 공유하는 quota가 포화되면 여러 facade에서 이후
청크를 거부할 수 있다. 계약은 대기 없는 진행을 보장하지만, 포화 상태의 facade별
공정성은 보장하지 않는다. completion callback은 구조적 개수만 보존하고 성공하지
못한 결과만 제한된 실패 버퍼에 `offer`한다. 성공하면 실패 버퍼 용량을 차지하지
않고 quota를 해제하고 구조적 상태를 갱신한다. 트랜잭션 callback에서는 future를
기다리거나 취소하지 않는다. 어댑터는 Redisson client event loop를 재사용하며
executor, scheduler, coroutine, 영속 background worker를 만들지 않는다.

coordinator는 준비된 각 청크를 독립적으로 제출하고 즉시 발생한 제출 예외를 잡아
해당 청크를 실패로 기록한 뒤 나머지 청크/저장소를 계속 제출한다. 실제 재인코딩과
청크 검증은 quota admission 전에 수행한다. admission에 성공하면 exactly-once
lease를 반환한다. future가 생기기 전에 제출이 실패하면 lease를 즉시 해제한다.
future가 생기면 소유권이 completion callback으로 이전되고, callback은 구조적
결과를 기록한 뒤 `finally`에서 lease를 해제한다. 인코딩 또는 admission 거부는
lease를 만들지 않는다. 따라서 동기 실패가 반복되어도 quota가 누출되지 않는다.

명시적 잠금으로 보호되는 약한 참조 동일성 레지스트리는 `RedissonClient`마다 quota
하나를 보유한다. 첫 팩터리가 `maxOutstandingChunks`와
`maxOutstandingEncodedBytes`를 고정한다. 정확히 같은 client를 사용하는 이후
facade는 같은 값을 제공해야 하며, 그렇지 않으면 map 접근이나 스테이징 전에 팩터리
생성이 실패한다. 각 명령 전에 coordinator는 청크 하나와 실제 인코딩 바이트를
원자적으로 예약한다. quota가 고갈되면 제출을 생략하고 정제된 `REJECTED` 결과
하나를 제공한다. 따라서 영원히 완료되지 않는 client는 트랜잭션에 걸쳐 제한 없이
명령이나 버퍼를 누적하는 대신 quota를 닫는다. 팩터리 문서는 유한한 Redisson 명령
timeout과 5초 이하의 재시도 정책을 요구하며 integration fixture에서 이를 검증한다.

facade는 구성된 청크/바이트 수와 미완료 청크/바이트 수, 거부 횟수, admission 포화
여부로 구성된 공유 구조적 quota 상태를 노출한다. 식별자나 Redis endpoint 데이터는
포함하지 않는다. 구성된 Redisson 명령 timeout보다 오래 quota가 포화 상태로
남으면 운영자는 새 스테이징을 중지하고, 영향받은 client를 닫아 future가 예외로
완료되게 하고, quota가 0으로 돌아왔는지 검증하고, 실패 버퍼를 drain한 다음 새
client와 facade를 만든다. 실패 버퍼는 제한된 queue를 기반으로 한 라이브러리의
구체 타입이며 completion 경로는 비차단 `offer`만 호출한다. 포화되면 정제된
`droppedCount`를 증가시킨다. `drainTo`는 트랜잭션과 Redisson callback 밖에서
`drainTo`를 명시적으로 호출한 thread에서만 호출자 observer를 실행한다. 전달 전에
항목을 제거한다. observer 예외는 실패한 해당 항목을 소비하고
`observerFailureCount`를 증가시키며 drain을 중단한다. 반환하는
`SnapshotCacheDrainResult`에는 observer 실패 1건과 예외 타입만 담는다.
`droppedCount`는 queue 포화로 잃은 항목만 나타낸다. delivered, observer-failed,
remaining, dropped 개수는 서로 합치지 않는다. 이 버퍼는 진단용이며 영속 복구
저장소가 아니다.

각 Caffeine facade는 크기가 2의 거듭제곱으로 고정된 lock/generation-token
스트라이프 배열을 소유한다. cache miss lookup은 데이터베이스를 읽기 전에 저장소
소유자, 논리 식별자, 스트라이프, generation을 비공개로 결합한 불투명 capability를
캡처한다. capability는 내부 생성자를 쓰고 data-class copy/component 표면이 없는
일반 비직렬화 클래스다. commit 후 PUT은 소유 레지스트리에 스트라이프 잠금 획득을
요청하고, 소유자와 generation이 여전히 동일성 기준으로 일치하고 식별자도 캡처된
식별자와 일치할 때만 적용한다. 토큰을 새 비공개 객체로 교체하고 잠금을 해제하기
전에 `cache.put`을 수행한다. 무효화도 같은 스트라이프를 획득하고 토큰을
무조건 교체한 뒤 잠금을 풀기 전에 무효화한다. 따라서 이전 트랜잭션 callback은
같은 스트라이프의 더 새로운 PUT 또는 무효화 뒤에 게시할 수 없다. 동일성 토큰에는
숫자 overflow가 없다. hash collision은 무관한 PUT을 보수적으로 생략할 수 있지만,
capability의 대상을 충돌한 식별자로 바꾸거나 오래된 데이터를 노출할 수 없다.
고정 스트라이프 배열은 제한 없는 tombstone map도 만들지 않는다. 캡처한 generation은
내부 metadata이며 직렬화하거나 분산하지 않는다. fence가 불일치하면 PUT을 생략하고
구조적 `REJECTED` 결과 하나를 제공하며, 다음 읽기는 안전한 cache miss로 남는다.

모든 저장소 보고서는 `requireReconciled(operation, expectedCount)`를 거쳐
coordinator 경계를 통과한다. 음수 기대값, 잘못된 연산 결과, 부족하거나 초과한
개수를 거부한다. `Long`으로 누적하므로 `Int` overflow가 잘못된 보고서를 유효하게
보이게 할 수 없다.

로컬 단계는 가장 작은 `localDrainBudget`에서 파생한 단조 증가
`SnapshotCacheDeadline`을 공유한다. 이는 협력형 예산이다. 내장 Caffeine 저장소는
각 항목 전에 이를 확인하고 남은 작업을 `NOT_ATTEMPTED`로 표시한다. 단일 캐시
연산/listener가 만료 뒤 반환하면 개수가 0인 `OVERRUN`을 보고한다. 완료된 연산은
정상적인 1건 결과를 유지하고 나머지 항목은 `NOT_ATTEMPTED`가 된다. 동기 SPI는
임의의 사용자 코드를 선점할 수 없으므로 이 설계는 callback 지연 시간의 엄격한
상한을 주장하지 않는다. 모든 보고서는 연산과 결과별로 해당 단계의 입력 개수와
정확히 일치한다.

## 트랜잭션 수명 주기

### 커밋

1. 애플리케이션이 트랜잭션에 결합된 상태를 불변 스냅숏으로 mapping한다.
2. `stageSnapshot` 또는 `stageInvalidation`이 현재 트랜잭션 버퍼에 변경을 기록한다.
3. Exposed가 데이터베이스 트랜잭션을 commit한다.
4. `afterCommit`이 트랜잭션 상태를 분리하고 지운다.
5. coordinator가 제한된 모든 분산 무효화를 기다리지 않고 제출한 다음, 로컬
   무효화와 PUT을 협력형 방식으로 drain한다.
6. async completion observer가 트랜잭션이나 스냅숏 페이로드를 보존하지 않고 구조적
   결과를 보고한다.

캐시 변경은 데이터베이스 commit 뒤에 일어난다. commit에서 캐시 반영까지의 짧은
구간에 다른 프로세스가 이전 캐시 데이터를 읽을 수 있으므로, 이 기능은 분산
원자성이 아니라 commit 안전성을 제공한다.

### 롤백

`beforeRollback`은 저장소를 호출하지 않고 상태를 terminal로 표시한 뒤 트랜잭션
버퍼를 지운다. `afterRollback`은 방어적으로 정리를 반복한다. 명시적 rollback과
예외로 발생한 rollback 모두 스테이징된 스냅숏을 노출할 수 없다.

버퍼 키는 rollback을 넘어 보존되지 않는다. 정상 callback 순서 뒤에는 트랜잭션이
수거될 때까지 페이로드 없는 약한 참조 terminal guard만 남는다.

### 재시도와 중첩

Exposed 트랜잭션 재시도는 새 트랜잭션 객체를 만든다. 무효화 전용 작업은
트랜잭션 로컬이므로 실패한 시도의 변경이 이후 시도로 누출되지 않는다. 스냅숏
채우기는 의도적으로 단일 시도만 허용한다. 확장 함수는 미리 읽은 miss capability를
소비하기 전에 `maxAttempts == 1`을 검사한다. 읽기 채우기를 재시도하는
애플리케이션은 외부 정책에서 `lookup`과 단일 시도
`transaction`/`suspendTransaction` 전체를 반복한다. 따라서 Exposed 내부 lambda
재실행에서 오래된 입력을 재사용하지 않고, 매 시도의 데이터베이스 읽기 전에 fence를
캡처한다.

같은 Exposed 트랜잭션을 재사용하는 중첩 작업은 같은 버퍼를 공유한다. Exposed가
null이 아닌 `outerTransaction`을 가진 savepoint 기반 중첩 트랜잭션을 만들면
`stageSnapshot`과 `stageInvalidation`은 어느 버퍼도 바꾸기 전에 실패한다. 중첩
트랜잭션의 `afterCommit`은 물리적 커넥션 commit이 아니라 savepoint 성공만
뜻하므로, 여기서 게시하면 외부 트랜잭션이 나중에 rollback할 데이터를 노출할 수
있다. 애플리케이션은 중첩 작업이 성공적으로 반환된 뒤 root 트랜잭션에서
스테이징한다.

## Read-Through 사용법

coordinator는 트랜잭션을 열지 않는다. 호출자는 다음 패턴을 따른다.

1. 백엔드 facade의 `lookup(id)`를 호출한다.
2. `snapshot`이 있으면 즉시 반환하고, 없으면 일회용 `miss` 토큰을 보존한다.
3. miss라면 호출자 소유 Exposed 트랜잭션 안에서 행 또는 DAO를 읽는다.
4. 해당 토큰으로 source-plus-mapper `stageSnapshot` 오버로드를 호출한다.
5. 호출자가 쓸 반환값에서 `CacheSnapshot.value`를 꺼낸다.
6. `revision`은 애플리케이션 진단 또는 비교에만 사용한다.
7. commit 뒤에만 캐시가 채워지게 한다.

쓰기 경로는 무효화를 스테이징하여 다음 읽기가 데이터베이스의 기준 상태를 다시
읽게 한다. 스냅숏 PUT은 read-miss 채우기이며 미리 읽은 `SnapshotCacheMiss`
토큰이 필요하다. 쓰기 경로는 bare-ID PUT을 만들 수 없다.

사용 가능한 트랜잭션이 없을 때 어떤 API도 조용히 즉시 캐시 쓰기로 fallback하지
않는다. 활성 트랜잭션이 확장 함수 receiver이므로 경계가 명시적이고 컴파일로
검증할 수 있다.

JDBC 문서 fixture는 다음 최종 형태를 사용한다.

```kotlin
val lookup = orderSnapshots.lookup(orderId)
lookup.snapshot?.let { return it.value }
val miss = requireNotNull(lookup.miss)

return transaction {
    maxAttempts = 1
    val row = Orders.selectAll().where { Orders.id eq orderId }.single()
    stageSnapshot(orderSnapshots, miss, row) { source ->
        CacheSnapshot(
            value = OrderSnapshot(
                id = source[Orders.id].value,
                lines = source[Orders.lines].toList(),
            ),
            revision = source[Orders.version].toString(),
        )
    }.value
}
```

R2DBC fixture는 `suspendTransaction` 안에서 같은 본문을 사용한다. 쓰기 예제는
데이터베이스 변경 뒤 `stageInvalidation(cache, id)`를 호출한다. negative
compile/documentation fixture는 일치하는 활성 트랜잭션 타입에서만 확장 함수를
사용할 수 있음을 보여 준다. rollback 예제는 반환된 애플리케이션 값이 캐시의 조기
노출을 뜻하지 않음을 입증한다.

## 실패 의미

데이터베이스 실패와 캐시 실패는 서로 다른 결과다.

- mapping 또는 스테이징 실패는 commit 전에 발생하며 정상적으로 전파된다.
- rollback은 스테이징된 모든 변경을 폐기한다.
- `afterCommit`의 비치명적 저장소 실패는 저장소 동일성, 연산, 결과, 영향받은 개수,
  예외 타입과 함께 제한된 실패 버퍼에 제공된다. 값, 식별자, credential, SQL, URL,
  직렬화된 스냅숏은 보존하거나 기록하지 않는다.
- 저장소 실패 시 데이터베이스 rollback을 시도하거나 commit된 DB 연산을 반복하지
  않는다. 실패한 각 분산 연산 그룹은 영향받은 개수와 함께 한 번만 보고하며,
  제한 없는 키별 재시도 루프로 분해하지 않는다.
- 로컬 저장소는 일반적인 항목별 실패를 격리하여 잘못된 항목 하나가 무관한 변경을
  막지 않게 한다. 치명적인 JVM 오류는 삼키지 않는다.
- 물리적 commit 뒤 coordinator가 관찰한 `CancellationException`을 포함해 저장소
  `Exception`은 callback 밖으로 빠져나가지 않는다. commit 후 캐시 실패로 제공하며
  commit된 데이터베이스 결과를 바꿀 수 없다. commit 전에 관찰한 cancellation은
  일반 트랜잭션 rollback을 따르며 아무것도 게시하지 않는다. 동기 callback은
  coroutine을 시작하지 않는다.
- R2DBC 물리적 commit 진행 중 발생한 cancellation, 커넥션 손실, driver 실패의
  데이터베이스 결과는 알 수 없다. 이를 rollback으로 설명하지 않는다. Exposed는
  기다리던 commit이 끝날 때까지 `afterCommit`을 호출하지 않으므로 이 상태에서
  coordinator는 스냅숏을 게시하지 않는다. 캐시는 오래된 상태로 남을 수 있으며
  일반 miss/reload 또는 애플리케이션 소유 reconciliation 경로로 복구한다. 캐시
  callback이 실행되지 않았으므로 캐시 버퍼에는 이벤트가 들어오지 않는다. 호출자는
  commit/cancellation 예외를 받고 unknown-commit 모니터링을 소유한다.
- 동기 로컬 JVM 치명적 `Error`는 캐시 상태 이벤트로 변환하지 않고 coordinator
  callback 밖으로 전파한다. `Error`로 끝난 비동기 completion은
  `CompletionStage` chain에서 예외 상태로 남으며 실패 이벤트로 변환하지 않는다.
  일반 `CompletionStage` 의미는 호출자 thread로의 동기 전파를 보장하지 않는다.

Exposed는 등록된 interceptor를 순서대로 호출하며 각 callback을 격리하지 않는다.
물리적 commit 뒤 더 앞선 third-party `afterCommit` interceptor가 예외를 던지면
이 coordinator의 `afterCommit`이 실행되지 않을 수 있다. 캐시 변경이나 캐시 실패
이벤트가 발생하지 않고 캐시는 오래된 상태로 남으며, 호출자는 third-party 예외를
관찰하고 pending 값은 트랜잭션 객체가 수거될 때까지만 남는다. 더 앞선 rollback
callback이 이 interceptor 실행을 막으면 캐시 변경은 없으며, coordinator가
`beforeRollback`을 받지 못했으므로 스테이징된 페이로드는 트랜잭션 수명까지만
보존된다. 라이브러리는 다른 interceptor의 예외를 변환하거나 callback 순서를
보장할 수 없다. JDBC/R2DBC 테스트는 예외를 던지는 interceptor를 이 interceptor
앞에 배치하여 안전한 stale-cache/보존 결과를 고정한다.

기본 사용법은 애플리케이션 소유 maintenance/health 작업에서 버퍼를 logging
observer로 drain하는 것이다. 영속적인 캐시 복구가 필요한 애플리케이션은 자체
outbox 또는 복구 queue를 사용해야 한다. 제한된 버퍼는 그런 queue가 아니다.

observer 계약은 스냅숏이나 식별자 대신 정제된 구조적 context를 받는다.

```kotlin
fun interface SnapshotCacheFailureObserver {
    fun onFailure(failure: SnapshotCacheFailure)
}

fun loggingSnapshotCacheFailureObserver(): SnapshotCacheFailureObserver

data class SnapshotCacheFailure(
    val storeId: SnapshotStoreId,
    val operation: SnapshotCacheOperation,
    val outcome: SnapshotCacheOutcome,
    val affectedCount: Int,
    val exceptionType: String? = null,
)
```

observer는 제한된 저카디널리티 구조 데이터만 받는다. `storeId.namespace`는 운영자가
작성한 정적 namespace이며 엔티티 키가 아니다. `affectedCount`는 이벤트 측정값이므로
metrics tag로 사용해서는 안 된다. raw throwable은 coordinator logging 경로 내부에
남는다. 백엔드 예외에 URL, credential, 키가 포함될 수 있으므로 메시지, stack
rendering, suppressed exception, cause chain 없이 타입만 기록한다.

사용자 정의 observer는 명시적 `failureBuffer.drainTo` 호출 안에서만 실행된다.
`Exception`을 던지면 정제된 구조적 drain 결과가 소비한 실패를 보고하고 현재
drain을 중단한다. 트랜잭션 callback이나 async Redisson completion에는 영향을 줄
수 없다. JVM 치명적 `Error`는 일반 호출자 thread 정책을 유지한다.

JDBC Redisson invalidator는 바이트 한도가 있는 모든 async 청크를 시도하고 기다리지
않은 채 반환한다. admission된 청크는 제출하며 거부된 청크는 기록하되 이후 시도를
막지 않는다. 일반적인 completion 실패, 동기 제출 실패, admission 거부만 실패
버퍼에 제공한다. 영원히 끝나지 않는 future는 공유 quota 상태에 계속 보이고
라이브러리 timeout 작업 대신 quota로 제한된다. completion observer는 불변 저장소
동일성, 호출자 소유의 제한된 실패 버퍼, 기대 개수만 캡처하며 어댑터, 저장소,
client를 보존하지 않는다. 치명적인 async completion 오류는 dependent stage에서
예외 상태로 남고 실패 이벤트로 변환되지 않는다. 늦은 무효화는 추가 miss를 일으킬
수 있지만 오래된 데이터를 되살릴 수 없다. 이 기능은 재시도, 보상 변경, 분산
`get`, 분산 PUT을 추가하지 않는다. Caffeine 읽기는 인프로세스이며, 비치명적 로컬
읽기 예외는 동일한 fail-open-as-miss 및 실패 버퍼 규칙을 따른다.

## 일관성과 노드 간 규칙

- 데이터베이스가 기준 데이터 원본이다.
- `revision`은 비교, 진단, 백엔드별 조건부 로직을 위한 metadata다. 코어는 불투명
  revision의 순서를 정하지 않는다.
- 한 트랜잭션 버퍼 안에서는 마지막 변경이 이긴다. 로컬 Caffeine callback은 제한된
  스트라이프 fence로 순서를 정하며, 오래되었거나 충돌한 PUT을 보수적으로 거부할 수
  있다. 노드 간 total order는 주장하지 않는다.
- 동시 writer에서는 다음 cache miss가 기준 상태를 다시 읽으므로 commit 시점
  무효화가 안전한 기본값이다.
- 이 이슈의 분산 어댑터는 무효화만 게시한다. 불투명 revision을 노드 간 fence로
  취급하지 않는다.
- Redisson invalidator는 `INVALIDATE` 동기화를 사용하여 스냅숏 broadcast 없이 peer
  노드에 키 무효화를 전달한다. `NONE`과 `UPDATE`는 거부한다.
- Redisson 재연결 시 로컬 hit를 제공하기 전에 전용 로컬 스냅숏 캐시를 지워,
  놓친 무효화 뒤의 오래된 상태를 피한다.
- Lettuce는 기존 near-cache 무효화 channel과 TTL 동작을 유지해야 한다.
- 공개 분산 통합은 라이브러리 정책으로 검증한 스칼라 식별자만 받는다. 따라서
  `EntityCache`, 트랜잭션 handle, DAO 인스턴스, 복합 그래프, lazy 관계 상태를 분산
  값으로 노출하거나 직렬화할 수 없다. 로컬 스냅숏 호출자는 문서화된 detached-value
  계약을 계속 책임진다.

### Namespace와 rollout 계약

`SnapshotStoreId.backend`는 `caffeine` 또는 `redisson` 같은 제한된 라이브러리
상수다. `namespace`는 `[a-z][a-z0-9._-]{0,62}:v[1-9][0-9]*`와 일치해야 하고
명시적으로 운영자가 소유하며 tenant, request, entity 식별자를 포함해서는 안 된다.
분산 배포에서는 `orders-snapshot:v1` 같은 versioned namespace를 사용한다. backend,
namespace, codec/스냅숏 타입 fingerprint, 동기화 mode가 함께 호환 저장소 하나를
식별한다. 호환되지 않는 fingerprint로 namespace를 재사용하면 로컬에서 값을 섞는
대신 facade 생성 또는 같은 트랜잭션의 최초 등록 중에 실패한다.

Redisson은 추가로 namespace에서 파생한 예약 원격 metadata 키 아래에 fingerprint를
저장한다. facade 생성 중 string codec과 원자적 claim-or-compare script를 사용한다.
metadata는 없지만 원격 map에 이미 항목이 있으면 알 수 없는 legacy format을
claim하지 않고 fail-closed한다. script는 metadata와 map이 모두 없을 때만 marker를
claim하고, 아니면 기존 marker가 정확히 일치할 때만 승인한다. 생성 작업에는
`namespaceVerificationTimeout` 한도가 있으며 timeout, 커넥션 실패, 불일치 시
fail-closed한다. 검증되지 않은 namespace로 traffic을 처리하지 않는다. metadata
키에는 TTL이 없으며 문서화된 운영 정리에서 해당 namespace와 함께만 삭제한다.
따라서 호환되지 않는 codec 또는 값 타입 재사용을 한 트랜잭션 내부뿐 아니라
프로세스 간에도 탐지할 수 있다.

표준 fingerprint 입력은 UTF-8, 줄 구분, 필드 이름 정렬을 적용한
`bt4k-snapshot-fingerprint/v1` 데이터다. backend, namespace, key raw class,
snapshot raw class, 필수 애플리케이션 `schemaVersion`, codec class, sync strategy와
필수 `codecVersion`, 표준 key-encoding 식별자를 포함한다. 로컬 최대 크기, TTL,
max-idle 등 near-cache tuning 값은 직렬화 format 필드가 아니므로 원격 marker에서
제외한다. 저장 형식은 소문자 SHA-256 hex다. 커넥션 endpoint, 사용자 이름,
credential, 임의의 `toString()` 출력은 금지한다. 명시적 schema 토큰은 JVM raw
class 토큰이 표현할 수 없는 중첩 generic/schema 의미를 포함한다.

format을 바꿀 때 운영자는 `v2`로 구성한 reader와 writer를 배포하고, `v2`를
warming하거나 자연스럽게 다시 채운 뒤, 모든 노드를 전환하고 `v1` writer를
중지한다. 진행 중 request가 drain되기를 기다린 다음 `v1` 원격 map, near-cache
상태, metadata 키를 명시적으로 삭제한다. Redisson local-cache TTL/max-idle은 원격
map 항목을 만료시키지 않으므로 정리 완료의 증거로 사용하지 않는다. rollback
시에는 `v2` writer를 중지하고 traffic을 quiesce한 뒤, fingerprint marker는
보존하고 재검증하면서 남겨 둔 `v1` 원격 map과 모든 노드의 로컬 view를 지운다.
그런 다음 모든 노드를 빈 `v1`으로 전환하여 읽기가 데이터베이스에서 다시 구성되게
한다. 읽기를 검증한 뒤에만 `v2`를 정리할 수 있다. `v1`을 보존한다고 해서 `v2`가
활성인 동안 해당 데이터가 최신으로 유지되는 것은 아니다. 버전이 섞인 노드는
version이 없는 namespace를 공유해서는 안 된다.

`jdbc-redisson`은 제한적이고 멱등인 관리 helper도 노출한다.

```kotlin
fun <ID : Any> clearSnapshotNamespace(
    redissonClient: RedissonClient,
    codec: SnapshotRedissonCodec<ID>,
    namespace: String,
    expectedFingerprint: String,
    timeout: Duration = Duration.ofSeconds(2),
): SnapshotNamespaceCleanupResult
```

운영자가 모든 live client에서 namespace를 제거하고 traffic을 drain한 뒤에만 호출한다.
예상 marker를 검증하고 marker보다 먼저 원격 map 데이터를 삭제하며 호출자의 로컬
view를 지운 뒤, 하나의 전체 timeout 안에서 부재를 검증한다. 부분 실패 뒤 다시
실행해도 안전하다. map은 없고 marker가 있으면 marker 삭제부터 재개하며, marker는
없고 map이 있으면 fail-closed한다. rollback 준비는 같은 quiescence, timeout, 검증
규칙을 가진 별도 `clearMapRetainingMarker` 연산을 사용한다. 어느 helper도 client의
quiescence를 추측하거나 자동 실행하지 않는다.

두 helper 모두 주의가 필요한 관리 API로 표시한다. 비기밀 fingerprint는 실수로
format을 삭제하는 일을 막지만 authorization은 아니다. 호출자는 대상 namespace만
검사/unlink할 수 있는 전용 Redis ACL identity가 필요하며, request-facing 경로에
이 helper를 노출해서는 안 된다. 큰 map 제거는 blocking delete가 아니라 Redis
비동기 unlink 의미를 사용한다. client timeout은 acknowledgement/검증만 제한한다.
이미 승인된 서버 측 정리를 취소할 수 없으며 재실행 시 검사를 안전하게 재개한다.
운영 지침은 네트워크 격리, 반복 무효화 alert/rate 제어, 충분한 데이터베이스 load
shedding을 요구한다. 신뢰할 수 없는 namespace writer가 이 API로 스냅숏을 주입할
수는 없지만 peer eviction과 cache-miss 증폭을 강제할 수 있다.

## 초기 통합 범위

이 이슈의 필수 항목:

- `exposed/cache`의 엔진 중립 스냅숏 envelope/mapper/구성, 캐시 전용 SPI,
  변경/보고 모델, 제한된 실패 버퍼, 레지스트리 알고리즘
- 새 Gradle 모듈이나 엔진 간 의존성 없이 `jdbc-caffeine`, `r2dbc-caffeine`,
  `jdbc-redisson`에 제공하는 구체 interceptor 수명 주기 bridge와 컴파일 검증
  트랜잭션 확장 함수
- JDBC 및 R2DBC Caffeine 모듈용 고정 크기 스트라이프 commit-order fence를 갖춘
  직접 Caffeine 저장소 어댑터
- 전용 versioned namespace 아래에서 필수 스칼라 정책 codec wrapper와 writer 없는
  `RLocalCachedMap`을 사용하는 JDBC Redisson invalidator. 이 구현은
  `ExposedRedissonCodecSafety`를 재사용하고 `trustedBinaryCache=false`를 기본값으로
  하며 key/value codec 경로를 검증하고 원격 format metadata를 등록한다.
  `INVALIDATE`와 재연결 `CLEAR`를 요구하며 분산 get/put은 노출하지 않는다.
- 코어 수명 주기, 순서, 실패 격리, 두 노드 무효화 의미를 위한 집중 fake-store 테스트
- commit 후에만 갱신되고 rollback 시 폐기됨을 입증하는 JDBC 및 R2DBC integration 테스트
- commit된 무효화를 두 번째 near-cache client가 관찰하고 rollback은 아무것도
  게시하지 않음을 입증하는 순차 Redisson integration 테스트
- Exposed `EntityCache`와 저장소 스냅숏 near-cache의 차이, 정확한 API 이름,
  quota/실패 버퍼 동작, 복구, namespace rollout을 설명하는 영문/한국어
  cache/backend README 문서
- 모든 공개 계약의 KDoc

이 이슈의 조건부 항목:

- 현재 소스를 검사하여 commit 후 비차단 무효화를 제출하고 모든 DB writer를
  우회하며 새 의존성이나 blocking bridge 없이 기존 peer 무효화 프로토콜을 보존할
  수 있음이 입증될 때만 JDBC Lettuce 어댑터를 추가한다.

명시적으로 연기하는 항목:

- `runBlocking`, detached coroutine, 새 background worker가 필요한 async 어댑터
- 동기 Exposed commit callback에서 blocking network I/O를 수행하게 되는 R2DBC
  Redis/Redisson/Lettuce 어댑터
- Ktor 운영 route와 metrics(#325)
- 예제 애플리케이션 조합(#326)
- 현재 1.11.0 tree로 고정된 stable manual baseline을 1.12.0으로 올릴 때까지
  `docs/manual/{en,ko}` feature page 쌍. 1.12 release checklist는 release 전에
  page를 추가하고 parity를 검증해야 한다. 이 feature PR은 develop 전용 API를
  stable 1.11 manual에 넣어서는 안 된다.

## 테스트 전략

### 코어 테스트

- 스테이징한 put은 commit 전에는 보이지 않고 commit 후에는 보인다.
- rollback은 저장소를 변경하지 않는다.
- put/put, put/invalidate, invalidate/put은 last-mutation-wins를 따른다.
- 서로 다른 식별자는 결정적 순서를 유지한다.
- 같은 facade의 반복 변경은 병합하고, 논리적 `SnapshotStoreId`를 재사용하는 두 번째
  저장소 인스턴스는 fingerprint가 일치해도 거부한다.
- 값은 같지만 서로 다른 토큰 객체의 regression을 포함하여, 저장소 동일성은 비공개
  토큰의 참조 동일성을 사용한다.
- root 트랜잭션마다 interceptor를 하나만 등록한다.
- Exposed가 `afterCommit` 전에 user data를 정리해도 coordinator 버퍼 키만 남고,
  저장소 적용 전에는 그 키도 제거한다.
- 서로 다른 트랜잭션은 버퍼 상태를 공유하지 않는다.
- 실패한 무효화 시도가 Exposed의 성공한 재시도로 누출되지 않는다. 스냅숏 채우기
  재시도는 외부 단일 시도 루프에서 miss 토큰을 다시 얻는다.
- savepoint 기반 중첩 트랜잭션은 버퍼 변경 전에 거부한다.
- nested-commit/outer-rollback, nested-rollback/outer-commit, 전체 root commit
  테스트는 JDBC와 R2DBC에서 중첩 callback이 조기에 게시하지 않음을 입증한다.
- 저장소 하나의 실패가 독립적인 이후 변경을 생략하게 하지 않는다.
- 최상위 DAO `Entity` 값은 버퍼 변경 전에 거부하며 사용자 정의 값 validator는
  애플리케이션별 가변/초과 크기 그래프를 거부할 수 있다.
- `maxStagedMutations`는 한도에서 교체를 허용하고, 기존 버퍼를 바꾸지 않은 채
  한도+1의 새 키를 거부한다.
- 키가 많은 트랜잭션은 연산 단계 분할 전에 병합하고, 비어 있지 않은 단계마다 로컬
  저장소를 최대 한 번 호출한다.
- 트랜잭션 전체 항목 한도는 참여 저장소 중 최솟값을 사용하며 바이트 단위 한도를
  주장하지 않는다.
- 하나의 협력형 local-drain 예산이 여러 로컬 저장소에 걸쳐 적용된다. 만료 작업은
  `NOT_ATTEMPTED`, 초과 실행은 관찰되며 개수 합계는 입력과 일치한다.
- 등록 순서와 무관하게 모든 분산/로컬 무효화를 스냅숏 PUT보다 먼저 실행한다.
  모든 원격 청크에 admission/submission을 먼저 시도하며 멈춘 로컬 PUT이 peer
  무효화를 막을 수 없다.
- 불변 복합 ID는 안정적인 map 의미를 유지하고 문서는 가변 식별자 타입을 거부한다.
- 기본 direct-Entity validator는 runtime classpath에 Exposed DAO가 없어도 load되고 동작한다.
- 실패 버퍼 항목과 drain된 observer는 스냅숏 페이로드를 노출하지 않는다.
- URL, credential, 키가 든 예외도 공개 실패 버퍼에는 예외 타입만 제공한다.
- 버퍼 포화는 구조적 counter를 올리는 비차단 drop이다. 예외를 던지는 사용자 정의
  observer는 이벤트 하나를 소비하고 명시적 `drainTo`에서 delivered/failed/remaining
  개수를 구분해 반환한다. commit 후 `CancellationException`은 밖으로 전파되거나
  commit 결과를 바꾸지 않는다.
- 앞선 JDBC/R2DBC interceptor가 예외를 던지면 이 coordinator의 after-callback을
  건너뛸 수 있다. 캐시 상태는 그대로이며 보존 페이로드는 트랜잭션 수명으로 제한된다.
- 참여 트랜잭션 객체는 interceptor를 누적하지 않고 수동 경계 재사용, coordinator
  callback 스테이징, 경계 후 스테이징을 거부한다. 공통 계약은 coordinator보다 먼저
  등록된 callback에 관한 Exposed 1.3.1 제한을 기록한다.
- 캡처한 비현재 JDBC/R2DBC 트랜잭션 receiver는 mapping 전에 정확한 bridge 검사로 거부한다.
- 나중에 참여한 더 낮은 한도의 저장소는 이전 레지스트리, 한도, 버퍼를 바꾸지 않고
  원자적으로 거부한다.
- weight 교체는 정확한 한도에서 subtract-old/add-new 계산을 사용하며 거부 시 이전
  변경을 보존한다.
- 현재 트랜잭션 fixture가 지원하면 fake R2DBC commit-boundary seam으로 기다리던
  물리적 commit 중 cancellation이 rollback으로 보고되지 않고 스냅숏도 캐시
  이벤트도 게시하지 않음을 입증한다. seam을 사용할 수 없으면 bytecode/source
  증거와 집중 계약 테스트로 제한을 문서화한다.
- fake 두 노드 저장소는 commit된 무효화가 peer의 오래된 상태를 제거함을 입증한다.
- 공개 값 계약에는 Exposed DAO 객체를 사용하려던 예제가 없고 문서 fixture는 불변
  직렬화 가능 record를 사용한다.

### JDBC Caffeine 통합 테스트

- 트랜잭션이 불변 `ResultRow` 파생 스냅숏을 스테이징한다.
- DB 성공 후에만 commit이 Caffeine을 채운다.
- rollback은 로컬 캐시와 peer 테스트 observer를 모두 바꾸지 않는다.
- 스테이징한 무효화는 commit 후 기존 스냅숏을 제거한다.
- 캐시 전용 어댑터는 일반 저장소 write mode를 호출하지 않는다.
- `SnapshotCacheConfig`와 `CaffeineSnapshotCacheConfig`에는 write mode가 없고
  `LocalCacheConfig` 오버로드도 노출하지 않는다. Redisson은 Caffeine 전용 tuning을 받지 않는다.
- 구성한 값 크기 측정은 값별 검증, Caffeine `maximumWeight`, 스테이징 보존 바이트
  한도를 강제하며 초과 크기 중첩 DTO는 버퍼 변경 전에 거부한다.
- 이전 트랜잭션이 스테이징한 늦은 PUT은 더 새로운 트랜잭션의 PUT 또는 무효화 뒤에
  다시 채울 수 없다. 스트라이프 충돌은 무관한 PUT을 생략할 수 있지만 오래된
  데이터를 노출하지 않으며 fence 저장소 크기는 고정된다.
- 긴 DB 읽기 전에 캡처한 miss 토큰은 더 새로운 무효화가 스테이징/commit 전에
  끝나면 거부된다. bare-ID PUT이나 토큰 재사용으로 이 순서를 우회할 수 없다.
- 스냅숏 채우기는 `maxAttempts > 1`을 거부한다. 애플리케이션 소유 외부 재시도는
  lookup과 새 단일 시도 트랜잭션을 반복한다. 첫 트랜잭션 실패 후 새 토큰을 쓰는
  두 번째 트랜잭션은 스냅숏 하나만 commit한다.
- 불투명 miss 토큰은 ID/fence getter나 데이터가 담긴 `toString`을 노출하지 않고
  직렬화할 수 없다. claim/GC 시 약한 참조 레지스트리에서 사라지고 구성된 미완료
  토큰 한도에 도달하면 DB 작업 전에 lookup이 실패한다.
- mapped staging은 mapper 호출 전에 miss를 claim하고 제거한다. mapper 실패 뒤에는
  재사용 가능한 capability나 레지스트리 변경이 남지 않는다.

### R2DBC Caffeine 통합 테스트

- `suspendTransaction`은 동일한 commit-only 의미를 사용한다.
- rollback과 coroutine cancellation은 dirty 스냅숏을 게시하지 않는다.
- 캐시 callback은 `runBlocking`이나 detached coroutine 작업을 수행하지 않는다.
- 스테이징한 무효화는 commit 후 기존 스냅숏을 제거한다.
- 최종 R2DBC 확장 시그니처는 consumer fixture에서 컴파일되고 공통 캐시 artifact는
  `R2dbcTransaction` 타입을 노출하지 않는다.
- weighted capacity와 초과 크기 그래프 거부는 JDBC facade와 일치한다.
- 같은 고정 크기 스트라이프 fence는 동시 `suspendTransaction` 실행에서 더 새로운
  PUT 또는 무효화 뒤의 이전 callback을 거부한다.
- R2DBC 스냅숏 채우기도 `maxAttempts = 1`을 요구한다. 외부 재시도는 다음
  데이터베이스 읽기 전에 새 miss 토큰을 다시 얻는다.

### JDBC Redisson 통합 테스트

- invalidator는 전용 namespace 아래에서 필수 스칼라 정책 codec wrapper를 사용해
  writer 없는 local cached map에 연결된다.
- facade 생성은 원격 표준 format fingerprint를 원자적으로 생성하거나 비교하고,
  불일치 또는 timeout 시 fail-closed한다.
- 초과 크기 인코딩 ID와 commit별 인코딩 합계는 레지스트리 변경 전에 거부하며,
  승인한 ID는 바이트 한도의 청크로 나눈다.
- 스칼라 ID 정책은 codec 호출 전에 복합/중첩 객체를 거부한다.
- 분산 String 정책은 없다. 문서/컴파일 fixture는 민감하지 않은 Long/UUID 대체
  식별자를 요구하고 secret, credential, PII Redis 키를 금지한다.
- Long/UUID 표준 키 encoder/decoder는 규범적인 0, 음수, UUID golden vector를
  통과한다. fingerprint fixture는 encoding 식별자를 포함하고 같은 namespace에서
  바뀐 식별자를 거부한다.
- 실제 제출 시 재인코딩 결과는 스테이징한 length/SHA-256 및 활성 청크 예산과
  일치해야 한다. 비결정적 일반 인코딩을 사용하는 delegate는 wrapper의 표준
  map-key 바이트를 바꿀 수 없다.
- 모든 저장소의 모든 invalidator 청크는 로컬 drain 전에 admission/submission
  시도를 받는다. 승인한 청크는 제출하고 거부한 청크는 구조적으로 보고하며 이후
  시도는 계속된다. 트랜잭션 callback은 Redis를 기다리지 않는다.
- 멈춘 여러 invalidator를 기다리지 않으며 Exposed 트랜잭션/커넥션을 보존하지
  않는다. 같은 client quota가 포화되면 이후 facade를 block하지 않고 거부할 수 있다.
- 여러 commit에 걸쳐 끝나지 않는 future는 공유 client quota를 포화시키고 보존
  청크/바이트를 제한하며 새 제출 없이 `REJECTED`를 만든다.
- 재인코딩 실패, 부분 동기 제출 실패, 이미 shutdown된 client는 각각 future 이전
  quota lease를 정확히 한 번 해제하며 이후 청크, 저장소, 로컬 drain을 막지 않는다.
- quota 한도가 다른 같은-client 팩터리는 원격 map 접근 전에 실패한다. 일치하는
  facade는 하나의 공유 구조적 quota 상태를 보고한다.
- 제한된 실패 버퍼 포화는 Redisson event loop를 block하지 않고
  ID/트랜잭션/스냅숏을 캡처하지 않으며 dropped counter만 증가시킨다.
- 많은 성공 completion은 실패 버퍼를 비운 채 공유 quota를 해제한다.
- 영구 포화 quota는 quiesce, client close, quota 0 검증, 버퍼 drain,
  client/facade 교체 절차를 문서화하고 테스트한다.
- 양수 local-cache 크기와 재연결 `CLEAR`를 적용하되 로컬 TTL이 원격 map 항목을
  만료시킨다고 주장하지 않는다.
- Fory, Kryo, JDK binary codec은 기본적으로 거부한다. trusted binary codec은
  명시적으로 opt-in해야 한다.
- 다중 노드 `NONE`과 `UPDATE`는 거부한다. `INVALIDATE`는 스냅숏 페이로드를
  전파하지 않으며 재연결 시 오래된 로컬 항목을 지운다.
- commit된 무효화는 구성된 Redisson sync strategy로 peer client의 오래된 로컬
  항목을 제거한다.
- timeout된 이전 무효화가 더 새로운 캐시 채우기 뒤 완료되어도 miss만 추가로
  일으킬 수 있고 오래된 데이터를 되살릴 수 없다.
- rollback은 Redis 변경이나 peer 무효화를 만들지 않는다.
- Exposed map writer나 데이터베이스 delete hook을 호출하지 않는다.
- 구성 계약 테스트는 namespace 충돌/fingerprint 거부와 문서화된 v1-to-v2
  rollout/rollback 순서를 다룬다.
- 같은 codec class라도 `codecVersion`이 다르면 거부한다.
- cleanup helper는 제한적이고 멱등이며 marker보다 map을 먼저 삭제하고 안전하지
  않은 부분 상태를 거부하며 rollback traffic 전에 비운 v1을 다시 구성한다.

### Regression 명령

```bash
./gradlew :bluetape4k-exposed-cache:test
./gradlew :bluetape4k-exposed-jdbc-caffeine:test
./gradlew :bluetape4k-exposed-r2dbc-caffeine:test
./gradlew :bluetape4k-exposed-jdbc-redisson:test
./gradlew exportManualModuleInventory
ruby scripts/manual/validate_manuals.rb \
  build/manual/module-inventory.json docs/manual/manifest.yaml
./gradlew detekt
git diff --check
```

조건부 분산 어댑터를 추가하더라도 Redis/Testcontainers 기반 검증은 순차 실행한다.

키 하나 및 최대 count/weight 버퍼, 반복 키 병합, 다중 저장소 단계 분할, 최대 drain
allocation, 스트라이프 lookup/fence 경합, Redisson 키 인코딩/청크 제출, 실패 버퍼
포화, timeout/장애 시 커넥션 보유 동작에 관한 non-gating benchmark를 추가한다.
CI assertion은 불안정한 네트워크 wall-clock 임계값 대신 결정적인 보존
count/weight, 제출 횟수, Exposed callback이 Redis를 기다리지 않는다는 증거를
사용한다.

## 문서화

`exposed/cache`와 영향받는 백엔드 모듈의 영문/한국어 README 쌍을 갱신한다. 문서는
다음 내용을 명시해야 한다.

- Exposed DAO `EntityCache`는 트랜잭션 로컬 identity map이며 애플리케이션 캐시가 아니다.
- 이 캐시에는 불변 직렬화 가능 스냅숏만 저장한다.
- 스냅숏 채우기에는 데이터베이스 읽기 전에 캡처한 일회용 miss 토큰과
  `maxAttempts = 1`인 트랜잭션이 필요하다. 재시도는 lookup과 단일 시도 트랜잭션
  전체를 반복한다. 쓰기 경로는 무효화하며 bare-ID 스냅숏 PUT을 발행할 수 없다.
- commit-safe가 DB/캐시 원자성이나 crash durability를 뜻하지 않는다.
- 노드 간 순서를 보장하지 못할 때는 스냅숏 게시보다 무효화가 안전하다.
- 더 강한 보장이 필요하면 commit 후 캐시 실패를 애플리케이션이 직접 복구해야 한다.
- 스냅숏 구성에는 저장소 write mode가 없다.
- Redisson 무효화는 commit 후 완료를 기다리며 트랜잭션을 보유하지 않고 제출한다.
  로컬 drain은 협력형 예산을 사용하며 시작하지 못한 작업이나 overrun을 보고할 수 있다.
- value sizer, weighted Caffeine capacity, staged-weight 상한을 구성하지 않으면 항목
  한도는 heap 한도가 아니다.
- versioned namespace, 혼합 버전 제한, rollout, rollback 순서
- codec/schema versioning, 인코딩 키 바이트 상한, Redis ACL/네트워크 격리, 무효화
  증폭 위험, 주의가 필요한 cleanup API 제한
- client 전체 미완료 청크/바이트 quota, `REJECTED` 포화 동작, 공유 quota 상태 복구,
  exactly-once lease 해제, 제한된 실패 버퍼 drain

stable `docs/manual/{en,ko}` page는 1.11.0에 고정하며 이 feature PR에서는 바꾸지
않는다. manual baseline을 1.12.0으로 올리는 작업은 release gate다. release
checklist는 스냅숏 캐시 안내 쌍을 추가하고 정확한 1.12.0 ref/commit을 설정하며
게시 전에 manual inventory/parity validator를 실행해야 한다.

새 diagram은 필요 없다. 이 기능은 수명 주기/API 계약이므로 간결한 commit/rollback
예제와 동작 표가 더 명확하다. route와 metrics를 추가할 때 운영 topology 문서는
issue #325가 담당한다.

## 호환성과 운영 rollback

- 기존 저장소 interface와 write mode는 바뀌지 않는다.
- 기능은 opt-in이며 기존 호출자는 트랜잭션 buffering을 수행하지 않는다.
- 데이터베이스 schema나 직렬화된 기존 캐시 format을 migration하지 않는다.
- raw DTO 값과 `CacheSnapshot` 값이 섞이지 않도록 새 envelope에는 전용 versioned
  namespace가 필요하다.
- 코드 rollback은 위 namespace 절차를 따른다. traffic을 quiesce하고 보존한 이전
  namespace를 비운 뒤 재검증하며, 모든 노드를 되돌려 데이터베이스에서 다시
  구성되게 한 다음 폐기한 새 namespace를 정리한다. 기존 저장소 캐시는 그대로다.

## 실패 모드

1. **Dirty 스냅숏 노출:** 스테이징만 하고 `afterCommit`에서 적용하여 방지한다.
   rollback 테스트는 변경이 없음을 입증한다.
2. **Callback의 데이터베이스 재작성:** 캐시 전용 SPI와 저장소 writer를 우회하는 직접
   어댑터 테스트로 방지한다.
3. **노드 간 오래된 항목:** commit 시점 무효화를 기본값으로 사용하고 기존 백엔드
   무효화 프로토콜을 재사용하여 완화한다.
4. **Commit 후 캐시 장애:** 데이터베이스는 commit 상태를 유지한다. Redisson 작업은
   기다리지 않고 제출하며 성공하지 못한 결과만 제한된 구조적 버퍼에 제공한다.
   quota 상태가 멈춘 작업을 노출하며 암시적 재시도는 없다.
5. **재시도/재사용 누출:** 트랜잭션 동일성 상태, 약한 참조 terminal guard, 첫 물리적
   경계 뒤 fail-fast 거부로 방지한다.
6. **R2DBC/JDBC pool block:** 모든 Redisson future를 제출하고 callback에서 기다리지
   않는다. 로컬 Caffeine 작업은 협력형 예산 안에서 수행한다.
7. **Savepoint 게시:** null이 아닌 `outerTransaction`을 가진 트랜잭션의 스테이징을
   거부한다. 물리적 commit 뒤에는 root만 게시할 수 있다.
8. **Observer로 인한 거짓 실패:** commit 또는 Redisson callback에서 애플리케이션
   observer를 호출하지 않는다. observer는 명시적 버퍼 drain에서만 실행한다.
9. **Namespace/codec 충돌:** 운영자 소유 versioned 저장소 동일성과 스테이징 전에
   확인하는 비기밀 호환성 fingerprint로 방지한다.
10. **제한 없는 callback 지연:** 원격 장애 대기를 제거한다. 로컬 SPI 작업은
    협력형이며 임의 listener/사용자 코드에서 예산을 초과할 수 있으므로, 강제로
    선점한다고 설명하지 않고 overrun을 보고한다.
11. **Async 장애 누적:** client 전체 청크/바이트 quota는 원래 future가 완료될 때까지
    예약을 유지한다. 포화 시 queued command를 제한 없이 늘리지 않고 새 작업을
    구조적으로 거부한다.
12. **늦은 로컬 스냅숏 부활:** 스트라이프 잠금 아래에서 Caffeine PUT/무효화 순서를
    정하고 충돌을 안전하게 miss로 바꾸는 고정 크기 스트라이프 generation fence로
    방지한다.

## 수용 기준 매핑

- 분산 값에 DAO `Entity` 또는 `EntityCache`가 없음: 분산 API는 라이브러리 소유
  스칼라 정책과 필수 결정적 codec wrapper가 다루는 식별자만 받는다. 로컬
  스냅숏에는 classpath-safe 최상위 Entity 거부, 호출자 확장 가능 값 validator,
  안전한 예제, KDoc을 추가한다.
- Commit 후에만 캐시 갱신: 코어, JDBC, R2DBC commit/rollback 테스트
- Rollback 시 미갱신: interceptor 수명 주기 테스트와 integration 테스트
- 중첩 트랜잭션 안전성: JDBC/R2DBC savepoint 테스트가 child 스테이징을 거부하고
  outer rollback이 child 스냅숏을 노출할 수 없음을 입증한다.
- 오래된 무효화: last-mutation-wins 테스트, 트랜잭션 간 로컬 fence 테스트,
  두 노드 무효화 fake
- write-through/write-behind와 공존: 캐시 전용 SPI는 기존 저장소 `put` 경로나 map
  writer를 호출하지 않는다.
- 로컬 near-cache: JDBC와 R2DBC 모듈의 Caffeine 어댑터
- 분산 조정: 실제 두 client 무효화 테스트를 갖춘 writer 없는 JDBC Redisson
  어댑터. Lettuce는 동일한 비차단 캐시 전용 경계를 입증하는 조건으로 남긴다.
- 제한된 overhead: 양수 항목 수 한도, 선택적 Caffeine 보존 바이트 한도, 제한된
  약한 참조 miss-capability 레지스트리, 실제 인코딩으로 검증한 Redisson
  key/commit/chunk 상한, client 전체 미완료 청크/바이트 quota, 제한된 비차단 실패
  버퍼, 비차단 청크 제출, 고정 크기 로컬 fence, 협력형 local-drain 예산, 결정적
  개수, 확장한 allocation/encoding/장애 benchmark
- API/모듈 경계: 컴파일 테스트가 정확한 JDBC/R2DBC 확장 이름, lookup/miss-token
  스냅숏 채우기, 스냅숏 전용 구성, 공통 캐시 artifact에서 엔진 타입이 누출되지
  않음을 입증한다.
- 문서 구분: 현재는 영문/한국어 README parity와 공개 KDoc을 제공한다. stable
  manual이 1.11.0에 고정되어 있으므로 manual 쌍의 parity는 명시적 1.12.0 release
  gate다.

## 완료 정의

### 독립 검토 결과

모든 수정 뒤 최종 설계를 독립적으로 다시 검토했다. 모든 관점이 남은 지적 사항
없이 수렴했다.

| 관점 | P0 | P1 | P2 | P3 |
|---|---:|---:|---:|---:|
| 안정성과 트랜잭션 수명 주기 | 0 | 0 | 0 | 0 |
| 호출자 사용성과 재시도 동작 | 0 | 0 | 0 | 0 |
| 성능과 제한된 리소스 동작 | 0 | 0 | 0 | 0 |
| 공개 API와 모듈 경계 | 0 | 0 | 0 | 0 |
| 보안과 식별자 privacy | 0 | 0 | 0 | 0 |
| 운영자 rollout과 장애 복구 | 0 | 0 | 0 | 0 |

검토에서는 구현 전에 핵심 위험을 해결했다. Exposed callback 상태는 user-data를
지우기 전에 이동하고, 스냅숏 채우기는 미리 읽은 불투명 miss capability와 단일
시도 트랜잭션을 사용한다. Caffeine은 제한된 스트라이프 commit-order fence를
사용한다. Redisson은 표준 Long/UUID 키 인코딩과 exactly-once quota lease를
사용하는 무효화 전용 구현이며 callback에서 기다리지 않는다. 애플리케이션 observer
코드는 명시적으로 제한된 버퍼를 drain할 때만 실행한다. stable 1.11 manual은 1.12
release gate까지 그대로 둔다.

- 공개 API와 수명 주기는 미해결 placeholder나 숨은 Spring 의존성 없이 이 설계와 일치한다.
- 필요한 모든 코어/JDBC/R2DBC 동작에는 failing-first regression 테스트가 있으며 구현 후 통과한다.
- 대상 Gradle 테스트, Detekt, `git diff --check`가 통과한다.
- 영문/한국어 문서는 동등하며 실제 API 이름을 사용한다.
- PR 전 검토와 PR 검토는 P0=0, P1=0으로 수렴한다.
- PR 전달은 새롭고 명시적인 merge 승인이 있을 때까지 merge-ready 상태에서 멈춘다.
