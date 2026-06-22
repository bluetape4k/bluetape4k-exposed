package io.bluetape4k.exposed.bigquery

import com.google.api.services.bigquery.model.QueryRequest
import com.google.api.services.bigquery.model.TableReference
import java.io.Serializable

/**
 * BigQuery REST query-job options applied to generated or raw SQL execution.
 *
 * These options map to BigQuery `jobs.query` request fields so callers can
 * validate queries with dry runs, cap billed bytes, select execution priority,
 * attach job labels, and control destination-table behavior without switching
 * away from the Exposed DSL integration.
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
 * BigQuery query priority values supported by the REST query job API.
 */
enum class BigQueryQueryPriority(val apiValue: String) {
    INTERACTIVE("INTERACTIVE"),
    BATCH("BATCH"),
}

/**
 * Destination table for BigQuery query-job results.
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
