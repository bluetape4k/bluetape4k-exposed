package io.bluetape4k.exposed.redisson.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.cache.CacheMode
import io.bluetape4k.exposed.cache.CacheWriteMode
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.redisson.api.RMap
import java.io.Serializable

class JdbcRedissonRepositoryDefaultMethodTest {

    private val cache = mockk<RMap<Long, ProbeEntity?>>(relaxed = true)
    private val repository = ProbeJdbcRedissonRepository(cache)

    @BeforeEach
    fun beforeEach() {
        clearMocks(cache)
    }

    @Test
    fun `read methods delegate to redisson map`() {
        val entity = ProbeEntity(1L, "alice")
        every { cache.containsKey(1L) } returns true
        every { cache.containsKey(2L) } returns false
        every { cache[1L] } returns entity
        every { cache[2L] } returns null

        repository.containsKey(1L).shouldBeTrue()
        repository.containsKey(2L).shouldBeFalse()
        repository.get(1L) shouldBeEqualTo entity
        repository.get(2L).shouldBeNull()

        verify(exactly = 1) { cache.containsKey(1L) }
        verify(exactly = 1) { cache.containsKey(2L) }
        verify(exactly = 1) { cache[1L] }
        verify(exactly = 1) { cache[2L] }
    }

    @Test
    fun `write and clear methods delegate to redisson map`() {
        val alice = ProbeEntity(1L, "alice")
        val bob = ProbeEntity(2L, "bob")
        every { cache.fastPut(1L, alice) } returns true
        every { cache.putAll(mapOf(1L to alice, 2L to bob), 2) } returns Unit
        every { cache.fastRemove(1L) } returns 1L
        every { cache.fastRemove(2L) } returns 1L
        every { cache.clear() } returns Unit

        repository.put(1L, alice)
        repository.putAll(mapOf(1L to alice, 2L to bob), batchSize = 2)
        repository.upsertAll(mapOf(2L to bob), batchSize = 1)
        repository.invalidate(1L)
        repository.invalidateAll(listOf(1L, 2L))
        repository.clear()

        verify(exactly = 1) { cache.fastPut(1L, alice) }
        verify(exactly = 1) { cache.putAll(mapOf(1L to alice, 2L to bob), 2) }
        verify(exactly = 1) { cache.putAll(mapOf(2L to bob), 1) }
        verify(exactly = 2) { cache.fastRemove(1L) }
        verify(exactly = 1) { cache.fastRemove(2L) }
        verify(exactly = 1) { cache.clear() }
    }

    @Test
    fun `empty bulk operations return before touching redisson map`() {
        repository.upsertAll(emptyMap(), batchSize = 1)
        repository.invalidateAll(emptyList())

        verify(exactly = 0) { cache.putAll(any<Map<Long, ProbeEntity>>(), any()) }
        verify(exactly = 0) { cache.fastRemove(any()) }
    }

    @Test
    fun `bulk operations require positive batch size`() {
        assertFailsWith<IllegalArgumentException> {
            repository.upsertAll(mapOf(1L to ProbeEntity(1L, "alice")), batchSize = 0)
        }

        assertFailsWith<IllegalArgumentException> {
            repository.putAll(mapOf(1L to ProbeEntity(1L, "alice")), batchSize = -1)
        }

        verify(exactly = 0) { cache.putAll(any<Map<Long, ProbeEntity>>(), any()) }
    }

    @Test
    fun `invalidate by pattern removes matching keys and sums removed count`() {
        every { cache.keySet("user:*", 20) } returns setOf(1L, 2L)
        every { cache.fastRemove(1L) } returns 1L
        every { cache.fastRemove(2L) } returns 0L

        repository.invalidateByPattern("user:*", 20) shouldBeEqualTo 1L

        verify(exactly = 1) { cache.keySet("user:*", 20) }
        verify(exactly = 1) { cache.fastRemove(1L) }
        verify(exactly = 1) { cache.fastRemove(2L) }
    }

    @Test
    fun `invalidate by pattern returns zero when no keys match`() {
        every { cache.keySet("missing:*", 20) } returns emptySet()

        repository.invalidateByPattern("missing:*", 20) shouldBeEqualTo 0L

        verify(exactly = 1) { cache.keySet("missing:*", 20) }
        verify(exactly = 0) { cache.fastRemove(any()) }
    }

    @Test
    fun `invalidate by pattern requires positive scan count`() {
        assertFailsWith<IllegalArgumentException> {
            repository.invalidateByPattern("user:*", 0)
        }

        verify(exactly = 0) { cache.keySet(any<String>(), any()) }
        verify(exactly = 0) { cache.fastRemove(any()) }
    }

    private data class ProbeEntity(
        val id: Long,
        val name: String,
    ): Serializable

    private object ProbeTable: LongIdTable("redisson_repository_probe")

    private class ProbeJdbcRedissonRepository(
        override val cache: RMap<Long, ProbeEntity?>,
    ): JdbcRedissonRepository<Long, ProbeEntity> {

        override val table: IdTable<Long> = ProbeTable
        override val cacheName: String = "probe"
        override val cacheMode: CacheMode = CacheMode.REMOTE
        override val cacheWriteMode: CacheWriteMode = CacheWriteMode.WRITE_THROUGH

        override fun ResultRow.toEntity(): ProbeEntity = error("not used")
        override fun extractId(entity: ProbeEntity): Long = entity.id
        override fun findByIdFromDb(id: Long): ProbeEntity? = null
        override fun findAllFromDb(ids: Collection<Long>): List<ProbeEntity> = emptyList()
        override fun countFromDb(): Long = 0L
        override fun getAll(ids: Collection<Long>): Map<Long, ProbeEntity> = emptyMap()
        override fun findAll(
            limit: Int?,
            offset: Long?,
            sortBy: Expression<*>,
            sortOrder: SortOrder,
            where: () -> Op<Boolean>,
        ): List<ProbeEntity> = emptyList()
    }
}
