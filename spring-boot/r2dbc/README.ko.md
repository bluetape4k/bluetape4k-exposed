# exposed-spring-boot-r2dbc

[English](./README.md) | 한국어

**Exposed R2DBC DSL 기반 코루틴 Spring Data Repository (Spring Boot 4.x / Spring 7)**

Spring Data coroutine repository를 Exposed R2DBC와 연결하는 repository
브리지입니다. suspend와 `Flow` 시그니처를 유지하면서, 실제 트랜잭션 실행은
Exposed R2DBC `suspendTransaction` 블록에 위임합니다.

## Coroutine Repository Wiring

![Spring Boot Exposed R2DBC coroutine repository wiring diagram](../../docs/images/readme-diagrams/spring-boot-exposed-r2dbc-diagram-01.png)

## Suspend Query and Flow Execution

![Spring Boot Exposed R2DBC suspend query flow diagram](../../docs/images/readme-diagrams/spring-boot-exposed-r2dbc-diagram-02.png)

## 설치

```gradle
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:<spring-boot-version>"))
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<bluetape4k-version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-r2dbc")

    // 코루틴 지원 (필수)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
}
```

## 주요 기능

### 1. ExposedR2dbcRepository - Spring Data Coroutine 표준

```kotlin
@NoRepositoryBean
interface ExposedR2dbcRepository<R : Any, ID : Any> : CoroutineCrudRepository<R, ID>
```

- **CoroutineCrudRepository**: suspend 기반 표준 CRUD 작업
- **Flow 지원**: 대용량 데이터 스트리밍 (백프레셔 포함)
- **페이징**: suspend 기반 페이징 조회
- **Exposed DSL 통합**: R2DBC 조건 쿼리
- **PartTree 파생 쿼리**: `findByName`, `countByAge`, `existsByEmail`, `deleteByName` 같은 Spring Data 메서드명 기반 쿼리

### 2. 도메인 객체 매핑

Row-to-Domain 변환을 인터페이스에서 정의:

```kotlin
interface UserRepository : ExposedR2dbcRepository<User, Long> {
    override val table: IdTable<Long> get() = Users

    override fun extractId(entity: User): Long? = entity.id

    override fun toDomain(row: ResultRow): User =
        User(
            id = row[Users.id].value,
            name = row[Users.name],
            email = row[Users.email],
            age = row[Users.age],
        )

    override fun toPersistValues(domain: User): Map<Column<*>, Any?> =
        mapOf(
            Users.name to domain.name,
            Users.email to domain.email,
            Users.age to domain.age,
        )
}
```

### 3. Suspend 기반 CRUD

```kotlin
interface UserRepository : ExposedR2dbcRepository<User, Long> {
    // 자동 구현됨
}

// 사용
suspend fun getUser(id: Long): User? {
    return userRepository.findByIdOrNull(id)
}

suspend fun saveUser(user: User): User {
    return userRepository.save(user)
}
```

### 4. Flow 스트리밍

대용량 데이터를 백프레셔와 함께 처리:

```kotlin
suspend fun processAllUsers() {
    userRepository.findAll()
        .collect { user ->
            println("Processing: $user")
        }
}

// 조건부 스트리밍
userRepository.findAll { Users.age greaterEq 18 }
    .collect { adult ->
        // 처리...
    }

// row-by-row 스트리밍 (메모리 효율적)
userRepository.streamAll()
    .collect { user ->
        // 처리...
    }
```

### 5. 메서드명 기반 파생 쿼리

Spring Data PartTree 쿼리를 suspend Repository 메서드로 선언할 수 있습니다:

```kotlin
interface UserRepository : ExposedR2dbcRepository<User, Long> {
    suspend fun findByName(name: String): List<User>
    suspend fun findByAgeGreaterThan(age: Int): List<User>
    suspend fun findByEmailContaining(keyword: String): List<User>
    suspend fun findByNameAndAge(name: String, age: Int): User?
    suspend fun countByAge(age: Int): Long
    suspend fun existsByEmail(email: String): Boolean
    suspend fun deleteByName(name: String): Long
    suspend fun findTop3ByOrderByAgeDesc(): List<User>
    suspend fun findFirstByNameOrderByAgeDesc(name: String): User?
}
```

지원 범위는 Exposed DSL helper와 같은 컬럼명 매핑을 사용하며, equality, comparison, `Containing`, count/exists/delete projection, 선언적 정렬, top/first limit을 포함합니다.

### 6. `@Query` raw SQL

`@Query` 어노테이션으로 네이티브 SQL을 실행할 수 있습니다. 위치 기반 플레이스홀더 `?1`, `?2`, ...가 메서드 파라미터에 순서대로 매핑됩니다.

```kotlin
import io.bluetape4k.spring.data.exposed.jdbc.annotation.Query

interface UserRepository : ExposedR2dbcRepository<User, Long> {
    @Query("SELECT * FROM users WHERE email = ?1")
    suspend fun findByEmailNative(email: String): List<User>

    @Query("SELECT * FROM users WHERE age = ?2 AND email = ?1")
    suspend fun findByEmailAndAgeNative(email: String, age: Int): List<User>

    @Query("SELECT * FROM users WHERE age BETWEEN ?1 AND ?2")
    suspend fun findByAgeRangeNative(minAge: Int, maxAge: Int): List<User>

    @Query("SELECT id FROM users ORDER BY age DESC LIMIT 10")
    suspend fun findTopTenByAgeNative(): List<User>
}
```

파라미터는 prepared statement 플레이스홀더로 바인딩되므로 SQL injection이 방지됩니다.

raw SQL은 엔티티 ID 컬럼을 매핑된 컬럼명으로 조회해야 합니다. 엔티티는 Exposed로 다시
로드한 뒤 SQL이 반환한 ID 순서대로 정렬하므로 `ORDER BY`, `LIMIT`, JOIN 쿼리의 정렬 순서가 유지됩니다.
JOIN에서는 필요에 따라 `SELECT u.id AS id FROM users u JOIN ...`처럼 엔티티 ID에 매핑된
컬럼명을 alias로 지정하세요.

> **제약**: 엔티티 ID를 조회하지 않는 scalar projection이나 grouping 쿼리는 엔티티 쿼리가
> 아니므로 명확한 `IllegalArgumentException`을 던집니다. Projection이나 집계 결과가 필요하면
> 전용 row mapper 또는 Exposed DSL을 사용하세요.

### 7. Actuator Cache Health

Spring Boot Actuator와 `bluetape4k-exposed-r2dbc-caffeine`이 classpath에 있으면
`exposedR2dbcCacheHealthIndicator`가 reactive health indicator로 자동 등록됩니다.
이 indicator는 suspend cache consistency check 결과에서 cache mode, queue depth,
`workerState`, 마지막 flush error를 노출합니다.
호환되는 R2DBC Caffeine repository bean이 없으면 indicator를 등록하지 않으므로
`repositoryCount=0`인 선택적 `UP` component를 만들지 않습니다.

```properties
bluetape4k.exposed.cache.health.enabled=true
```

| Report | Actuator status |
|---|---|
| Flush error가 없고 `workerState=NOT_APPLICABLE|IDLE|RUNNING` | `UP` |
| Flush error가 없고 `workerState=DRAINING|STOPPED` | `OUT_OF_SERVICE` |
| Flush error 또는 `workerState=FAILED` | `DOWN` |

비활성화하려면 이 property를 `false`로 설정하세요. Spring Boot는 reactive
indicator를 자동으로 찾습니다. Ktor는 `ExposedKtorCacheContributor`를 명시적으로
등록해야 하며, `DRAINING`, `FAILED`, `STOPPED`를 redacted detail의 readiness
`DOWN`으로 매핑합니다. Actuator management endpoint 접근 정책과 Ktor route 보안
정책은 별도로 관리하세요.

### 8. 페이징 조회

```kotlin
suspend fun getUsersPage(pageNo: Int, pageSize: Int): Page<User> {
    return userRepository.findAll(PageRequest.of(pageNo, pageSize))
}

suspend fun getUsersSorted(): Page<User> {
    return userRepository.findAll(
        PageRequest.of(0, 20, Sort.by("age").descending())
    )
}
```

### 9. Exposed DSL 조건

복잡한 조건은 DSL로 표현:

```kotlin
val adults = userRepository.findAll { Users.age greaterEq 18 }.toList()

val emailContains = userRepository.findAll {
    (Users.email like "%@example.com") and (Users.age greaterEq 20)
}.toList()

val count = userRepository.count { Users.age greaterEq 18 }

val exists = userRepository.exists { Users.email eq "alice@example.com" }
```

<!-- r2dbc-coroutine-fluent-query:START -->
### 10. Coroutine Query by Example 및 FluentQuery

<!-- contract-key:coroutine-only -->
<!-- contract-key:suspend-terminal -->
<!-- contract-key:cold-flow -->
<!-- contract-key:flow-collection-context -->
<!-- contract-key:outer-transaction -->
<!-- contract-key:database-selection -->
<!-- contract-key:nested-transactions-rejected -->
<!-- contract-key:closed-projection -->
<!-- contract-key:open-projection-rejected -->
<!-- contract-key:matcher-projection-matrix -->
<!-- contract-key:find-one-cardinality -->
<!-- contract-key:first-one-all-page-slice-count-exists -->
<!-- contract-key:error-taxonomy -->
<!-- contract-key:callback-scope -->
<!-- contract-key:cancellation -->
<!-- contract-key:streaming-retry-no-duplicate -->
<!-- contract-key:terminal-retry-delegated -->

coroutine-native Query by Example API가 필요하면
`ExposedR2dbcQueryByExampleRepository`를 사용하세요. 이 계약은 `suspend`와
Kotlin `Flow`만 노출하며 Reactor `Mono`/`Flux`는 포함하지 않습니다.

```kotlin
interface UserRepository : ExposedR2dbcQueryByExampleRepository<User, Long> {
    override val table: IdTable<Long> get() = Users
    override fun extractId(entity: User): Long? = entity.id
    override fun toDomain(row: ResultRow): User = User(
        id = row[Users.id].value,
        name = row[Users.name],
        email = row[Users.email],
        age = row[Users.age],
    )
    override fun toPersistValues(domain: User): Map<Column<*>, Any?> = mapOf(
        Users.name to domain.name,
        Users.email to domain.email,
        Users.age to domain.age,
    )
}

val example = Example.of(
    User(name = "Alice", email = "ignored", age = 0),
    ExampleMatcher.matching().withIgnorePaths("id", "email", "age"),
)

val alice: User? = userRepository.findOne(example)
val users: Flow<User> = userRepository.findAll(example)
val names: Flow<NameView> = userRepository.findBy(example) { query ->
    query.asType(NameView::class)
        .project("name")
        .sortBy(Sort.by(Sort.Direction.DESC, "age"))
        .all()
}
```

지원하는 matcher는 exact/default, `CONTAINING`, `STARTING`, `ENDING`이며 명시적
null 포함을 지원합니다. Regex, ignore-case, 중첩 property, open/SpEL
projection, 부분 domain projection은 SQL 실행 전에 실패합니다. `findOne`과
fluent `one()`은 strict cardinality를 사용합니다. 결과가 없으면 `null`, 하나면
반환하고, 여러 개면 `IncorrectResultSizeDataAccessException`을 던집니다.

Fluent plan은 immutable입니다. property를 지정한 `project()`는 closed
projection의 required source property와 정확히 일치해야 하며, 빈 호출은 필요한
property 자동 선택으로 초기화합니다. closed interface, Kotlin constructor type,
Java record는 필요한 컬럼만 조회합니다. `first()`, `one()`, `all()`, `page()`,
`slice()`, `count()`, `exists()`는 `Pageable` 우선순위와 ID-only 존재 확인을
포함한 각 terminal semantics를 유지합니다.

`Flow`는 cold이므로 같은 flow를 두 번 collect하면 서로 독립적인 transaction이
두 번 실행됩니다. query는 현재 coroutine context에서 collect할 때 실행됩니다.
다른 database를 선택하려면 `suspendTransaction(database) { flow.collect { ... } }`
안에서 collect하세요. 호출자가 소유한 활성 Exposed transaction은 재사용하며,
`useNestedTransactions=true`인 경우 SQL 실행 전에 거부합니다. `findBy` callback
scope에서는 query를 만들고 terminal을 호출할 수 있으며, cancellation 시 원래
`CancellationException`을 유지하고 transaction lease를 해제합니다.

top-level streaming은 row 중복 재방출을 막기 위해 `maxAttempts = 1`을 사용합니다.
non-streaming terminal의 retry, backoff, timeout과 outer transaction 설정은 Exposed와
호출자에게 위임합니다. 지원하지 않는 matcher/projection/sort는
`UnsupportedOperationException` 또는 `InvalidDataAccessApiUsageException`으로
실패하며, mapping 실패는 sanitized `MappingException`, cardinality 위반은
`IncorrectResultSizeDataAccessException`으로 보고합니다.

<!-- r2dbc-coroutine-fluent-query:END -->

## 사용 예시

### 엔티티 및 테이블 정의

```kotlin
object Users : LongIdTable("users") {
    val name = varchar("name", 255)
    val email = varchar("email", 255)
    val age = integer("age")
}

data class User(
    val id: Long? = null,
    val name: String,
    val email: String,
    val age: Int,
) : java.io.Serializable
```

### Repository 구현

```kotlin
interface UserRepository : ExposedR2dbcRepository<User, Long> {
    override val table: IdTable<Long> get() = Users

    override fun extractId(entity: User): Long? = entity.id

    override fun toDomain(row: ResultRow): User =
        User(
            id = row[Users.id].value,
            name = row[Users.name],
            email = row[Users.email],
            age = row[Users.age],
        )

    override fun toPersistValues(domain: User): Map<Column<*>, Any?> =
        mapOf(
            Users.name to domain.name,
            Users.email to domain.email,
            Users.age to domain.age,
        )
}
```

### Service 사용

```kotlin
@Service
class UserService(
    private val userRepository: UserRepository
) {
    suspend fun createUser(name: String, email: String, age: Int): User {
        return userRepository.save(User(name = name, email = email, age = age))
    }

    suspend fun getUserById(id: Long): User? {
        return userRepository.findByIdOrNull(id)
    }

    suspend fun getAdultUsers(): List<User> {
        return userRepository.findAll { Users.age greaterEq 18 }.toList()
    }

    suspend fun getUsersPage(pageable: Pageable): Page<User> {
        return userRepository.findAll(pageable)
    }

    suspend fun streamLargeUserList(): Flow<User> {
        return userRepository.streamAll()
    }

    suspend fun countByAge(age: Int): Long {
        return userRepository.count { Users.age eq age }
    }
}
```

### REST Controller (WebFlux)

```kotlin
@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService
) {
    @PostMapping
    suspend fun createUser(@RequestBody request: CreateUserRequest): ResponseEntity<User> {
        val user = userService.createUser(request.name, request.email, request.age)
        return ResponseEntity.status(HttpStatus.CREATED).body(user)
    }

    @GetMapping("/{id}")
    suspend fun getUser(@PathVariable id: Long): ResponseEntity<User> {
        return userService.getUserById(id)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }

    @GetMapping
    suspend fun listUsers(@ParameterObject pageable: Pageable): Page<User> {
        return userService.getUsersPage(pageable)
    }

    @GetMapping("/adults")
    fun getAdults(): Flow<User> = flow {
        userService.getAdultUsers().forEach { emit(it) }
    }

    @GetMapping("/stream")
    fun streamUsers(): Flow<User> {
        return userService.streamLargeUserList()
    }

    @GetMapping("/count")
    suspend fun countAdults(): ResponseEntity<Long> {
        val count = userService.countByAge(18)
        return ResponseEntity.ok(count)
    }

    @DeleteMapping("/{id}")
    suspend fun deleteUser(@PathVariable id: Long): ResponseEntity<Void> {
        userService.deleteUser(id)
        return ResponseEntity.noContent().build()
    }
}
```

## 핵심 메서드

### CRUD 작업

```kotlin
// 저장
suspend fun save(entity: User): User

// 조회
suspend fun findByIdOrNull(id: Long): User?

// 모두 조회
suspend fun findAllAsList(): List<User>  // 메모리 로드

// 스트림 조회 (백프레셔)
fun findAll(): Flow<User>

// 존재 확인
suspend fun existsById(id: Long): Boolean

// 삭제
suspend fun deleteById(id: Long)

// 개수
suspend fun count(): Long
```

### 페이징 및 정렬

```kotlin
suspend fun findAll(pageable: Pageable): Page<User>

// 예제
val pageable = PageRequest.of(
    0,  // 페이지 번호
    20, // 페이지 크기
    Sort.by("age").descending()
)
```

### Flow 및 스트리밍

```kotlin
// 일괄 로드 후 Flow 반환
fun findAll(): Flow<User>

// row-by-row 스트리밍 (메모리 효율적)
fun streamAll(database: R2dbcDatabase? = null): Flow<User>

// 조건부 스트리밍
fun findAll(op: () -> Op<Boolean>): Flow<User>
```

### 대량 작업

```kotlin
// 여러 엔티티 저장
fun saveAll(entities: Iterable<User>): Flow<User>

// Flow로 저장 (atomic transaction; commit 이후 방출)
fun saveAll(entityStream: Flow<User>): Flow<User>

// 여러 개 삭제
suspend fun deleteAllById(ids: Iterable<Long>)
```

`saveAll(entityStream: Flow<User>)`는 cold `Flow`입니다. 하나의 Exposed 트랜잭션에서
엔티티를 순차 저장하고 transaction block이 끝날 때까지 저장 결과를 보관합니다.
최상위에서 호출하면 입력 수집 block이 정상 완료될 때 트랜잭션을 커밋한 뒤 저장 결과를
방출하며, 입력 수집 중 취소나 예외가 발생하면 롤백하고 결과를 방출하지 않습니다.
commit 이후 downstream collector에서 취소나 예외가 발생해도 이미 완료된 트랜잭션은
롤백할 수 없고 남은 결과 방출만 중단될 수 있습니다. 이미 활성화된 outer transaction을
재사용하는 경우 nested block이 반환된 뒤 outer transaction 커밋 전에 결과를 방출할 수
있습니다. 최종 commit/rollback 경계는 호출자가 소유하므로 외부 side effect는 outer
scope가 성공한 뒤에 수행해야 합니다. Exposed가 데이터베이스 예외로 최상위 R2DBC
트랜잭션을 재시도하면 입력 `Flow`를 다시 collect할 수 있으므로, retry를 사용하는
경우 replayable하고 side effect가 없는 입력을 제공하세요. 반환된 `Flow`를 collect해야
작업이 시작됩니다. 하나의 atomic transaction 안에서 결과를 구체화하므로 입력이
크거나 끝나지 않으면 메모리를 점유하고 트랜잭션을 오래 유지할 수 있습니다. chunked
저장은 별도 API 범위입니다.

## 테스트 작성

### Unit 테스트

```kotlin
@SpringBootTest
class UserRepositoryTest {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var r2dbcDatabase: R2dbcDatabase

    @Test
    fun `save and findById`() = runTest {
        val user = User(name = "Alice", email = "alice@example.com", age = 30)
        val saved = userRepository.save(user)

        val found = userRepository.findByIdOrNull(saved.id!!)
        assertThat(found).isNotNull()
        assertThat(found?.name).isEqualTo("Alice")
    }

    @Test
    fun `findAll returns users`() = runTest {
        // 테스트 데이터 준비
        suspendTransaction(r2dbcDatabase) {
            Users.deleteAll()
        }

        userRepository.save(User(name = "Alice", email = "alice@example.com", age = 30))
        userRepository.save(User(name = "Bob", email = "bob@example.com", age = 25))

        val users = userRepository.findAllAsList()
        assertThat(users).hasSize(2)
    }

    @Test
    fun `streamAll processes large dataset`() = runTest {
        val count = AtomicInteger(0)

        userRepository.streamAll()
            .collect { user ->
                count.incrementAndGet()
            }

        assertThat(count.get()).isGreaterThan(0)
    }
}
```

## 의존성

- **Spring Boot**: 4.0.x 이상
- **Spring Data Reactive**: 3.4.x 이상
- **Exposed**: 1.0.x 이상 (R2DBC 지원)
- **Kotlin**: 2.0 이상
- **Coroutines**: 1.8.x 이상
- **R2DBC Driver**: H2, PostgreSQL, MySQL, MariaDB 등

### 데이터베이스별 드라이버

```gradle
dependencies {
    // H2
    implementation("io.r2dbc:r2dbc-h2:${r2dbcH2Version}")

    // PostgreSQL
    implementation("io.r2dbc:r2dbc-postgresql:${r2dbcPostgresqlVersion}")

    // MySQL
    implementation("io.r2dbc:r2dbc-mysql:${r2dbcMysqlVersion}")

    // MariaDB
    implementation("io.r2dbc:r2dbc-mariadb:${r2dbcMariadbVersion}")
}
```

## 설정

### Spring Boot 자동 구성

```properties
# application.properties (H2 예시)
spring.r2dbc.url=r2dbc:h2:mem:///test
spring.r2dbc.username=sa
spring.r2dbc.password=
```

```properties
# application.properties (PostgreSQL 예시)
spring.r2dbc.url=r2dbc:postgresql://localhost:5432/mydb
spring.r2dbc.username=postgres
spring.r2dbc.password=password
```

### 명시적 구성

```kotlin
@Configuration
@EnableExposedR2dbcRepositories(basePackages = ["com.example.repository"])
class RepositoryConfig {
    // 자동 구성 처리
}
```

## 주의사항

### Suspend 함수 사용

Repository의 모든 조회/저장/삭제 메서드는 suspend 함수입니다:

```kotlin
// 반드시 코루틴 컨텍스트에서 호출
suspend fun getUser(id: Long) = userRepository.findByIdOrNull(id)

// Controller에서
@GetMapping("/{id}")
suspend fun get(@PathVariable id: Long): User? = getUser(id)
```

### Flow 소비 방식

`findAll()`과 `streamAll()`의 차이:

```kotlin
// findAll: 결과를 모두 메모리로 로드한 후 Flow 반환
userRepository.findAll().toList()  // 메모리 사용량 증가

// streamAll: row-by-row 스트리밍, 백프레셔 지원
userRepository.streamAll()  // 메모리 효율적
    .collect { user -> /* 처리 */ }

// saveAll(Flow): 순차 저장 후 transaction 완료 뒤 결과 방출
userRepository.saveAll(inputUsers)
    .collect { savedUser -> /* 저장된 엔티티 처리 */ }
```

`saveAll(Flow)` 오버로드는 collect할 때만 시작합니다. 입력을 모두 소비하고 저장한 뒤
결과를 방출하므로 collector가 입력 저장 속도를 제어하는 API는 아닙니다.
row-by-row 조회 스트리밍에는 `streamAll()`을 사용하세요. 이 오버로드는 하나의 atomic
transaction을 유지하며 chunked 쓰기는 제공하지 않습니다.

### toDomain과 toPersistValues 구현 필수

Repository 인터페이스를 구현할 때 반드시 정의:

```kotlin
interface UserRepository : ExposedR2dbcRepository<User, Long> {
    // 필수: row 변환
    override fun toDomain(row: ResultRow): User

    // 필수: 저장 값 정의
    override fun toPersistValues(domain: User): Map<Column<*>, Any?>
}
```

### ID 컬럼 제외

`toPersistValues`에서 ID 컬럼은 반드시 제외:

```kotlin
override fun toPersistValues(domain: User): Map<Column<*>, Any?> =
    mapOf(
        Users.name to domain.name,
        Users.email to domain.email,
        // Users.id는 제외 (자동 생성)
    )
```

### Transaction 범위

R2DBC 기반 대체로 suspend 함수 내부에서 자동으로 처리됩니다. 복잡한 작업은 `suspendTransaction` 사용:

```kotlin
suspend fun complexOperation() {
    suspendTransaction(r2dbcDatabase) {
        userRepository.save(user1)
        userRepository.save(user2)
        // 트랜잭션 내 모두 성공 또는 모두 실패
    }
}
```

`@EnableExposedR2dbcRepositories(transactionManagerRef = ...)`는 소스·바이너리
호환성만을 위해 유지하며 deprecated 상태입니다. 이 어댑터는 Spring 트랜잭션
인터셉터를 우회하므로 기본값이 아닌 값을 지정하면 저장소 등록 단계에서 거부하며,
Exposed `R2dbcDatabase`를 선택하지 않습니다. 데이터베이스가 여러 개라면
`suspendTransaction(database) { ... }`에서 대상을 명시하고, 스트리밍 API 자체에서
선택해야 한다면 `streamAll(database)`을 사용하세요.

## 성능 최적화

### 대용량 스트리밍

```kotlin
userRepository.streamAll()
    .buffer(256)  // 버퍼 크기 조정
    .collect { user ->
        // 백프레셔 제어
    }
```

### 배치 삽입

```kotlin
userRepository.saveAll(
    listOf(
        User(name = "Alice", email = "alice@example.com", age = 30),
        User(name = "Bob", email = "bob@example.com", age = 25)
    )
).toList()
```

### 조건부 스트리밍

복잡한 조건은 DSL 사용:

```kotlin
userRepository.findAll {
    (Users.age greaterEq 18) and (Users.email like "%example.com")
}.toList()
```

## 문제 해결

### "suspend 함수를 블로킹 컨텍스트에서 호출 불가"

WebFlux 또는 코루틴 컨텍스트 필요:

```kotlin
// 잘못된 사용
fun getUser(id: Long) {
    val user = userRepository.findByIdOrNull(id)  // 컴파일 에러
}

// 올바른 사용
suspend fun getUser(id: Long) {
    val user = userRepository.findByIdOrNull(id)  // OK
}

// 또는
@GetMapping
suspend fun getUser(): User? = userRepository.findByIdOrNull(1)
```

### "Flow를 toList() 없이 사용"

Response로 Stream 반환:

```kotlin
@GetMapping("/stream")
fun getUsers(): Flow<User> = userRepository.findAll()
```

또는 명시적으로 리스트 변환:

```kotlin
@GetMapping
suspend fun getUsers(): List<User> =
    userRepository.findAll().toList()
```

## 관련 모듈

- **exposed-r2dbc**: 핵심 Exposed R2DBC Repository 구현
- **exposed-spring-boot-r2dbc**: Spring Boot 4 버전
- **exposed-spring-boot-jdbc**: JDBC 기반 Repository
- **bluetape4k-coroutines**: 코루틴 유틸리티
