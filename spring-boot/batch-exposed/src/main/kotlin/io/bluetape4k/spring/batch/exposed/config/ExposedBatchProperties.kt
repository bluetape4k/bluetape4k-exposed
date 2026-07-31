package io.bluetape4k.spring.batch.exposed.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Exposed Spring Batch 통합 설정 속성입니다.
 *
 * ## 동작 계약
 * - `bluetape4k.batch.executor.enabled=false`는 기본 `batchPartitionTaskExecutor` bean을 비활성화합니다.
 * - `virtualThreads`는 기본 executor의 virtual thread 생성 여부를 제어합니다.
 * - `concurrencyLimit`는 동시에 실행하는 partition task 수를 제한합니다.
 * - `awaitTerminationSeconds`는 Spring의 task 종료 timeout에 적용됩니다.
 *
 * ```yaml
 * bluetape4k:
 *   batch:
 *     executor:
 *       enabled: true
 *       virtual-threads: true
 *       concurrency-limit: 8
 *       await-termination-seconds: 30
 * ```
 */
@ConfigurationProperties(prefix = "bluetape4k.batch")
class ExposedBatchProperties {

    /** 기본 partition executor 설정입니다. */
    var executor: Executor = Executor()

    /** 자동 설정되는 `batchPartitionTaskExecutor` bean의 설정입니다. */
    class Executor {
        /** 기본 `batchPartitionTaskExecutor` bean을 생성할지 여부입니다. */
        var enabled: Boolean = true

        /** 기본 executor가 virtual thread를 사용할지 여부입니다. */
        var virtualThreads: Boolean = true

        /** 동시에 실행할 수 있는 partition task의 최대 수입니다. */
        var concurrencyLimit: Int = Runtime.getRuntime().availableProcessors() * 2

        /** executor 종료 중 실행 중인 task를 기다리는 최대 시간입니다. */
        var awaitTerminationSeconds: Long = 30
    }
}
