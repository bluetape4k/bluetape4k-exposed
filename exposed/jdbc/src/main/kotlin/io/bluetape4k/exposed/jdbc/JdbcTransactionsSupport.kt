package io.bluetape4k.exposed.jdbc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transactionManager

suspend fun <T> suspendTransactionWithIO(
    db: Database? = null,
    transactionIsolation: Int? = db?.transactionManager?.defaultIsolationLevel,
    readOnly: Boolean? = db?.transactionManager?.defaultReadOnly,
    statement: suspend JdbcTransaction.() -> T,
): T {
    return withContext(Dispatchers.IO) {
        suspendTransaction(db, transactionIsolation, readOnly, statement)
    }
}
