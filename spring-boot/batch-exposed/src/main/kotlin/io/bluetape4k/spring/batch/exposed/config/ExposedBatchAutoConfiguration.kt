package io.bluetape4k.spring.batch.exposed.config

import io.bluetape4k.logging.KLogging
import org.springframework.batch.core.job.Job
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import java.time.Duration

/**
 * Spring Boot auto-configuration for Exposed Batch components.
 *
 * Do not add `@EnableBatchProcessing` to applications using this module. It
 * disables Spring Boot 4 batch auto-configuration.
 *
 * Recommended application configuration:
 * ```yaml
 * spring:
 *   batch:
 *     job:
 *       enabled: false  # prefer explicit JobLauncher usage
 * ```
 */
@AutoConfiguration(after = [BatchAutoConfiguration::class])
@ConditionalOnClass(Job::class)
@EnableConfigurationProperties(ExposedBatchProperties::class)
class ExposedBatchAutoConfiguration {

    companion object : KLogging()

    /**
     * Default [TaskExecutor] for partitioned batch execution.
     *
     * User-defined `batchPartitionTaskExecutor` beans take precedence over this
     * default bean.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["batchPartitionTaskExecutor"])
    @ConditionalOnProperty(
        prefix = "bluetape4k.batch.executor",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true
    )
    fun batchPartitionTaskExecutor(properties: ExposedBatchProperties): TaskExecutor =
        SimpleAsyncTaskExecutor("batch-partition-").apply {
            val executor = properties.executor
            setVirtualThreads(executor.virtualThreads)
            setConcurrencyLimit(executor.concurrencyLimit)
            setTaskTerminationTimeout(Duration.ofSeconds(executor.awaitTerminationSeconds).toMillis())
        }
}
