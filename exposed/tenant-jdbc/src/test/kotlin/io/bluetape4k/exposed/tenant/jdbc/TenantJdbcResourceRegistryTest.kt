package io.bluetape4k.exposed.tenant.jdbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

class TenantJdbcResourceRegistryTest {

    companion object: KLogging()

    private enum class Tenant {
        ALPHA,
        BETA,
    }

    @Test
    fun `string과 enum tenant가 같은 resource identity를 반환한다`() {
        val registry = registryOf("alpha", "beta")
        registry.use {
            it.resourceFor("alpha").database shouldBeSameInstanceAs it.databaseFor("alpha")
            it.resourceFor("alpha").dataSource shouldBeSameInstanceAs it.dataSourceFor("alpha")
            it.configuredTenants.toList() shouldBeEqualTo listOf("alpha", "beta")
        }

        val enumRegistry = registryOf(Tenant.ALPHA, Tenant.BETA)
        enumRegistry.use {
            val resolver: (Tenant) -> Database = it::databaseFor
            resolver(Tenant.ALPHA) shouldBeSameInstanceAs it.databaseFor(Tenant.ALPHA)
        }
    }

    @Test
    fun `empty tenant 목록은 빈 immutable registry를 만든다`() {
        val registry = TenantJdbcResourceRegistry.create<String, DataSource>(
            tenants = emptyList(),
            dataSourceFactory = { error("factory must not be called") },
            disposeDataSource = { _, _ -> },
        )
        registry.use {
            it.configuredTenants shouldBeEqualTo emptySet()
            assertFailsWith<UnknownTenantJdbcResourceException> { it.resourceFor("missing") }
        }
    }

    @Test
    fun `configuredTenants는 입력과 registry 외부 변경으로부터 보호된다`() {
        val input = mutableListOf("alpha")
        val registry = TenantJdbcResourceRegistry.create(
            tenants = input,
            dataSourceFactory = { dataSource("immutable-${it}") },
            disposeDataSource = { _, _ -> },
        )
        registry.use {
            input += "beta"
            it.configuredTenants.toList() shouldBeEqualTo listOf("alpha")

            @Suppress("UNCHECKED_CAST")
            assertFailsWith<UnsupportedOperationException> {
                (it.configuredTenants as MutableSet<String>).add("beta")
            }
        }
    }

    @Test
    fun `stable hash collision도 exact tenant를 조회한다`() {
        val first = ConstantHashTenant("first")
        val second = ConstantHashTenant("second")
        val registry = registryOf(first, second)

        registry.use {
            it.resourceFor(first).dataSource shouldBeSameInstanceAs it.dataSourceFor(first)
            it.resourceFor(second).dataSource shouldBeSameInstanceAs it.dataSourceFor(second)
        }
    }

    @Test
    fun `unknown tenant는 key 문자열 없이 고정 예외로 실패한다`() {
        val configured = SecretTenant("configured-secret")
        val unknown = SecretTenant("credential-value")
        val registry = TenantJdbcResourceRegistry.create(
            tenants = listOf(configured),
            dataSourceFactory = { dataSource("configured") },
            disposeDataSource = { _, _ -> },
        )
        registry.use {
            val failure = assertFailsWith<UnknownTenantJdbcResourceException> {
                it.resourceFor(unknown)
            }
            failure.message shouldBeEqualTo "Unknown tenant JDBC resource."
            unknown.toStringCalls.get() shouldBeEqualTo 0
        }
    }

    @Test
    fun `중복 tenant는 factory 호출 전에 실패한다`() {
        val calls = AtomicInteger()
        val failure = assertFailsWith<IllegalArgumentException> {
            TenantJdbcResourceRegistry.create(
                tenants = listOf("a", "a"),
                dataSourceFactory = {
                    calls.incrementAndGet()
                    dataSource("duplicate")
                },
                disposeDataSource = { _, _ -> },
            )
        }
        failure.message shouldBeEqualTo "Duplicate tenant key."
        calls.get() shouldBeEqualTo 0
    }

    @Test
    fun `null tenant와 null factory result는 자원을 만들기 전에 실패한다`() {
        val calls = AtomicInteger()
        val tenants: Iterable<String?> = listOf("a", null)
        val nullTenantFailure = assertFailsWith<IllegalArgumentException> {
            @Suppress("UNCHECKED_CAST")
            TenantJdbcResourceRegistry.create(
                tenants = tenants as Iterable<String>,
                dataSourceFactory = {
                    calls.incrementAndGet()
                    dataSource("null-tenant")
                },
                disposeDataSource = { _, _ -> },
            )
        }
        nullTenantFailure.message shouldBeEqualTo "Tenant key must not be null."
        calls.get() shouldBeEqualTo 0
    }

    @Test
    fun `same DataSource reference는 두 번째 ownership을 만들지 않는다`() {
        val shared = dataSource("shared")
        val disposeCalls = AtomicInteger()
        val failure = assertFailsWith<IllegalArgumentException> {
            TenantJdbcResourceRegistry.create(
                tenants = listOf("a", "b"),
                dataSourceFactory = { shared },
                disposeDataSource = { _, _ -> disposeCalls.incrementAndGet() },
            )
        }
        failure.message shouldBeEqualTo "Tenant JDBC DataSource is reused."
        disposeCalls.get() shouldBeEqualTo 1
    }

    @Test
    fun `tenant별 H2 database의 schema와 data가 격리된다`() {
        val dataSources = mapOf(
            "a" to h2DataSource("isolation-a"),
            "b" to h2DataSource("isolation-b"),
        )
        val registry = TenantJdbcResourceRegistry.create(
            tenants = dataSources.keys,
            dataSourceFactory = { dataSources.getValue(it) },
            disposeDataSource = { _, dataSource -> dataSource.close() },
        )
        try {
            transaction(registry.databaseFor("a")) {
                exec("CREATE TABLE tenant_marker (marker VARCHAR(16))")
                exec("INSERT INTO tenant_marker VALUES ('a')")
            }
            transaction(registry.databaseFor("b")) {
                exec("CREATE TABLE tenant_marker (marker VARCHAR(16))")
                exec("INSERT INTO tenant_marker VALUES ('b')")
            }

            marker(registry.databaseFor("a")) shouldBeEqualTo "a"
            marker(registry.databaseFor("b")) shouldBeEqualTo "b"
        } finally {
            registry.close()
        }

        dataSources.values.forEach { it.isClosed shouldBeEqualTo true }
    }

    @Test
    fun `default hook은 Exposed manager를 해제한 뒤 datasource를 dispose한다`() {
        repeat(20) { round ->
            val dataSource = h2DataSource("manager-$round")
            lateinit var database: Database
            val registry = TenantJdbcResourceRegistry.create(
                tenants = listOf("tenant-$round"),
                dataSourceFactory = { dataSource },
                disposeDataSource = { _, source ->
                    try {
                        assertFailsWith<IllegalStateException> {
                            TransactionManager.managerFor(database)
                        }
                    } finally {
                        source.close()
                    }
                },
            )
            database = registry.databaseFor("tenant-$round")
            try {
                TransactionManager.managerFor(database)
            } finally {
                registry.close()
            }
            assertFailsWith<IllegalStateException> {
                TransactionManager.managerFor(database)
            }
            dataSource.isClosed shouldBeEqualTo true
        }
    }

    private fun marker(database: Database): String =
        transaction(database) {
            checkNotNull(exec("SELECT marker FROM tenant_marker") { resultSet ->
                check(resultSet.next()) { "tenant marker row is missing" }
                resultSet.getString(1)
            })
        }
}
