package io.bluetape4k.spring.data.exposed.jdbc.repository.support

import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.data.exposed.jdbc.mapping.ExposedMappingContext
import io.bluetape4k.spring.data.exposed.jdbc.mapping.ExposedPersistentEntity
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.data.domain.Example
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.projection.ProjectionFactory
import org.springframework.data.projection.SpelAwareProxyProjectionFactory
import org.springframework.data.repository.query.FluentQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.*
import java.util.WeakHashMap
import java.util.function.Function
import java.util.stream.Stream

/**
 * [ExposedJdbcRepository]의 기본 CRUD 구현체입니다.
 * 모든 Exposed DAO 연산은 트랜잭션 내에서 실행됩니다.
 *
 * ```kotlin
 * // Spring이 자동으로 생성합니다. UserRepository가 ExposedJdbcRepository를 상속하면 됩니다.
 * // 트랜잭션 예 (쓰기 작업):
 * transaction {
 *     val user = User.new { name = "Alice"; age = 30 }
 *     userRepository.save(user) // Exposed 변경 감지 모델에 위임 — 별도 INSERT 없음
 * }
 *
 * // 읽기 작업:
 * val page = userRepository.findAll(PageRequest.of(0, 10, Sort.by("name")))
 * val users = userRepository.findAll { Users.age greaterEq 18 }
 * ```
 *
 * 일반 애플리케이션은 Spring repository factory가 생성한 인스턴스를 사용하세요.
 * 이 생성자를 직접 사용할 때는 모든 호출을 caller-owned Exposed `transaction {}`
 * 안에서 실행해야 합니다. `findBy(Example) { ... }`의 cursor-backed `stream()`도
 * 같은 transaction과 thread에서 소비하고 명시적으로 닫아야 합니다. QBE 문자열
 * matcher는 exact/contains/startsWith/endsWith만 지원하며 ignore-case와 regex는
 * SQL 전에 거부합니다. Non-empty `project(properties)`는 projection 필수 input과
 * 정확히 같아야 하고 `one`은 limit과 무관하게 다건 결과를 거부합니다.
 */

@Repository
@Transactional(readOnly = true)
@Suppress("UNCHECKED_CAST")
class SimpleExposedJdbcRepository<E: Entity<ID>, ID: Any>(
    private val entityInformation: ExposedEntityInformation<E, ID>,
): ExposedJdbcRepository<E, ID> {

    companion object: KLogging() {
        @JvmSynthetic
        internal fun <E: Entity<ID>, ID: Any> create(
            entityInformation: ExposedEntityInformation<E, ID>,
            mappingContext: ExposedMappingContext,
            projectionFactory: ProjectionFactory,
            creationMode: JdbcRepositoryCreationMode,
        ): SimpleExposedJdbcRepository<E, ID> = SimpleExposedJdbcRepository(entityInformation).also { repository ->
            JdbcRepositoryCollaboratorRegistry.register(
                repository,
                JdbcRepositoryCollaborators(mappingContext, projectionFactory, creationMode),
            )
        }
    }

    private val collaborators by lazy { JdbcRepositoryCollaboratorRegistry.resolve(this) }

    private val entityClass: EntityClass<ID, E> get() = entityInformation.entityClass
    override val table: IdTable<ID> get() = entityInformation.table
    private val persistentEntity: ExposedPersistentEntity<E> by lazy {
        collaborators.mappingContext
            .getRequiredPersistentEntity(entityInformation.javaType) as ExposedPersistentEntity<E>
    }
    private val fluentQueryExecutor by lazy {
        JdbcFluentQueryExecutor(entityClass, table, collaborators.creationMode)
    }

    override fun extractId(entity: E): ID? =
        if (entityInformation.isNew(entity)) null else entity.id.value

    // ============================================================
    // CrudRepository
    // ============================================================

    /**
     * Exposed DAO 변경 감지 모델에서 [save]는 이미 트랜잭션 내에서
     * `EntityClass.new { }` 로 생성된 엔티티를 그대로 반환합니다.
     *
     * **중요**: 반드시 트랜잭션 내에서 `EntityClass.new { }` 로 엔티티를 생성해야 합니다.
     * 생성 즉시 Exposed 캐시에 등록되며, 트랜잭션 커밋 시 INSERT SQL 이 실행됩니다.
     * 기존 엔티티의 프로퍼티 변경도 트랜잭션 커밋 시 자동으로 UPDATE 됩니다.
     */
    @Transactional
    override fun <S: E> save(entity: S): S = entity

    @Transactional
    override fun <S: E> saveAll(entities: Iterable<S>): List<S> = entities.toList()

    override fun findById(id: ID): Optional<E> = Optional.ofNullable(entityClass.findById(id))

    override fun existsById(id: ID): Boolean = !entityClass.find { table.id eq id }.empty()

    override fun findAll(): List<E> = entityClass.all().toList()

    override fun findAll(sort: Sort): List<E> {
        if (sort.isUnsorted) return findAll()
        return entityClass
            .all()
            .orderBy(*sort.toExposedOrderBy(table))
            .toList()
    }

    override fun findAllById(ids: Iterable<ID>): List<E> {
        val idList = ids.toList()
        if (idList.isEmpty()) return emptyList()
        return entityClass.forIds(idList).toList()
    }

    override fun count(): Long = entityClass.count()

    @Transactional
    override fun deleteById(id: ID) {
        table.deleteWhere { table.id eq id }
    }

    @Transactional
    override fun delete(entity: E) {
        entity.delete()
    }

    @Transactional
    override fun deleteAllById(ids: Iterable<ID>) {
        val idList = ids.toList()
        if (idList.isEmpty()) return
        table.deleteWhere { table.id inList idList }
    }

    @Transactional
    override fun deleteAll(entities: Iterable<E>) {
        val idList = entities.map { it.id.value }
        if (idList.isEmpty()) return
        table.deleteWhere { table.id inList idList }
    }

    @Transactional
    override fun deleteAll() {
        table.deleteAll()
    }

    // ============================================================
    // PagingAndSortingRepository
    // ============================================================

    override fun findAll(pageable: Pageable): Page<E> {
        if (pageable.isUnpaged) {
            val all = findAll(pageable.sort)
            return PageImpl(all, pageable, all.size.toLong())
        }
        val total = entityClass.count()
        val query = entityClass.all()
        if (pageable.sort.isSorted) {
            query.orderBy(*pageable.sort.toExposedOrderBy(table))
        }
        val content = query
            .limit(pageable.pageSize)
            .offset(pageable.offset)
            .toList()
        return PageImpl(content, pageable, total)
    }

    // ============================================================
    // ExposedRepository DSL extensions
    // ============================================================

    override fun findAll(op: () -> Op<Boolean>): List<E> = entityClass.find(op).toList()

    override fun count(op: () -> Op<Boolean>): Long = entityClass.find(op).count()

    override fun exists(op: () -> Op<Boolean>): Boolean = !entityClass.find(op).empty()

    // ============================================================
    // QueryByExampleExecutor
    // ============================================================

    override fun <S: E> findOne(example: Example<S>): Optional<S> {
        return withFluentQuery(example) { plan -> fluentQueryExecutor.one(plan) }
    }

    override fun <S: E> findAll(example: Example<S>): List<S> {
        return withFluentQuery(example) { plan -> fluentQueryExecutor.all(plan) }
    }

    override fun <S: E> findAll(example: Example<S>, sort: Sort): List<S> {
        return withFluentQuery(example) { plan -> fluentQueryExecutor.all(plan.withSort(sort)) }
    }

    override fun <S: E> findAll(example: Example<S>, pageable: Pageable): Page<S> {
        return withFluentQuery(example) { plan -> fluentQueryExecutor.page(plan, pageable) }
    }

    override fun <S: E> count(example: Example<S>): Long =
        withFluentQuery(example) { plan -> fluentQueryExecutor.count(plan) }

    override fun <S: E> exists(example: Example<S>): Boolean =
        withFluentQuery(example) { plan -> fluentQueryExecutor.exists(plan) }

    override fun <S: E, R> findBy(
        example: Example<S>,
        queryFunction: Function<FluentQuery.FetchableFluentQuery<S>, R>,
    ): R = withFluentQuery(example) { plan ->
        queryFunction.apply(JdbcFetchableFluentQuery<E, ID, S>(fluentQueryExecutor, plan))
    }

    // ============================================================
    // Internal helpers
    // ============================================================

    private fun <S: E, R> withFluentQuery(
        example: Example<S>,
        block: (JdbcFluentQueryPlan<E>) -> R,
    ): R {
        val transaction = TransactionManager.currentOrNull()
            ?: throw InvalidDataAccessApiUsageException(
                "JDBC FluentQuery requires an active caller-owned Exposed transaction.",
            )
        val scope = JdbcFluentQueryScope.open(transaction)
        @Suppress("UNCHECKED_CAST")
        val plan = JdbcFluentQueryPlan.create(
            example = example as Example<E>,
            domainType = entityInformation.javaType,
            projectionFactory = collaborators.projectionFactory,
            persistentEntity = persistentEntity,
            scope = scope,
        )
        return try {
            block(plan)
        } finally {
            scope.close()
        }
    }
}

internal enum class JdbcRepositoryCreationMode {
    DIRECT,
    FACTORY,
}

internal data class JdbcRepositoryCollaborators(
    val mappingContext: ExposedMappingContext,
    val projectionFactory: ProjectionFactory,
    val creationMode: JdbcRepositoryCreationMode,
)

internal object JdbcRepositoryCollaboratorRegistry {
    private val collaborators = Collections.synchronizedMap(
        WeakHashMap<SimpleExposedJdbcRepository<*, *>, JdbcRepositoryCollaborators>(),
    )

    fun register(
        repository: SimpleExposedJdbcRepository<*, *>,
        value: JdbcRepositoryCollaborators,
    ) {
        collaborators[repository] = value
    }

    fun resolve(repository: SimpleExposedJdbcRepository<*, *>): JdbcRepositoryCollaborators =
        collaborators.remove(repository) ?: JdbcRepositoryCollaborators(
            mappingContext = ExposedMappingContext(),
            projectionFactory = SpelAwareProxyProjectionFactory(),
            creationMode = JdbcRepositoryCreationMode.DIRECT,
        )
}

@Suppress("TooManyFunctions")
internal class JdbcFetchableFluentQuery<E: Entity<ID>, ID: Any, R: Any>(
    private val executor: JdbcFluentQueryExecutor<E, ID>,
    private val plan: JdbcFluentQueryPlan<E>,
): FluentQuery.FetchableFluentQuery<R> {

    override fun sortBy(sort: Sort): FluentQuery.FetchableFluentQuery<R> {
        executor.validateScope(plan)
        return JdbcFetchableFluentQuery(executor, plan.withSort(sort))
    }

    override fun limit(limit: Int): FluentQuery.FetchableFluentQuery<R> {
        executor.validateScope(plan)
        return JdbcFetchableFluentQuery(executor, plan.withLimit(limit))
    }

    override fun <T: Any> `as`(projectionType: Class<T>): FluentQuery.FetchableFluentQuery<T> {
        executor.validateScope(plan)
        return JdbcFetchableFluentQuery(executor, plan.asType(projectionType))
    }

    override fun project(properties: MutableCollection<String>): FluentQuery.FetchableFluentQuery<R> {
        executor.validateScope(plan)
        return JdbcFetchableFluentQuery(executor, plan.withProperties(properties))
    }

    override fun first(): Optional<R> = executor.first(plan)

    override fun firstValue(): R? = executor.firstValue(plan)

    override fun one(): Optional<R> = executor.one(plan)

    override fun oneValue(): R? = executor.oneValue(plan)

    override fun all(): List<R> = executor.all(plan)

    override fun page(pageable: Pageable): Page<R> = executor.page(plan, pageable)

    override fun count(): Long = executor.count(plan)

    override fun exists(): Boolean = executor.exists(plan)

    override fun stream(): Stream<R> = executor.stream(plan)
}
