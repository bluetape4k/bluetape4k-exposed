package io.bluetape4k.exposed.r2dbc.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.exposed.core.auditable.Auditable
import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable
import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant

/**
 * [AuditableR2dbcRepository] 통합 테스트입니다.
 *
 * R2DBC Repository에서 감사 컬럼 기본값, 명시적 수정 사용자, 기본 수정 사용자,
 * 일반 update와 audited update의 차이를 검증합니다.
 */
class AuditableR2dbcRepositoryTest: AbstractExposedR2dbcTest() {

    object ArticleTable: AuditableLongIdTable("auditable_r2dbc_articles") {
        val title = varchar("title", 100)
        val category = varchar("category", 50)
    }

    data class ArticleRecord(
        val title: String,
        val category: String,
        override val createdBy: String = UserContext.DEFAULT_USERNAME,
        override val createdAt: Instant? = null,
        override val updatedBy: String? = null,
        override val updatedAt: Instant? = null,
        val id: Long = 0L,
    ): Auditable

    object ArticleRepository: LongAuditableR2dbcRepository<ArticleRecord, ArticleTable> {
        override val table = ArticleTable

        override fun extractId(entity: ArticleRecord): Long = entity.id

        override suspend fun ResultRow.toEntity(): ArticleRecord = ArticleRecord(
            id = this[ArticleTable.id].value,
            title = this[ArticleTable.title],
            category = this[ArticleTable.category],
            createdBy = this[ArticleTable.createdBy],
            createdAt = this[ArticleTable.createdAt],
            updatedBy = this[ArticleTable.updatedBy],
            updatedAt = this[ArticleTable.updatedAt],
        )
    }

    private suspend fun insertArticle(
        title: String = faker.book().title(),
        category: String = "draft",
    ): Long =
        ArticleTable.insertAndGetId {
            it[ArticleTable.title] = title
            it[ArticleTable.category] = category
        }.value

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `INSERT 후 createdBy 는 system 이고 createdAt 은 설정된다`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, ArticleTable) {
            val id = insertArticle()

            val article = ArticleRepository.findById(id)
            article.createdBy shouldBeEqualTo UserContext.DEFAULT_USERNAME
            article.createdAt.shouldNotBeNull()
            article.updatedBy.shouldBeNull()
            article.updatedAt.shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `auditedUpdateById 는 명시한 updatedBy 와 updatedAt 을 설정한다`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, ArticleTable) {
            val id = insertArticle()

            val updatedCount = ArticleRepository.auditedUpdateById(id, updatedBy = "editor") {
                it[title] = "updated-title"
            }

            updatedCount shouldBeEqualTo 1
            val article = ArticleRepository.findById(id)
            article.title shouldBeEqualTo "updated-title"
            article.updatedBy shouldBeEqualTo "editor"
            article.updatedAt.shouldNotBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `auditedUpdateAll 은 조건에 맞는 모든 row 의 감사 필드를 설정한다`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, ArticleTable) {
            val id1 = insertArticle(title = "first", category = "news")
            val id2 = insertArticle(title = "second", category = "news")
            insertArticle(title = "third", category = "memo")

            val updatedCount = ArticleRepository.auditedUpdateAll(
                updatedBy = "batch",
                predicate = { ArticleTable.category eq "news" },
            ) {
                it[category] = "published"
            }

            updatedCount shouldBeEqualTo 2
            val article1 = ArticleRepository.findById(id1)
            val article2 = ArticleRepository.findById(id2)
            article1.category shouldBeEqualTo "published"
            article2.category shouldBeEqualTo "published"
            article1.updatedBy shouldBeEqualTo "batch"
            article2.updatedBy shouldBeEqualTo "batch"
            article1.updatedAt.shouldNotBeNull()
            article2.updatedAt.shouldNotBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `updatedBy 를 생략하면 기본 사용자명을 사용한다`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, ArticleTable) {
            val id = insertArticle()

            ArticleRepository.auditedUpdateById(id) {
                it[title] = "default-user"
            }

            val article = ArticleRepository.findById(id)
            article.updatedBy shouldBeEqualTo UserContext.DEFAULT_USERNAME
            article.updatedAt.shouldNotBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `일반 updateById 는 감사 필드를 자동 설정하지 않는다`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, ArticleTable) {
            val id = insertArticle()

            ArticleRepository.updateById(id) {
                it[ArticleTable.title] = "plain-update"
            }

            val article = ArticleRepository.findById(id)
            article.title shouldBeEqualTo "plain-update"
            article.updatedBy.shouldBeNull()
            article.updatedAt.shouldBeNull()
        }
    }
}
