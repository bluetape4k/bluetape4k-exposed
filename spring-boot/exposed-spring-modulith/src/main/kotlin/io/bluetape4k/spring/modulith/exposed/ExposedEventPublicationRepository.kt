@file:OptIn(ExperimentalUuidApi::class)

package io.bluetape4k.spring.modulith.exposed

import org.jetbrains.exposed.v1.core.Coalesce
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.beans.factory.BeanClassLoaderAware
import org.springframework.modulith.events.EventPublication.Status
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.modulith.events.core.PublicationTargetIdentifier
import org.springframework.modulith.events.core.TargetEventPublication
import org.springframework.modulith.events.support.CompletionMode
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.ClassUtils
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * JDBC-only Spring Modulith [EventPublicationRepository] backed by Exposed DSL.
 *
 * Methods are bound to the `springTransactionManager` created by the Exposed
 * Spring integration so publication rows can participate in the same
 * Exposed/JDBC transaction as application data.
 */
@Transactional(transactionManager = "springTransactionManager")
class ExposedEventPublicationRepository(
    private val table: ExposedEventPublicationTable,
    private val archiveTable: ExposedEventPublicationTable,
    private val serializer: EventSerializer,
    private val completionMode: CompletionMode = CompletionMode.UPDATE,
) : EventPublicationRepository, BeanClassLoaderAware {

    private var classLoader: ClassLoader? = Thread.currentThread().contextClassLoader

    override fun setBeanClassLoader(classLoader: ClassLoader) {
        this.classLoader = classLoader
    }

    override fun create(publication: TargetEventPublication): TargetEventPublication {
        table.insert { row ->
            row[table.id] = publication.identifier.toKotlinUuid()
            row[table.eventType] = publication.event.javaClass.name
            row[table.listenerId] = publication.targetIdentifier.value
            row[table.publicationDate] = publication.publicationDate
            row[table.serializedEvent] = serialize(publication.event)
            row[table.status] = publication.status.name
            row[table.completionAttempts] = publication.completionAttempts
            row[table.lastResubmissionDate] = publication.lastResubmissionDate ?: publication.publicationDate
        }
        return publication
    }

    override fun markProcessing(identifier: UUID) {
        updateStatus(identifier, Status.PROCESSING)
    }

    override fun markCompleted(event: Any, identifier: PublicationTargetIdentifier, completionDate: Instant) {
        val serialized = serialize(event)

        when (completionMode) {
            CompletionMode.DELETE -> table.deleteWhere {
                (table.listenerId eq identifier.value) and (table.serializedEvent eq serialized)
            }

            CompletionMode.ARCHIVE -> {
                copyToArchive(identifier, serialized, completionDate)
                table.deleteWhere {
                    (table.listenerId eq identifier.value) and (table.serializedEvent eq serialized)
                }
            }

            CompletionMode.UPDATE -> table.update({
                (table.listenerId eq identifier.value) and
                        (table.completionDate.isNull()) and
                        (table.serializedEvent eq serialized)
            }) { row ->
                row[table.status] = Status.COMPLETED.name
                row[table.completionDate] = completionDate
            }
        }
    }

    override fun markCompleted(identifier: UUID, completionDate: Instant) {
        when (completionMode) {
            CompletionMode.DELETE -> table.deleteWhere { table.id eq identifier.toKotlinUuid() }
            CompletionMode.ARCHIVE -> {
                copyToArchive(identifier, completionDate)
                table.deleteWhere { table.id eq identifier.toKotlinUuid() }
            }

            CompletionMode.UPDATE -> table.update({ table.id eq identifier.toKotlinUuid() }) { row ->
                row[table.status] = Status.COMPLETED.name
                row[table.completionDate] = completionDate
            }
        }
    }

    override fun markFailed(identifier: UUID) {
        updateStatus(identifier, Status.FAILED)
    }

    override fun markResubmitted(identifier: UUID, resubmissionDate: Instant): Boolean {
        val updated = table.update({
            (table.id eq identifier.toKotlinUuid()) and (table.status neq Status.RESUBMITTED.name)
        }) { row ->
            row[table.status] = Status.RESUBMITTED.name
            row[table.completionAttempts] = Coalesce(table.completionAttempts, intLiteral(0)) + 1
            row[table.lastResubmissionDate] = resubmissionDate
        }

        return updated == 1
    }

    @Transactional(transactionManager = "springTransactionManager", readOnly = true)
    override fun findIncompletePublications(): List<TargetEventPublication> =
        table.selectAll()
            .where { table.completionDate.isNull() or (table.status neq Status.COMPLETED.name) }
            .orderBy(table.publicationDate, SortOrder.ASC)
            .toPublications()

    @Transactional(transactionManager = "springTransactionManager", readOnly = true)
    override fun findIncompletePublicationsPublishedBefore(instant: Instant): List<TargetEventPublication> =
        table.selectAll()
            .where {
                (table.completionDate.isNull() or (table.status eq Status.PROCESSING.name)) and
                        (table.publicationDate less instant)
            }
            .orderBy(table.publicationDate, SortOrder.ASC)
            .toPublications()

    @Transactional(transactionManager = "springTransactionManager", readOnly = true)
    override fun findIncompletePublicationsByEventAndTargetIdentifier(
        event: Any,
        targetIdentifier: PublicationTargetIdentifier,
    ): Optional<TargetEventPublication> {
        val publication = table.selectAll()
            .where {
                (table.serializedEvent eq serialize(event)) and
                        (table.listenerId eq targetIdentifier.value) and
                        (table.completionDate.isNull() or (table.status eq Status.FAILED.name))
            }
            .orderBy(table.publicationDate, SortOrder.ASC)
            .firstNotNullOfOrNull(::toPublication)

        return Optional.ofNullable(publication)
    }

    @Transactional(transactionManager = "springTransactionManager", readOnly = true)
    override fun findCompletedPublications(): List<TargetEventPublication> {
        val source = if (completionMode == CompletionMode.ARCHIVE) archiveTable else table
        return source.selectAll()
            .where { source.completionDate.isNotNull() or (source.status eq Status.COMPLETED.name) }
            .orderBy(source.publicationDate, SortOrder.ASC)
            .toPublications(source)
    }

    override fun deletePublications(identifiers: List<UUID>) {
        if (identifiers.isEmpty()) return
        table.deleteWhere { table.id inList identifiers.map { it.toKotlinUuid() } }
    }

    override fun deleteCompletedPublications() {
        val source = if (completionMode == CompletionMode.ARCHIVE) archiveTable else table
        source.deleteWhere {
            source.completionDate.isNotNull() or (source.status eq Status.COMPLETED.name)
        }
    }

    override fun deleteCompletedPublicationsBefore(instant: Instant) {
        val source = if (completionMode == CompletionMode.ARCHIVE) archiveTable else table
        source.deleteWhere {
            (source.completionDate less instant) and
                    ((source.status eq Status.COMPLETED.name) or source.status.isNull())
        }
    }

    @Transactional(transactionManager = "springTransactionManager", readOnly = true)
    override fun findFailedPublications(criteria: EventPublicationRepository.FailedCriteria): List<TargetEventPublication> {
        val query = table.selectAll()
            .where {
                (table.status eq Status.FAILED.name) or
                        (table.status.isNull() and table.completionDate.isNull())
            }
            .orderBy(table.publicationDate, SortOrder.ASC)

        criteria.publicationDateReference?.let { reference ->
            query.andWhere { table.publicationDate less reference }
        }

        val limited = if (criteria.maxItemsToRead != -1L) {
            query.limit(criteria.maxItemsToRead.toInt())
        } else {
            query
        }

        return limited.toPublications()
    }

    @Transactional(transactionManager = "springTransactionManager", readOnly = true)
    override fun findByStatus(status: Status): List<TargetEventPublication> {
        val source = if (status == Status.COMPLETED && completionMode == CompletionMode.ARCHIVE) archiveTable else table
        return source.selectAll()
            .where { source.status eq status.name }
            .orderBy(source.publicationDate, SortOrder.ASC)
            .toPublications(source)
    }

    @Transactional(transactionManager = "springTransactionManager", readOnly = true)
    override fun countByStatus(status: Status): Int {
        val source = if (status == Status.COMPLETED && completionMode == CompletionMode.ARCHIVE) archiveTable else table
        return source.selectAll()
            .where { source.status eq status.name }
            .count()
            .toInt()
    }

    /**
     * Creates the publication table and, when archive completion is enabled,
     * the archive table.
     */
    fun createSchema() {
        if (completionMode == CompletionMode.ARCHIVE) {
            SchemaUtils.create(table, archiveTable)
        } else {
            SchemaUtils.create(table)
        }
    }

    private fun updateStatus(identifier: UUID, status: Status) {
        table.update({
            (table.id eq identifier.toKotlinUuid()) and (table.status neq status.name)
        }) { row ->
            row[table.status] = status.name
        }
    }

    private fun copyToArchive(identifier: UUID, completionDate: Instant) {
        table.selectAll()
            .where { table.id eq identifier.toKotlinUuid() }
            .firstOrNull()
            ?.let { row -> insertArchive(row, completionDate) }
    }

    private fun copyToArchive(identifier: PublicationTargetIdentifier, serializedEvent: String, completionDate: Instant) {
        table.selectAll()
            .where { (table.listenerId eq identifier.value) and (table.serializedEvent eq serializedEvent) }
            .forEach { row -> insertArchive(row, completionDate) }
    }

    private fun insertArchive(row: ResultRow, completionDate: Instant) {
        // Use a savepoint so that a duplicate-key violation does not abort the outer
        // PostgreSQL transaction. Without a savepoint, catching ExposedSQLException
        // leaves the connection in the "current transaction is aborted" state.
        val conn = TransactionManager.current().connection
        val savepoint = conn.setSavepoint("archive_insert")
        try {
            archiveTable.insert { archive ->
                archive[archiveTable.id] = row[table.id]
                archive[archiveTable.listenerId] = row[table.listenerId]
                archive[archiveTable.eventType] = row[table.eventType]
                archive[archiveTable.serializedEvent] = row[table.serializedEvent]
                archive[archiveTable.publicationDate] = row[table.publicationDate]
                archive[archiveTable.status] = Status.COMPLETED.name
                archive[archiveTable.completionDate] = completionDate
                archive[archiveTable.completionAttempts] = row[table.completionAttempts]
                archive[archiveTable.lastResubmissionDate] = row[table.lastResubmissionDate]
            }
            conn.releaseSavepoint(savepoint)
        } catch (e: ExposedSQLException) {
            conn.rollback(savepoint)
            // SQL state 23xxx = integrity constraint violation (unique key already exists)
            // Treat as idempotent: the row was already archived by a concurrent caller.
            if (e.sqlState?.startsWith("23") == true) return
            throw e
        }
    }

    private fun Iterable<ResultRow>.toPublications(
        publicationTable: ExposedEventPublicationTable = table,
    ): List<TargetEventPublication> =
        mapNotNull { row -> toPublication(row, publicationTable) }

    private fun toPublication(
        row: ResultRow,
        publicationTable: ExposedEventPublicationTable = table,
    ): TargetEventPublication? {
        val eventClass = loadEventClass(row[publicationTable.eventType]) ?: return null
        return StoredEventPublication(
            id = row[publicationTable.id].toJavaUuid(),
            publicationDate = row[publicationTable.publicationDate],
            listenerId = row[publicationTable.listenerId],
            eventSupplier = { serializer.deserialize(row[publicationTable.serializedEvent], eventClass) },
            completionDate = row[publicationTable.completionDate],
            status = row[publicationTable.status]?.let(Status::valueOf),
            lastResubmissionDate = row[publicationTable.lastResubmissionDate],
            completionAttempts = row[publicationTable.completionAttempts] ?: 0,
        )
    }

    private fun loadEventClass(className: String): Class<Any>? =
        try {
            @Suppress("UNCHECKED_CAST")
            ClassUtils.forName(className, classLoader) as Class<Any>
        } catch (_: ClassNotFoundException) {
            null
        }

    private fun serialize(event: Any): String = serializer.serialize(event).toString()

    private fun UUID.toKotlinUuid(): Uuid =
        Uuid.parse(toString())

    private fun Uuid.toJavaUuid(): UUID =
        UUID.fromString(toString())

    private class StoredEventPublication(
        private val id: UUID,
        private val publicationDate: Instant,
        private val listenerId: String,
        private val eventSupplier: () -> Any,
        private var completionDate: Instant?,
        private var status: Status?,
        private val lastResubmissionDate: Instant?,
        private val completionAttempts: Int,
    ) : TargetEventPublication {

        private val eventValue: Any by lazy(eventSupplier)

        override fun getIdentifier(): UUID = id

        override fun getEvent(): Any = eventValue

        override fun getTargetIdentifier(): PublicationTargetIdentifier =
            PublicationTargetIdentifier.of(listenerId)

        override fun getPublicationDate(): Instant = publicationDate

        override fun getCompletionDate(): Optional<Instant> =
            Optional.ofNullable(completionDate)

        override fun markCompleted(instant: Instant) {
            completionDate = instant
            status = Status.COMPLETED
        }

        override fun getStatus(): Status =
            status ?: if (completionDate != null) Status.COMPLETED else Status.PROCESSING

        override fun getLastResubmissionDate(): Instant? = lastResubmissionDate

        override fun getCompletionAttempts(): Int = completionAttempts
    }
}
