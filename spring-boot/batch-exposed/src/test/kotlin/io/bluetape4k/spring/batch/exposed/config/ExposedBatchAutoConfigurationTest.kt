package io.bluetape4k.spring.batch.exposed.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import org.springframework.test.util.ReflectionTestUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ExposedBatchAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ExposedBatchAutoConfiguration::class.java))

    @Test
    fun `creates batch partition executor with configured properties`() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.batch.executor.virtual-threads=false",
                "bluetape4k.batch.executor.concurrency-limit=7",
                "bluetape4k.batch.executor.await-termination-seconds=5",
            )
            .run { context ->
                val executor = context.getBean("batchPartitionTaskExecutor", SimpleAsyncTaskExecutor::class.java)

                executor.concurrencyLimit shouldBeEqualTo 7
                ReflectionTestUtils.getField(executor, "taskTerminationTimeout") shouldBeEqualTo 5_000L

                val ranOnVirtualThread = AtomicBoolean(true)
                val latch = CountDownLatch(1)
                executor.execute {
                    ranOnVirtualThread.set(Thread.currentThread().isVirtual)
                    latch.countDown()
                }

                latch.await(5, TimeUnit.SECONDS).shouldBeTrue()
                ranOnVirtualThread.get().shouldBeFalse()
            }
    }

    @Test
    fun `does not create batch partition executor when disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.batch.executor.enabled=false")
            .run { context ->
                context.containsBean("batchPartitionTaskExecutor").shouldBeFalse()
            }
    }

    @Test
    fun `backs off when user provides batch partition executor`() {
        contextRunner
            .withUserConfiguration(UserExecutorConfiguration::class.java)
            .run { context ->
                context.getBean("batchPartitionTaskExecutor").javaClass shouldBeEqualTo SyncTaskExecutor::class.java
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class UserExecutorConfiguration {

        @Bean("batchPartitionTaskExecutor")
        fun batchPartitionTaskExecutor(): TaskExecutor = SyncTaskExecutor()
    }
}
