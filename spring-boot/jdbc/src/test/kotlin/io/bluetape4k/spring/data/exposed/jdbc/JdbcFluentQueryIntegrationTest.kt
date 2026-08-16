package io.bluetape4k.spring.data.exposed.jdbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.spring.data.exposed.jdbc.domain.UserEntity
import io.bluetape4k.spring.data.exposed.jdbc.domain.Users
import io.bluetape4k.spring.data.exposed.jdbc.repository.UserJdbcRepository
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformationImpl
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.dao.LongEntityClass
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.IncorrectResultSizeDataAccessException
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.data.domain.Example
import org.springframework.data.domain.ExampleMatcher
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.repository.query.FluentQuery
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class JdbcFluentQueryIntegrationTest: AbstractExposedJdbcRepositoryTest() {

    @Autowired
    private lateinit var factoryRepository: UserJdbcRepository

    private val repository = SimpleExposedJdbcRepository(
        ExposedEntityInformationImpl(UserEntity::class.java),
    )

    @Test
    fun `closed interface Kotlin DTO and Java record projections select only required columns`() {
        transaction {
            seedUsers()
            val example = allUsersExample()
            val statements = mutableListOf<String>()
            addLogger(recordSql(statements))

            val interfaces = repository.findBy(example) { query ->
                query.`as`(UserNameView::class.java).sortBy(Sort.by("name")).all()
            }
            val dataClasses = repository.findBy(example) { query ->
                query.`as`(UserNameDto::class.java).sortBy(Sort.by("name")).all()
            }
            val records = repository.findBy(example) { query ->
                query.`as`(JdbcUserNameRecord::class.java).sortBy(Sort.by("name")).all()
            }

            interfaces.map { it.name } shouldBeEqualTo listOf("Alice", "Bob", "Charlie")
            dataClasses.map { it.name } shouldBeEqualTo listOf("Alice", "Bob", "Charlie")
            records.map { it.name() } shouldBeEqualTo listOf("Alice", "Bob", "Charlie")
            statements shouldHaveSize 3
            statements.forEach { sql ->
                sql.contains("name", ignoreCase = true).shouldBeTrue()
                sql.contains("email", ignoreCase = true).shouldBeFalse()
                sql.contains("age", ignoreCase = true).shouldBeFalse()
            }
        }
    }

    @Test
    fun `sort limit first all count exists and page use terminal semantics`() {
        transaction {
            seedUsers()
            val example = allUsersExample()

            repository.findBy(example) { query ->
                query.sortBy(Sort.by(Sort.Direction.DESC, "age")).limit(2).all()
            }.map { it.age } shouldBeEqualTo listOf(40, 30)

            repository.findBy(example) { it.sortBy(Sort.by("age")).firstValue() }?.age shouldBeEqualTo 20
            repository.findBy(example) { it.limit(1).limit(0).all() } shouldHaveSize 3
            repository.findBy(example) { it.limit(1).count() } shouldBeEqualTo 3L
            repository.findBy(example) { it.limit(1).exists() }.shouldBeTrue()

            val page = repository.findBy(example) {
                it.sortBy(Sort.by("name")).page(PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "age")))
            }
            page.content.map { it.age } shouldBeEqualTo listOf(20)
            page.totalElements shouldBeEqualTo 3L
        }
    }

    @Test
    fun `one terminals reject multiple rows and findOne shares cardinality`() {
        transaction {
            seedUsers()
            val example = allUsersExample()

            assertFailsWith<IncorrectResultSizeDataAccessException> {
                repository.findBy(example) { it.oneValue() }
            }
            assertFailsWith<IncorrectResultSizeDataAccessException> {
                repository.findBy(example) { it.limit(1).oneValue() }
            }
            assertFailsWith<IncorrectResultSizeDataAccessException> {
                repository.findOne(example)
            }
        }
    }

    @Test
    fun `empty project keeps automatic entity column selection`() {
        transaction {
            seedUsers()

            val users = repository.findBy(allUsersExample()) {
                it.project(mutableListOf("name")).project(mutableListOf()).sortBy(Sort.by("name")).all()
            }

            users.map { it.name } shouldBeEqualTo listOf("Alice", "Bob", "Charlie")
        }
    }

    @Test
    fun `entity terminal preserves transaction identity cache`() {
        transaction {
            seedUsers()
            val probe = UserEntity.find { Users.name eq "Alice" }.single()
            val example = Example.of(
                probe,
                ExampleMatcher.matchingAll().withIgnorePaths("email", "age"),
            )

            val result = repository.findBy(example) { it.oneValue() }

            (result === probe).shouldBeTrue()
        }
    }

    @Test
    fun `fluent query cannot escape callback scope`() {
        transaction {
            seedUsers()
            lateinit var escaped: FluentQuery.FetchableFluentQuery<UserEntity>

            repository.findBy(allUsersExample()) { query ->
                escaped = query
                query.count()
            }

            assertFailsWith<InvalidDataAccessApiUsageException> { escaped.all() }
            assertFailsWith<InvalidDataAccessApiUsageException> { escaped.sortBy(Sort.by("name")) }
            assertFailsWith<InvalidDataAccessApiUsageException> { escaped.limit(1) }
            assertFailsWith<InvalidDataAccessApiUsageException> { escaped.`as`(UserNameView::class.java) }
            assertFailsWith<InvalidDataAccessApiUsageException> { escaped.project(mutableListOf("name")) }
        }

        lateinit var escapedOutsideTransaction: FluentQuery.FetchableFluentQuery<UserEntity>
        transaction {
            repository.findBy(allUsersExample()) { query ->
                escapedOutsideTransaction = query
                query.count()
            }
        }
        assertFailsWith<InvalidDataAccessApiUsageException> { escapedOutsideTransaction.all() }
    }

    @Test
    fun `terminal statement budgets and page count optimization stay bounded`() {
        transaction {
            seedUsers()
            val example = allUsersExample()
            val statements = mutableListOf<String>()
            addLogger(recordSql(statements))

            repository.findBy(example) { it.firstValue() }
            selectStatements(statements) shouldHaveSize 1
            selectStatements(statements).single().contains("limit", ignoreCase = true).shouldBeTrue()

            statements.clear()
            assertFailsWith<IncorrectResultSizeDataAccessException> {
                repository.findBy(example) { it.oneValue() }
            }
            selectStatements(statements) shouldHaveSize 1
            selectStatements(statements).single().contains("limit", ignoreCase = true).shouldBeTrue()
        }
    }

    @Test
    fun `count exists and page ignore projection and plan limit`() {
        transaction {
            seedUsers()
            val example = allUsersExample()
            val statements = mutableListOf<String>()
            addLogger(recordSql(statements))

            repository.findBy(example) {
                it.`as`(UserNameView::class.java).project(mutableListOf("name")).limit(1).count()
            } shouldBeEqualTo 3L
            selectStatements(statements) shouldHaveSize 1

            statements.clear()
            repository.findBy(example) { it.limit(1).exists() }.shouldBeTrue()
            selectStatements(statements) shouldHaveSize 1

            statements.clear()
            val firstPage = repository.findBy(example) { it.limit(1).page(PageRequest.of(0, 2)) }
            firstPage.content shouldHaveSize 2
            firstPage.totalElements shouldBeEqualTo 3L
            selectStatements(statements) shouldHaveSize 2

            statements.clear()
            val lastPage = repository.findBy(example) { it.limit(1).page(PageRequest.of(1, 2)) }
            lastPage.content shouldHaveSize 1
            lastPage.totalElements shouldBeEqualTo 3L
            selectStatements(statements) shouldHaveSize 1

            val unpaged = repository.findBy(example) {
                it.limit(2).page(Pageable.unpaged(Sort.by(Sort.Direction.DESC, "age")))
            }
            unpaged.content.map { it.age } shouldBeEqualTo listOf(40, 30)

            statements.clear()
            repository.findBy(example) {
                it.sortBy(Sort.by("name")).page(PageRequest.of(0, 2))
            }
            selectStatements(statements).first().contains("order by", ignoreCase = true).shouldBeFalse()
        }
    }

    @Test
    fun `invalid projection and sort options fail before SQL`() {
        transaction {
            seedUsers()
            val example = allUsersExample()
            val statements = mutableListOf<String>()
            val transformerCalls = AtomicInteger()
            addLogger(recordSql(statements))

            assertFailsWith<InvalidDataAccessApiUsageException> {
                repository.findBy(example) {
                    it.`as`(UserNameView::class.java).project(mutableListOf("email")).all()
                }
            }
            assertFailsWith<InvalidDataAccessApiUsageException> {
                repository.findBy(example) { it.sortBy(Sort.by(Sort.Order.asc("missing"))).all() }
            }
            assertFailsWith<InvalidDataAccessApiUsageException> {
                repository.findBy(example) {
                    it.sortBy(Sort.by(Sort.Order.asc("name").ignoreCase())).all()
                }
            }
            assertFailsWith<UnsupportedOperationException> {
                val transformedExample = Example.of(
                    example.probe,
                    ExampleMatcher.matchingAll()
                        .withIgnorePaths("email", "age")
                        .withTransformer("name") { value ->
                            transformerCalls.incrementAndGet()
                            value
                        },
                )
                repository.findBy(transformedExample) { it.`as`(OpenUserView::class.java).stream() }
            }

            transformerCalls.get() shouldBeEqualTo 0
            selectStatements(statements) shouldHaveSize 0
        }
    }

    @Test
    @Transactional
    fun `factory and direct repositories share projection behavior`() {
        seedUsers()
        val example = allUsersExample()

        val direct = repository.findBy(example) {
            it.`as`(UserNameView::class.java).sortBy(Sort.by("name")).all()
        }
        val factory = factoryRepository.findBy(example) {
            it.`as`(UserNameView::class.java).sortBy(Sort.by("name")).all()
        }

        factory.map { it.name } shouldBeEqualTo direct.map { it.name }
    }

    @Test
    @Transactional
    fun `factory stream joins caller owned Spring transaction`() {
        seedUsers()

        factoryRepository.findBy(allUsersExample()) {
            it.`as`(UserNameView::class.java).sortBy(Sort.by("name")).stream()
        }.use { rows ->
            rows.map { it.name }.toList() shouldBeEqualTo listOf("Alice", "Bob", "Charlie")
        }

        factoryRepository.count() shouldBeEqualTo 3L
    }

    @Test
    fun `factory stream rejects repository owned Spring transaction`() {
        val example = transaction {
            seedUsers()
            allUsersExample()
        }

        assertFailsWith<InvalidDataAccessApiUsageException> {
            factoryRepository.findBy(example) { it.stream() }
        }
    }

    @Test
    fun `custom EntityClass searchQuery accepts filters but rejects query shaping before SQL`() {
        transaction {
            seedUsers()
            val statements = mutableListOf<String>()
            addLogger(recordSql(statements))

            val filteringEntityClass = object: LongEntityClass<UserEntity>(Users) {
                override fun searchQuery(op: Op<Boolean>): Query = super.searchQuery(op and (Users.age greaterEq 30))
            }
            customRepository(filteringEntityClass)
                .findBy(allUsersExample()) { it.sortBy(Sort.by("name")).all() }
                .map { it.name } shouldBeEqualTo listOf("Alice", "Charlie")

            val transformerCalls = AtomicInteger()
            val shapingExample = Example.of(
                UserEntity.find { Users.name eq "Alice" }.single(),
                ExampleMatcher.matchingAll()
                    .withIgnorePaths("email", "age")
                    .withTransformer("name") { value ->
                        transformerCalls.incrementAndGet()
                        value
                    },
            )
            statements.clear()
            val shapingEntityClass = object: LongEntityClass<UserEntity>(Users) {
                override fun searchQuery(op: Op<Boolean>): Query = super.searchQuery(op).orderBy(Users.name)
            }
            assertFailsWith<UnsupportedOperationException> {
                customRepository(shapingEntityClass).findBy(shapingExample) { it.all() }
            }

            val partialSelectionEntityClass = object: LongEntityClass<UserEntity>(Users) {
                override fun searchQuery(op: Op<Boolean>): Query =
                    super.searchQuery(op).adjustSelect { select(Users.name) }
            }
            assertFailsWith<UnsupportedOperationException> {
                customRepository(partialSelectionEntityClass).findBy(shapingExample) { it.all() }
            }
            transformerCalls.get() shouldBeEqualTo 0
            selectStatements(statements) shouldHaveSize 0
        }
    }

    @Test
    fun `stream maps rows lazily and closes on exhaustion`() {
        transaction {
            db.supportsMultipleResultSets.shouldBeFalse()
            seedUsers()
            CountingNameDto.instances.set(0)

            val stream = repository.findBy(allUsersExample()) {
                it.`as`(CountingNameDto::class.java).sortBy(Sort.by("name")).stream()
            }
            CountingNameDto.instances.get() shouldBeEqualTo 0

            stream.use { rows ->
                val iterator = rows.iterator()
                iterator.next().name shouldBeEqualTo "Alice"
                CountingNameDto.instances.get() shouldBeEqualTo 1
                iterator.asSequence().toList() shouldHaveSize 2
            }

            repository.count() shouldBeEqualTo 3L
        }
    }

    @Test
    fun `open stream rejects nested Exposed SQL until it is closed`() {
        transaction {
            seedUsers()
            val stream = repository.findBy(allUsersExample()) { it.stream() }

            assertFailsWith<InvalidDataAccessApiUsageException> { repository.count() }

            stream.close()
            repository.count() shouldBeEqualTo 3L
        }
    }

    @Test
    fun `stream rejects consumption outside owner transaction or thread`() {
        val escaped = transaction {
            seedUsers()
            repository.findBy(allUsersExample()) { it.stream() }
        }
        assertFailsWith<InvalidDataAccessApiUsageException> { escaped.findFirst() }

        val outsideTransactionExample = transaction { allUsersExample() }
        assertFailsWith<InvalidDataAccessApiUsageException> {
            repository.findBy(outsideTransactionExample) { it.stream() }
        }

        transaction {
            val stream = repository.findBy(allUsersExample()) { it.stream() }
            Executors.newSingleThreadExecutor().use { executor ->
                val failure = executor.submit<Throwable?> {
                    runCatching { stream.findFirst() }.exceptionOrNull()
                }.get()
                (failure is InvalidDataAccessApiUsageException).shouldBeTrue()
            }
        }
    }

    private fun allUsersExample(): Example<UserEntity> {
        val probe = UserEntity.find { Users.name eq "Alice" }.single()
        val matcher = ExampleMatcher.matchingAll().withIgnorePaths("name", "email", "age")
        return Example.of(probe, matcher)
    }

    private fun seedUsers() {
        UserEntity.new { name = "Alice"; email = "alice@example.com"; age = 30 }
        UserEntity.new { name = "Bob"; email = "bob@example.com"; age = 20 }
        UserEntity.new { name = "Charlie"; email = "charlie@example.com"; age = 40 }
    }

    private fun customRepository(entityClass: LongEntityClass<UserEntity>) =
        SimpleExposedJdbcRepository(
            ExposedEntityInformationImpl(UserEntity::class.java, entityClass),
        )

    private fun recordSql(statements: MutableList<String>) = object: SqlLogger {
        override fun log(context: StatementContext, transaction: Transaction) {
            statements += context.sql(transaction)
        }
    }

    private fun selectStatements(statements: List<String>): List<String> =
        statements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }
}

internal interface UserNameView {
    val name: String
}

internal interface OpenUserView {
    @get:org.springframework.beans.factory.annotation.Value("#{target.name}")
    val name: String
}

internal data class UserNameDto(
    val name: String,
)

internal class CountingNameDto(
    val name: String,
) {
    init {
        instances.incrementAndGet()
    }

    companion object {
        val instances = AtomicInteger()
    }
}
