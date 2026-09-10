package io.bluetape4k.exposed.clickhouse.support

import io.bluetape4k.exposed.clickhouse.ClickHouseConnectionProvider
import io.bluetape4k.exposed.clickhouse.ClickHouseRowBinaryFallbackEvent
import io.bluetape4k.exposed.clickhouse.ClickHouseRowBinaryFallbackRecorder
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 실제 네트워크 없이 JDBC profile·배치 count·cleanup 순서를 검증하는 fixture입니다.
 */
internal class RowBinaryConnectionProviderFixture(
    private val setterFailure: SQLException? = null,
    private val firstByteFailure: SQLException? = null,
    private val executionCounts: List<IntArray> = listOf(intArrayOf(1)),
): ClickHouseConnectionProvider, ClickHouseRowBinaryFallbackRecorder {

    val openedProfiles = mutableListOf<Boolean>()
    val fallbackEvents = mutableListOf<ClickHouseRowBinaryFallbackEvent>()

    private val openedConnections = mutableListOf<Connection>()
    private val closedConnections = mutableListOf<Connection>()
    private val closedStatements = mutableListOf<PreparedStatement>()
    private var executionIndex = 0

    override fun open(rowBinaryEnabled: Boolean): Connection {
        openedProfiles += rowBinaryEnabled
        lateinit var connection: Connection
        val closed = AtomicBoolean(false)
        connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "prepareStatement" -> preparedStatement(connection, args?.firstOrNull() as String)
                "close" -> {
                    if (closed.compareAndSet(false, true)) closedConnections += connection
                    null
                }
                "isClosed" -> closed.get()
                "getAutoCommit" -> true
                "commit", "rollback" -> error("RowBinary executor must not call ${method.name}")
                "getClientInfo" -> "rowBinary=$rowBinaryEnabled"
                "toString" -> "RowBinaryConnection(rowBinary=$rowBinaryEnabled)"
                else -> defaultValue(method.returnType)
            }
        } as Connection
        openedConnections += connection
        return connection
    }

    override fun record(event: ClickHouseRowBinaryFallbackEvent) {
        fallbackEvents += event
    }

    fun profileOf(connection: Connection): Boolean =
        connection.getClientInfo("profile").substringAfter("rowBinary=").toBoolean()

    fun distinctConnections() {
        check(Collections.newSetFromMap(IdentityHashMap<Connection, Boolean>()).let { set ->
            set.addAll(openedConnections)
            set.size == openedConnections.size
        }) { "connection profiles must not be reused" }
    }

    fun assertClosedExactlyOnce() {
        check(closedConnections.size == openedConnections.size) {
            "expected each connection to close once: opened=${openedConnections.size}, closed=${closedConnections.size}"
        }
        check(closedStatements.size == openedConnections.size) {
            "expected each statement to close once: statements=${closedStatements.size}"
        }
    }

    private fun preparedStatement(connection: Connection, sql: String): PreparedStatement {
        var closed = false
        var setterInvocations = 0
        var addedRows = 0
        return Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
        ) { proxy, method, args ->
            when {
                method.name == "close" -> {
                    if (!closed) {
                        closed = true
                        closedStatements += proxy as PreparedStatement
                    }
                    null
                }
                method.name == "addBatch" -> {
                    firstByteFailure?.let { throw it }
                    addedRows++
                    null
                }
                method.name == "executeBatch" -> {
                    val counts = executionCounts.getOrElse(executionIndex) { executionCounts.lastOrNull() ?: intArrayOf() }
                    executionIndex++
                    counts.copyOf()
                }
                method.name == "clearBatch" -> null
                method.name.startsWith("set") -> {
                    setterInvocations++
                    if (setterInvocations == 1) setterFailure?.let { throw it }
                    null
                }
                method.name == "toString" -> "RowBinaryPreparedStatement(sql=$sql, rows=$addedRows)"
                else -> defaultValue(method.returnType)
            }
        } as PreparedStatement
    }
}

private fun defaultValue(type: Class<*>): Any? = when {
    !type.isPrimitive -> null
    type == Boolean::class.javaPrimitiveType -> false
    type == Char::class.javaPrimitiveType -> '\u0000'
    type == Byte::class.javaPrimitiveType -> 0.toByte()
    type == Short::class.javaPrimitiveType -> 0.toShort()
    type == Int::class.javaPrimitiveType -> 0
    type == Long::class.javaPrimitiveType -> 0L
    type == Float::class.javaPrimitiveType -> 0f
    type == Double::class.javaPrimitiveType -> 0.0
    else -> null
}
