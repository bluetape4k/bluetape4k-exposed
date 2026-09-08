package io.bluetape4k.exposed.bigquery

import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.api.services.bigquery.Bigquery
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.exposed.bigquery.domain.Events
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test

/**
 * BigQuery continuation 요청의 실제 REST URL을 검증하는 단위 테스트입니다.
 * 실제 BigQuery 자격 증명이나 네트워크를 사용하지 않고 Google HTTP mock transport만 사용합니다.
 */
class BigQueryQueryContinuationHttpUnitTest {

    @Test
    fun `toList는 jobReference location을 우선하여 모든 continuation 요청에 전달한다`() {
        val transport = RecordingTransport(
            responses = listOf(
                queryResponse(
                    jobLocation = "europe-west1",
                    jobComplete = true,
                    pageToken = "page-1",
                    rows = listOf(1L to "initial"),
                ),
                getQueryResultsResponse(
                    jobComplete = true,
                    pageToken = "page-2",
                    rows = listOf(2L to "middle"),
                ),
                getQueryResultsResponse(jobComplete = true, rows = listOf(3L to "final")),
            )
        )
        val context = context(transport)

        val rows = with(context) {
            Events.selectAll()
                .withBigQuery(BigQueryQueryOptions(location = "us-central1"))
                .toList()
        }

        rows shouldHaveSize 3
        rows[0][Events.eventId] shouldBeEqualTo 1L
        rows[0][Events.region] shouldBeEqualTo "initial"
        rows[1][Events.eventId] shouldBeEqualTo 2L
        rows[1][Events.region] shouldBeEqualTo "middle"
        rows[2][Events.eventId] shouldBeEqualTo 3L
        rows[2][Events.region] shouldBeEqualTo "final"
        transport.requestUrls shouldHaveSize 3
        transport.requestUrls[1] shouldContain "pageToken=page-1"
        transport.requestUrls[2] shouldContain "pageToken=page-2"
        transport.requestUrls.drop(1).forEach { url ->
            url shouldContain "location=europe-west1"
            url shouldNotContain "location=us-central1"
            url shouldContain "timeoutMs=30000"
        }
    }

    @Test
    fun `toFlow는 jobReference location이 없으면 options location을 모든 polling 요청에 전달한다`() = runTest {
        val transport = RecordingTransport(
            responses = listOf(
                queryResponse(
                    jobLocation = null,
                    jobComplete = false,
                ),
                getQueryResultsResponse(jobComplete = false),
                getQueryResultsResponse(
                    jobComplete = true,
                    pageToken = "page-1",
                    rows = listOf(10L to "initial"),
                ),
                getQueryResultsResponse(
                    jobComplete = true,
                    pageToken = "page-2",
                    rows = listOf(11L to "middle"),
                ),
                getQueryResultsResponse(jobComplete = true, rows = listOf(12L to "final")),
            )
        )
        val context = context(transport)

        val rows = with(context) {
            Events.selectAll()
                .withBigQuery(BigQueryQueryOptions(location = "asia-northeast3"))
                .toFlow()
                .toList()
        }

        rows shouldHaveSize 3
        rows[0][Events.eventId] shouldBeEqualTo 10L
        rows[0][Events.region] shouldBeEqualTo "initial"
        rows[1][Events.eventId] shouldBeEqualTo 11L
        rows[1][Events.region] shouldBeEqualTo "middle"
        rows[2][Events.eventId] shouldBeEqualTo 12L
        rows[2][Events.region] shouldBeEqualTo "final"
        transport.requestUrls shouldHaveSize 5
        transport.requestUrls[1] shouldNotContain "pageToken="
        transport.requestUrls[2] shouldNotContain "pageToken="
        transport.requestUrls[3] shouldContain "pageToken=page-1"
        transport.requestUrls[4] shouldContain "pageToken=page-2"
        transport.requestUrls.drop(1).forEach { url ->
            url shouldContain "location=asia-northeast3"
            url shouldContain "timeoutMs=30000"
        }
    }

    private fun context(transport: RecordingTransport): BigQueryContext =
        BigQueryContext(
            bigquery = Bigquery.Builder(transport, GsonFactory.getDefaultInstance(), null)
                .setRootUrl("http://localhost/")
                .setApplicationName("exposed-bigquery-location-test")
                .build(),
            projectId = PROJECT_ID,
            datasetId = DATASET_ID,
            sqlGenDb = Database.connect(
                url = "jdbc:h2:mem:bq_http_location_${transport.id};" +
                    "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            ),
        )

    private class RecordingTransport(
        responses: List<String>,
    ): MockHttpTransport() {
        private val remainingResponses = ArrayDeque(responses)
        val requestUrls = mutableListOf<String>()
        val id: Int = nextId++

        override fun buildRequest(method: String, url: String): LowLevelHttpRequest {
            requestUrls += url
            check(remainingResponses.isNotEmpty()) { "mock response queue exhausted for $method $url" }
            return MockLowLevelHttpRequest(url).setResponse(
                MockLowLevelHttpResponse()
                    .setContent(remainingResponses.removeFirst())
                    .setContentType("application/json; charset=UTF-8")
            )
        }

        private companion object {
            private var nextId = 0
        }
    }

    private companion object {
        private const val PROJECT_ID = "proj"
        private const val DATASET_ID = "ds"

        private fun queryResponse(
            jobLocation: String?,
            jobComplete: Boolean,
            pageToken: String? = null,
            rows: List<Pair<Long, String>> = emptyList(),
        ): String = response(
            kind = "bigquery#queryResponse",
            jobLocation = jobLocation,
            jobComplete = jobComplete,
            pageToken = pageToken,
            rows = rows,
        )

        private fun getQueryResultsResponse(
            jobComplete: Boolean,
            pageToken: String? = null,
            rows: List<Pair<Long, String>> = emptyList(),
        ): String = response(
            kind = "bigquery#getQueryResultsResponse",
            jobLocation = null,
            jobComplete = jobComplete,
            pageToken = pageToken,
            rows = rows,
        )

        private fun response(
            kind: String,
            jobLocation: String?,
            jobComplete: Boolean,
            pageToken: String?,
            rows: List<Pair<Long, String>>,
        ): String = buildString {
            append("{\"kind\":\"$kind\",\"jobReference\":{\"projectId\":\"$PROJECT_ID\",\"jobId\":\"job-1\"")
            jobLocation?.let { append(",\"location\":\"$it\"") }
            append("},\"jobComplete\":$jobComplete")
            if (jobComplete) {
                append(",\"schema\":{\"fields\":[{\"name\":\"event_id\",\"type\":\"INTEGER\"},")
                append("{\"name\":\"region\",\"type\":\"STRING\"}]}")
            }
            pageToken?.let { append(",\"pageToken\":\"$it\"") }
            if (rows.isNotEmpty()) {
                append(",\"rows\":[")
                rows.joinTo(this, separator = ",") { (eventId, region) ->
                    "{\"f\":[{\"v\":\"$eventId\"},{\"v\":\"$region\"}]}"
                }
                append("]")
            }
            append("}")
        }
    }
}
