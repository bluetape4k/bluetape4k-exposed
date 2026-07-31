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
 * Exposed Batch 구성 요소를 위한 Spring Boot 자동 설정입니다.
 *
 * 이 모듈을 사용하는 애플리케이션에는 `@EnableBatchProcessing`을 추가하지 마십시오.
 * 추가하면 Spring Boot 4 batch 자동 설정이 비활성화됩니다.
 *
 * 권장 애플리케이션 설정:
 * ```yaml
 * spring:
 *   batch:
 *     job:
 *       enabled: false  # 명시적인 JobLauncher 사용 권장
 * ```
 */
@AutoConfiguration(after = [BatchAutoConfiguration::class])
@ConditionalOnClass(Job::class)
@EnableConfigurationProperties(ExposedBatchProperties::class)
class ExposedBatchAutoConfiguration {

    companion object : KLogging()

    /**
     * partition batch 실행에 사용하는 기본 [TaskExecutor]입니다.
     *
     * 사용자가 정의한 `batchPartitionTaskExecutor` bean이 이 기본 bean보다 우선합니다.
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
