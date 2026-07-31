package io.bluetape4k.exposed.core.auditable

import io.bluetape4k.exposed.core.auditable.UserContext.DEFAULT_USERNAME
import io.bluetape4k.exposed.core.auditable.UserContext.SCOPED_USER
import io.bluetape4k.exposed.core.auditable.UserContext.THREAD_LOCAL_USER
import io.bluetape4k.exposed.core.auditable.UserContext.getCurrentUser
import io.bluetape4k.exposed.core.auditable.UserContext.withThreadLocalUser
import io.bluetape4k.exposed.core.auditable.UserContext.withUser
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext


/**
 * 현재 작업을 수행하는 사용자명을 전파하는 컨텍스트 객체입니다.
 *
 * ## 우선순위
 * [ScopedValue] > [ThreadLocal] > [DEFAULT_USERNAME] 순서로 현재 사용자명을 결정합니다.
 *
 * ## 사용 시나리오
 * - **Virtual Thread / 단일 요청 스코프**: [withUser]를 사용합니다.
 *   `ScopedValue`와 `ThreadLocal`을 동시에 설정하므로 Virtual Thread 내부에서
 *   파생된 Structured Concurrency 블록에서도 안전하게 전파됩니다.
 * - **Coroutines 환경**: [withCoroutineUser] 또는 [asContextElement]를 사용합니다.
 *   `ThreadLocal` 값은 코루틴 재개 시점마다 복원되어 dispatcher hop 이후에도 전파됩니다.
 * - **일반 Thread 환경**: [withThreadLocalUser]를 사용합니다.
 * - **읽기 전용**: [getCurrentUser]로 현재 사용자명을 조회합니다.
 *
 * ```kotlin
 * // Virtual Thread 환경
 * UserContext.withUser("admin") {
 *     userRepository.save(entity) // createdBy = "admin"
 * }
 *
 * // Coroutines 환경
 * UserContext.withCoroutineUser("admin") {
 *     withContext(Dispatchers.IO) {
 *         userRepository.save(entity) // createdBy = "admin"
 *     }
 * }
 * ```
 */
object UserContext {

    /** 사용자명이 지정되지 않았을 때 사용하는 기본값입니다. */
    const val DEFAULT_USERNAME = "system"

    /**
     * Virtual Thread / Structured Concurrency 환경에서 사용자명을 전파하는 [ScopedValue]입니다.
     *
     * `ScopedValue.where(...).call(...)` 스코프 내에서만 유효하며,
     * 해당 스코프가 종료되면 자동으로 이전 값으로 복원됩니다.
     */
    val SCOPED_USER: ScopedValue<String> = ScopedValue.newInstance()

    /**
     * 일반 Thread 환경에서 사용자명을 전파하는 [InheritableThreadLocal]입니다.
     *
     * Coroutine에서는 [asContextElement]로 설치된 경우에만 dispatcher hop 이후 값이 복원됩니다.
     * 자식 스레드에 값이 상속되므로 `newFixedThreadPool` 등에서도 사용 가능합니다.
     */
    private val THREAD_LOCAL_USER: InheritableThreadLocal<String?> = InheritableThreadLocal()

    /**
     * [ScopedValue]와 [ThreadLocal]을 동시에 설정하고 [block]을 실행합니다.
     *
     * Virtual Thread 환경에서 권장되는 방법입니다.
     * [ScopedValue]는 `Callable` 경계 내에서 전파되며,
     * `finally` 블록에서 이전 [ThreadLocal] 값을 복원합니다.
     *
     * @param username 이 블록 내에서 사용할 사용자명
     * @param block 사용자명 컨텍스트 내에서 실행할 코드 블록
     * @return [block]의 반환값
     */
    fun <T> withUser(username: String, block: () -> T): T {
        val prev = THREAD_LOCAL_USER.get()
        THREAD_LOCAL_USER.set(username)
        return try {
            ScopedValue.where(SCOPED_USER, username).call(block)
        } finally {
            if (prev != null) THREAD_LOCAL_USER.set(prev) else THREAD_LOCAL_USER.remove()
        }
    }

    /**
     * [ThreadLocal]만 설정하고 [block]을 실행합니다.
     *
     * 일반 Thread 환경에서 권장되는 방법입니다.
     * Coroutine dispatcher hop 이후 값 보존이 필요하면 [withCoroutineUser] 또는
     * [asContextElement]를 사용합니다. 블록 실행 후 이전 값으로 복원됩니다.
     *
     * @param username 이 블록 내에서 사용할 사용자명
     * @param block 사용자명 컨텍스트 내에서 실행할 코드 블록
     * @return [block]의 반환값
     */
    fun <T> withThreadLocalUser(username: String, block: () -> T): T {
        val prev = THREAD_LOCAL_USER.get()
        THREAD_LOCAL_USER.set(username)
        return try {
            block()
        } finally {
            if (prev != null) THREAD_LOCAL_USER.set(prev) else THREAD_LOCAL_USER.remove()
        }
    }

    /**
     * Coroutine이 재개될 때마다 현재 사용자를 복원하는 context element를 반환합니다.
     *
     * 다음과 같이 coroutine context를 직접 구성할 때 사용합니다.
     *
     * ```kotlin
     * withContext(UserContext.asContextElement("admin") + Dispatchers.IO) {
     *     repository.save(entity)
     * }
     * ```
     */
    fun asContextElement(username: String): ThreadContextElement<String?> =
        THREAD_LOCAL_USER.asContextElement(username)

    /**
     * Dispatcher hop을 포함한 coroutine 실행에 [username]을 바인딩하여 [block]을 실행합니다.
     *
     * 현재 coroutine context에 [ThreadContextElement]만 추가하므로 호출자의 structured concurrency를
     * 그대로 유지합니다.
     */
    suspend fun <T> withCoroutineUser(username: String, block: suspend () -> T): T =
        withContext(asContextElement(username)) {
            block()
        }

    /**
     * 현재 활성화된 사용자명을 반환합니다.
     *
     * 우선순위: [SCOPED_USER] > [THREAD_LOCAL_USER] > [DEFAULT_USERNAME]
     *
     * - [ScopedValue]가 바인딩된 경우 해당 값을 반환합니다.
     * - [ThreadLocal]에 값이 있으면 해당 값을 반환합니다.
     * - 둘 다 없으면 [DEFAULT_USERNAME](`"system"`)을 반환합니다.
     *
     * @return 현재 사용자명
     */
    fun getCurrentUser(): String {
        runCatching { SCOPED_USER.get() }.getOrNull()?.let { return it }
        THREAD_LOCAL_USER.get()?.let { return it }
        return DEFAULT_USERNAME
    }
}
