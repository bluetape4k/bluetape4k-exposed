package io.bluetape4k.exposed.cache.snapshot

import java.io.Serializable
import java.time.Duration

/**
 * 트랜잭션 인식 snapshot cache의 공통 안전 한계와 식별자입니다.
 *
 * [namespace]는 운영자가 소유하는 정적 non-tenant 식별자입니다. request, entity, user 같은 동적 식별자를
 * 포함하면 안 됩니다. 생성자는 namespace 문법만 강제하므로 cardinality 의미론은 호출자가 보장합니다.
 *
 * @property namespace `name:v1` 형식의 안정적인 cache namespace입니다. metrics tag 후보가 될 수 있으므로
 * 배포 단위에서 정적으로 고정되어야 합니다.
 * @property schemaVersion 애플리케이션이 정의한 snapshot payload schema version입니다. 저장된 값의
 * 역직렬화/마이그레이션 판단에 사용되며 library는 값을 해석하지 않습니다.
 * @property maxStagedMutations 한 transaction에서 stage할 수 있는 mutation 최대 개수입니다. 과도한 메모리
 * 점유와 commit 후 drain 폭주를 제한합니다.
 * @property maxParticipatingStores 한 transaction에 참여할 수 있는 snapshot store 최대 개수입니다. store
 * 충돌 검사와 per-store limit 병합 비용을 제한합니다.
 */
data class SnapshotCacheConfig(
    /** 안정적인 cache namespace입니다. 동적 identifier를 포함하지 않는 정적 값이어야 합니다. */
    val namespace: String,
    /** snapshot payload의 애플리케이션 schema version입니다. */
    val schemaVersion: String,
    /** 한 transaction에 stage 가능한 mutation 개수 상한입니다. */
    val maxStagedMutations: Int = 10_000,
    /** 한 transaction에 참여 가능한 store 개수 상한입니다. */
    val maxParticipatingStores: Int = 8,
) : Serializable {

    init {
        require(NAMESPACE_PATTERN.matches(namespace)) {
            "namespace must match ${NAMESPACE_PATTERN.pattern}."
        }
        require(schemaVersion.isNotBlank()) { "schemaVersion must not be blank." }
        require(maxStagedMutations > 0) {
            "maxStagedMutations[$maxStagedMutations] must be positive."
        }
        require(maxParticipatingStores > 0) {
            "maxParticipatingStores[$maxParticipatingStores] must be positive."
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
        private val NAMESPACE_PATTERN = Regex("[a-z][a-z0-9._-]{0,62}:v[1-9][0-9]*")
    }
}

/**
 * 로컬 Caffeine snapshot cache adapter의 안전 한계와 만료 정책입니다.
 *
 * [maximumWeight] 또는 [maxStagedWeight]가 `null`이 아니면 adapter 생성 시 [SnapshotValueSizer]가 필요합니다.
 * configuration 객체는 불변 설정과 runtime collaborator를 분리하기 위해 sizer 자체를 요구하지 않습니다.
 *
 * @property snapshot 공통 snapshot cache 식별자와 transaction staging 한계입니다.
 * @property maximumSize 로컬에 보관할 cache entry 최대 개수입니다.
 * @property maximumWeight 로컬 entry의 추정 retained weight 상한입니다. 설정하면 sizer 기반 eviction을 사용합니다.
 * @property expireAfterWrite 마지막 write 이후 entry가 유지되는 시간입니다.
 * @property expireAfterAccess 마지막 access 이후 entry가 유지되는 선택적 시간입니다.
 * @property maxStagedWeight 한 transaction에 stage할 수 있는 추정 retained weight 상한입니다.
 * @property localDrainBudget commit 후 로컬 put/invalidation drain에 사용할 최대 시간 예산입니다.
 * @property fenceStripes local concurrency fence stripe 개수입니다. lock 분산을 위해 2의 거듭제곱이어야 합니다.
 * @property maxOutstandingMissTokens 아직 claim되지 않은 cache-miss token의 최대 보유 개수입니다.
 */
data class CaffeineSnapshotCacheConfig(
    /** 공통 snapshot cache identity와 staging limit입니다. */
    val snapshot: SnapshotCacheConfig,
    /** 로컬 Caffeine cache에 보관할 entry 개수 상한입니다. */
    val maximumSize: Long = 10_000,
    /** 로컬 Caffeine cache의 추정 retained weight 상한입니다. */
    val maximumWeight: Long? = null,
    /** write 시점 기준 entry 만료 시간입니다. */
    val expireAfterWrite: Duration = Duration.ofMinutes(10),
    /** access 시점 기준 entry 만료 시간입니다. 설정하지 않으면 access 기반 만료를 사용하지 않습니다. */
    val expireAfterAccess: Duration? = null,
    /** transaction staging 단계의 추정 retained weight 상한입니다. */
    val maxStagedWeight: Long? = null,
    /** commit 이후 로컬 drain 단계에 허용되는 시간 예산입니다. */
    val localDrainBudget: Duration = Duration.ofMillis(250),
    /** local fence를 분산할 stripe 수입니다. */
    val fenceStripes: Int = 1_024,
    /** 동시에 유지할 수 있는 opaque miss token 상한입니다. */
    val maxOutstandingMissTokens: Int = 10_000,
) : Serializable {

    init {
        require(maximumSize > 0L) { "maximumSize[$maximumSize] must be positive." }
        maximumWeight?.let {
            require(it > 0L) { "maximumWeight[$it] must be positive when set." }
        }
        require(expireAfterWrite > Duration.ZERO) {
            "expireAfterWrite[$expireAfterWrite] must be positive."
        }
        expireAfterAccess?.let {
            require(it > Duration.ZERO) { "expireAfterAccess[$it] must be positive when set." }
        }
        maxStagedWeight?.let {
            require(it > 0L) { "maxStagedWeight[$it] must be positive when set." }
        }
        require(localDrainBudget > Duration.ZERO) {
            "localDrainBudget[$localDrainBudget] must be positive."
        }
        require(fenceStripes in MIN_FENCE_STRIPES..MAX_FENCE_STRIPES && fenceStripes.isPowerOfTwo()) {
            "fenceStripes[$fenceStripes] must be a power of two in $MIN_FENCE_STRIPES..$MAX_FENCE_STRIPES."
        }
        require(maxOutstandingMissTokens > 0) {
            "maxOutstandingMissTokens[$maxOutstandingMissTokens] must be positive."
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
        private const val MIN_FENCE_STRIPES = 64
        private const val MAX_FENCE_STRIPES = 65_536
    }
}

private fun Int.isPowerOfTwo(): Boolean = this > 0 && this and (this - 1) == 0
