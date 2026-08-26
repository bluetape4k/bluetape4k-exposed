package io.bluetape4k.exposed.r2dbc.redisson.map

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * loader/writer 인스턴스가 기본으로 소유하는 root scope입니다.
 *
 * 주입된 scope와 구분해야 기본 scope를 인스턴스 종료 시 함께 취소할 수 있습니다.
 */
internal class R2dbcMapCoroutineScope(
    override val coroutineContext: CoroutineContext,
): CoroutineScope

/**
 * loader/writer가 주입된 scope 없이 생성될 때 사용할 인스턴스 소유 scope를 만듭니다.
 */
internal fun newR2dbcMapCoroutineScope(name: String): CoroutineScope =
    R2dbcMapCoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName(name),
    )

/**
 * 호출자의 cancellation은 전달하되, adapter가 종료될 때 호출자의 structured scope를 기다리게 하지 않는
 * repository-owned [SupervisorJob] scope를 만듭니다.
 *
 * Exposed의 ambient transaction context 같은 non-[Job] context element는 유지합니다.
 */
internal fun CoroutineScope.linkedSupervisorScope(name: String): CoroutineScope {
    if (this is R2dbcMapCoroutineScope) return this

    val parentJob = coroutineContext[Job]
    val lifecycleJob = SupervisorJob(parentJob)
    return CoroutineScope(coroutineContext + lifecycleJob + CoroutineName(name))
}
