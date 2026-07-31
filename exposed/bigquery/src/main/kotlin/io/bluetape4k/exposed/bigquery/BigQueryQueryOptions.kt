package io.bluetape4k.exposed.bigquery

import com.google.api.services.bigquery.model.QueryRequest
import com.google.api.services.bigquery.model.TableReference
import java.io.Serializable

/**
 * 생성 SQL 또는 원시 SQL 실행에 적용할 BigQuery REST 쿼리 작업 옵션입니다.
 *
 * 이 옵션은 BigQuery `jobs.query` 요청 필드에 매핑됩니다.
 * 호출자는 Exposed DSL 통합을 유지하면서 dry run 검증, 과금 바이트 상한,
 * 실행 우선순위, 작업 라벨, 대상 테이블 동작을 제어할 수 있습니다.
 */
data class BigQueryQueryOptions(
    val dryRun: Boolean = false,
    val maximumBytesBilled: Long? = null,
    val labels: Map<String, String> = emptyMap(),
    val priority: BigQueryQueryPriority? = null,
    val location: String? = null,
    val destinationTable: BigQueryDestinationTable? = null,
    val timeoutMs: Long? = null,
    val useQueryCache: Boolean? = null,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }

    init {
        maximumBytesBilled?.let {
            require(it > 0L) { "maximumBytesBilled must be positive: $it" }
        }
        timeoutMs?.let {
            require(it > 0L) { "timeoutMs must be positive: $it" }
        }
        labels.forEach { (key, value) ->
            require(key.isNotBlank()) { "label key must not be blank." }
            require(value.isNotBlank()) { "label value must not be blank for key '$key'." }
        }
        location?.let {
            require(it.isNotBlank()) { "location must not be blank." }
        }
    }

    internal fun applyTo(request: QueryRequest): QueryRequest = request.apply {
        setDryRun(this@BigQueryQueryOptions.dryRun)
        this@BigQueryQueryOptions.maximumBytesBilled?.let(::setMaximumBytesBilled)
        this@BigQueryQueryOptions.labels
            .takeIf { it.isNotEmpty() }
            ?.let { set("labels", it) }
        this@BigQueryQueryOptions.priority?.let { set("priority", it.apiValue) }
        this@BigQueryQueryOptions.location?.let(::setLocation)
        this@BigQueryQueryOptions.destinationTable?.let { set("destinationTable", it.toTableReference()) }
        this@BigQueryQueryOptions.timeoutMs?.let(::setTimeoutMs)
        this@BigQueryQueryOptions.useQueryCache?.let(::setUseQueryCache)
    }

    internal fun asDryRun(): BigQueryQueryOptions =
        if (dryRun) this else copy(dryRun = true)
}

/**
 * REST 쿼리 작업 API가 지원하는 BigQuery 쿼리 우선순위입니다.
 */
enum class BigQueryQueryPriority(val apiValue: String) {
    INTERACTIVE("INTERACTIVE"),
    BATCH("BATCH"),
}

/**
 * BigQuery 쿼리 작업 결과를 저장할 대상 테이블입니다.
 */
data class BigQueryDestinationTable(
    val projectId: String,
    val datasetId: String,
    val tableId: String,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }

    init {
        require(projectId.isNotBlank()) { "projectId must not be blank." }
        require(datasetId.isNotBlank()) { "datasetId must not be blank." }
        require(tableId.isNotBlank()) { "tableId must not be blank." }
    }

    internal fun toTableReference(): TableReference =
        TableReference()
            .setProjectId(projectId)
            .setDatasetId(datasetId)
            .setTableId(tableId)
}
