package io.bluetape4k.exposed.bigquery

import com.google.api.services.bigquery.Bigquery
import com.google.api.services.bigquery.Bigquery.Jobs
import com.google.api.services.bigquery.model.ErrorProto
import com.google.api.services.bigquery.model.GetQueryResultsResponse
import com.google.api.services.bigquery.model.JobReference
import com.google.api.services.bigquery.model.QueryResponse
import com.google.api.services.bigquery.model.TableCell
import com.google.api.services.bigquery.model.TableFieldSchema
import com.google.api.services.bigquery.model.TableRow
import com.google.api.services.bigquery.model.TableSchema
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.coInvoking
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.exposed.bigquery.domain.Events
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for BigQuery query-job continuation without real BigQuery credentials.
 */
class BigQueryQueryContinuationUnitTest {

    private val bigquery = mockk<Bigquery>()
    private val jobs = mockk<Jobs>()
    private val queryCall = mockk<Jobs.Query>()
    private val getQueryResultsCall = mockk<Jobs.GetQueryResults>()
    private val sqlGenDb: Database = Database.connect(
        url = "jdbc:h2:mem:bq_query_continuation_unit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
    )
    private val context = BigQueryContext(bigquery, PROJECT_ID, DATASET_ID, sqlGenDb)

    @BeforeEach
    fun setUp() {
        clearMocks(bigquery, jobs, queryCall, getQueryResultsCall)

        every { bigquery.jobs() } returns jobs
        every { jobs.query(PROJECT_ID, any()) } returns queryCall
        every { jobs.getQueryResults(PROJECT_ID, JOB_ID) } returns getQueryResultsCall
        every { getQueryResultsCall.setPageToken(any()) } returns getQueryResultsCall
        every { getQueryResultsCall.setTimeoutMs(any()) } returns getQueryResultsCall
    }

    @Test
    fun `toList follows incomplete job and page tokens using continuation schema`() {
        givenQueryPages(
            initial = queryResponse(jobComplete = false, pageToken = "page-1"),
            continuations = listOf(
                getResultsResponse(
                    rows = listOf(tableRow(1L, "kr")),
                    schema = eventsSchema(),
                    jobComplete = false,
                    pageToken = "page-2",
                ),
                getResultsResponse(
                    rows = listOf(tableRow(2L, "us")),
                    jobComplete = true,
                ),
            ),
        )

        val rows = executor().toList()

        rows shouldHaveSize 2
        rows[0][Events.eventId] shouldBeEqualTo 1L
        rows[0][Events.region] shouldBeEqualTo "kr"
        rows[1][Events.eventId] shouldBeEqualTo 2L
        rows[1][Events.region] shouldBeEqualTo "us"
        verify(exactly = 2) { jobs.getQueryResults(PROJECT_ID, JOB_ID) }
        verify(exactly = 1) { getQueryResultsCall.setPageToken("page-1") }
        verify(exactly = 1) { getQueryResultsCall.setPageToken("page-2") }
    }

    @Test
    fun `toFlow follows incomplete job and page tokens using continuation schema`() = runTest(timeout = 30.seconds) {
        givenQueryPages(
            initial = queryResponse(jobComplete = false, pageToken = "page-1"),
            continuations = listOf(
                getResultsResponse(
                    rows = listOf(tableRow(10L, "eu")),
                    schema = eventsSchema(),
                    jobComplete = false,
                    pageToken = "page-2",
                ),
                getResultsResponse(
                    rows = listOf(tableRow(11L, "apac")),
                    jobComplete = true,
                ),
            ),
        )

        val rows = executor().toFlow().toList()

        rows shouldHaveSize 2
        rows[0][Events.eventId] shouldBeEqualTo 10L
        rows[0][Events.region] shouldBeEqualTo "eu"
        rows[1][Events.eventId] shouldBeEqualTo 11L
        rows[1][Events.region] shouldBeEqualTo "apac"
        verify(exactly = 2) { jobs.getQueryResults(PROJECT_ID, JOB_ID) }
    }

    @Test
    fun `toFlow does not fetch continuation page after downstream cancellation`() = runTest(timeout = 30.seconds) {
        givenQueryPages(
            initial = queryResponse(
                rows = listOf(tableRow(21L, "kr")),
                schema = eventsSchema(),
                jobComplete = false,
                pageToken = "page-1",
            ),
            continuations = listOf(
                getResultsResponse(
                    rows = listOf(tableRow(22L, "us")),
                    schema = eventsSchema(),
                    jobComplete = true,
                ),
            ),
        )

        val rows = executor().toFlow().take(1).toList()

        rows shouldHaveSize 1
        rows[0][Events.eventId] shouldBeEqualTo 21L
        verify(exactly = 0) { jobs.getQueryResults(any(), any()) }
    }

    @Test
    fun `toList and toFlow surface continuation page errors as BigQueryQueryException`() = runTest(timeout = 30.seconds) {
        givenQueryPages(
            initial = queryResponse(jobComplete = false, pageToken = "page-1"),
            continuations = listOf(pageError("backendError"), pageError("backendError")),
        )

        val listError = assertFailsWith<BigQueryQueryException> {
            executor().toList()
        }
        val flowError = coInvoking {
            executor().toFlow().toList()
        } shouldThrow BigQueryQueryException::class

        listError.message.shouldNotBeNull() shouldContain "backendError"
        flowError.message.shouldNotBeNull() shouldContain "backendError"
    }

    @Test
    fun `missing jobReference fails clearly when more pages are required`() {
        every { queryCall.execute() } returns QueryResponse()
            .setJobComplete(false)
            .setPageToken("page-1")

        val error = assertFailsWith<IllegalStateException> {
            executor().toList()
        }

        error.message.shouldNotBeNull() shouldContain "jobReference"
        verify(exactly = 0) { jobs.getQueryResults(any(), any()) }
    }

    private fun executor(): BigQueryQueryExecutor =
        with(context) { Events.selectAll().withBigQuery() }

    private fun givenQueryPages(
        initial: QueryResponse,
        continuations: List<GetQueryResultsResponse>,
    ) {
        every { queryCall.execute() } returns initial
        every { getQueryResultsCall.execute() } returnsMany continuations
    }

    private fun queryResponse(
        rows: List<TableRow> = emptyList(),
        schema: TableSchema? = null,
        jobComplete: Boolean = true,
        pageToken: String? = null,
    ): QueryResponse =
        QueryResponse()
            .setJobReference(JobReference().setJobId(JOB_ID))
            .setSchema(schema)
            .setRows(rows)
            .setJobComplete(jobComplete)
            .setPageToken(pageToken)

    private fun getResultsResponse(
        rows: List<TableRow> = emptyList(),
        schema: TableSchema? = null,
        jobComplete: Boolean = true,
        pageToken: String? = null,
    ): GetQueryResultsResponse =
        GetQueryResultsResponse()
            .setSchema(schema)
            .setRows(rows)
            .setJobComplete(jobComplete)
            .setPageToken(pageToken)

    private fun pageError(message: String): GetQueryResultsResponse =
        GetQueryResultsResponse()
            .setErrors(listOf(ErrorProto().setMessage(message).setReason("internalError")))
            .setJobComplete(true)

    private fun eventsSchema(): TableSchema =
        TableSchema().setFields(
            listOf(
                TableFieldSchema().setName("event_id"),
                TableFieldSchema().setName("region"),
            )
        )

    private fun tableRow(eventId: Long, region: String): TableRow =
        TableRow().setF(
            listOf(
                TableCell().setV(eventId.toString()),
                TableCell().setV(region),
            )
        )

    private companion object {
        private const val PROJECT_ID = "proj"
        private const val DATASET_ID = "ds"
        private const val JOB_ID = "job-1"
    }
}
