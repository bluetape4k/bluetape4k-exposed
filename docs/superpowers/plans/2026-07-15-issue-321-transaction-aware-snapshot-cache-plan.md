# 이슈 #321 트랜잭션 인식 스냅숏 Near Cache 구현 계획

> **에이전트 작업자용:** 필수 하위 스킬: superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans를 사용해 이 계획을 작업별로 구현한다. 단계 추적에는 체크박스(`- [ ]`) 문법을 사용한다.

**목표:** 활성 루트 Exposed 트랜잭션에서 변경을 스테이징하고, 롤백 시 폐기하며, 커밋 후에만 적용하는 선택형 JDBC 및 R2DBC 스냅숏 Near Cache를 추가한다. 로컬 저장소에는 Caffeine을, JDBC 무효화 어댑터에는 Redisson을 사용한다.

**아키텍처:** 스냅숏 값, 제한, 트랜잭션 조정, 실패 보고, 엔진 중립적인 캐시 전용 SPI를 `exposed/cache`에 둔다. JDBC/R2DBC Caffeine 모듈은 Exposed 트랜잭션 생명주기에 맞춰 동작하며, 불투명한 miss 기능과 스트라이프 방식의 로컬 순서 펜스를 사용한다. JDBC Redisson은 정규 키 인코딩, 제한된 논블로킹 승인, 네임스페이스 호환성 검사, 명시적 복구/관리 API로 무효화만 분산한다. 기존 리포지토리 쓰기 모드는 재사용하지 않으며, 어떤 캐시 콜백도 데이터베이스 쓰기를 수행하지 않는다.

**기술 스택:** Kotlin 2.3+, 중앙 카탈로그에서 제공하는 JetBrains Exposed 1.3.1 API, Caffeine, Redisson, kotlinx-coroutines, JUnit 5, bluetape4k assertions, Testcontainers, JMH/Gradle 벤치마크.

---

## 전달 계약

- 리포지토리: `bluetape4k-exposed`
- 이슈: `#321`
- 기준 브랜치: `develop`
- 헤드 브랜치: `feat/issue-321-transaction-aware-snapshot-cache`
- Pull request: 모든 로컬 게이트와 독립 코드 리뷰를 통과한 뒤 생성한다.
- 병합: 병합 준비가 완료된 정확한 PR을 보고한 뒤 중단하며, 병합 전에 사용자의 새로운 승인을 받는다.
- 범위 제외: Spring Boot 자동 구성, Ktor 상태 확인 경로, 스키마 드리프트 도구, 영속 아웃박스, 직접 `Entity` 캐싱, Lettuce 어댑터는 이 이슈에 포함하지 않는다.
- 의존성 규칙: 프로덕션 의존성을 추가하지 않는다. 기존 모듈 의존성 그래프에는 이 계획에 필요한 Exposed core/JDBC/R2DBC, Caffeine, Redisson, 테스트 픽스처, 벤치마크 의존성이 이미 포함되어 있다. 작업 9에서는 카탈로그에 등록된 `testcontainers-toxiproxy` 의존성을 `testImplementation`에만 추가한다. `bluetape4k-testcontainers`는 `ToxiproxyServer`를 공개 하위 타입으로 노출하지만, 구현 의존성만으로는 Toxiproxy 컨테이너/클라이언트 API가 소비자의 테스트 컴파일 클래스패스에 포함되지 않는다. 직접 테스트 의존성은 피어 전용 연결 해제 픽스처에 필요하며, 게시된 런타임에는 영향을 주지 않는다.
- 매뉴얼 규칙: 모듈 README와 공개 KDoc은 갱신하되, 1.11.0에 고정된 안정 버전 `docs/manual/{en,ko}` 콘텐츠는 변경하지 않는다.

## 인수 조건 매핑

| ID | 인수 조건 | 구현 작업 | 증명 |
|---|---|---|---|
| AC-1 | 롤백은 스테이징된 스냅숏을 절대 노출하지 않는다 | 3, 4, 5, 6 | 코디네이터 및 어댑터 롤백 테스트 |
| AC-2 | 공개 API는 Exposed DAO `Entity` 직접 값을 거부한다 | 1 | 런타임 거부 및 컴파일 대상 API 테스트 |
| AC-3 | 캐시 콜백은 데이터베이스 쓰기를 반복할 수 없다 | 2, 4, 6 | 캐시 전용 SPI 형태 및 트랜잭션 통합 테스트 |
| AC-4 | 반복 변경은 결정론적으로 마지막 변경이 우선한다 | 3 | 교체/순서/제한 테스트 |
| AC-5 | Caffeine은 이전 fill이 더 새로운 무효화를 덮어쓰지 못하게 한다 | 2, 4, 6 | 제어된 동시성 펜스 테스트 |
| AC-6 | Exposed 재시도 시 오래된 miss 기능을 재사용할 수 없다 | 2, 4, 6 | `maxAttempts` 거부 및 외부 재시도 테스트 |
| AC-7 | Redisson은 Redis 스냅숏 읽기/쓰기 없이 무효화를 분산한다 | 7, 8, 9 | spy/계약 및 두 클라이언트 Testcontainers 테스트 |
| AC-8 | 분산 승인과 실패는 제한되고 논블로킹이어야 한다 | 8 | 할당량, 영원히 완료되지 않는 future, 버퍼 drain 테스트 |
| AC-9 | 네임스페이스/스키마/키 인코딩 비호환성은 사용 전에 실패한다 | 7, 9 | 골든 벡터 및 마커 불일치 테스트 |
| AC-10 | 공개 API와 이중 언어 문서를 완성한다 | 10 | KDoc 컴파일 및 README 동등성 리뷰 |
| AC-11 | 기존 벤치마크 모듈에서 새 경로를 측정할 수 있다 | 11 | 벤치마크 클래스 컴파일 및 제한된 스모크 실행 |

## 위험 예측 및 재실행 조건

| 위험 | 예방 설계 | 재실행 조건 |
|---|---|---|
| Exposed가 `afterCommit` 전에 트랜잭션 사용자 데이터를 지움 | `beforeCommit`에서 활성 버퍼를 인터셉터 소유의 대기 상태로 이동 | Exposed/카탈로그 업그레이드 또는 콜백 순서 변경 |
| 다른 인터셉터가 이 어댑터의 `afterCommit` 전에 예외를 던짐 | 강한 트랜잭션 참조를 보관하지 않음. 오래된 캐시는 안전하며 대기 상태는 GC로 회수 가능 | 인터셉터 등록/순서 변경 |
| 이전 DB fill이 더 새로운 무효화를 덮어씀 | 조회 시 identity generation token을 캡처하고 단일 스트라이프 잠금에서 검증 및 변경 | 펜스 또는 Caffeine 구현 변경 |
| Exposed 자동 재시도가 시도별 로컬 상태를 재사용함 | `maxAttempts != 1`을 거부하고 전체 조회/트랜잭션 주기 외부의 재시도를 문서화 | 트랜잭션 브리지 변경 |
| 교체 또는 여러 저장소로 스테이징 제한을 우회함 | 트랜잭션 전체 엔트리/저장소 최솟값과 교체 가중치 차이를 원자적으로 적용 | 코디네이터/저장소 등록 변경 |
| Redisson future 또는 동기 제출이 할당량을 누수함 | future 생성 전 실패 또는 완료 콜백에서 lease를 정확히 한 번 해제 | 할당량/제출 리팩터링 |
| Redis 키 코덱 드리프트가 노드 간 비호환성을 일으킴 | 정규 Long/UUID 바이트와 원격 마커 fingerprint 사용 | 코덱, 스키마 또는 Redisson 업그레이드 |
| 이벤트 루프/스레드 블로킹 | 무효화 제출에서 `await`, `get`, 스케줄러, 실행기, 작업자 스레드를 사용하지 않음 | Redisson 어댑터 변경 |
| Testcontainers 불안정성이 회귀를 숨김 | Redis 통합 테스트를 순차 실행하고 단위 수준의 결정론적 증명을 유지 | Docker/Redis/Redisson 업그레이드 |
| 안정 버전 매뉴얼이 실수로 미출시 API를 가리킴 | 매뉴얼 소스를 변경하지 않고 고정 인벤토리를 검증 | docs/manual 또는 manifest 변경 |

## 리포지토리 위험 요소 점검

- 모듈 등록: 해당 없음. 모든 구현은 이미 등록된 모듈에 추가하며, `settings.gradle.kts`는 변경하지 않는다.
- 생성 카탈로그/검사기: 해당 없음. 아티팩트나 모듈을 추가하거나 이동하지 않는다.
- 광범위한 백엔드 매트릭스: Redisson 통합 테스트는 순차 실행한다. Caffeine 테스트는 로컬/인메모리로 유지한다.
- Lettuce: 의도적으로 제외한다. 기존 JDBC Lettuce 접근은 동기 방식이고 기존 suspend/R2DBC 접근은 future를 기다린다. 이 설계에 필요한 생명주기를 갖춘 재사용 가능한 논블로킹 피어 무효화 프로토콜은 없다. 이를 추가하려면 별도의 분산 백엔드 기능으로 다뤄야 한다.
- 벤치마크: `benchmark/exposed-benchmark`를 확장하며 다른 프로젝트를 등록하지 않는다.

## 작업 1: 불변 스냅숏 값, 검증 및 구성 추가

**파일:**
- 생성: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot/CacheSnapshot.kt`
- 생성: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotCacheConfig.kt`
- 생성: `exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/snapshot/CacheSnapshotTest.kt`
- 생성: `exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotCacheConfigTest.kt`
- 생성: `exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/snapshot/CacheSnapshotDaoFreeClasspathTest.kt`

- [x] 직렬화 가능한 불변 DTO envelope, 선택적 revision, 스키마 거부, 양수 제한/기간, sizer 요구 사항, 페이로드 validator 동작, 최상위 `Entity` 거부에 대한 실패 테스트를 작성한다. 네임스페이스 문법 `[a-z][a-z0-9._-]{0,62}:v[1-9][0-9]*`를 강제한다. 이를 어휘적으로 추론하려 하지 말고 운영자 소유의 정적 이름이어야 하며 tenant, request, entity 식별자여서는 안 된다고 문서화한다. `fenceStripes`는 정확히 64..65,536 범위의 2의 거듭제곱이어야 한다.
- [x] `./gradlew :bluetape4k-exposed-cache:test --tests '*CacheSnapshotTest' --tests '*SnapshotCacheConfigTest'`를 실행하고 API가 없어서 테스트가 실패하는지 확인한다.
- [x] 모든 공개 선언에 영문 KDoc을 포함해 다음 공개 표면을 구현한다.

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

- [x] 클래스패스에 안전한 정확한 `rejectDirectEntitySnapshotValues()` 기본 validator와 선택형 페이로드 거부용 `maximumEstimatedPayloadBytes(sizer, limit)`를 제공한다. DAO 기반 클래스가 있을 때만 이름으로 해석하고 정적 DAO 타입 참조 없이 할당 가능성을 사용한다. 객체 그래프를 재귀적으로 리플렉션하지 않는다.
- [x] Exposed DAO 없이 하위 classloader/process를 실행하고 validator 생성/DTO 검증이 `NoClassDefFoundError`를 던지지 않음을 증명하는 `CacheSnapshotDaoFreeClasspathTest.kt`를 추가한다.
- [x] 대상 테스트를 다시 실행해 통과하는지 확인한다.
- [x] Lore 트레일러가 포함된 커밋을 생성한다.

```text
Define detached snapshot values before transaction integration

Constraint: Snapshot values must not retain Exposed Entity state
Rejected: Reflection-based deep immutability validation | it is incomplete and expensive
Confidence: high
Scope-risk: narrow
Tested: exposed cache snapshot value and configuration tests
```

## 작업 2: 캐시 전용 SPI, 불투명한 miss 기능 및 로컬 순서 펜스 추가

**파일:**
- 생성: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotCacheStore.kt`
- 생성: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotLocalFenceRegistry.kt`
- 생성: `exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotCacheStoreTest.kt`
- 생성: `exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotLocalFenceRegistryTest.kt`

- [x] lookup이 snapshot/miss 중 정확히 하나만 반환하고, miss 객체가 ID/fence를 노출하지 않으며 직렬화할 수 없고, 고정된 `toString`을 가지며 두 번 claim할 수 없음을 증명하는 실패 테스트를 작성한다.
- [x] latch/barrier를 사용한 동시성 테스트를 추가한다. lookup miss -> 동시 무효화 -> 이전 fill은 거부되어야 한다. 관련 없는 스트라이프 작업은 계속 진행하며, 의도적인 스트라이프 충돌은 안전한 fill을 거부할 수 있지만 오래된 데이터는 절대 허용하지 않는다.
- [x] `./gradlew :bluetape4k-exposed-cache:test --tests '*SnapshotCacheStoreTest' --tests '*SnapshotLocalFenceRegistryTest'`를 실행하고 RED를 확인한다.
- [x] 엔진 중립적 표면을 구현한다.

```kotlin
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

@InternalSnapshotCacheApi
fun interface ClaimedSnapshotMiss<ID : Any, V : Serializable> {
    fun prepare(snapshot: CacheSnapshot<V>): SnapshotCacheMutation.Put<ID, V>
}

@InternalSnapshotCacheApi
interface SnapshotCacheStore<ID : Any, V : Serializable> {
    val storeId: SnapshotStoreId
    val storeInstanceToken: Any
    val compatibilityFingerprint: String
    val limits: SnapshotCacheLimits
    @InternalSnapshotCacheApi
    fun claimMiss(miss: SnapshotCacheMiss<ID, V>): ClaimedSnapshotMiss<ID, V>
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
interface AsyncSnapshotInvalidationStore<ID : Any> {
    val storeId: SnapshotStoreId
    val storeInstanceToken: Any
    val compatibilityFingerprint: String
    val limits: SnapshotCacheLimits
    fun measure(id: ID): MeasuredInvalidation<ID>
    fun submitInvalidation(
        batch: List<MeasuredInvalidation<ID>>,
    ): CompletionStage<SnapshotCacheApplyReport>
}

interface SnapshotCacheDeadline {
    fun remaining(): Duration
    val isExpired: Boolean
}

@InternalSnapshotCacheApi
class SnapshotLocalFence<ID : Any> internal constructor()
```

- [x] ID에 결합된 `SnapshotLocalFence`를 internal 생성자가 있는 불투명한 일반 클래스로 유지하며, token getter, copy, component, 직렬화, 구조적 동등성 표면을 제공하지 않는다. 소유 registry만 이를 캡처하고 검증한다. 이를 운반하는 변경 필드는 `@InternalSnapshotCacheApi` 뒤에 유지하고, bare-ID 공개 `put` 메서드는 노출하지 않는다.
- [x] 명시적 잠금으로 보호되고 `maxOutstandingMissTokens`로 제한되는 weak-identity miss registry를 구현한다. 오래된 weak entry를 제거한 뒤 registry가 가득 차면 트랜잭션이나 데이터베이스 읽기 전에 `lookup`을 거부한다. mapper/preparer 작업 전에 token을 제거하고 claim된 preparer를 일회용으로 유지하여 mapper 실패 시 새 lookup이 필요하게 한다.
- [x] identity generation token을 사용하는 고정된 2의 거듭제곱 크기의 명시적 잠금 스트라이프 registry를 구현한다. 동일한 잠금 아래에서 token을 교체하고 캐시를 변경한다.
- [x] 승인된 설계의 `SnapshotStoreId`, `SnapshotCacheLimits`, 측정된 무효화, 변경, deadline, operation/outcome, 보고 모델을 추가한다.
- [x] 엔트리별 SPI 호출 대신 bulk apply를 테스트한다. 각 저장소는 단계마다 최대 한 번 호출하고, 모든 단계 입력은 overflow에 안전한 `Long` 누적을 사용하는 `SnapshotCacheApplyReport.requireReconciled(operation, expectedCount)`를 통과하며 success/failure/rejected/not-attempted 개수와 정확히 일치해야 한다. 공유 단조 deadline은 엔트리 사이에서 만료될 수 있다.
- [x] 제어된 경합 100회 반복을 포함해 대상 테스트를 다시 실행한다.
- [x] Lore 트레일러가 포함된 커밋을 생성한다.

```text
Make stale snapshot fills unrepresentable at the cache boundary

Constraint: A lookup capability is valid only for the observed local generation
Rejected: Public put by identifier | it permits stale read-fill races
Confidence: high
Scope-risk: moderate
Tested: opaque miss, bounded registry, and striped fence concurrency tests
```

## 작업 3: 실패 보고 및 트랜잭션 전체 스테이징 코디네이터 추가

**파일:**
- 생성: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotCacheFailure.kt`
- 생성: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotTransactionCoordinator.kt`
- 수정: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotCacheStore.kt`
- 생성: `exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotCacheFailureTest.kt`
- 생성: `exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotTransactionCoordinatorTest.kt`
- 생성: `exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotCacheApiContractTest.kt`
- 생성: `exposed/jdbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/jdbc/caffeine/snapshot/SnapshotCacheCommonApiCompileTest.kt`

- [x] 루트/현재 트랜잭션 검사, 중첩/캡처/경계 이후 거부, 단일 인터셉터 등록, 마지막 변경 우선 순서, 롤백 폐기, 커밋 전/후 전송, 캐시 작업 전 콜백 정리, 저장소/엔트리/가중치 제한, observer 실패 계산에 대한 실패 상태 머신 테스트를 작성한다.
- [x] 앞선 타사 인터셉터가 이 인터셉터의 `afterCommit` 전에 예외를 던지는 회귀 테스트를 추가한다. 데이터베이스 완료는 독립적으로 유지되고, 캐시 변경은 발생하지 않으며, weak 트랜잭션 엔트리는 회수할 수 있어야 한다.
- [x] 대상 테스트 클래스 두 개를 실행해 RED를 확인한다.
- [x] 클래스패스에 안전한 공통 브리지를 구현한다.

```kotlin
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

@InternalSnapshotCacheApi
fun <TX : Transaction, ID : Any, V : Serializable> stageInvalidationMutation(
    transaction: TX,
    bridge: SnapshotTransactionBridge<TX>,
    store: SnapshotCacheStore<ID, V>,
    id: ID,
)
```

- [x] `@RequiresOptIn(level = RequiresOptIn.Level.ERROR) annotation class InternalSnapshotCacheApi`를 정확히 선언한다. 선택형 `SnapshotCacheLookup.hit/miss` 팩토리를 어댑터에서 사용할 수 있고, 구현 hook에는 명시적 opt-in이 필요하며, local/async 무효화 overload가 모두 해석되고, 공통 공개 시그니처가 `JdbcTransaction`과 `R2dbcTransaction`을 모두 누출하지 않음을 증명하는 모듈 간 컴파일 대상 계약 테스트를 추가한다.

- [x] 하나의 private Exposed 트랜잭션 user-data key 아래에 registry를 저장하고, 명시적 잠금으로 보호되는 페이로드 없는 weak terminal guard에는 상태만 반영하며 강한 트랜잭션 역참조를 두지 않는다. 참여자를 원자적으로 등록하고 참여 저장소 중 가장 엄격한 트랜잭션 전체 제한을 사용한다.
- [x] 논리적 `SnapshotStoreId`가 같은 두 facade는 private instance token이 참조상 동일하고, 호출자가 제공한 failure buffer가 참조상 동일하며, 비밀이 아닌 호환성 fingerprint가 일치하는 경우에만 허용한다. 버퍼 변경 전에 거부한다.
- [x] `beforeCommit`에서 활성 버퍼를 분리해 인터셉터 소유의 대기 상태로 옮긴다. `afterCommit`에서는 캐시 작업을 호출하기 전에 registry/대기 상태를 제거한다. `afterRollback`에서는 캐시 작업 없이 두 상태를 모두 정리한다.
- [x] 예외를 던지지 않는 `beforeRollback`에서 상태를 terminal로 표시하고, 뒤쪽 롤백 인터셉터 때문에 `afterRollback`이 건너뛰어질 수 있기 전에 활성 및 대기 페이로드를 정리한다. `afterRollback`은 방어적이고 멱등인 정리로 만든다.
- [x] 서로 다른 키의 삽입 순서를 보존하면서 같은 `(store identity, id)`의 유효 변경을 교체한다. 버퍼 변경 전에 교체 가중치 차이를 적용한다.
- [x] 정확한 단계로 drain한다. 대기 없이 모든 분산 chunk 승인/제출을 시도하고, 모든 로컬 무효화를 적용한 다음, 모든 로컬 스냅숏 PUT을 적용한다. 가장 작은 로컬 budget에서 도출한 하나의 트랜잭션 전체 단조 deadline을 사용하고, 각 로컬 엔트리 전에 poll하며, 나머지 엔트리를 `NOT_ATTEMPTED`로 표시한다. 강한 선점이라고 주장하지 말고 협력적 초과를 보고한다.
- [x] 커밋 후 `CancellationException`을 포함한 일반적인 엔트리별 `Exception`을 격리하고 관련 없는 엔트리는 계속 처리한다. 치명적인 JVM `Error`를 캐시 상태 이벤트로 변환하지 않는다. 최종 보고서에서 모든 단계 입력을 정확히 reconcile한다.
- [x] 제한되고 정제된 실패 레코드와 구조화된 drain 결과를 구현한다. 예외 타입과 구조적 개수만 유지하며 메시지, 원인, suppressed exception, stack trace, 값, 식별자, 자격 증명, SQL, URL, endpoint, 직렬화된 스냅숏은 절대 보관하지 않는다. 악성, Unicode, 과대, bidi-control, identifier-ignorable 예외 픽스처를 추가한다. Observer 콜백은 명시적인 호출자 스레드 drain 중에만 실행하며, 예외를 던진 observer는 해당 이벤트를 소비하고 `observerFailureCount`를 증가시킨다.
- [x] `loggingSnapshotCacheFailureObserver()`를 구현하고 테스트한다. 정제된 실패 객체/타입만 로그로 남긴다. `storeId.namespace`만 정적인 저카디널리티 tag 후보이고 `affectedCount`는 tag가 아닌 측정값임을 문서화한다.
- [x] 다음과 같은 정확한 공개 실패 API를 구현하고 `poll`, 기본/제한 `drainTo`, logging observer, 호출자가 제공한 buffer identity의 소스 사용을 컴파일한다.

```kotlin
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
- [x] 대상 테스트와 전체 `:bluetape4k-exposed-cache:test` 작업을 다시 실행한다.
- [x] Lore 트레일러가 포함된 커밋을 생성한다.

```text
Bind snapshot mutation visibility to one Exposed commit boundary

Constraint: Cache failure after commit must not affect database outcome
Rejected: Immediate cache mutation with rollback repair | readers could observe dirty state
Confidence: high
Scope-risk: moderate
Tested: coordinator lifecycle, limits, ordering, cleanup, and failure tests
```

완료 증거: 코디네이터/실패 집중 테스트 28/28, 전체
`:bluetape4k-exposed-cache:test` 141/141, `jdbc-caffeine` 모듈 간
API 계약 2/2가 통과했다. 독립 명세 및 코드 품질 리뷰에서 남은
Critical, Important, Minor 발견 사항이 없다고 보고했다. 루트 `detekt`는
`NO-SOURCE`로 성공했으며, 캐시 모듈에는 모듈별 detekt 작업이 없다.

## 작업 4: JDBC Caffeine facade 및 트랜잭션 확장 구현

**파일:**
- 수정: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotCacheStore.kt`
- 수정: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotLocalFenceRegistry.kt`
- 생성: `exposed/jdbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/jdbc/caffeine/snapshot/JdbcCaffeineSnapshotCache.kt`
- 생성: `exposed/jdbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/jdbc/caffeine/snapshot/JdbcSnapshotTransaction.kt`
- 생성: `exposed/jdbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/jdbc/caffeine/snapshot/JdbcCaffeineSnapshotCacheTest.kt`
- 생성: `exposed/jdbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/jdbc/caffeine/snapshot/JdbcSnapshotTransactionTest.kt`
- 생성: `exposed/jdbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/jdbc/caffeine/snapshot/JdbcSnapshotCacheApiUsageTest.kt`
- 수정: `exposed/jdbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/jdbc/caffeine/snapshot/SnapshotCacheCommonApiCompileTest.kt`

- [x] 팩토리 구성, hit/miss, 불투명 claim, 가중/비가중 생성, 커밋 PUT, 커밋 무효화, 롤백 폐기, 현재 루트 트랜잭션 내부 mapper 실행, 캡처된 트랜잭션 거부, 스냅숏 fill에만 적용되는 `maxAttempts > 1` 거부의 실패 테스트를 작성한다. 무효화는 시도별 로컬 상태로 유지되며 Exposed 재시도 구성에서도 허용됨을 증명한다.
- [x] SQL 쓰기 횟수를 세고 캐시 커밋 콜백이 추가 데이터베이스 쓰기를 전혀 수행하지 않음을 증명하는 H2 테스트를 추가한다.
- [x] 공개 facade 경계에 결정론적 stale-fill 경합 테스트를 추가한다.
- [x] 실제 엔진 생명주기 테스트를 추가한다. 앞에서 예외를 던지는 `afterCommit`, `beforeRollback`, `afterRollback` `StatementInterceptor` 콜백, 콜백 시점 스테이징, commit-then-stage, rollback-then-stage, 인터셉터 비누적, 중첩 savepoint 커밋 후 외부 롤백, 중첩 롤백 후 외부 커밋을 다룬다. 앞선 콜백 실패 시 캐시 변경은 0이어야 하고 페이로드는 트랜잭션 GC까지만 유지해야 한다. 모든 잘못된 receiver는 mapping 또는 버퍼 변경 전에 실패해야 한다.
- [x] 실패한 무효화 시도는 아무것도 누출하지 않고 재시도에 성공한 시도는 정확히 한 번 게시함을 증명하는 재시도 테스트를 추가한다. 외부 스냅숏 fill 재시도는 데이터베이스 읽기마다 새 miss를 다시 획득해야 한다.
- [x] 공개 facade에서 용량/오류 시점을 증명한다. 보관된 miss token이 registry를 채우면 다음 `lookup`은 SQL 카운터가 바뀌기 전에 실패하고, mapper 실패는 token을 소비하므로 재사용 시 두 번째 mapping 호출 전에 실패해야 한다.
- [x] `./gradlew :bluetape4k-exposed-jdbc-caffeine:test --tests '*Jdbc*CaffeineSnapshotCacheTest' --tests '*JdbcSnapshotTransactionTest'`를 실행해 RED를 확인한다.
- [x] 정확한 팩토리와 트랜잭션 확장을 구현한다.

```kotlin
fun <ID : Any, V : Serializable> jdbcCaffeineSnapshotCache(
    idType: KClass<ID>,
    valueType: KClass<V>,
    config: CaffeineSnapshotCacheConfig,
    valueSizer: SnapshotValueSizer<V>? = null,
    validator: CacheSnapshotValueValidator<V> = rejectDirectEntitySnapshotValues(),
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): JdbcCaffeineSnapshotCache<ID, V>

inline fun <reified ID : Any, reified V : Serializable> jdbcCaffeineSnapshotCache(
    config: CaffeineSnapshotCacheConfig,
    valueSizer: SnapshotValueSizer<V>? = null,
    validator: CacheSnapshotValueValidator<V> = rejectDirectEntitySnapshotValues(),
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): JdbcCaffeineSnapshotCache<ID, V> =
    jdbcCaffeineSnapshotCache(ID::class, V::class, config, valueSizer, validator, failureBuffer)

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

- [x] facade 생성자는 internal로 유지한다. `storeId`, 호출자가 제공한 정확한 `failureBuffer` 인스턴스, `lookup(id): SnapshotCacheLookup<ID, V>`만 노출한다. 명시적 token 및 reified 팩토리가 `@PublishedApi` 생성자 접근 없이 제공된 버퍼 identity를 유지함을 증명한다.
- [x] `JdbcSnapshotCacheApiUsageTest`에서 README와 동등한 JDBC 사용 코드를 컴파일한다. R2DBC 엔진 타입이 이 모듈로 누출되지 않도록 정확한 receiver/signature 표면을 리플렉션으로 단언한다.
- [x] `CaffeineSnapshotCacheConfig`에서 Caffeine 가중치/만료를 정확히 구성한다. 음수가 아닌 정확한 `SnapshotValueSizer` 추정치를 Caffeine에 전달하고, 합성 가중치 부풀리기 없이 maintenance 후 `maximumSize`를 독립적으로 강제한다. 선택 설정을 조용히 무시하지 않는다.
- [x] 현재 루트 `JdbcTransaction`을 요구하고, 스냅숏 fill용 miss를 소비할 때만 `maxAttempts == 1`을 요구한다. 공통 코디네이터를 통해 핵심 `StatementInterceptor` 하나를 등록한다.
- [x] 캐시 콜백은 캐시 전용으로 유지하고 `localDrainBudget`로 협력적으로 제한한다. 완료된 작업이 deadline을 넘으면 정상적인 1개 결과 뒤에 `OVERRUN(0)`을 기록하고 나머지 엔트리를 `NOT_ATTEMPTED`로 표시한다. 강한 지연 시간 한계를 주장하거나 완료된 트랜잭션에 예외를 던지지 않으면서 보고 개수 reconcile을 유지한다.
- [x] 모듈 테스트를 다시 실행해 기존 테스트가 모두 계속 통과하는지 확인한다.
- [x] Lore 트레일러가 포함된 커밋을 생성한다.

```text
Expose commit-safe JDBC Caffeine snapshot caching

Constraint: Automatic Exposed retries cannot reuse attempt-local miss capabilities
Rejected: Reusing repository put methods | they can persist to the database again
Confidence: high
Scope-risk: moderate
Tested: JDBC Caffeine unit, transaction, H2, and concurrency tests
```

완료 증거: cache 141/141, JDBC Caffeine 364개가 통과했고 기존 환경 게이트
테스트 22개가 건너뛰어졌다. 리포지토리의 Ryuk 비활성화 테스트 계약에서
실패/오류는 0이었다. 제어된 가중 용량 및 stale-fill 경합은 각각 100회
반복을 통과했다. 독립 명세 및 코드 품질 리뷰에서 남은 Critical, Important,
Minor 발견 사항이 없다고 보고했다. 루트 `detekt`는 `NO-SOURCE`로 성공했으며,
모듈에는 모듈별 detekt 작업이 없다.

## 작업 5: JDBC 수직 슬라이스를 복제하기 전에 리뷰

**파일:**
- 발견 사항을 수정해야 할 때만 작업 1~4의 파일을 수정한다.

- [x] `./gradlew :bluetape4k-exposed-cache:test :bluetape4k-exposed-jdbc-caffeine:test --no-daemon`을 실행한다.
- [x] 공개 API에서 의도하지 않은 ID/fence 노출, 직접 PUT, 강한 트랜잭션 참조, 콜백의 데이터베이스 접근, 블로킹 primitive, 누락된 KDoc을 검사한다.
- [x] 변경한 모듈에서 `git diff --check`와 Kotlin 진단/컴파일 패스를 실행한다.
- [x] 진행하기 전에 모든 P0/P1 발견 사항을 수정한다. 구체적인 연기 이슈를 만들지 않는 한 P2/P3 발견 사항도 수정한다.
- [x] 리뷰로 코드가 변경된 경우에만 의도 우선 Lore 메시지로 커밋한다.

완료 증거: 설계 SPI를 검증된 구현과 맞춘 뒤 독립 수직 리뷰에서
P0=P1=P2=P3=0을 보고했다. 정확한 두 모듈 게이트에서 cache 141/141,
JDBC Caffeine 364개가 통과했고 기존 환경 게이트 22개가 건너뛰어졌다.
강제 Kotlin main/test 컴파일은 경고나 오류 없이 통과했다. 기존의 스냅숏과
무관한 H2 타이밍 단언 하나가 강제 전체 재실행 중 간헐적으로 실패했으나
격리 재실행에서는 즉시 통과했다.

## 작업 6: R2DBC Caffeine facade 및 트랜잭션 확장 구현

**파일:**
- 생성: `exposed/r2dbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/r2dbc/caffeine/snapshot/R2dbcCaffeineSnapshotCache.kt`
- 생성: `exposed/r2dbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/r2dbc/caffeine/snapshot/R2dbcSnapshotTransaction.kt`
- 생성: `exposed/r2dbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/r2dbc/caffeine/snapshot/R2dbcCaffeineSnapshotCacheTest.kt`
- 생성: `exposed/r2dbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/r2dbc/caffeine/snapshot/R2dbcSnapshotTransactionTest.kt`
- 생성: `exposed/r2dbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/r2dbc/caffeine/snapshot/R2dbcSnapshotCacheApiUsageTest.kt`

- [x] 먼저 JDBC 계약 테스트를 포팅하되 트랜잭션 엔진만 교체하고 동일한 불투명 miss/fence/coordinator 단언을 유지한다.
- [x] H2 R2DBC 커밋/롤백 테스트와 커밋 후 캐시 작업이 캐시 전용임을 증명하는 SQL 쓰기 카운터를 추가한다.
- [x] 작업 4의 실제 엔진 생명주기 사례를 R2DBC에서 반복한다. 앞에서 예외를 던지는 `afterCommit`, `beforeRollback`, `afterRollback` 콜백, 중첩, 콜백 시점 스테이징, 인터셉터 순서/비누적, 무효화 재시도, 새로운 miss를 쓰는 외부 fill 재시도를 포함한다. 앞선 콜백이 우리 콜백을 건너뛰게 하면 캐시 변경은 0이고 보관 기간은 트랜잭션 GC로 제한됨을 단언한다.
- [x] 공개 용량/오류 시점 테스트를 반복한다. registry가 가득 차면 R2DBC 작업 전 lookup에서 실패하고 mapper 실패는 token을 소비해야 한다.
- [x] 조건부 unknown-physical-commit/cancellation 증명 seam을 추가한다. Exposed가 주입 가능한 commit seam을 제공하지 않으면 `afterCommit`/캐시 이벤트가 없음을 보여주는 소스/bytecode 증거와 집중 계약 테스트를 확보하고, 결과를 rollback이라고 표시하지 않는다.
- [x] 대상 R2DBC 테스트 두 개를 실행해 RED를 확인한다.
- [x] 다음을 구현한다.

```kotlin
fun <ID : Any, V : Serializable> r2dbcCaffeineSnapshotCache(
    idType: KClass<ID>,
    valueType: KClass<V>,
    config: CaffeineSnapshotCacheConfig,
    valueSizer: SnapshotValueSizer<V>? = null,
    validator: CacheSnapshotValueValidator<V> = rejectDirectEntitySnapshotValues(),
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): R2dbcCaffeineSnapshotCache<ID, V>

inline fun <reified ID : Any, reified V : Serializable> r2dbcCaffeineSnapshotCache(
    config: CaffeineSnapshotCacheConfig,
    valueSizer: SnapshotValueSizer<V>? = null,
    validator: CacheSnapshotValueValidator<V> = rejectDirectEntitySnapshotValues(),
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): R2dbcCaffeineSnapshotCache<ID, V> =
    r2dbcCaffeineSnapshotCache(ID::class, V::class, config, valueSizer, validator, failureBuffer)

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

- [x] 생성자를 internal로 유지하고, 명시적 token 및 reified 팩토리가 호출자 제공 failure-buffer identity를 보존함을 증명하며, `R2dbcSnapshotCacheApiUsageTest`에서 README와 동등한 사용 코드를 컴파일한다. JDBC 엔진 타입이 이 모듈로 누출되지 않음을 단언한다.
- [x] 동일한 공통 코디네이터와 캐시 전용 구현을 사용하고 `runBlocking`, 스케줄러, 작업자 스레드를 도입하지 않는다.
- [x] `./gradlew :bluetape4k-exposed-r2dbc-caffeine:test --no-daemon`을 실행하고 API 동작을 JDBC 계약과 비교한다.
- [x] Lore 트레일러가 포함된 커밋을 생성한다.

```text
Keep R2DBC snapshot visibility aligned with JDBC commits

Constraint: R2DBC cache callbacks must remain non-suspending and cache-only
Rejected: Awaiting cache work after commit | it couples database completion to cache health
Confidence: high
Scope-risk: moderate
Tested: R2DBC Caffeine unit, transaction, H2, and concurrency tests
```

완료 증거: 대상 R2DBC 계약 40/40, cache 141/141, 전체 R2DBC Caffeine
106개가 통과했고 기존 pending 테스트 하나가 남았다. Main/test Kotlin
컴파일은 경고나 오류 없이 완료됐다. 공개 주입 commit seam은
`BEFORE_COMMIT -> PHYSICAL_COMMIT_STARTED`, `AFTER_COMMIT` 없음, 호출자
cancellation 전파, 변경되지 않은 cache/failure 상태를 증명하며 결과를
rollback이라고 부르지 않는다. 제어된 경합과 모든 coroutine 대기는 제한된다.
독립 명세 및 코드 품질 리뷰에서 남은 Critical, Important, Minor 발견 사항이
없다고 보고했다.

## 작업 7: 정규 Redisson 식별자, 코덱, 구성 및 네임스페이스 fingerprint 정의

**파일:**
- 생성: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot/SnapshotIdentifierPolicy.kt`
- 생성: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot/SnapshotRedissonCodec.kt`
- 생성: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot/JdbcRedissonSnapshotInvalidatorConfig.kt`
- 생성: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot/SnapshotNamespaceFingerprint.kt`
- 수정: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/repository/ExposedRedissonCodecSafety.kt`
- 생성: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/snapshot/SnapshotRedissonCodecTest.kt`
- 생성: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/snapshot/SnapshotNamespaceFingerprintTest.kt`
- 수정: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/repository/RedissonRepositoryCodecSafetyTest.kt`
- 생성: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/snapshot/SnapshotRedissonApiUsageTest.kt`

- [x] 실패하는 골든 벡터 테스트를 작성한다. signed Long은 정확히 8개의 big-endian 바이트를 사용하고, UUID는 most-significant bits 다음 least-significant bits 순서의 정확히 16개 big-endian 바이트를 사용한다. String과 지원하지 않는 ID 타입은 거부한다.
- [x] 모든 양수 cap/timeout과 `trustedBinaryCache = false` 기본값에 대한 구성 테스트를 작성한다. 별도의 매개변수화 테스트에서 `SyncStrategy.INVALIDATE`만 허용하고 `UPDATE` 및 다중 노드 `NONE`은 거부한다. `ReconnectionStrategy.CLEAR`만 허용하고 `NONE`/`LOAD`를 포함한 다른 모든 enum 값은 거부한다. 기존 `ExposedRedissonCodecSafety`로 Fory, Kryo, JDK 객체 코덱을 기본 거부하고 명시적 trusted-binary opt-in에서만 허용한다.
- [x] 정규 UTF-8, 줄 구분, 필드명 정렬 allowlist만 다루는 fingerprint 테스트를 작성한다. backend, namespace, key raw class, snapshot raw class, schema version, codec class/version, sync strategy, canonical key-encoding ID를 포함한다. endpoint, username, credential, tuning 값, 임의의 `toString()` 출력이 제외됨을 증명한다.
- [x] 대상 테스트를 실행해 RED를 확인한다.
- [x] 정확한 공개 코덱 API를 구현하고 `[A-Za-z0-9._-]{1,64}`로 `codecVersion`을 검증한다.

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

- [x] `ExposedRedissonCodecSafety`에 직접 `Codec` overload를 추가하고 테스트한다. 기존 리포지토리의 `RedissonCacheConfig.codec`과 invalidator에 동일한 wrapper 인스턴스를 요구한다. `AbstractJdbcRedissonRepository`/`AbstractSuspendedJdbcRedissonRepository`는 이미 map 생성 시 `config.codec`을 전달하므로, 테스트에서 누락을 발견하지 않는 한 클래스를 변경하지 말고 local-cached map이 wrapper를 받음을 증명하는 소스 사용 테스트를 추가한다.
- [x] 모든 delegate codec을 `ExposedRedissonCodecSafety`를 거쳐 처리한다. trusted-binary opt-in은 모든 writer와 payload를 신뢰하는 격리 데이터에만 사용할 수 있다고 문서화한다.
- [x] encoded key/batch/commit cap과 outstanding chunk/byte 제한을 포함해 승인된 그대로 `JdbcRedissonSnapshotInvalidatorConfig`를 구현한다.
- [x] 다음과 같은 정확한 공개 구성 선언과 기본값을 사용한다.

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

- [x] 식별자는 비밀, 자격 증명, PII가 아닌 surrogate key여야 한다고 문서화한다.
- [x] `longSnapshotIdentifierPolicy`, `uuidSnapshotIdentifierPolicy`, `snapshotRedissonCodec` 소스 사용을 컴파일한다. String 식별자 policy/factory가 없다는 API 계약 단언을 포함한다.
- [x] 대상 테스트를 다시 실행한다.
- [x] Lore 트레일러가 포함된 커밋을 생성한다.

```text
Pin distributed invalidation to canonical identifier bytes

Constraint: Every node must derive identical Redis key material and namespace metadata
Rejected: String identifiers | they make normalization and sensitive-key leakage ambiguous
Confidence: high
Scope-risk: moderate
Tested: Redisson codec golden vectors, configuration, and fingerprint tests
```

작업 7 증거: 커밋 `2382cc3`, `f89141a`, `c3c7afd`, `bd50cc8`은 정확한
3-인자 공개 코덱 팩토리, 정규 Long/UUID 키 바이트, 결정론적 allowlist 기반
fingerprinting, 소비자 소유 binary-codec 신뢰를 구현한다. Redisson 4.6.1의
리포지토리 관련 delegate wrapper는 identity-cycle 및 64-node 제한으로
순회한다. 지원 wrapper 검사 드리프트는 fail closed하고 리뷰된 미확인 코덱은
계속 사용할 수 있다. 대상 작업 7 테스트는 78/78, 전체 JDBC Redisson 모듈은
기존 skip 하나를 포함해 523개 테스트를 통과했다. main/test 컴파일과 루트
detekt가 통과했고 독립 명세 및 품질 리뷰는 P0=0, P1=0, P2=0, P3=0을 보고했다.

## 작업 8: 제한된 논블로킹 Redisson 무효화 및 실패 처리 구현

**파일:**
- 생성: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot/RedissonInvalidationQuotaRegistry.kt`
- 생성: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot/JdbcRedissonSnapshotInvalidator.kt`
- 생성: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot/JdbcRedissonSnapshotTransaction.kt`
- 생성: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/snapshot/RedissonInvalidationQuotaRegistryTest.kt`
- 생성: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/snapshot/JdbcRedissonSnapshotInvalidatorTest.kt`
- 생성: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/snapshot/JdbcRedissonSnapshotTransactionTest.kt`

- [x] 스테이징 시 정확한 encoded-byte 측정, 제출 시 정규 re-encode/hash 검증, batch/commit cap, 로컬 작업 전 모든 chunk의 승인/제출 시도, 고정된 첫 클라이언트 할당량 구성, 불일치 팩토리 거부, 무효화 전용 Redis 명령에 대한 실패 테스트를 작성한다. 총 chunk보다 할당량이 작더라도 거부된 chunk와 초기 동기 실패가 뒤쪽 저장소나 로컬 단계를 막아서는 안 된다.
- [x] future 생성 전 동기 실패, 정상 완료, 예외 완료, 중복 완료 알림, 영원히 완료되지 않는 future에 대한 테스트를 추가한다. lease를 정확히 한 번 해제하고 outstanding count/byte가 제한됨을 단언한다.
- [x] failure-buffer 테스트를 추가한다. 성공 완료는 아무것도 기록하지 않고, 성공하지 못한 결과는 제한된 논블로킹 offer를 사용하며, 명시적 drain은 호출자 스레드에서 observer를 호출한다. observer 예외는 이벤트를 소비하고 구조화된 drain 결과에 나타난다.
- [x] 대상 테스트를 실행해 RED를 확인한다.
- [x] 정확한 공개 생성/조합 표면을 구현한다.

```kotlin
fun <ID : Any, V : Serializable> jdbcRedissonSnapshotInvalidator(
    redissonClient: RedissonClient,
    codec: SnapshotRedissonCodec<ID>,
    idType: KClass<ID>,
    valueType: KClass<V>,
    config: JdbcRedissonSnapshotInvalidatorConfig,
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): JdbcRedissonSnapshotInvalidator<ID>

inline fun <reified ID : Any, reified V : Serializable> jdbcRedissonSnapshotInvalidator(
    redissonClient: RedissonClient,
    codec: SnapshotRedissonCodec<ID>,
    config: JdbcRedissonSnapshotInvalidatorConfig,
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): JdbcRedissonSnapshotInvalidator<ID> =
    jdbcRedissonSnapshotInvalidator(
        redissonClient,
        codec,
        ID::class,
        V::class,
        config,
        failureBuffer,
    )

data class SnapshotInvalidationQuotaHealth(
    val maxOutstandingChunks: Int,
    val outstandingChunks: Int,
    val maxOutstandingEncodedBytes: Long,
    val outstandingEncodedBytes: Long,
    val rejectedChunks: Long,
    val saturated: Boolean,
)

fun JdbcRedissonSnapshotInvalidator<*>.quotaHealth(): SnapshotInvalidationQuotaHealth

fun <ID : Any> JdbcTransaction.stageInvalidation(
    invalidator: JdbcRedissonSnapshotInvalidator<ID>,
    id: ID,
)
```

- [x] invalidator 생성자는 internal로 유지한다. `storeId`, 호출자가 제공한 동일한 `failureBuffer`, `quotaHealth()`, 트랜잭션 무효화만 노출하고 읽기나 스냅숏 PUT은 노출하지 않는다. 명시적 token 및 reified 사용을 컴파일하고 둘 다 제공된 buffer identity를 보존함을 증명한다.
- [x] 좁은 repository-plus-invalidator 계약 픽스처를 추가한다. 기존 리포지토리 `RedissonCacheConfig`와 invalidator를 동일한 `SnapshotRedissonCodec` 객체, 버전이 있는 map namespace, value-type token, 호출자 소유 `RedissonClient`, 동일한 제공 failure buffer로 생성한다. map 사용 전에 정확한 팩토리가 로컬에서 관찰할 수 있는 모든 불일치, 즉 지원하지 않는 ID/value token, codec safety, 잘못된 구성, 동일 클라이언트 quota-cap 드리프트를 거부한다. 트랜잭션 상태 변경 전에 동일 트랜잭션의 store-token, compatibility-fingerprint, failure-buffer 충돌을 거부한다. 정확한 공개 팩토리는 의도적으로 repository 계약 객체를 받지 않으므로, 트랜잭션 간 repository/invalidator namespace, codec, value-token, schema, 구성 불일치는 부분적인 프로세스 로컬 registry가 아니라 작업 9의 원격 네임스페이스 마커가 변경 승인 전에 거부한다. 예제 애플리케이션 작업은 #326으로 남긴다.
- [x] Redisson 클라이언트별 weak-identity quota registry를 구현한다. 첫 유효 팩토리가 cap을 고정하고 이후 불일치는 facade 생성 전에 실패한다.
- [x] 공개 상태/실패 보고서에 민감한 원시 ID를 보관하지 않고 모든 스테이징 ID를 인코딩하고 측정한다. 제출 전에 bytes/hash를 재인코딩하고 검증한다.
- [x] 모든 chunk를 순서대로 재인코딩하고 승인을 시도하며, 승인된 경우에만 제출하고, 거부/실패를 구조적으로 기록한 뒤 계속한다. 모든 chunk/store가 시도를 받은 뒤에만 로컬 단계를 시작하며 모든 chunk가 승인될 필요는 없다. 완료 콜백을 연결하고 `await`, `get`, `join`, `runBlocking`, cancellation, 실행기, 스케줄러, 작업자 스레드를 절대 호출하지 않는다.
- [x] 동기 제출 실패 시 할당량을 즉시 해제하고 그 외에는 완료 콜백의 `finally`에서 해제한다. 영원히 완료되지 않는 future는 클라이언트 교체 시까지 제한된 lease를 의도적으로 유지한다.
- [x] 정확한 구조적 할당량 상태와 제공된 `failureBuffer`만 노출한다. 호출자는 `failureBuffer.drainTo(observer)`로 drain한다. 모든 상태 카운터 전이와 포화/복구 상태를 테스트한다.
- [x] 대상 테스트와 전체 JDBC Redisson 단위 테스트 모음을 다시 실행한다.
- [x] Lore 트레일러가 포함된 커밋을 생성한다.

```text
Bound distributed invalidation without blocking commit callbacks

Constraint: Post-commit Redis health cannot control the committed database result
Rejected: Awaiting or cancelling Redis futures | both violate callback latency and ownership
Confidence: high
Scope-risk: broad
Tested: quota, submission, completion, failure buffer, and invalidation command tests
```

작업 8 증거: weak client-identity 할당량과 네임스페이스 조합 예약은 map 접근
전에 cap과 정확한 로컬 facade 계약을 고정하고, 클라이언트를 보관하지 않은 채
실패한 생성을 롤백하며, 정확히 일치할 때 하나의 트랜잭션 identity를 공유한다.
스테이징은 정규 바이트를 측정하고 트랜잭션 전체 commit cap을 강제한다. 제출은
재인코딩, 검증, chunk 분할, 승인을 수행하고 로컬 단계 전에 `fastRemoveAsync`만
호출한다. 완료 경로는 lease를 정확히 한 번 해제하고 대기 콜백에 측정 목록이나
식별자를 보관하지 않으며 일반 실패를 구조적으로 유지하고 동기 치명적 `Error`
값을 변경 없이 다시 던진다. 작업 8 집중 테스트 37/37, `exposed/cache` 142/142,
전체 JDBC Redisson 모듈은 기존 skip 하나를 포함해 560개 테스트를 통과했다.
Main/test 컴파일, 루트 detekt, diff/금지 호출 감사, 독립 명세 및 품질 리뷰가
모두 통과했으며 P0=0, P1=0, P2=0, P3=0이었다.

## 작업 9: 네임스페이스 관리, 복구 및 두 클라이언트 Redis 통합 추가

**파일:**
- 수정: `exposed/jdbc-redisson/build.gradle.kts` (테스트 전용 Toxiproxy API 컴파일 의존성)
- 생성: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot/SnapshotNamespaceAdmin.kt`
- 생성: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/snapshot/SnapshotNamespaceAdminTest.kt`
- 생성: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/snapshot/JdbcRedissonSnapshotInvalidatorIntegrationTest.kt`

- [x] 원자적 마커 claim/compare, 캐시 사용 전 불일치 거부, map-before-marker 정리 순서, 제한된 비동기 unlink, ACL 실패 보고, quiescence 요구 사항의 단위 테스트를 작성한다. 정확한 `expectedFingerprint`를 요구한다. marker-absent/map-present는 fail closed하고, map-absent/marker-present는 마커 삭제를 안전하게 재개한다.
- [x] Redisson 클라이언트 두 개를 사용하는 순차 Testcontainers 테스트를 작성한다. 클라이언트 B의 Near Cache를 채우고 클라이언트 A에서 무효화를 커밋한 뒤, B가 오래된 로컬 값을 더 이상 제공하지 않을 때까지 결정론적 timeout 진단이 포함된 제한된 단조 deadline으로 poll한다. 롤백이 무효화를 보내지 않는지도 검증한다.
- [x] 양수 local-cache 크기, `SyncStrategy.INVALIDATE`, `ReconnectionStrategy.CLEAR`가 `RLocalCachedMap`에 전달됨을 증명하는 생성 옵션 테스트를 추가한다. 클라이언트 B의 오래된 로컬 상태를 준비하고, A는 직접 연결된 채 원격 키를 무효화하면서 리포지토리 소유 Toxiproxy로 B만 연결 해제하는 결정론적 피어 전용 재연결 테스트를 추가한다. 캐시 `get` 없이 B transport 재연결과 invalidation-topic 재구독을 관찰하고, 결정론적 timeout 진단이 포함된 제한된 단조 deadline 안에서 재연결 후 첫 `mapB[3]` hit를 정확히 한 번 수행하기 전에 CLEAR가 캐시된 키를 제거했음을 증명한다.
- [x] 네임스페이스 마커 스크립트 timeout 및 연결 실패 테스트를 추가한다. timeout, 연결 실패, 불일치 시 facade 생성은 map 접근, 등록, 변경 전에 fail closed해야 한다.
- [x] 비호환 fingerprint와 영원히 완료되지 않는 future 복구 테스트를 추가한다. 복구는 quiesce하고 기존 클라이언트를 닫으며, 결정론적 timeout 진단이 포함된 하나의 제한된 단조 deadline에서 close/완료 후 기존 할당량이 0임을 증명하고 교체 전에 실패를 drain해야 한다. 이후 새로운 `RedissonClient` identity와 fresh quota registry로 facade를 만들고 닫힌 이전 클라이언트를 재사용할 수 없음을 증명한다. 만료 시 복구는 fail closed한다.
- [x] `./gradlew :bluetape4k-exposed-jdbc-redisson:test --tests '*SnapshotNamespaceAdminTest' --tests '*JdbcRedissonSnapshotInvalidatorIntegrationTest' --no-daemon`을 순차 실행하고 구현 전에 RED를 확인한다.
- [x] 정확하게 보호된 정리 표면과 `@RequiresOptIn(ERROR)` delicate 관리 annotation을 구현한다.

```kotlin
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
annotation class DelicateSnapshotCacheAdminApi

enum class SnapshotNamespaceCleanupOutcome {
    COMPLETED,
    ALREADY_COMPLETE,
    MARKER_RETAINED,
    TIMED_OUT_ACCEPTED_UNKNOWN,
    FAILED,
}

data class SnapshotNamespaceCleanupResult(
    val outcome: SnapshotNamespaceCleanupOutcome,
    val mapAbsent: Boolean,
    val markerPresent: Boolean,
    val exceptionType: String? = null,
)

@DelicateSnapshotCacheAdminApi
fun <ID : Any> clearSnapshotNamespace(
    redissonClient: RedissonClient,
    codec: SnapshotRedissonCodec<ID>,
    namespace: String,
    expectedFingerprint: String,
    timeout: Duration = Duration.ofSeconds(2),
): SnapshotNamespaceCleanupResult

@DelicateSnapshotCacheAdminApi
fun <ID : Any> clearMapRetainingMarker(
    redissonClient: RedissonClient,
    codec: SnapshotRedissonCodec<ID>,
    namespace: String,
    expectedFingerprint: String,
    timeout: Duration = Duration.ofSeconds(2),
): SnapshotNamespaceCleanupResult
```

- [x] delicate opt-in과 모든 정리 결과 outcome의 소스 사용을 컴파일한다. 마커 검증, 비동기 map unlink, 로컬 clear, 부재 검증 전체에 하나의 공유 단조 timeout을 사용한다. 승인된 서버 정리는 취소할 수 없고 재실행은 관찰된 부분 상태에서 재개한다.
- [x] Redisson/map/script 상호 작용 전에 두 관리 helper 모두 네임스페이스를 `[a-z][a-z0-9._-]{0,62}:v[1-9][0-9]*`, `expectedFingerprint`를 소문자 SHA-256 `[0-9a-f]{64}`, timeout을 양수 제한값으로 검증한다. 모든 잘못된 입력에 대해 클라이언트 상호 작용이 0인 테스트를 추가한다.
- [x] 마커보다 map 엔트리를 먼저 삭제하고 부분 상태 매트릭스에서는 fail closed한다. 두 API를 delicate로 표시하고 네임스페이스 전용 Redis ACL 자격 증명, 네트워크 격리, quiescence, request-facing 노출 금지를 문서화한다. fingerprint는 권한 부여가 아니라 실수 방지 장치로 취급한다.
- [x] `namespaceVerificationTimeout` 안에서 원격 네임스페이스 마커 검증을 구현한다. 변경을 승인하기 전에 비호환 네임스페이스 재사용을 거부한다.
- [x] outage/recovery 사례 전에 통합 픽스처가 유한한 Redisson 명령 timeout과 5초 이하 재시도 정책을 사용하는지 검증한다.
- [x] 정확한 v1-to-v2 상태 머신의 구성 계약 테스트를 추가한다. Rollout: v2 reader/writer를 배포하고, v2를 워밍업하거나 자연스럽게 다시 채우며, 모든 노드를 전환하고, 모든 v1 writer를 중지하고, 처리 중 요청을 drain한 뒤 v1 원격 map, 모든 노드의 v1 로컬 view, v1 마커를 정리한다. Rollback: v2 writer를 중지하고 트래픽을 quiesce하며, 마커를 유지/재검증하면서 유지된 v1 원격 map과 모든 노드의 v1 로컬 view를 정리하고, 모든 노드를 빈 v1로 전환하며, 데이터베이스에서 다시 구축하고 읽기를 검증한 뒤에만 v2를 정리한다. 버전 없는 네임스페이스를 공유하는 혼합 버전 노드는 거부한다.
- [x] 대상 통합 테스트를 순차 재실행한 뒤 전체 모듈 테스트 작업을 순차 실행한다.
- [x] Lore 트레일러가 포함된 커밋을 생성한다.

```text
Make Redisson namespace compatibility and recovery explicit

Constraint: Operators need bounded cleanup without silently mixing incompatible nodes
Rejected: Automatic destructive cleanup on mismatch | it is unsafe without quiescence and ACL authority
Confidence: high
Scope-risk: broad
Tested: namespace admin unit tests and sequential two-client Redis integration tests
```

작업 9 증거: 순차 live 픽스처는 `RedisServer.Launcher.redis`, 독립 소유 Redisson
application/operator 클라이언트, 2초 command/connect timeout, 재시도 1회, 250밀리초
재시도 지연, 500밀리초 heartbeat를 사용한다. 모든 무작위 네임스페이스를 추적한다.
teardown은 중지된 transport를 복원하고 클라이언트를 닫으며 Toxiproxy proxy와 map 및
marker key를 삭제한 뒤 추적된 Redis key가 남지 않았는지 검증한다. 실제 Redis Lua
claim은 클라이언트 사이에서 유지됐고, 정확한 마커는 일치했으며, 불일치는 fail
closed했다. 보호된 롤백 정리는 마커를 유지하고 재검증했으며, 파괴적 정리는 마커보다
map을 먼저 제거했다. 두 클라이언트 커밋 무효화는 제한된 단조 poll에서 클라이언트
B에 준비된 오래된 로컬 값을 제거했다. 롤백은 timing sleep 없이 이후 커밋된 barrier
무효화 뒤에도 값을 유지했다. 재연결 증명은 A가 직접 연결된 상태에서 리포지토리 소유
`ToxiproxyServer`를 통해 B만 라우팅한다. B의 Redisson 4.6.1 connection-listener
연결 해제를 관찰하고, B가 격리된 동안 A가 키를 제거하도록 한 뒤 proxy를 복원한다.
B transport 재연결과 정확한 `{$namespace}:topic` local-cache invalidation-topic
재구독을 기다리고, 재연결 후 첫 `mapB[3]` 읽기를 정확히 한 번 수행하기 전에 공개
local-cache view가 정리됨을 관찰한다. 복구는 outage 중 무효화를 커밋하고 outstanding
quota lease 하나를 관찰했으며, 이전 클라이언트를 제한된 시간 안에 종료했다. fresh
cap을 가진 별도 교체 클라이언트를 만들기 전에 할당량 0과 drain 가능한 실패 하나를
증명했다. 이전 클라이언트 재사용과 만료된 검증은 fail closed했다. live v1/v2 상태
머신은 별도의 operator/application 클라이언트를 사용하고, 정리 전에 할당량 quiescence와
실제 클라이언트 종료를 단언한다. retained-marker 정리 후 fresh empty v1 클라이언트를
만들어 데이터베이스에서 재구축하고, 재구축된 v1 클라이언트를 닫은 뒤에만 v2를 정리한다.
trace는 실제 작업이 성공한 뒤에만 추가된다. 단일 읽기 재연결 회귀는 재연결 후 첫 hit의
stale 값으로 RED에 실패했고, 피어 전용 listener/subscription/CLEAR 구현은 이후 격리
재실행 3회를 연속 통과했다. 정확한 대상 모음은 41/41을 통과했다. 최종 전체 JDBC
Redisson 모듈 실행은 607개 테스트 중 606개 통과, 기존 skip 1개, 실패 0개, 오류 0개였다.
루트 `detekt`는 성공했고 루트 작업은 `NO-SOURCE`로 보고됐다.

## 작업 10: 공개 계약을 영어와 한국어로 문서화

**파일:**
- 수정: `exposed/cache/README.md`
- 수정: `exposed/cache/README.ko.md`
- 수정: `exposed/jdbc-caffeine/README.md`
- 수정: `exposed/jdbc-caffeine/README.ko.md`
- 수정: `exposed/r2dbc-caffeine/README.md`
- 수정: `exposed/r2dbc-caffeine/README.ko.md`
- 수정: `exposed/jdbc-redisson/README.md`
- 수정: `exposed/jdbc-redisson/README.ko.md`
- 수정 금지: `docs/manual/en/**`, `docs/manual/ko/**`, `docs/manual/manifest.yaml`

- [x] 분리된 불변 DTO, mapper 실행 시점, `Entity` 금지, 루트/현재 트랜잭션 요구 사항, `maxAttempts = 1`, 외부 재시도 형태, 커밋/롤백 의미, 마지막 변경 우선, 로컬 펜스 동작, 제한, 커밋 후 실패 관찰 가능성, 콜백의 데이터베이스 쓰기 금지를 다루는 대응 섹션을 추가한다.
- [x] 무효화 전용 동작, Long/UUID 키 정책, 키 민감도 제한, 정규 codec/fingerprint 호환성, 할당량 포화, 실패 drain, quiescent 정리, 클라이언트 교체를 다루는 Redisson 지침을 추가한다.
- [x] 네임스페이스는 `[a-z][a-z0-9._-]{0,62}:v[1-9][0-9]*`와 일치하는 운영자 소유 정적 버전 이름이며 tenant/request/entity 식별자가 아님을 명시한다. 안전하지 않은 binary codec은 명시적인 trusted isolated-cache opt-in이 필요하고 정리 API에는 전용 ACL이 필요하며 request-facing이어서는 안 된다.
- [x] 공개 실패/상태 표면은 제한된 구조 데이터와 예외 타입만 유지하고 예외 텍스트, stack trace, payload, 식별자, SQL, URL, endpoint, 자격 증명은 절대 보관하지 않는다고 문서화한다.
- [x] Exposed 트랜잭션 로컬 `EntityCache`와 이 애플리케이션 Near Cache를 구분하는 대응 동작 표를 추가한다. commit-safe는 데이터베이스/캐시 원자성이나 crash durability가 아니며, 커밋 후 캐시 실패를 위한 애플리케이션 소유 outbox/repair 경로를 대체하지 않는다고 명시한다.
- [x] 기존 리포지토리 캐시를 마이그레이션하지 않는 선택형 기능임을 명시한다. 데이터베이스 작업 전 lookup 용량 실패, mapper/staging 실패 시 일회용 token 소비, 콜백 순서에 따른 stale-cache 동작, savepoint 거부, 무효화 재시도 지원, 외부 스냅숏 fill 재시도마다 fresh lookup 수행을 문서화한다.
- [x] 혼합 버전 금지, v2 정리 전 검증된 데이터베이스 재구축, 공유 정리 timeout 의미, 반복 무효화의 alert/rate control, miss 증폭 시 데이터베이스 load shedding을 포함해 작업 9와 일치하는 정확한 이중 언어 v1-to-v2 rollout/rollback runbook을 추가한다.
- [x] 두 언어에 동등한 실행 가능한 snippet을 포함한다. JDBC, R2DBC, Redisson block을 canonical로 표시하고 소비자가 BOM 버전을 소유하므로 라이브러리 좌표에는 버전을 쓰지 않는다.
- [x] 테스트에서 영어/한국어 README의 각 canonical fenced block을 추출하고 정규화하거나 컴파일된 fixture 소스와 정확히 동일함을 단언한 뒤, 작업 4, 6, 7, 8에서 만든 소스 사용 테스트로 canonical fixture를 컴파일한다. API 이름 동등성을 유지하고 compiler-testing 의존성을 추가하지 않은 채 잘못된 엔진 및 String 정책 부재의 negative 사례에 API reflection/ABI 단언을 사용한다.
- [x] 모든 새 공개 선언에서 영문 KDoc과 필요한 `@InternalSnapshotCacheApi` opt-in을 감사한다.
- [x] 모든 README 쌍의 공개 타입/함수 이름에 대해 리터럴 동등성 검사를 실행한다.
- [x] `git diff -- docs/manual docs/manual/manifest.yaml`이 비어 있는지 확인한다.
- [x] Lore 트레일러가 포함된 커밋을 생성한다.

```text
Explain safe snapshot caching at transaction and operator boundaries

Constraint: Stable manuals remain pinned to released 1.11.0 APIs
Rejected: Publishing issue 321 APIs in stable manuals | the feature is not released yet
Confidence: high
Scope-risk: narrow
Tested: bilingual API-name parity and stable-manual diff check
```

**증거:** 강제로 새로 실행한 대상 검증에서 cache, JDBC Caffeine, R2DBC Caffeine,
JDBC Redisson 소스 사용/관리 계약의 57/57 테스트가 통과했다. canonical typed JSON
codec은 문서화된 불변 DTO를 round-trip하고 README 게이트는 명시적 Maven 아티팩트
버전을 거부한다. 새로운 최상위 공개 선언 61개에는 영문 KDoc이 있고 internal SPI는
필수 opt-in 경계를 유지하며 안정 버전 매뉴얼 diff는 비어 있다.

## 작업 11: 기존 벤치마크 모듈 확장

**파일:**
- 생성: `benchmark/exposed-benchmark/src/benchmark/kotlin/io/bluetape4k/exposed/benchmark/cache/SnapshotCacheBenchmark.kt`
- 수정: `benchmark/exposed-benchmark/build.gradle.kts`

- [x] local hit, miss 기능 생성/claim, 단일 키 및 최대 count/weight 버퍼, 반복 키 병합, 다중 저장소 단계 분할, peak drain 할당, commit drain, 스트라이프 lookup/fence 경합, 가짜 async-store seam을 통한 Redisson 키 인코딩/chunk 제출, failure-buffer 포화, timeout/outage connection-hold 동작의 벤치마크를 추가한다. 제한된 인메모리 픽스처를 사용하고 기본 벤치마크 작업에서 Redis를 요구하지 않는다.
- [x] 기존 `cacheBenchmark` include 패턴을 확장해 새 클래스를 컴파일하고 선택적으로 실행한다. 모듈이나 의존성을 추가하지 않는다.
- [x] `./gradlew :benchmark-exposed-benchmark:benchmarkClasses --no-daemon`을 실행한다.
- [x] `./gradlew :benchmark-exposed-benchmark:smokeBenchmark --no-daemon`을 실행한다. 기존 스모크 구성은 warmup 1회, measurement 1회, 100 ms iteration을 제공한다. 수치는 출시 게이트가 아닌 진단값으로 취급한다.
- [x] 엔트리별 스레드, 스케줄러, 무제한 컬렉션, 리플렉션 순회, 반복 직렬화, 잠금 hot spot, 유지된 connection/future 상태가 우발적으로 생겼는지 allocation/latency를 검사한다. 구조적 회귀를 수정하고 환경 민감 수치는 PR 본문에만 기록한다. 유지 count/weight, 제출 횟수, 콜백이 Redis를 기다리지 않는다는 증명은 결정론적 테스트를 게이트로 유지한다.

작업 11 증거: 기존 벤치마크 모듈은 `src/benchmark/kotlin`을 source set으로 선언하므로
계획했던 `src/jmh/kotlin` 경로를 리포지토리의 기존 source-set 규칙에 맞게 수정했다.
컴파일 중심 RED는 구현 전에 의도적으로 없는 coverage seam에서 실패했다.
`benchmarkClasses`는 이후 새 클래스를 컴파일했고 Redis 없는 스모크 실행은 제한된
warmup과 measurement 각 1회로 스냅숏 캐시 메서드 12개를 모두 발견해 실행했다.
픽스처는 고정 저장소 집합, H2 pool connection 하나, 제한된 buffer/chunk, 작업자
스레드와 스케줄러 없음, 최대 하나의 영원히 완료되지 않는 future를 유지한다. 빠른
경로에는 클래스 전체 invocation setup/teardown이 없다. 고정 메모리 단조 counter는
전후 차이를 사용하고, 실패 포화와 outage 정리는 생명주기 비용을 의도적으로 측정하는
두 벤치마크 메서드에 격리한다. 따라서 4-thread fence 벤치마크는 공유 정리와 경합하지
않는다. 리뷰 RED는 기존 one-encode/fixed-count 경로가 구조적 단언에 실패하게 했다.
교체 fake seam은 측정된 encoded byte로 분할하고, chunk마다 모든 식별자 digest를
재인코딩하고 검증하며, 리플렉션 boxed-Long 배열을 구체화해 소비하고, 제한된 chunk와
제출 식별자를 계산한다. 정규 hex 변환은 formatter 순회 대신 고정 크기 문자 배열을
사용한다. outage 벤치마크는 제한된 future 하나가 미완료인 동안 H2/Hikari 활성
connection 수가 0이어야 하며, 메서드 로컬 정리에서 해당 future를 해제한다. 강제로
새로 실행한 벤치마크 컴파일과 Redis 없는 스모크 실행은 12개 메서드 모두 통과했고
JMH 예외 출력은 없었다. 스모크 throughput은 진단값일 뿐이며 count/weight 유지,
제출 계산, 대기하지 않는 콜백의 정확성 게이트는 결정론적 어댑터 테스트로 유지한다.
- [x] Lore 트레일러가 포함된 커밋을 생성한다.

```text
Make snapshot cache coordination costs observable

Constraint: Benchmarks must compile in the existing benchmark module without Redis
Rejected: A new benchmark project | it adds registration and publication surface without value
Confidence: medium
Scope-risk: narrow
Tested: benchmark class compilation and bounded local smoke run
```

## 작업 12: 최종 검증, 독립 리뷰 및 PR 전달

**파일:**
- 검증된 발견 사항을 해결하는 데 필요한 파일만 수정한다.

- [x] 빠른 결정론적 게이트를 실행한다.

```bash
./gradlew :bluetape4k-exposed-cache:test \
  :bluetape4k-exposed-jdbc-caffeine:test \
  :bluetape4k-exposed-r2dbc-caffeine:test \
  :benchmark-exposed-benchmark:benchmarkClasses \
  :benchmark-exposed-benchmark:smokeBenchmark \
  --no-daemon --no-parallel --rerun-tasks -Pkotlin.incremental=false
```

- [x] Testcontainers 기반 Redisson 게이트를 단독 실행한다.

```bash
TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :bluetape4k-exposed-jdbc-redisson:test \
  --no-daemon --no-parallel --rerun-tasks -Pkotlin.incremental=false
```

- [x] 고정된 매뉴얼 콘텐츠를 변경하지 않고 안정 버전 매뉴얼 인벤토리를 검증한다.

```bash
./gradlew exportManualModuleInventory --no-daemon
ruby -Itest scripts/manual/release_inventory_test.rb
ruby scripts/manual/release_inventory.rb \
  1.11.0 0b494a5fd1e083006046764757342b68a397e4c5 \
  build/manual/module-inventory.json build/manual/module-inventory-1.11.0.json 40
ruby scripts/manual/validate_manuals.rb build/manual/module-inventory-1.11.0.json docs/manual/manifest.yaml
```

- [x] 리포지토리 정적 게이트를 실행한다.

```bash
./gradlew detekt --no-daemon
git diff --check
```

- [x] 성능, 안정성/동시성, 보안, 운영자/Ops, 개발자/API, 사용자/호출자 동작 관점의 독립 리뷰를 수행한다. 발견 사항은 주 세션에서 통합한다. `P0=0`, `P1=0`을 요구하고 P2/P3를 해결하거나 명확히 정당화된 후속 이슈를 만든다.
- [x] 리뷰 수정의 영향을 받은 모든 게이트를 다시 실행하고 정확한 결과를 기록한다.
- [x] 최종 diff에 `settings.gradle.kts`, 안정 버전 매뉴얼, 의존성 카탈로그, Spring Boot, Ktor, Lettuce, 이슈 #322 스키마 드리프트 변경이 없는지 검증한다.
- [x] `feat/issue-321-transaction-aware-snapshot-cache`를 push하고 `Closes #321`을 참조하는 영문 PR #381을 `develop` 대상으로 연다. 설계 결정, 테스트 증거, 벤치마크 환경 주의 사항, 분산 실패 의미, 알려진 비목표를 포함한다.
- [ ] GitHub check와 현재 review/thread 상태를 기다린다. 병합 준비가 된 정확한 PR/head를 보고하고 사용자의 새로운 병합 승인을 받기 위해 중단한다.

**최종 증거(`origin/develop` `0907513a4dfb358a39f2b79002ec6ccd049635c6`에 rebase):**

- Cache: 테스트 149개, failure/error/skip 0개.
- JDBC Caffeine: 테스트 387개, failure/error 0개, skip 22개.
- R2DBC Caffeine: 테스트 108개, failure/error 0개, skip 1개.
- JDBC Redisson: 테스트 612개, failure/error 0개, skip 1개. Ryuk을 비활성화하고 별도로 실행했다.
- 벤치마크: `benchmarkClasses`와 스모크 벤치마크 32개. `SnapshotCacheBenchmark` 메서드 12개 모두 구조적 예외 없이 유한한 점수를 출력했다.
- 안정 버전 매뉴얼: 현재 인벤토리를 커밋 `0b494a5fd1e083006046764757342b68a397e4c5`의 불변 릴리스 `1.11.0`과 대조해 필터링했으며 프로젝트 40개가 일치했다.
- 정적 검사/범위: `detekt` 성공(`:detekt NO-SOURCE`), `git diff --check` 통과, 금지 표면 없음.
- 수정 후 독립 재리뷰: 성능/안정성, 보안/Ops, 개발자/API/사용자 관점에서 각각 `P0=0`, `P1=0`, `P2=0`, `P3=0`, `COMPLETE=YES`를 보고했다.

## 완료 체크리스트

- [x] 모든 인수 조건이 통과 증거에 매핑된다.
- [x] 어떤 캐시 콜백도 데이터베이스에 쓸 수 없다.
- [x] 롤백 및 재시도 동작이 명시되어 있고 JDBC와 R2DBC에서 테스트됐다.
- [x] 공개 miss token은 식별자나 generation 상태를 노출하지 않는다.
- [x] Caffeine 순서 펜스는 제어된 동시성에서 stale fill을 거부한다.
- [x] Redisson은 무효화 전용이고, 제한되며, 논블로킹이고, 네임스페이스와 호환된다.
- [x] 영원히 완료되지 않는 future에서도 failure buffer와 복구는 제한된다.
- [x] 영어/한국어 README API가 일치하고 공개 KDoc이 완성됐다.
- [x] 안정 버전 매뉴얼은 변경되지 않았고 고정 릴리스 인벤토리와 대조해 검증됐다.
- [x] 벤치마크 소스가 컴파일되고 제한된 스모크 실행에 구조적 회귀가 없다.
- [x] 독립 리뷰에서 `P0=0`, `P1=0`을 보고했다.
- [x] PR #381이 `develop` 대상으로 열려 있으며 병합은 사용자의 새로운 승인을 기다린다.
