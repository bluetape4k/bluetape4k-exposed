package io.bluetape4k.spring.batch.exposed.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for Exposed Spring Batch integration.
 *
 * ## Behaviour / Contract
 * - `bluetape4k.batch.executor.enabled=false` disables the default
 *   `batchPartitionTaskExecutor` bean.
 * - `virtualThreads` controls whether the default executor creates virtual
 *   threads.
 * - `concurrencyLimit` limits concurrently running partition tasks.
 * - `awaitTerminationSeconds` is applied to Spring's task termination timeout.
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

    /**
     * Default partition executor settings.
     */
    var executor: Executor = Executor()

    /**
     * Settings for the auto-configured `batchPartitionTaskExecutor` bean.
     */
    class Executor {
        /**
         * Whether to create the default `batchPartitionTaskExecutor` bean.
         */
        var enabled: Boolean = true

        /**
         * Whether the default executor should use virtual threads.
         */
        var virtualThreads: Boolean = true

        /**
         * Maximum number of partition tasks allowed to run concurrently.
         */
        var concurrencyLimit: Int = Runtime.getRuntime().availableProcessors() * 2

        /**
         * Maximum time to wait for active tasks during executor shutdown.
         */
        var awaitTerminationSeconds: Long = 30
    }
}
