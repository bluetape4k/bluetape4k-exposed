package io.bluetape4k.examples.bigquery.dryrun

import com.google.api.services.bigquery.Bigquery
import com.google.api.services.bigquery.Bigquery.Jobs
import com.google.api.services.bigquery.model.QueryRequest
import com.google.api.services.bigquery.model.QueryResponse
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.bigquery.BigQueryQueryOptions
import io.bluetape4k.exposed.bigquery.BigQueryQueryPriority
import io.bluetape4k.exposed.bigquery.BigQueryContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test

class BigQueryDryRunExampleTest {

    @Test
    fun `validate raw SQL with BigQuery dry run options`() {
        val request = slot<QueryRequest>()
        val queryCall = mockk<Jobs.Query>(relaxed = true) {
            every { execute() } returns QueryResponse().setJobComplete(true)
        }
        val jobs = mockk<Jobs>(relaxed = true) {
            every { query(any(), capture(request)) } returns queryCall
        }
        val bigquery = mockk<Bigquery>(relaxed = true) {
            every { jobs() } returns jobs
        }
        val sqlGenDb = Database.connect(
            url = "jdbc:h2:mem:bq_example;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        val context = BigQueryContext(bigquery, "analytics-project", "events", sqlGenDb)

        context.validateRawQuery(
            """
            SELECT region, COUNT(*) AS event_count
            FROM events
            GROUP BY region
            """,
            BigQueryQueryOptions(
                maximumBytesBilled = 1_000_000L,
                labels = mapOf("example" to "dry-run"),
                priority = BigQueryQueryPriority.BATCH,
                location = "US",
                timeoutMs = 5_000L,
            )
        )

        request.captured.dryRun shouldBeEqualTo true
        request.captured.maximumBytesBilled shouldBeEqualTo 1_000_000L
        request.captured.labels shouldBeEqualTo mapOf("example" to "dry-run")
        request.captured.get("priority") shouldBeEqualTo "BATCH"
        request.captured.location shouldBeEqualTo "US"
        request.captured.timeoutMs shouldBeEqualTo 5_000L
    }
}
