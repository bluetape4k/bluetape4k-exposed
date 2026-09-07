@file:JvmName("TenantJdbcTestFixtures")

package io.bluetape4k.exposed.tenant.jdbc

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** 테스트에서 secret key의 문자열화가 호출되는지 관찰하는 tenant key입니다. */
class SecretTenant(private val value: String) {
    val toStringCalls = AtomicInteger()

    override fun equals(other: Any?): Boolean = other is SecretTenant && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String {
        toStringCalls.incrementAndGet()
        return "SecretTenant($value)"
    }
}

/** 서로 다른 key가 같은 hash bucket에 들어가도 exact match가 가능한 key입니다. */
class ConstantHashTenant(val value: String) {
    override fun equals(other: Any?): Boolean = other is ConstantHashTenant && value == other.value

    override fun hashCode(): Int = 17

    override fun toString(): String = value
}

/** Java interop 및 빠른 registry 단위 테스트에 사용하는 H2 DataSource입니다. */
@JvmOverloads
fun dataSource(name: String, closeDelay: Boolean = true): JdbcDataSource = JdbcDataSource().apply {
    setURL("jdbc:h2:mem:$name-${UUID.randomUUID()}" + if (closeDelay) ";DB_CLOSE_DELAY=-1" else "")
    setUser("sa")
    setPassword("")
}

/** 실제 pool ownership 및 tenant별 H2 schema 격리에 사용하는 Hikari DataSource입니다. */
fun h2DataSource(name: String): HikariDataSource = HikariDataSource(HikariConfig().apply {
    jdbcUrl = "jdbc:h2:mem:$name-${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
    username = "sa"
    password = ""
    maximumPoolSize = 2
    minimumIdle = 0
    poolName = "tenant-jdbc-test-$name"
})

/** 테스트의 DataSource close와 callback 순서를 기록하는 얇은 delegate입니다. */
class TrackingDataSource(
    private val delegate: HikariDataSource,
    private val events: MutableList<String>,
    private val name: String,
) : DataSource by delegate, AutoCloseable {
    val closeCalls = AtomicInteger()

    override fun close() {
        if (closeCalls.incrementAndGet() == 1) {
            events += "dispose:$name"
            delegate.close()
        }
    }
}

fun <K : Any> registryOf(vararg tenants: K): TenantJdbcResourceRegistry<K> =
    TenantJdbcResourceRegistry.create(
        tenants = tenants.asList(),
        dataSourceFactory = { tenant -> dataSource("registry-${tenant.hashCode()}-${UUID.randomUUID()}") },
        disposeDataSource = { _, _ -> },
    )
