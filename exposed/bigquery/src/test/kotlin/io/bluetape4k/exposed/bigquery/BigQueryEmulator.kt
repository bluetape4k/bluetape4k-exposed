package io.bluetape4k.exposed.bigquery

import io.bluetape4k.logging.KLogging
import io.bluetape4k.utils.ShutdownQueue
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

/**
 * BigQuery 에뮬레이터 컨테이너 (goccy/bigquery-emulator)
 *
 * 로컬에 설치된 에뮬레이터(localhost:9050) 또는 Testcontainers Docker 컨테이너를 자동 선택합니다.
 *
 * ```bash
 * brew install goccy/bigquery-emulator/bigquery-emulator
 * bigquery-emulator --project=test --dataset=testdb --port=9050
 * ```
 */
object BigQueryEmulator: KLogging() {

    const val PROJECT_ID = "test"
    const val DATASET = "testdb"
    const val IMAGE = "ghcr.io/goccy/bigquery-emulator:0.6.3"
    const val HTTP_PORT = 9050
    internal const val REUSE_ENV = "BLUETAPE4K_TESTCONTAINERS_REUSE"

    /** brew install goccy/bigquery-emulator/bigquery-emulator 로 설치된 로컬 에뮬레이터 확인 */
    private fun isLocalRunning(): Boolean = runCatching {
        java.net.Socket("localhost", HTTP_PORT).use { true }
    }.getOrDefault(false)

    internal fun shouldReuseContainer(environment: Map<String, String> = System.getenv()): Boolean =
        "CI" !in environment && "GITHUB_ACTIONS" !in environment && environment[REUSE_ENV].toBoolean()

    internal fun createContainer(environment: Map<String, String> = System.getenv()): GenericContainer<*> =
        GenericContainer(IMAGE)
            .withExposedPorts(HTTP_PORT)
            .withCommand("--project=$PROJECT_ID", "--dataset=$DATASET")
            .withReuse(shouldReuseContainer(environment))
            .waitingFor(
                Wait.forHttp("/discovery/v1/apis/bigquery/v2/rest")
                    .forPort(HTTP_PORT)
                    .forStatusCode(200)
            )

    internal fun registerShutdownIfNeeded(
        container: GenericContainer<*>,
        register: (AutoCloseable) -> Unit = ShutdownQueue::register,
    ) {
        if (!container.isShouldBeReused) {
            register(AutoCloseable { container.stop() })
        }
    }

    data class Endpoint(
        val projectId: String,
        val dataset: String,
        val host: String,
        val port: Int,
        val local: Boolean,
    ) {
        val rootUrl: String = "http://$host:$port/"
    }

    object Launcher: KLogging() {
        val endpoint: Endpoint by lazy {
            if (isLocalRunning()) {
                log.info("로컬 BigQuery 에뮬레이터 사용 (localhost:{})", HTTP_PORT)
                Endpoint(PROJECT_ID, DATASET, "localhost", HTTP_PORT, local = true)
            } else {
                log.info("Testcontainers BigQuery 에뮬레이터 시작")
                val container = createContainer()
                    .also {
                        it.start()
                        registerShutdownIfNeeded(it)
                    }

                val mappedPort = container.getMappedPort(HTTP_PORT)
                check(container.host.isNotBlank()) { "BigQuery emulator host must not be blank." }
                check(mappedPort > 0) { "BigQuery emulator HTTP port must be mapped." }
                Endpoint(PROJECT_ID, DATASET, container.host, mappedPort, local = false)
            }
        }
    }

    val endpoint: Endpoint by lazy { Launcher.endpoint }
    val host: String by lazy { endpoint.host }
    val port: Int by lazy { endpoint.port }

}
