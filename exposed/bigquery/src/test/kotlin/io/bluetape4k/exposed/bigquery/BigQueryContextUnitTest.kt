package io.bluetape4k.exposed.bigquery

import com.google.api.services.bigquery.Bigquery
import com.google.api.services.bigquery.Bigquery.Jobs
import com.google.api.services.bigquery.model.ErrorProto
import com.google.api.services.bigquery.model.QueryRequest
import com.google.api.services.bigquery.model.QueryResponse
import com.google.api.services.bigquery.model.JobReference
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.mockk.CapturingSlot
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.core.Table
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [BigQueryContext] 단위 테스트 — 에뮬레이터 없이 MockK로 동작 검증.
 */
class BigQueryContextUnitTest {

    private val bq = mockk<Bigquery>(relaxed = true)
    private val jobs = mockk<Jobs>(relaxed = true)
    private val queryCall = mockk<Jobs.Query>(relaxed = true)

    private val sqlGenDb: Database = Database.connect(
        url = "jdbc:h2:mem:bq_unit_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
    )

    @BeforeEach
    fun setUpMocks() {
        clearMocks(bq, jobs, queryCall)
        every { bq.jobs() } returns jobs
    }

    /**
     * BigQuery REST API mock 헬퍼.
     * [responseSupplier]가 반환하는 [QueryResponse]를 `jobs().query().execute()` 호출 시 반환합니다.
     */
    private fun mockBigquery(
        requestSlot: CapturingSlot<QueryRequest>? = null,
        responseSupplier: () -> QueryResponse,
    ): Bigquery {
        if (requestSlot == null) {
            every { jobs.query(any(), any()) } returns queryCall
        } else {
            every { jobs.query(any(), capture(requestSlot)) } returns queryCall
        }
        every { queryCall.execute() } answers { responseSupplier() }
        return bq
    }

    @Test
    fun `runRawQuery - 성공 응답은 QueryResponse를 반환한다`() {
        val expected = QueryResponse().setJobComplete(true)
        val bq = mockBigquery { expected }

        val context = BigQueryContext(bq, "proj", "ds", sqlGenDb)
        val result = context.runRawQuery("SELECT 1")

        result.shouldNotBeNull()
        verify(exactly = 1) { bq.jobs() }
    }

    @Test
    fun `runRawQuery applies query job options to QueryRequest`() {
        val request = slot<QueryRequest>()
        val bq = mockBigquery(request) { QueryResponse().setJobComplete(true) }
        val context = BigQueryContext(bq, "proj", "ds", sqlGenDb)

        context.runRawQuery(
            "SELECT 1",
            BigQueryQueryOptions(
                maximumBytesBilled = 10_000L,
                labels = mapOf("workload" to "unit"),
                priority = BigQueryQueryPriority.BATCH,
                location = "US",
                destinationTable = BigQueryDestinationTable("proj", "scratch", "result_1"),
                timeoutMs = 7_000L,
                useQueryCache = false,
            )
        )

        request.captured.maximumBytesBilled shouldBeEqualTo 10_000L
        request.captured.labels shouldBeEqualTo mapOf("workload" to "unit")
        request.captured.get("priority") shouldBeEqualTo "BATCH"
        request.captured.location shouldBeEqualTo "US"
        (request.captured.get("destinationTable") as com.google.api.services.bigquery.model.TableReference).tableId shouldBeEqualTo "result_1"
        request.captured.timeoutMs shouldBeEqualTo 7_000L
        request.captured.useQueryCache.shouldBeFalse()
    }

    @Test
    fun `validateRawQuery forces dry run without executing billable query`() {
        val request = slot<QueryRequest>()
        val bq = mockBigquery(request) { QueryResponse().setJobComplete(true) }
        val context = BigQueryContext(bq, "proj", "ds", sqlGenDb)

        context.validateRawQuery("SELECT 1")

        request.captured.dryRun.shouldBeTrue()
    }

    @Test
    fun `BigQueryQueryOptions validates positive numeric options`() {
        assertFailsWith<IllegalArgumentException> {
            BigQueryQueryOptions(maximumBytesBilled = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            BigQueryQueryOptions(timeoutMs = 0L)
        }
    }

    @Test
    fun `runRawQuery - 오류 응답은 BigQueryQueryException을 던진다`() {
        val errorResponse = QueryResponse()
            .setJobComplete(true)
            .setErrors(listOf(ErrorProto().setMessage("테이블을 찾을 수 없습니다").setReason("notFound")))
        val bq = mockBigquery { errorResponse }

        val context = BigQueryContext(bq, "proj", "ds", sqlGenDb)

        val ex = assertFailsWith<BigQueryQueryException> {
            context.runRawQuery("SELECT * FROM missing_table")
        }
        ex.message.shouldNotBeNull()
        ex.message!! shouldContain "reasons=notFound"
        ex.message!! shouldNotContain "테이블을 찾을 수 없습니다"
    }

    @Test
    fun `runRawQuery - 예외 타입은 BigQueryQueryException이다`() {
        val errorResponse = QueryResponse()
            .setJobComplete(true)
            .setErrors(listOf(ErrorProto().setMessage("권한 없음").setReason("accessDenied")))
        val bq = mockBigquery { errorResponse }

        val context = BigQueryContext(bq, "proj", "ds", sqlGenDb)

        val ex = assertFailsWith<BigQueryQueryException> {
            context.runRawQuery("SELECT 1")
        }
        ex.shouldBeInstanceOf<BigQueryQueryException>()
    }

    @Test
    fun `runRawQuery 오류 진단은 SQL 리터럴을 숨기고 안전한 메타데이터만 포함한다`() {
        val secret = "token=bigquery-secret"
        val sql = "SELECT * FROM bad_table WHERE credential = '$secret'"
        val errorResponse = QueryResponse()
            .setJobComplete(true)
            .setJobReference(JobReference().setJobId("job-safe-123"))
            .setErrors(listOf(ErrorProto().setMessage("invalid literal '$secret'").setReason("badRequest")))
        val bq = mockBigquery { errorResponse }

        val context = BigQueryContext(bq, "proj", "ds", sqlGenDb)

        val ex = assertFailsWith<BigQueryQueryException> {
            context.runRawQuery(sql)
        }
        ex.message!! shouldContain "statement=SELECT"
        ex.message!! shouldContain "sqlFingerprint=sha256:"
        ex.message!! shouldContain "jobId=job-safe-123"
        ex.message!! shouldNotContain secret
        ex.message!! shouldNotContain "bad_table"
    }

    @Test
    fun `runRawQuery 오류 진단은 알 수 없는 첫 토큰을 statement kind로 노출하지 않는다`() {
        val secret = "credentialSecretToken"
        val errorResponse = QueryResponse()
            .setJobComplete(true)
            .setErrors(listOf(ErrorProto().setMessage(secret).setReason("badRequest")))
        val bq = mockBigquery { errorResponse }
        val context = BigQueryContext(bq, "proj", "ds", sqlGenDb)

        val ex = assertFailsWith<BigQueryQueryException> {
            context.runRawQuery("$secret is not SQL")
        }

        ex.message!! shouldContain "statement=UNKNOWN"
        ex.message!! shouldNotContain secret
    }

    @Test
    fun `runRawQuery 오류 진단은 알 수 없는 reason을 노출하지 않는다`() {
        val secretReason = "credentialSecretReason"
        val errorResponse = QueryResponse()
            .setJobComplete(true)
            .setErrors(listOf(ErrorProto().setMessage("error").setReason(secretReason)))
        val bq = mockBigquery { errorResponse }
        val context = BigQueryContext(bq, "proj", "ds", sqlGenDb)

        val ex = assertFailsWith<BigQueryQueryException> {
            context.runRawQuery("SELECT 1")
        }

        ex.message!! shouldContain "reasons=unknown"
        ex.message!! shouldNotContain secretReason
    }

    @Test
    fun `execDeleteAll은 안전한 단일 식별자만 backtick으로 인용한다`() {
        val request = slot<QueryRequest>()
        val bq = mockBigquery(request) { QueryResponse().setJobComplete(true) }
        val context = BigQueryContext(bq, "proj", "ds", sqlGenDb)
        val table = object: Table("safe_events") {}

        with(context) { table.execDeleteAll() }

        request.captured.query shouldBeEqualTo "DELETE FROM `safe_events` WHERE TRUE"
    }

    @Test
    fun `execDeleteAll은 악성 또는 모호한 테이블 이름을 쿼리 전에 거부한다`() {
        val bq = mockBigquery { QueryResponse().setJobComplete(true) }
        val context = BigQueryContext(bq, "proj", "ds", sqlGenDb)
        val unsafeNames = listOf("events; DROP TABLE audit", "dataset.events", "`events`")

        unsafeNames.forEach { unsafeName ->
            val table = object: Table(unsafeName) {}
            val ex = assertFailsWith<IllegalArgumentException> {
                with(context) { table.execDeleteAll() }
            }
            ex.message.orEmpty() shouldNotContain unsafeName
        }

        verify(exactly = 0) { jobs.query(any(), any()) }
    }

    @Test
    fun `create 팩토리 - BigQueryContext 인스턴스를 올바르게 생성한다`() {
        val context = BigQueryContext.create(bq, projectId = "my-project", datasetId = "my-dataset")

        context.shouldNotBeNull()
        context.projectId shouldContain "my-project"
        context.datasetId shouldContain "my-dataset"
    }
}
