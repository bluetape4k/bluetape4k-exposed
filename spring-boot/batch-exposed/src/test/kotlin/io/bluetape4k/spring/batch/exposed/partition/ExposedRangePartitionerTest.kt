package io.bluetape4k.spring.batch.exposed.partition

import io.bluetape4k.spring.batch.exposed.AbstractExposedBatchTest
import io.bluetape4k.spring.batch.exposed.SourceTable
import io.bluetape4k.spring.batch.exposed.insertTestData
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.spring.batch.exposed.support.castToLong
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ExposedRangePartitionerTest : AbstractExposedBatchTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `빈 테이블에서 단일 빈 파티션 반환`(testDB: TestDB) {
        withBatchTables(testDB) {
            val partitioner = ExposedRangePartitioner.forEntityId(
                table = SourceTable,
                gridSize = 4,
                database = testDB.db,
            )

            val partitions = partitioner.partition(4)

            partitions shouldHaveSize 1
            partitions["partition-0"]!!.getLong(ExposedRangePartitioner.PARTITION_MIN_ID) shouldBeEqualTo 0L
            partitions["partition-0"]!!.getLong(ExposedRangePartitioner.PARTITION_MAX_ID) shouldBeEqualTo -1L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `1000건 데이터를 4개 파티션으로 균등 분할`(testDB: TestDB) {
        withBatchTables(testDB) {
            insertTestData(1000)

            val partitioner = ExposedRangePartitioner.forEntityId(
                table = SourceTable,
                gridSize = 4,
                database = testDB.db,
            )

            val partitions = partitioner.partition(4)

            partitions shouldHaveSize 4

            val ranges = partitions.values.map { ctx ->
                ctx.getLong(ExposedRangePartitioner.PARTITION_MIN_ID)..ctx.getLong(ExposedRangePartitioner.PARTITION_MAX_ID)
            }.sortedBy { it.first }

            ranges.first().first shouldBeEqualTo 1L
            ranges.last().last shouldBeEqualTo 1000L

            for (i in 0 until ranges.size - 1) {
                ranges[i].last + 1 shouldBeEqualTo ranges[i + 1].first
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `단일 행 테이블에서 1개 파티션 반환`(testDB: TestDB) {
        withBatchTables(testDB) {
            insertTestData(1)

            val partitioner = ExposedRangePartitioner.forEntityId(
                table = SourceTable,
                gridSize = 8,
                database = testDB.db,
            )

            val partitions = partitioner.partition(8)

            partitions shouldHaveSize 1
            partitions["partition-0"]!!.getLong(ExposedRangePartitioner.PARTITION_MIN_ID) shouldBeEqualTo 1L
            partitions["partition-0"]!!.getLong(ExposedRangePartitioner.PARTITION_MAX_ID) shouldBeEqualTo 1L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `gridSize가 totalRange보다 클 때 safeGridSize로 보정`(testDB: TestDB) {
        withBatchTables(testDB) {
            insertTestData(3)

            val partitioner = ExposedRangePartitioner.forEntityId(
                table = SourceTable,
                gridSize = 10,
                database = testDB.db,
            )

            val partitions = partitioner.partition(10)

            partitions.size shouldBeLessOrEqualTo 3
            partitions.size shouldBeGreaterOrEqualTo 1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Long 전체 범위도 overflow 없이 4개 파티션으로 분할`(testDB: TestDB) {
        withBatchTables(testDB) {
            val partitioner = ExposedRangePartitioner(
                table = SourceTable,
                column = SourceTable.id.castToLong(),
                gridSize = 4,
                database = testDB.db,
                selectMinMax = { Long.MIN_VALUE to Long.MAX_VALUE },
            )

            val partitions = partitioner.partition(4)
            val ranges = partitions.values
                .sortedBy { it.getLong(ExposedRangePartitioner.PARTITION_MIN_ID) }
                .map { context ->
                    context.getLong(ExposedRangePartitioner.PARTITION_MIN_ID) to
                        context.getLong(ExposedRangePartitioner.PARTITION_MAX_ID)
                }

            ranges shouldBeEqualTo listOf(
                Long.MIN_VALUE to -4_611_686_018_427_387_905L,
                -4_611_686_018_427_387_904L to -1L,
                0L to 4_611_686_018_427_387_903L,
                4_611_686_018_427_387_904L to Long.MAX_VALUE,
            )
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Long 최대값 인근의 작은 범위도 누락 없이 분할`(testDB: TestDB) {
        withBatchTables(testDB) {
            val partitioner = ExposedRangePartitioner(
                table = SourceTable,
                column = SourceTable.id.castToLong(),
                gridSize = 8,
                database = testDB.db,
                selectMinMax = { Long.MAX_VALUE - 2L to Long.MAX_VALUE },
            )

            val partitions = partitioner.partition(8)
            val ranges = partitions.values
                .sortedBy { it.getLong(ExposedRangePartitioner.PARTITION_MIN_ID) }
                .map { context ->
                    context.getLong(ExposedRangePartitioner.PARTITION_MIN_ID) to
                        context.getLong(ExposedRangePartitioner.PARTITION_MAX_ID)
                }

            ranges shouldBeEqualTo listOf(
                Long.MAX_VALUE - 2L to Long.MAX_VALUE - 2L,
                Long.MAX_VALUE - 1L to Long.MAX_VALUE - 1L,
                Long.MAX_VALUE to Long.MAX_VALUE,
            )
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `음수에서 양수로 이어지는 범위는 파티션 사이에 누락과 중복이 없다`(testDB: TestDB) {
        withBatchTables(testDB) {
            val partitioner = ExposedRangePartitioner(
                table = SourceTable,
                column = SourceTable.id.castToLong(),
                gridSize = 4,
                database = testDB.db,
                selectMinMax = { -3L to 3L },
            )

            val partitions = partitioner.partition(4)
            val ranges = partitions.values
                .sortedBy { it.getLong(ExposedRangePartitioner.PARTITION_MIN_ID) }
                .map { context ->
                    context.getLong(ExposedRangePartitioner.PARTITION_MIN_ID) to
                        context.getLong(ExposedRangePartitioner.PARTITION_MAX_ID)
                }

            ranges shouldBeEqualTo listOf(-3L to -3L, -2L to -2L, -1L to -1L, 0L to 3L)
            ranges.first().first shouldBeEqualTo -3L
            ranges.last().second shouldBeEqualTo 3L
            ranges.zipWithNext().forEach { (left, right) ->
                (left.second + 1L) shouldBeEqualTo right.first
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `min이 max보다 크면 명확한 IllegalArgumentException을 반환`(testDB: TestDB) {
        withBatchTables(testDB) {
            val partitioner = ExposedRangePartitioner(
                table = SourceTable,
                column = SourceTable.id.castToLong(),
                gridSize = 4,
                database = testDB.db,
                selectMinMax = { 3L to 2L },
            )

            assertFailsWith<IllegalArgumentException> {
                partitioner.partition(4)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `직접 생성한 양수 gridSize는 partition의 0 이하 인자에서 fallback으로 사용`(testDB: TestDB) {
        withBatchTables(testDB) {
            val partitioner = ExposedRangePartitioner(
                table = SourceTable,
                column = SourceTable.id.castToLong(),
                gridSize = 4,
                database = testDB.db,
                selectMinMax = { 1L to 10L },
            )

            val expectedRanges = listOf(1L to 2L, 3L to 4L, 5L to 6L, 7L to 10L)
            listOf(0, -1).forEach { requestedGridSize ->
                val ranges = partitioner.partition(requestedGridSize).values
                    .sortedBy { it.getLong(ExposedRangePartitioner.PARTITION_MIN_ID) }
                    .map { context ->
                        context.getLong(ExposedRangePartitioner.PARTITION_MIN_ID) to
                            context.getLong(ExposedRangePartitioner.PARTITION_MAX_ID)
                    }

                ranges shouldBeEqualTo expectedRanges
            }
        }
    }

    @Test
    fun `생성자의 0 또는 음수 gridSize를 거부`() {
        assertFailsWith<IllegalArgumentException> {
            ExposedRangePartitioner.forEntityId(SourceTable, gridSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ExposedRangePartitioner.forEntityId(SourceTable, gridSize = -1)
        }
    }
}
