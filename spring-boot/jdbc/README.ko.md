# exposed-spring-boot-jdbc

[English](./README.md) | 한국어

**Exposed DAO Entity 기반 Spring Data JDBC Repository (Spring Boot 4.x / Spring 7)**

Exposed DAO 엔티티를 Spring Data Repository처럼 다루기 위한 JDBC 브리지입니다.
Spring Boot 자동 구성, Spring Data repository factory, Exposed transaction,
method-name query parsing을 하나의 repository 모델로 연결합니다.

## Repository Wiring

![Spring Boot Exposed JDBC repository wiring diagram](../../docs/images/readme-diagrams/spring-boot-exposed-jdbc-diagram-01.png)

## Query Resolution Flow

![Spring Boot Exposed JDBC query resolution flow diagram](../../docs/images/readme-diagrams/spring-boot-exposed-jdbc-diagram-02.png)

## 설치

```gradle
dependencies {
    implementation(platform(Libs.spring_boot_dependencies))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc:${version}")
}
```

## 주요 기능

### 1. ExposedJdbcRepository - Spring Data 표준 인터페이스

```kotlin
@NoRepositoryBean
interface ExposedJdbcRepository<E: Entity<ID>, ID: Any>:
    ListCrudRepository<E, ID>,
    ListPagingAndSortingRepository<E, ID>,
    QueryByExampleExecutor<E>
```

- **ListCrudRepository**: `save`, `findById`, `findAll`, `delete`, `deleteById` 등
- **ListPagingAndSortingRepository**: 페이징, 정렬 지원
- **QueryByExampleExecutor**: 예제 기반 쿼리
- **Exposed DSL 확장**: `findAll { op }`, `count { op }`, `exists { op }`

### 2. PartTree 쿼리 자동 생성

메서드 이름에 따라 자동으로 Exposed DSL 쿼리 생성:

```kotlin
interface UserRepository : ExposedJdbcRepository<User, Long> {
    // 자동 쿼리 생성
    fun findByName(name: String): List<User>
    fun findByAgeGreaterThan(age: Int): List<User>
    fun findByEmailContaining(keyword: String): List<User>
    fun findByNameAndAge(name: String, age: Int): User?
    fun findByAgeBetween(min: Int, max: Int): List<User>
    fun findByNameOrderByAgeDesc(name: String): List<User>
    fun findTop3ByOrderByAgeDesc(): List<User>
    fun countByAge(age: Int): Long
    fun existsByEmail(email: String): Boolean
    fun deleteByName(name: String): Long
}
```

### 3. @Query 어노테이션 - 직접 SQL 작성

```kotlin
interface UserRepository : ExposedJdbcRepository<User, Long> {
    @Query("SELECT * FROM users WHERE email = ?1")
    fun findByEmailNative(email: String): List<User>

    @Query("SELECT * FROM users WHERE age = ?2 AND email = ?1")
    fun findByEmailAndAgeNative(email: String, age: Int): List<User>

    @Query("SELECT * FROM users WHERE age BETWEEN ?1 AND ?2")
    fun findByAgeRangeNative(minAge: Int, maxAge: Int): List<User>
}
```

### 4. 자동 구성 (Auto Configuration)

```kotlin
@Configuration
@EnableExposedJdbcRepositories(basePackages = ["com.example.repository"])
class RepositoryConfig
```

또는 Spring Boot 자동 구성 사용:

```kotlin
// application.properties
spring.data.exposed-jdbc.repositories.enabled=true
spring.data.exposed-jdbc.repositories.base-packages=com.example.repository
```

### 5. Actuator Cache Health

Spring Boot Actuator와 `bluetape4k-exposed-jdbc-caffeine`이 classpath에 있으면
`exposedJdbcCacheHealthIndicator`가 자동 등록됩니다. 이 indicator는 Caffeine
write-through/write-behind 상태를 Boot health detail로 노출합니다: cache mode,
queue depth, flush job 상태, 마지막 flush error.

```properties
bluetape4k.exposed.cache.health.enabled=true
```

Flush 실패는 `DOWN`, write-behind queue가 남아 있는데 flush job이 동작하지 않는
상태는 `OUT_OF_SERVICE`로 매핑됩니다. 비활성화하려면 이 property를 `false`로
설정하세요.

## 사용 예시

### 엔티티 정의

```kotlin
object Users : LongIdTable("users") {
    val name = varchar("name", 255)
    val email = varchar("email", 255).uniqueIndex()
    val age = integer("age")
}

@ExposedEntity
class User(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<User>(Users)

    var name: String by Users.name
    var email: String by Users.email
    var age: Int by Users.age
}
```

### Repository 정의

```kotlin
interface UserRepository : ExposedJdbcRepository<User, Long> {
    fun findByName(name: String): List<User>
    fun findByAgeGreaterThan(age: Int): List<User>
    fun findByEmailContaining(keyword: String): List<User>
}
```

### Service 사용

```kotlin
@Service
@Transactional
class UserService(
    private val userRepository: UserRepository
) {
    fun createUser(name: String, email: String, age: Int): User {
        return transaction {
            User.new {
                this.name = name
                this.email = email
                this.age = age
            }
        }
    }

    fun getUserByName(name: String): List<User> {
        return userRepository.findByName(name)
    }

    fun getAdultUsers(): List<User> {
        return userRepository.findByAgeGreaterThan(18)
    }

    fun getUserPage(pageable: Pageable): Page<User> {
        return userRepository.findAll(pageable)
    }

    fun getUsersWithDslCondition(): List<User> {
        return userRepository.findAll { Users.age greaterEq 18 }
    }
}
```

### REST Controller 예제

```kotlin
@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService
) {
    @PostMapping
    fun createUser(@RequestBody request: CreateUserRequest): ResponseEntity<User> {
        val user = userService.createUser(request.name, request.email, request.age)
        return ResponseEntity.status(HttpStatus.CREATED).body(user)
    }

    @GetMapping
    fun listUsers(@ParameterObject pageable: Pageable): Page<User> {
        return userService.getUserPage(pageable)
    }

    @GetMapping("/by-name")
    fun searchByName(@RequestParam name: String): List<User> {
        return userService.getUserByName(name)
    }

    @GetMapping("/adults")
    fun getAdults(): List<User> {
        return userService.getAdultUsers()
    }
}
```

## Exposed DSL 확장 메서드

Repository 인터페이스에서 추가 메서드 사용:

```kotlin
@Service
@Transactional(readOnly = true)
class UserQueryService(
    private val userRepository: UserRepository
) {
    // DSL 조건으로 조회
    fun findActiveUsers(): List<User> =
        userRepository.findAll { Users.age greaterEq 18 }

    // DSL 조건으로 개수 세기
    fun countAdults(): Long =
        userRepository.count { Users.age greaterEq 18 }

    // DSL 조건으로 존재 확인
    fun hasAdults(): Boolean =
        userRepository.exists { Users.age greaterEq 18 }
}
```

## 의존성

- **Spring Boot**: 4.0.x 이상
- **Spring Data**: 3.4.x 이상
- **Exposed**: 1.0.x 이상
- **Kotlin**: 2.0 이상

### Spring Boot BOM 사용

```gradle
dependencies {
    implementation(platform(Libs.spring_boot_dependencies))
}
```

주의: `dependencyManagement` 플러그인은 Kotlin Gradle Plugin과 호환성 문제가 있으므로 `platform()`을 사용합니다.

<!-- issue-323-section:start -->
<a id="transaction-aware-domain-events"></a>
## 트랜잭션 인식 도메인 이벤트

![트랜잭션 인식 애그리거트 도메인 이벤트 시퀀스](../../docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.png)

`ExposedAggregateEventPublisher`는 repository 저장 직후 command transaction이 아직 활성 상태일 때
aggregate의 불변 event snapshot을 Spring에 전달합니다. JDBC starter는 `AggregateRoot`, Spring application
event 및 transaction synchronization API가 classpath에 있고, 여러 manager 중 하나의 `@Primary`를 포함해
정확히 하나의 `PlatformTransactionManager`를 선택할 수 있으며, 애플리케이션이 publisher bean을 직접
선언하지 않았을 때 이를 자동 구성합니다.

같은 command transaction 안에서 aggregate의 마지막 연산으로 `publishAfterSave`를 정확히 한 번 호출합니다.

```kotlin
transactionTemplate.executeWithoutResult {
    repository.save(OrderEntity.from(aggregate))
    aggregateEventPublisher.publishAfterSave(aggregate)
}
```

Event가 없는 aggregate는 transaction 없이도 no-op입니다. Event가 있는 aggregate는 활성 Spring transaction
synchronization과 실제 활성 transaction이 모두 필요합니다. Spring handoff는 즉시 일어나므로 synchronous
listener는 호출자 안에서 실행되고, 기본 `@TransactionalEventListener` 및 Spring Modulith listener는
`AFTER_COMMIT`에서 실행됩니다. Commit 완료 시 등록된 aggregate buffer를 비우고, 전체 rollback과
`STATUS_UNKNOWN`에서는 보존합니다. Publication 실패, 중복 등록, snapshot 변경은 호출자가 최초 예외를 잡아도
transaction을 poison 처리합니다. 따라서 synchronous listener는 command 실패 경계에 참여하며, irreversible
side effect는 별도로 중복 방지해야 합니다.

중복 등록은 동일 transaction에서 같은 aggregate 객체를 다시 등록하는 경우를 뜻합니다. Aggregate ID가 같아도
객체가 다르거나 이후 transaction에서 다시 등록하면 publisher가 중복을 제거하지 않으므로, 이 경우에는
애플리케이션 수준의 멱등성으로 처리해야 합니다.

Event와 전체 payload graph는 깊은 불변이어야 하며 호출자는 안정된 event 객체 reference를 유지해야 합니다.
Publisher는 identity 검증을 위해 원래 snapshot을 보관하며 event를 복사하거나 직렬화하지 않습니다. Handoff
이후 event를 추가, 제거, 재정렬, 교체하지 마세요. Aggregate마다 마지막에 한 번만 호출해야 합니다.
`PROPAGATION_NESTED` savepoint와 겹치는 `REQUIRES_NEW` transaction에서 같은 instance를 재사용하는 방식은
지원하지 않습니다. Suspend된 `REQUIRES_NEW` transaction에서 서로 다른 aggregate instance는 격리됩니다.
Commit 이후 database에 쓰는 listener는 `REQUIRES_NEW` transaction을 열어야 합니다.

Publisher는 plain Spring Boot infrastructure이며 Spring Modulith는 선택 사항입니다. Modulith가 있으면 publication
저장과 listener replay를 제공할 수 있지만, 이 bridge는 outbox도 exactly-once delivery도 아닙니다. Consumer는
idempotent해야 합니다. 이 publisher는 Spring의 동기 JDBC transaction synchronization을 사용하므로 R2DBC는
의도적으로 제외합니다. Audit history, snapshot persistence, JaVers commit semantics는 publisher의 금지된
dependency이며, 필요한 연결은 애플리케이션 소유 listener 또는 service에서 구성해야 합니다.

### 여러 Transaction Manager

자동 구성은 Spring의 single-candidate 규칙을 따릅니다. Manager가 하나이거나 여러 manager 중 정확히 하나가
`@Primary`이면 bean이 활성화됩니다. Publisher 자체는 manager를 선택하거나 보관하지 않습니다. 모호한
애플리케이션은 publisher를 명시적으로 선언하고 repository, command transaction, event handoff를 의도한 manager에
맞춰야 합니다. `transactionManagerRef`가 repository manager를 선택하고, command boundary도 다음 컴파일된 예제와
같이 동일한 manager를 선택해야 합니다.

<!-- issue-323-multi-manager:start -->
```kotlin
@Configuration(proxyBeanMethods = false)
@EnableExposedJdbcRepositories(
    basePackageClasses = [OrderRepository::class],
    transactionManagerRef = "secondTransactionManager",
)
class SecondOrderStoreConfiguration {
    @Bean("secondTransactionManager")
    fun secondTransactionManager(
        @Qualifier("secondDataSource") dataSource: DataSource,
    ): PlatformTransactionManager = SpringTransactionManager(dataSource, DatabaseConfig {}, false)

    @Bean("secondTransactionTemplate")
    fun secondTransactionTemplate(
        @Qualifier("secondTransactionManager") manager: PlatformTransactionManager,
    ): TransactionTemplate = TransactionTemplate(manager)
}

class OrderCommandService(
    private val repository: OrderRepository,
    private val aggregateEventPublisher: ExposedAggregateEventPublisher,
    @Qualifier("secondTransactionTemplate") private val transactionTemplate: TransactionTemplate,
) {
    fun save(aggregate: OrderAggregate, rollback: Boolean = false) {
        transactionTemplate.executeWithoutResult { status ->
            repository.save(OrderEntity.from(aggregate))
            aggregateEventPublisher.publishAfterSave(aggregate)
            if (rollback) status.setRollbackOnly()
        }
    }
}
```
<!-- issue-323-multi-manager:end -->

### 결과 및 재시도 판단

<!-- issue-323-outcome-table:start -->
| 결과 | 영속 상태 | Buffer | Command 재시도 |
|---|---|---|---|
| 활성 transaction 없음 또는 동일 transaction 전제 위반 | 불확정 | 보존 | 자동 재시도 금지, 먼저 대조 |
| 전체 rollback 또는 poison된 handoff | Rollback | 보존 | 새 transaction에서만 허용, 동기 부수 효과는 중복 실행 방지가 필요할 수 있음 |
| Commit된 listener 실패 | Commit | 비움 | Command 재시도 금지, listener retry/replay 사용 |
| Commit된 cleanup 실패 | Commit | 남아 있을 수 있음 | 재시도 금지, aggregate instance 폐기 |
| `STATUS_UNKNOWN` | 불확정 | 보존 | 자동 재시도 금지, 먼저 대조 |
<!-- issue-323-outcome-table:end -->

완료 단계에서는 민감 정보를 제거한 완료 이상 로그 두 종류를 기록합니다. Commit된 persistence 이후 buffer를 비우지 못하면
`aggregate-event-cleanup-failed`, Spring이 transaction 결과를 판단하지 못하면
`aggregate-event-completion-unknown`입니다. 로그에는 aggregate/event type과 개수, 형식이 유효한 허용 목록의
`traceId`, `spanId`, `requestId`만 포함하며 event payload나 exception message는 포함하지 않습니다.

<!-- issue-323-reconciliation:start -->
<!-- issue-323-reconciliation:state=present-present;action=listener-recovery;command-retry=false -->
- Persistence 있음 + publication 있음: command를 replay하지 말고 Modulith replay 또는 listener recovery를 사용합니다.
<!-- issue-323-reconciliation:state=present-absent;action=idempotent-repair;command-retry=false -->
- Persistence 있음 + publication 없음: command를 replay하지 말고 persisted state에서 애플리케이션 소유 idempotent repair를 실행합니다.
<!-- issue-323-reconciliation:state=absent-absent;action=fresh-command-after-side-effect-check;command-retry=conditional -->
- Persistence 없음 + publication 없음: irreversible synchronous side effect가 없음을 확인한 뒤 새 command로만 재시도합니다.
<!-- issue-323-reconciliation:state=absent-present;action=quarantine-and-compensate;command-retry=false -->
- Persistence 없음 + publication 있음: invariant 위반을 격리하고 수동 보상하며 어느 경로도 replay하지 않습니다.
<!-- issue-323-reconciliation:end -->

### 프로덕션 롤아웃 체크리스트

Canary 전에 애플리케이션 소유자를 지정해야 합니다. 앞서 설명한 두 가지 이상 범주에 대한 alert를 설정하고, 허용 목록에 포함된
correlation field를 하나 이상 전파해야 합니다. Audit record나 trace로 persistence key를 조회할 수 있어야 하며,
데이터베이스 읽기 권한과 publication 읽기 권한도 준비합니다. Canary는 영속 aggregate 1건, 내구 발행 1건,
listener side effect 1건, 이상 범주 로그 0건을 증명해야 합니다.

<!-- issue-323-rollout:01-stop -->
1. Canary 또는 anomaly alert가 실패하면 롤아웃을 중지합니다.
<!-- issue-323-rollout:02-preserve -->
2. 상태를 변경하기 전에 로그, aggregate record, publication row, listener evidence를 보존합니다.
<!-- issue-323-rollout:03-reconcile-repair -->
3. 위 네 상태를 대조하고 canary를 idempotent하게 복구합니다.
<!-- issue-323-rollout:04-binary-rollback-version-defect-only -->
4. 증거 보존과 복구가 끝난 뒤 확인된 version defect에만 전체 바이너리 롤백을 사용합니다.

허용 목록의 correlation field가 없으면 영향 시간 구간을 격리하고 애플리케이션 감사 record를 사용하며 자동 복구를 금지합니다.
Migration은 replacement-only입니다. 같은 변경에서 수동 event loop와 수동 buffer clear를 제거하고 두
경로를 함께 실행하지 마세요. 바이너리 롤백은 command retry가 아니며, 증거를 보존하고 persistence/publication 상태를
대조 및 복구하기 전에 시작하면 안 됩니다.

Modulith publication store를 보안 및 개인정보 경계로 취급하세요. 최소 권한 데이터베이스 접근 제어, 애플리케이션
infrastructure가 허용하는 저장 데이터 암호화와 전송 데이터 암호화, 무결성 보호, 명시적 보존/삭제 정책,
페이로드 최소화를 적용합니다. 저장된 이벤트 클래스 이름은 노출되는 schema metadata이므로 package 이름과 migration 계획을
검토해야 합니다. 이 통제 때문에 publisher가 audit history, snapshot persistence, JaVers commit에 의존해서는 안 됩니다.
<!-- issue-323-section:end -->

## 주의사항

### 트랜잭션 처리

Exposed DAO 엔티티 생성/수정은 `transaction` 블록 내에서만 가능합니다:

```kotlin
@Transactional
fun createUser(name: String, email: String): User {
    return transaction {  // Spring 트랜잭션과 Exposed 트랜잭션 통합
        User.new {
            this.name = name
            this.email = email
        }
    }
}
```

### PartTree 쿼리 제약

지원하는 키워드:

- **비교**: `GreaterThan`, `LessThan`, `Between`, `In`, `Contains`
- **정렬**: `OrderBy`
- **집계**: `count`, `exists`
- **삭제**: `deleteBy`
- **페이징**: `Top`, `First`

지원하지 않는 패턴:

- 복잡한 OR/AND 조합 → `@Query` 또는 DSL 메서드 사용
- 조인 → Exposed DSL 직접 사용

### @Query 플레이스홀더

- `?1`, `?2`, ... : 메서드 파라미터 순서 (1-indexed)
- 중복 플레이스홀더 지원: `?1 OR ?1`
- 플레이스홀더 건너뛰기 미지원: `?1`과 `?3` 동시 사용 불가

## 멀티 데이터베이스

동일한 Repository 패턴으로 H2, PostgreSQL, MySQL, MariaDB 지원:

```properties
# application.properties (MySQL 예시)
spring.datasource.url=jdbc:mysql://localhost:3306/mydb
spring.datasource.username=root
spring.datasource.password=password
```

```properties
# application.properties (PostgreSQL 예시)
spring.datasource.url=jdbc:postgresql://localhost:5432/mydb
```

## 성능 최적화

### 페이징 쿼리

```kotlin
val page = userRepository.findAll(PageRequest.of(0, 10, Sort.by("age").descending()))
```

### 배치 작업

```kotlin
val users = listOf(
    User(null, "Alice", 30),
    User(null, "Bob", 25)
)
userRepository.saveAll(users)
```

### DSL 직접 사용

복잡한 조건은 DSL 메서드 사용:

```kotlin
val users = userRepository.findAll {
    (Users.age greaterEq 18) and (Users.name like "%A%")
}
```

## 문제 해결

### "Repository 빈이 생성되지 않음"

```kotlin
@EnableExposedJdbcRepositories(basePackages = ["com.example.repository"])
class AppConfig
```

또는 자동 구성 확인:

```properties
# application.properties
spring.data.exposed-jdbc.repositories.enabled=true
spring.data.exposed-jdbc.repositories.base-packages=com.example.repository
```

### "PartTree 쿼리 해석 오류"

더 간단한 메서드 이름 사용 또는 `@Query` 어노테이션 사용:

```kotlin
// 복잡한 메서드 이름 대신
@Query("SELECT * FROM users WHERE age > ?1 AND status = ?2")
fun findActiveAdults(age: Int, status: String): List<User>
```

### "LazyInitializationException"

응답 객체 구성 시 트랜잭션 끝나기 전에 모든 연관 데이터 로드:

```kotlin
@Transactional(readOnly = true)
fun getUser(id: Long): UserDto {
    val user = userRepository.findById(id).get()
    // 이 시점에서 모든 lazy 로딩 발생
    return user.toDto()
}
```

## 관련 모듈

- **exposed-jdbc**: 핵심 Exposed JDBC Repository 구현
- **exposed-spring-boot-jdbc**: Spring Boot 4 버전
- **exposed-spring-boot-r2dbc**: R2DBC 코루틴 Repository
