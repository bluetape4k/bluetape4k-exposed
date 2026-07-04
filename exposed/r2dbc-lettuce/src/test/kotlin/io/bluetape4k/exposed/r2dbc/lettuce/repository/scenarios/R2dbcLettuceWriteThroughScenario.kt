package io.bluetape4k.exposed.r2dbc.lettuce.repository.scenarios

import io.bluetape4k.exposed.r2dbc.lettuce.repository.scenarios.R2DbcLettuceJCacheTestScenario.Companion.ENABLE_DIALECTS_METHOD
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldHaveSize
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.Serializable

/**
 * Write-through 캐시 전략 R2DBC Lettuce 시나리오.
 *
 * - put() 시 Redis와 DB를 즉시 갱신
 * - invalidate() 시 Redis 캐시만 제거하고 DB는 유지
 */
interface R2dbcLettuceWriteThroughScenario<ID: Any, E: Serializable>: R2DbcLettuceJCacheTestScenario<ID, E> {
    companion object: KLoggingChannel()

    /** 기존 엔티티의 이메일을 수정한 복사본을 반환한다 */
    suspend fun updateEmail(entity: E): E

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `put - 캐시와 DB 모두에 즉시 반영된다`(testDB: TestDB) =
        runSuspendIO {
            withR2dbcEntityTable(testDB) {
                val id = getExistingId()
                val entity = repository.findByIdFromDb(id).shouldNotBeNull()
                val updated = updateEmail(entity)
                repository.put(id, updated)

                repository.get(id) shouldBeEqualTo updated
                repository.findByIdFromDb(id) shouldBeEqualTo updated
            }
        }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `putAll - Map 일괄 저장 후 캐시와 DB 모두 반영된다`(testDB: TestDB) =
        runSuspendIO {
            withR2dbcEntityTable(testDB) {
                val ids = getExistingIds()
                val entities = repository.getAll(ids)
                val updated = entities.mapValues { (_, v) -> updateEmail(v) }
                repository.putAll(updated)

                updated.forEach { (id, entity) ->
                    repository.get(id) shouldBeEqualTo entity
                    repository.findByIdFromDb(id) shouldBeEqualTo entity
                }
            }
        }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `invalidate - 캐시에서만 삭제하고 DB는 유지된다`(testDB: TestDB) =
        runSuspendIO {
            withR2dbcEntityTable(testDB) {
                val id = getExistingId()
                val entity = repository.findByIdFromDb(id).shouldNotBeNull()
                repository.put(id, entity)
                repository.invalidate(id)

                repository.findByIdFromDb(id) shouldBeEqualTo entity
                repository.get(id) shouldBeEqualTo entity
            }
        }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `invalidateAll - 복수 ID를 캐시에서만 삭제하고 DB는 유지된다`(testDB: TestDB) =
        runSuspendIO {
            withR2dbcEntityTable(testDB) {
                val ids = getExistingIds()
                val entities = ids.associateWith { id -> repository.findByIdFromDb(id).shouldNotBeNull() }
                entities.forEach { (id, entity) -> repository.put(id, entity) }
                repository.invalidateAll(ids)

                entities.forEach { (id, entity) ->
                    repository.findByIdFromDb(id) shouldBeEqualTo entity
                    repository.get(id) shouldBeEqualTo entity
                }
            }
        }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `findByIdFromDb - DB 직접 조회가 정상 동작한다`(testDB: TestDB) =
        runSuspendIO {
            withR2dbcEntityTable(testDB) {
                val id = getExistingId()
                repository.findByIdFromDb(id).shouldNotBeNull()
            }
        }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `findAllFromDb - 복수 ID DB 직접 조회가 정상 동작한다`(testDB: TestDB) =
        runSuspendIO {
            withR2dbcEntityTable(testDB) {
                val ids = getExistingIds()
                val entities = repository.findAllFromDb(ids)
                entities shouldHaveSize ids.size
            }
        }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `countFromDb - DB 전체 레코드 수를 반환한다`(testDB: TestDB) =
        runSuspendIO {
            withR2dbcEntityTable(testDB) {
                repository.countFromDb() shouldBeEqualTo getExistingIds().size.toLong()
            }
        }
}
