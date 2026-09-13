package io.bluetape4k.exposed.r2dbc.redisson

import io.bluetape4k.LibraryName
import io.bluetape4k.codec.Base58
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.error
import io.bluetape4k.testcontainers.storage.RedisServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.redisson.api.RedissonClient

abstract class AbstractR2dbcRedissonTest: AbstractExposedR2dbcTest() {

    companion object: KLoggingChannel() {

        /**
         * Lettuce 캐시 테스트는 POSTGRESQL 만 사용한다. (테스트 시간, 다른 DB와의 간섭때문에 하나의 DB만 사용)
         */
        @JvmStatic
        fun enableDialects() = setOf(TestDB.H2)

        const val ENABLE_DIALECTS_METHOD = "enableDialects"

        @JvmStatic
        val redis: RedisServer by lazy { RedisServer.Launcher.redis }

        @JvmStatic
        val redissonClient by lazy { newRedisson() }

        @JvmStatic
        protected val faker = Fakers.faker

        @JvmStatic
        protected fun randomString(): String =
            Fakers.randomString(256, 512)

        @JvmStatic
        protected fun randomName(): String = "$LibraryName:${Base58.randomString(8)}"

        @JvmStatic
        protected fun newRedisson(): RedissonClient {
            return RedisServer.Launcher.RedissonLib.getRedisson(redis.url)
        }
    }

    protected val redisson: RedissonClient get() = redissonClient

    protected val redisScope = CoroutineScope(CoroutineName("redisson") + Dispatchers.IO)

    protected val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        log.error(exception) {
            "CoroutineExceptionHandler get exception with suppressed ${exception.suppressed.contentToString()} "
        }
        throw RuntimeException("Fail to execute in coroutine", exception)
    }
}
