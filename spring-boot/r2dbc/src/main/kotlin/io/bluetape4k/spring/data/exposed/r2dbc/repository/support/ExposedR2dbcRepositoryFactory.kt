package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import io.bluetape4k.spring.data.exposed.r2dbc.repository.query.DeclaredExposedR2dbcQuery
import io.bluetape4k.spring.data.exposed.r2dbc.repository.query.ExposedR2dbcQueryMethod
import io.bluetape4k.spring.data.exposed.r2dbc.repository.query.PartTreeExposedR2dbcQuery
import io.bluetape4k.spring.data.exposed.r2dbc.repository.query.R2dbcQueryMapper
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.springframework.data.repository.core.EntityInformation
import org.springframework.data.repository.core.RepositoryInformation
import org.springframework.data.repository.core.RepositoryMetadata
import org.springframework.data.repository.core.support.AbstractEntityInformation
import org.springframework.data.repository.core.support.RepositoryComposition
import org.springframework.data.repository.core.support.RepositoryFactorySupport
import org.springframework.data.repository.query.QueryLookupStrategy
import org.springframework.data.repository.query.RepositoryQuery
import java.lang.invoke.MethodHandles
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.reflect.KClass

/**
 * 테이블 기반 코루틴 Exposed Repository 프록시를 생성합니다.
 *
 * Spring Data 의 트랜잭션 인터셉터를 우회하여 suspend 메서드가 Mono 로 래핑되지 않도록
 * [getRepository] 를 직접 오버라이드합니다.
 *
 * ```kotlin
 * // Spring이 자동으로 사용합니다. 직접 사용할 경우:
 * val factory = ExposedR2dbcRepositoryFactory()
 * val repo = factory.getRepository(UserR2dbcRepository::class.java)
 * // repo는 SimpleExposedR2dbcRepository 기반의 JDK 프록시 인스턴스
 * ```
 */
@Suppress("UNCHECKED_CAST", "TooManyFunctions")
class ExposedR2dbcRepositoryFactory: RepositoryFactorySupport() {

    private var queryLookupStrategyKey: QueryLookupStrategy.Key? = null

    override fun setQueryLookupStrategyKey(key: QueryLookupStrategy.Key?) {
        queryLookupStrategyKey = key
        super.setQueryLookupStrategyKey(key)
    }

    override fun getEntityInformation(metadata: RepositoryMetadata): EntityInformation<*, *> =
        StaticEntityInformation(metadata.domainType as Class<Any>, Any::class.java)

    override fun getTargetRepository(information: RepositoryInformation): Any =
        createRepositoryImplementation(information.repositoryInterface)

    override fun getRepositoryBaseClass(metadata: RepositoryMetadata): Class<*> =
        SimpleExposedR2dbcRepository::class.java

    override fun getRepositoryFragments(metadata: RepositoryMetadata): RepositoryComposition.RepositoryFragments =
        RepositoryComposition.RepositoryFragments.just(createRepositoryImplementation(metadata.repositoryInterface))

    /**
     * Spring Data 의 프록시 인터셉터 체인(트랜잭션 래핑, 코루틴 변환 등)을 완전히 우회하여
     * [SimpleExposedR2dbcRepository] 에 직접 위임하는 JDK 프록시를 반환합니다.
     */
    override fun <T: Any> getRepository(
        repositoryInterface: Class<T>,
        fragments: RepositoryComposition.RepositoryFragments
    ): T {
        val impl = createRepositoryImplementation(repositoryInterface)
        return createDirectProxy(repositoryInterface, impl) as T
    }

    private fun createRepositoryImplementation(repositoryInterface: Class<*>): SimpleExposedR2dbcRepository<Any, Any> {
        val mapper = resolveMapper(repositoryInterface)

        return SimpleExposedR2dbcRepository(
            table = mapper.table,
            toDomainMapper = mapper.toDomain,
            persistValuesProvider = mapper.toPersistValues,
            idExtractor = mapper.extractId,
        ).also { repository ->
            @Suppress("UNCHECKED_CAST")
            repository.configureQbe(
                domainType = getRepositoryMetadata(repositoryInterface).domainType.kotlin as KClass<Any>,
                projectionFactory = projectionFactory,
                constructionMode = R2dbcQbeConstructionMode.FACTORY,
            )
        }
    }

    /**
     * Spring Data 인터셉터 없이 [impl] 에 직접 위임하는 JDK 프록시를 생성합니다.
     *
     * - impl 에 해당 메서드가 있으면 reflection 으로 직접 호출
     * - 없으면 interface default 메서드로 위임
     */
    private fun createDirectProxy(repositoryInterface: Class<*>, impl: SimpleExposedR2dbcRepository<Any, Any>): Any {
        val implClass: Class<*> = impl::class.java
        val repositoryMetadata = getRepositoryMetadata(repositoryInterface)
        val queryMapper = R2dbcQueryMapper(impl.table, impl::toDomain)
        val queryCache = ConcurrentHashMap<Method, RepositoryQuery>()

        val handler = InvocationHandler { proxy, method, args ->
            invokeDirect(
                proxy = proxy,
                method = method,
                args = args,
                repositoryInterface = repositoryInterface,
                implClass = implClass,
                impl = impl,
                repositoryMetadata = repositoryMetadata,
                queryMapper = queryMapper,
                queryCache = queryCache,
            )
        }

        return Proxy.newProxyInstance(repositoryInterface.classLoader, arrayOf(repositoryInterface), handler)
    }

    @Suppress("ReturnCount")
    private fun invokeDirect(
        proxy: Any,
        method: Method,
        args: Array<out Any?>?,
        repositoryInterface: Class<*>,
        implClass: Class<*>,
        impl: SimpleExposedR2dbcRepository<Any, Any>,
        repositoryMetadata: RepositoryMetadata,
        queryMapper: R2dbcQueryMapper<Any, Any>,
        queryCache: ConcurrentHashMap<Method, RepositoryQuery>,
    ): Any? {
        if (method.declaringClass == Any::class.java) {
            return invokeObjectMethod(proxy, method, args, repositoryInterface)
        }
        findImplementationMethod(implClass, method)?.let { implementation ->
            return invokeImplementation(implementation, impl, args)
        }
        if (method.isDefault) {
            return InvocationHandler.invokeDefault(proxy, method, *(args ?: emptyArray()))
        }
        val query = queryCache.computeIfAbsent(method) {
            createQuery(it, repositoryMetadata, queryMapper)
        }
        return invokeQuery(query, args)
    }

    private fun invokeObjectMethod(
        proxy: Any,
        method: Method,
        args: Array<out Any?>?,
        repositoryInterface: Class<*>,
    ): Any? = when (method.name) {
        "toString" -> "ExposedSuspendRepository(${repositoryInterface.simpleName})"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.firstOrNull()
        else -> null
    }

    private fun findImplementationMethod(implClass: Class<*>, method: Method): Method? = try {
        implClass.getMethod(method.name, *method.parameterTypes)
    } catch (_: NoSuchMethodException) {
        null
    }

    private fun invokeImplementation(
        method: Method,
        impl: SimpleExposedR2dbcRepository<Any, Any>,
        args: Array<out Any?>?,
    ): Any? = try {
        method.invoke(impl, *(args ?: emptyArray()))
    } catch (failure: InvocationTargetException) {
        throw (failure.targetException ?: failure)
    }

    private fun createQuery(
        method: Method,
        repositoryMetadata: RepositoryMetadata,
        queryMapper: R2dbcQueryMapper<Any, Any>,
    ): RepositoryQuery {
        val qMethod = ExposedR2dbcQueryMethod(method, repositoryMetadata, projectionFactory)
        val key = queryLookupStrategyKey ?: QueryLookupStrategy.Key.CREATE_IF_NOT_FOUND
        return when (key) {
            QueryLookupStrategy.Key.USE_DECLARED_QUERY -> {
                require(qMethod.isAnnotatedQuery) {
                    "No @Query annotation found on method '${method.name}'"
                }
                DeclaredExposedR2dbcQuery(qMethod, queryMapper)
            }
            QueryLookupStrategy.Key.CREATE -> PartTreeExposedR2dbcQuery(qMethod, queryMapper)
            QueryLookupStrategy.Key.CREATE_IF_NOT_FOUND ->
                if (qMethod.isAnnotatedQuery) DeclaredExposedR2dbcQuery(qMethod, queryMapper)
                else PartTreeExposedR2dbcQuery(qMethod, queryMapper)
        }
    }

    private fun invokeQuery(
        query: RepositoryQuery,
        args: Array<out Any?>?,
    ): Any? {
        val continuation = args?.lastOrNull() as? Continuation<Any?>
        if (continuation == null) return query.execute(args ?: emptyArray())
        return suspend {
            @Suppress("UNCHECKED_CAST")
            when (query) {
                is DeclaredExposedR2dbcQuery<*, *> ->
                    (query as DeclaredExposedR2dbcQuery<Any, Any>).executeSuspending(args)
                is PartTreeExposedR2dbcQuery<*, *> ->
                    (query as PartTreeExposedR2dbcQuery<Any, Any>).executeSuspending(args)
                else -> query.execute(args)
            }
        }.startCoroutineUninterceptedOrReturn(continuation)
    }

    private fun resolveMapper(repositoryInterface: Class<*>): RepositoryMapper {
        val toDomainMethod = repositoryInterface.methods.firstOrNull {
            it.name == "toDomain" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == ResultRow::class.java
        } ?: error("${repositoryInterface.name} must override toDomain(ResultRow)")

        val toPersistValuesMethod = repositoryInterface.methods.firstOrNull {
            it.name == "toPersistValues" && it.parameterCount == 1
        } ?: error("${repositoryInterface.name} must override toPersistValues(domain)")

        val tableGetterMethod = repositoryInterface.methods.firstOrNull {
            it.name == "getTable" && it.parameterCount == 0
        } ?: error("${repositoryInterface.name} must override val table: IdTable<ID>")

        val extractIdMethod = repositoryInterface.methods.firstOrNull {
            it.name == "extractId" && it.parameterCount == 1
        } ?: error("${repositoryInterface.name} must override fun extractId(entity)")

        val defaultMethodProxy = createDefaultMethodProxy(repositoryInterface)
        val toDomainHandle = bindDefaultMethodHandle(repositoryInterface, toDomainMethod, defaultMethodProxy)
        val toPersistValuesHandle =
            bindDefaultMethodHandle(repositoryInterface, toPersistValuesMethod, defaultMethodProxy)
        val tableGetterHandle = bindDefaultMethodHandle(repositoryInterface, tableGetterMethod, defaultMethodProxy)
        val extractIdHandle = bindDefaultMethodHandle(repositoryInterface, extractIdMethod, defaultMethodProxy)

        return RepositoryMapper(
            table = tableGetterHandle.invoke() as IdTable<Any>,
            toDomain = { row -> toDomainHandle.invoke(row) as Any },
            toPersistValues = { domain -> toPersistValuesHandle.invoke(domain) as Map<Column<*>, Any?> },
            extractId = { entity -> extractIdHandle.invoke(entity) },
        )
    }


    private fun bindDefaultMethodHandle(
        repositoryInterface: Class<*>,
        method: Method,
        proxy: Any,
    ): java.lang.invoke.MethodHandle {
        return runCatching {
            val lookup = MethodHandles.privateLookupIn(repositoryInterface, MethodHandles.lookup())
            lookup.unreflectSpecial(method, repositoryInterface).bindTo(proxy)
        }.getOrElse { e ->
            throw IllegalStateException(
                "Cannot bind default method '${method.name}' on ${repositoryInterface.name}. " +
                        "Ensure the interface is accessible and the method has a default implementation. " +
                        "If running on Java 9+, the module may need '--add-opens' flags.",
                e,
            )
        }
    }

    private fun createDefaultMethodProxy(repositoryInterface: Class<*>): Any {
        val handler = InvocationHandler { proxy, method, args ->
            when {
                method.declaringClass == Any::class.java ->
                    when (method.name) {
                        "toString" -> "DefaultMethodProxy(${repositoryInterface.name})"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        else     -> null
                    }

                method.isDefault                         ->
                    InvocationHandler.invokeDefault(proxy, method, *(args ?: emptyArray()))

                else                                     -> error("Method '${method.name}' must provide a default implementation in ${repositoryInterface.name}")
            }
        }

        return Proxy.newProxyInstance(
            repositoryInterface.classLoader,
            arrayOf(repositoryInterface),
            handler,
        )
    }

    private data class RepositoryMapper(
        val table: IdTable<Any>,
        val toDomain: (ResultRow) -> Any,
        val toPersistValues: (Any) -> Map<Column<*>, Any?>,
        val extractId: (Any) -> Any?,
    )

    private class StaticEntityInformation<T: Any, ID: Any>(
        domainClass: Class<T>,
        private val idType: Class<ID>,
    ): AbstractEntityInformation<T, ID>(domainClass) {

        override fun getId(entity: T): ID? = null

        override fun getIdType(): Class<ID> = idType

        override fun isNew(entity: T): Boolean = true
    }
}
