package io.bluetape4k.exposed.r2dbc.dao

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotBeSameInstanceAs
import io.bluetape4k.exposed.r2dbc.tests.Containers
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.Statement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.dao.r2dbc.ExperimentalR2dbcDaoApi
import org.jetbrains.exposed.v1.dao.r2dbc.LongEntity
import org.jetbrains.exposed.v1.dao.r2dbc.LongEntityClass
import org.jetbrains.exposed.v1.dao.r2dbc.exceptions.EntityNotFoundException
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalR2dbcDaoApi::class)
class ExposedR2dbcDaoContractTest {
    @Test
    fun `newSuspend 는 반환 전에 자동 생성 ID를 확정한다`() = runSuspendIO {
        withH2Database { database ->
            suspendTransaction(database) {
                SchemaUtils.create(Parents, Children)
                val parent = Parent.newSuspend { name = "parent" }

                parent.id.value shouldBeGreaterOrEqualTo 1L
                Parent.findById(parent.id)?.name shouldBeEqualTo "parent"
            }
        }
    }

    @Test
    fun `관계 Flow는 활성 transaction 안에서 목록과 개수를 수집한다`() = runSuspendIO {
        withH2Database { database ->
            suspendTransaction(database) {
                SchemaUtils.create(Parents, Children)
                val parent = Parent.newSuspend { name = "parent" }
                Child.newSuspend {
                    name = "first"
                    this.parent.set(parent)
                }
                Child.newSuspend {
                    name = "second"
                    this.parent.set(parent)
                }

                parent.children.toList().map { it.name }.sorted() shouldBeEqualTo listOf("first", "second")
                parent.children.count() shouldBeEqualTo 2L
            }
        }
    }

    @Test
    fun `attach는 이전 transaction의 entity를 현재 cache에 연결한다`() = runSuspendIO {
        withH2Database { database ->
            val detached = suspendTransaction(database) {
                SchemaUtils.create(Parents, Children)
                Parent.newSuspend { name = "before" }
            }

            suspendTransaction(database) {
                Parent.attach(detached)
                detached.name = "after"
            }

            suspendTransaction(database) { Parent.findById(detached.id)?.name } shouldBeEqualTo "after"
        }
    }

    @Test
    fun `attach는 삭제된 행에 EntityNotFoundException을 던진다`() = runSuspendIO {
        withH2Database { database ->
            val detached = suspendTransaction(database) {
                SchemaUtils.create(Parents, Children)
                Parent.newSuspend { name = "deleted" }
            }
            suspendTransaction(database) {
                Parent.findById(detached.id)?.delete()
            }

            assertFailsWith<EntityNotFoundException> {
                suspendTransaction(database) { Parent.attach(detached) }
            }
        }
    }

    @Test
    fun `attach는 dirty cache 충돌을 거부한다`() = runSuspendIO {
        withH2Database { database ->
            val detached = suspendTransaction(database) {
                SchemaUtils.create(Parents, Children)
                Parent.newSuspend { name = "persisted" }
            }

            assertFailsWith<IllegalStateException> {
                suspendTransaction(database) {
                    Parent.findById(detached.id).shouldNotBeNull().name = "unflushed"
                    Parent.attach(detached)
                }
            }

            suspendTransaction(database) { Parent.findById(detached.id)?.name } shouldBeEqualTo "persisted"
        }
    }

    @Test
    fun `attach force는 dirty instance를 버리고 entity를 cache에 교체한다`() = runSuspendIO {
        withH2Database { database ->
            val detached = suspendTransaction(database) {
                SchemaUtils.create(Parents, Children)
                Parent.newSuspend { name = "persisted" }
            }

            suspendTransaction(database) {
                val dirty = Parent.findById(detached.id).shouldNotBeNull()
                dirty shouldNotBeSameInstanceAs detached
                dirty.name = "discarded"

                Parent.attach(detached, force = true)

                Parent.findById(detached.id) shouldBeSameInstanceAs detached
                detached.name = "replaced"
                detached.name shouldBeEqualTo "replaced"
            }

            suspendTransaction(database) { Parent.findById(detached.id)?.name } shouldBeEqualTo "replaced"
        }
    }

    @Test
    fun `겹친 transaction은 entity cache를 분리하고 각각 commit한다`() = runSuspendIO {
        withH2Database { database ->
            suspendTransaction(database) { SchemaUtils.create(Parents, Children) }
            val arrived = AtomicInteger()
            val bothReady = CompletableDeferred<Unit>()

            val names = withTimeout(10.seconds) {
                coroutineScope {
                    val first = async {
                        suspendTransaction(database) {
                            val entity = Parent.newSuspend { name = "first" }
                            if (arrived.incrementAndGet() == 2) bothReady.complete(Unit)
                            bothReady.await()
                            entity.name
                        }
                    }
                    val second = async {
                        suspendTransaction(database) {
                            val entity = Parent.newSuspend { name = "second" }
                            if (arrived.incrementAndGet() == 2) bothReady.complete(Unit)
                            bothReady.await()
                            entity.name
                        }
                    }
                    setOf(first.await(), second.await())
                }
            }

            names shouldBeEqualTo setOf("first", "second")
            suspendTransaction(database) { Parent.all().count() } shouldBeEqualTo 2L
        }
    }

    @Test
    fun `database 정리 실패 뒤에도 connection pool을 폐기한다`() {
        val url = h2Url()
        val pool = ConnectionPool(
            ConnectionPoolConfiguration.builder(ConnectionFactories.get(url))
                .maxSize(1)
                .build(),
        )
        val database = R2dbcDatabase.connect(
            databaseConfig = R2dbcDatabaseConfig.Builder().apply {
                setUrl(url)
                explicitDialect = H2Dialect()
                defaultMaxAttempts = 1
            },
        )
        val cleanupFailure = IllegalStateException("database cleanup failed")

        try {
            val actual = assertFailsWith<IllegalStateException> {
                disposeDatabaseAndPool(database, pool) {
                    TransactionManager.closeAndUnregister(it)
                    throw cleanupFailure
                }
            }

            actual shouldBeSameInstanceAs cleanupFailure
            pool.isDisposed.shouldBeTrue()
        } finally {
            pool.dispose()
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EXPOSED_TEST_DB", matches = "POSTGRESQL")
    fun `PostgreSQL에서 newSuspend 관계 Flow와 transaction 간 attach를 지원한다`() {
        Containers.Postgres

        runSuspendIO {
            val previousDatabase = TestDB.POSTGRESQL.db
            val database = TestDB.POSTGRESQL.connect()

            try {
                suspendTransaction(database) {
                    SchemaUtils.drop(Children, Parents)
                    SchemaUtils.create(Parents, Children)
                }

                val detached = suspendTransaction(database) {
                    val parent = Parent.newSuspend { name = "postgres-parent" }
                    Child.newSuspend {
                        name = "postgres-child"
                        this.parent.set(parent)
                    }

                    parent.id.value shouldBeGreaterOrEqualTo 1L
                    parent.children.toList().map { it.name } shouldBeEqualTo listOf("postgres-child")
                    parent.children.count() shouldBeEqualTo 1L
                    parent
                }

                suspendTransaction(database) {
                    Parent.attach(detached)
                    detached.name = "attached-postgres-parent"
                }

                suspendTransaction(database) { Parent.findById(detached.id)?.name } shouldBeEqualTo
                    "attached-postgres-parent"
            } finally {
                try {
                    suspendTransaction(database) { SchemaUtils.drop(Children, Parents) }
                } finally {
                    TestDB.POSTGRESQL.db = previousDatabase
                    TransactionManager.closeAndUnregister(database)
                }
            }
        }
    }

    @Test
    // 취소, rollback, connection 반환이 한 transaction 경로에서 이어지는지 함께 검증한다.
    @Suppress("LongMethod")
    fun `관계 Flow 취소 후 rollback 하고 단일 connection pool을 재사용한다`() = runSuspendIO {
        val url = h2Url()
        val events = ConcurrentLinkedQueue<String>()
        val pool = ConnectionPool(
            ConnectionPoolConfiguration.builder(ConnectionFactories.get(url))
                .maxSize(1)
                .build(),
        )
        var database: R2dbcDatabase? = null

        try {
            val activeDatabase = R2dbcDatabase.connect(
                trackingConnectionFactory(pool, events),
                R2dbcDatabaseConfig.Builder().apply {
                    setUrl(url)
                    explicitDialect = H2Dialect()
                    defaultMaxAttempts = 1
                },
            )
            database = activeDatabase

            val parentId = suspendTransaction(activeDatabase) {
                SchemaUtils.create(Parents, Children)
                val parent = Parent.newSuspend { name = "parent" }
                Child.newSuspend {
                    name = "committed"
                    this.parent.set(parent)
                }
                parent.id
            }
            events.clear()

            val firstRow = CompletableDeferred<Unit>()
            val holdCollection = CompletableDeferred<Unit>()
            val observedFailure = CompletableDeferred<Throwable>()
            val job = launch {
                try {
                    suspendTransaction(activeDatabase) {
                        val parent = Parent.findById(parentId).shouldNotBeNull()
                        Child.newSuspend {
                            name = "rolled-back"
                            this.parent.set(parent)
                        }
                        parent.children.collect {
                            firstRow.complete(Unit)
                            holdCollection.await()
                        }
                    }
                } catch (cause: Throwable) {
                    observedFailure.complete(cause)
                    throw cause
                }
            }

            withTimeout(10.seconds) { firstRow.await() }
            events.contains("acquire").shouldBeTrue()
            events.contains("statement-subscribed").shouldBeTrue()
            job.cancel(CancellationException("cancel after DAO Flow emitted its first row"))
            job.cancelAndJoin()

            observedFailure.await() shouldBeInstanceOf CancellationException::class
            withTimeout(10.seconds) {
                while (!events.contains("release-done")) yield()
            }
            (events.indexOf("acquire") < events.indexOf("statement-subscribed")).shouldBeTrue()
            events.contains("rollback-done").shouldBeTrue()
            events.contains("release-done").shouldBeTrue()
            (events.indexOf("rollback-done") < events.indexOf("release-done")).shouldBeTrue()
            suspendTransaction(activeDatabase) {
                Parent.findById(parentId).shouldNotBeNull().children.toList().map { it.name }
            } shouldBeEqualTo listOf("committed")
        } finally {
            disposeDatabaseAndPool(database, pool)
        }
    }

    private suspend fun withH2Database(block: suspend (R2dbcDatabase) -> Unit) {
        val url = h2Url()
        val database = R2dbcDatabase.connect(
            databaseConfig = R2dbcDatabaseConfig.Builder().apply {
                setUrl(url)
                explicitDialect = H2Dialect()
                defaultMaxAttempts = 1
            },
        )
        try {
            block(database)
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }

    private fun h2Url(): String {
        val databaseId = UUID.randomUUID().toString().replace("-", "")
        return "r2dbc:h2:mem:///dao_${databaseId};DB_CLOSE_DELAY=-1;"
    }

    private fun disposeDatabaseAndPool(
        database: R2dbcDatabase?,
        pool: ConnectionPool,
        closeDatabase: (R2dbcDatabase) -> Unit = { TransactionManager.closeAndUnregister(it) },
    ) {
        try {
            database?.let(closeDatabase)
        } finally {
            pool.dispose()
        }
    }

    /** 취소 경로에서 statement 구독, rollback, connection 반환 순서를 기록한다. */
    private fun trackingConnectionFactory(
        delegate: ConnectionFactory,
        events: ConcurrentLinkedQueue<String>,
    ): ConnectionFactory = object : ConnectionFactory {
        override fun getMetadata() = delegate.metadata

        override fun create(): Publisher<out Connection> = Flux.from(delegate.create()).map { connection ->
            events.add("acquire")
            proxy(connection) { method, args ->
                when (method.name) {
                    "createStatement" -> {
                        val statement = invoke(connection, method, args) as Statement
                        proxy(statement) { statementMethod, statementArgs ->
                            if (statementMethod.name == "execute") {
                                @Suppress("UNCHECKED_CAST")
                                val publisher = invoke(statement, statementMethod, statementArgs) as Publisher<Any>
                                Flux.from(publisher).doOnSubscribe { events.add("statement-subscribed") }
                            } else {
                                invoke(statement, statementMethod, statementArgs)
                            }
                        }
                    }

                    "rollbackTransaction" -> {
                        val publisher = invoke(connection, method, args) as Publisher<*>
                        Mono.from(publisher)
                            .doOnSubscribe { events.add("rollback") }
                            .doOnSuccess { events.add("rollback-done") }
                    }

                    "close" -> {
                        val publisher = invoke(connection, method, args) as Publisher<*>
                        Mono.from(publisher)
                            .doOnSubscribe { events.add("release") }
                            .doOnSuccess { events.add("release-done") }
                    }

                    else -> invoke(connection, method, args)
                }
            }
        }
    }

    private inline fun <reified T : Any> proxy(
        target: T,
        crossinline handler: (Method, Array<out Any?>?) -> Any?,
    ): T =
        Proxy.newProxyInstance(target.javaClass.classLoader, arrayOf(T::class.java)) { _, method, args ->
            handler(method, args)
        } as T

    private fun invoke(target: Any, method: Method, args: Array<out Any?>?): Any? = try {
        method.invoke(target, *args.orEmpty())
    } catch (cause: InvocationTargetException) {
        throw cause.targetException
    }

    private object Parents : LongIdTable("r2dbc_dao_parents") {
        val name = varchar("name", 100)
    }

    private object Children : LongIdTable("r2dbc_dao_children") {
        val parent = reference("parent_id", Parents)
        val name = varchar("name", 100)
    }

    class Parent(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<Parent>(Parents)

        var name by Parents.name
        val children by Child referrersOn Children.parent
    }

    class Child(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<Child>(Children)

        var name by Children.name
        val parent by Parent referencedOn Children.parent
    }
}
