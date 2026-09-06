# Module exposed-jdbc-tests

[English](./README.md) | 한국어


## Exposed 1.5.0 단방향 해시

신규 Bluetape adapter 없이 upstream `exposed-crypt`를 직접 사용합니다. 이 모듈의
[`JdbcHashedColumnContractTest`](src/test/kotlin/io/bluetape4k/exposed/tests/crypt/JdbcHashedColumnContractTest.kt)는
H2에서 저장·조회·nullable·재저장·custom hasher·rehash를 검증합니다. R2DBC는 DSL 경로이며 DAO 지원을 뜻하지 않습니다.

애플리케이션은 기존 Exposed BOM/catalog를 사용하고 필요한 의존성을 명시적으로 선택합니다.
`exposed-crypt` 1.5.0은 `spring-security-crypto`를 전이 의존성으로 포함합니다.
Spring Boot starter는 필요하지 않습니다. Argon2/SCrypt에는 별도 BouncyCastle runtime이 필요합니다.
이 저장소에서 선택된 Spring Security 7.1.1의 검증 경로에는 `spring-core`도 필요합니다.
누락 시 `NoClassDefFoundError: org/springframework/util/StringUtils`를 재현했습니다.
아래 예제는 애플리케이션 BOM으로 Spring 버전을 관리한다는 전제입니다.
테스트에서만 사용할 때는 아래 implementation/runtimeOnly를 testImplementation/testRuntimeOnly로 바꿉니다.

```kotlin
dependencies {
    implementation(libs.exposed.crypt)
    runtimeOnly(bt4k.bouncycastle.bcprov) // Argon2 / SCrypt
    runtimeOnly("org.springframework:spring-core") // Spring Security 7.1.x
}
```

```kotlin
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.crypt.hash
import org.jetbrains.exposed.v1.crypt.hashed

object Accounts: Table("accounts") {
    val password = varchar("password_hash", 512).hashed()
}
val encoded = Accounts.password.hash(submittedPassword)
val accepted = encoded.matches(submittedPassword)
```

이 코드는 JDBC transaction 안에서 해당 backend의 insert/update DSL과 함께 사용합니다.
nullable 컬럼은 `varchar("optional", 512).nullable().hashed()`로 선언합니다.
조회한 `Hashed`는 그대로 대입합니다. `Hashed(hasher, encodedValue)`는 이미 인코딩된 값을 감쌀 뿐
해싱하지 않으므로 평문을 전달하면 안 됩니다.

- BCrypt 출력은 60자입니다. 예제의 512자는 알고리즘 식별자·파라미터 변경 여유이며 모든 custom hasher의 길이를 보장하지 않습니다.
- Argon2/SCrypt 출력 길이는 파라미터에 따라 달라집니다. PBKDF2의 pepper는 별도 비밀값이며 검증할 때 같은 pepper가 필요합니다.
- `Hasher`에는 `upgradeEncoding`이 없습니다. `PasswordEncoderHasher(DelegatingPasswordEncoder(...))`를 사용해 기존 `{id}hash`를 검증하고, 인증 성공 및 `upgradeEncoding(encodedValue)`가 true인 경우에만 제출된 평문을 새 설정으로 해시해 갱신합니다. 실패 입력은 기존 값을 변경하지 않습니다.
- 설정만 바꾸거나 평문 없이 기존 hash를 다시 해시해서 마이그레이션하지 않습니다. DB 갱신 경합 정책은 애플리케이션이 소유합니다.
- 테스트의 BCrypt strength 4/5는 실행 시간 단축용입니다. production 비용·입력 크기 제한·rate limit은 별도 측정·정책이며, 해시 연산을 이벤트 루프에서 실행하지 않습니다.
- `Hashed.toString()`은 `Hashed(***)`지만 `encodedValue` 직접 출력과 custom encoder 예외는 자동 redaction되지 않습니다. 평문·해시·요청 본문을 로그나 이벤트에 넣지 않고, 인증 실패는 일반화한 메시지로 처리합니다. SQL/driver debug logging도 별도로 제한합니다.
- 평문 미저장·로거/DB 오류 평문 미노출 테스트는 구성한 upstream 경로의 증거이며 임의 custom hasher나 애플리케이션 전체 로깅의 안전성을 보증하지 않습니다.
- 가역 암호화·검색 가능한 암호화는 [Tink 모듈](../tink/README.ko.md)의 별도 기능입니다.

공식 기준: [Exposed 1.5.0 crypt 소스](https://github.com/JetBrains/Exposed/tree/84361204b6639cad5696506a26595c97afac3531/exposed-crypt).


## 개요

Exposed 기반 모듈을 JDBC로 검증할 때 쓰는 공통 테스트 인프라입니다. 테스트 작성자는 `TestDB`로 실행 대상 DB를 고르고, 트랜잭션 스코프 헬퍼와 테이블/스키마 fixture 유틸을 사용해 H2 빠른 피드백부터 실제 MySQL/PostgreSQL 커버리지까지 같은 테스트 코드로 다룰 수 있습니다.

## Dialect 커버리지

![JDBC test dialect coverage](../../docs/images/readme-diagrams/exposed-jdbc-tests-diagram-01.png)

### 테스트 생명주기

![JDBC test lifecycle](../../docs/images/readme-diagrams/exposed-jdbc-tests-sequence-01.png)

## 의존성 추가

```kotlin
dependencies {
    testImplementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-tests")
}
```

## 주요 기능

- **공통 테스트 베이스**: `AbstractExposedTest`가 기본 시간대를 UTC로 고정하고, parameterized test용 `ENABLE_DIALECTS_METHOD`를 제공합니다.
- **Dialect 선택**: `TestDB.enabledDialects()`가 `useFastDB`, `EXPOSED_TEST_DB`, 기본 `H2 + PostgreSQL + MySQL 8` 조합을 기준으로 실행 대상을 정합니다.
- **JDBC 스코프 헬퍼**: `withDb`, `withTables`, `withSchemas`, auto-commit 변형이 하나의 Exposed transaction 안에서 fixture를 준비하고 정리합니다.
- **Coroutine 변형**: suspending 헬퍼가 blocking JDBC 헬퍼와 같은 흐름을 `suspendTransaction`으로 제공합니다.
- **공유 스키마와 assertion**: movie, board, blog, person, order, composite-id fixture를 재사용해 각 모듈 테스트 코드를 줄입니다.

## 지원 데이터베이스

| 데이터베이스             | TestDB         | Testcontainers |
|--------------------|----------------|----------------|
| H2 v1              | `H2_V1`        | 아니요            |
| H2 v2              | `H2`           | 아니요            |
| H2 MySQL 모드        | `H2_MYSQL`     | 아니요            |
| H2 MariaDB 모드      | `H2_MARIADB`   | 아니요            |
| H2 PostgreSQL 모드   | `H2_PSQL`      | 아니요            |
| MariaDB            | `MARIADB`      | 예              |
| MySQL 5.7          | `MYSQL_V5`     | 예              |
| MySQL 8.0          | `MYSQL_V8`     | 예              |
| PostgreSQL         | `POSTGRESQL`   | 예              |
| PostgreSQL pgjdbc-ng | `POSTGRESQLNG` | 예              |

## 사용 예시

### 기본 테스트 작성

```kotlin
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

object Users: LongIdTable("users") {
    val name = varchar("name", 50)
    val email = varchar("email", 100)
}

class UserRepositoryTest: AbstractExposedTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `should insert and find user`(testDB: TestDB) {
        withTables(testDB, Users) {
            // Insert
            Users.insert {
                it[name] = "John"
                it[email] = "john@example.com"
            }

            // Query
            val user = Users.selectAll().single()

            assertEquals("John", user[Users.name])
            assertEquals("john@example.com", user[Users.email])
        }
    }
}
```

### withDb - 테이블 없이 DB 연결만 필요한 경우

```kotlin
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withDb

@ParameterizedTest
@MethodSource(ENABLE_DIALECTS_METHOD)
fun `should connect to database`(testDB: TestDB) {
    withDb(testDB) {
        // 트랜잭션 내에서 실행
        val isConnected = connection.isValid(5)
        assertTrue(isConnected)
    }
}
```

### withTables - 테이블 자동 생성/삭제

```kotlin
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables

@ParameterizedTest
@MethodSource(ENABLE_DIALECTS_METHOD)
fun `should create and drop tables`(testDB: TestDB) {
    withTables(testDB, Users, Orders) {
        // 테스트 시작 전 테이블 자동 생성
        // 테스트 종료 후 테이블 자동 삭제

        Users.insert { /* ... */ }
        Orders.insert { /* ... */ }

        // 테스트 로직
    }
}
```

### Coroutine 환경 (비동기 테스트)

```kotlin
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTablesSuspending
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class AsyncRepositoryTest: AbstractExposedTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `should insert user in coroutine`(testDB: TestDB) = runBlocking {
        withTablesSuspending(testDB, Users) {
            // suspend 함수 내에서 실행
            Users.insert {
                it[name] = "John"
                it[email] = "john@example.com"
            }

            val user = Users.selectAll().single()
            assertEquals("John", user[Users.name])
        }
    }
}
```

### 특정 DB만 테스트

```kotlin
import io.bluetape4k.exposed.tests.TestDB

class PostgresOnlyTest: AbstractExposedTest() {

    // PostgreSQL만 테스트
    companion object {
        @JvmStatic
        fun databases() = TestDB.ALL_POSTGRES
    }

    @ParameterizedTest
    @MethodSource("databases")
    fun `postgres specific test`(testDB: TestDB) {
        withTables(testDB, Users) {
            // PostgreSQL 전용 테스트
        }
    }
}
```

### DB 그룹별 테스트

```kotlin
import io.bluetape4k.exposed.tests.TestDB

class MySQLLikeTest: AbstractExposedTest() {

    companion object {
        // MySQL + MariaDB + H2 MySQL 모드
        @JvmStatic
        fun databases() = TestDB.ALL_MYSQL_LIKE

        // PostgreSQL + H2 PostgreSQL 모드
        @JvmStatic
        fun postgresDatabases() = TestDB.ALL_POSTGRES_LIKE
    }

    @ParameterizedTest
    @MethodSource("databases")
    fun `mysql compatible test`(testDB: TestDB) {
        withTables(testDB, Users) {
            // MySQL 호환 DB 테스트
        }
    }
}
```

## TestDB 설정

```kotlin
import io.bluetape4k.exposed.tests.TestDBConfig

// Testcontainers 사용 여부
TestDBConfig.useTestcontainers = true  // 기본값

// 빠른 테스트를 위해 H2만 사용 (기본값: false)
TestDBConfig.useFastDB = true
```

## 테스트용 스키마/데이터

### MovieSchema (DAO 예시)

```kotlin
import io.bluetape4k.exposed.shared.entities.MovieSchema

class MovieTest: AbstractExposedTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `should query actors by movie`(testDB: TestDB) {
        withMovieAndActors(testDB) {
            // 샘플 데이터가 미리 로드됨
            val actors = ActorEntity.all()
            assertTrue(actors.isNotEmpty())
        }
    }
}
```

### 공유 테이블 스키마

| 파일                               | 설명                             |
|----------------------------------|--------------------------------|
| `shared/entities/MovieSchema.kt` | Movie, Actor, ActorInMovie 테이블 |
| `shared/entities/BoardSchema.kt` | Board 테이블                      |
| `shared/entities/BlogSchema.kt`  | Blog 테이블                       |
| `shared/mapping/PersonSchema.kt` | Person 매핑 테이블                  |
| `shared/mapping/OrderSchema.kt`  | Order 매핑 테이블                   |

## Testcontainers 구성

```kotlin
import io.bluetape4k.exposed.tests.Containers

// MariaDB 컨테이너
Containers.MariaDB

// MySQL 5.7 컨테이너
Containers.MySQL5

// MySQL 8.0 컨테이너
Containers.MySQL8

// PostgreSQL 컨테이너
Containers.Postgres
```

## 주요 기능 상세

| 파일                            | 설명                                                                                                                      |
|-------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `AbstractExposedTest.kt`      | 테스트 기본 클래스                                                                                                              |
| `TestDB.kt`                   | 지원 DB 정의 및 연결 정보                                                                                                        |
| `TestDBConfig.kt`             | 테스트 환경 설정 (useTestcontainers, useFastDB)                                                                                |
| `Containers.kt`               | Testcontainers 컨테이너 관리                                                                                                  |
| `WithDB.kt`                   | DB 연결 유틸                                                                                                                |
| `WithTables.kt`               | 테이블 생성/삭제 유틸                                                                                                            |
| `WithSchemas.kt`              | Schema 유틸                                                                                                               |
| `WithAutoCommit.kt`           | AutoCommit 모드 유틸                                                                                                        |
| `WithDBSuspending.kt`         | Coroutine DB 연결 유틸                                                                                                      |
| `WithTablesSuspending.kt`     | Coroutine 테이블 유틸                                                                                                        |
| `WithSchemasSuspending.kt`    | Coroutine Schema 유틸                                                                                                     |
| `WithAutoCommitSuspending.kt` | Coroutine AutoCommit 유틸                                                                                                 |
| `Assertions.kt`               | 테스트 어설션 유틸 (`assertTrue`, `assertFalse`, `assertEquals`, `assertNotEquals`, `assertFailAndRollback`, `expectException`) |
| `TestSupports.kt`             | 테스트 보조 유틸 (`inProperCase`, `currentDialectTest` 등)                                                                      |

## 테스트 실행 옵션

```bash
# H2만 실행
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-jdbc-tests:test

# 기본 활성 dialect: H2 + PostgreSQL + MySQL 8
./gradlew :bluetape4k-exposed-jdbc-tests:test

# CI 매트릭스처럼 H2 옆에 실제 DB 하나를 추가
EXPOSED_TEST_DB=POSTGRESQL ./gradlew :bluetape4k-exposed-jdbc-tests:test
EXPOSED_TEST_DB=MYSQL_V8 ./gradlew :bluetape4k-exposed-jdbc-tests:test
```

## 참고 사항

- 테스트와 함께 모듈별 `detekt`와 `checkKotlinAbi` 검사를 실행합니다.
- `TestDBConfig.useTestcontainers`가 `true`이면 Docker가 필요합니다.
- 로컬 PostgreSQL/MySQL/MariaDB 서버를 사용할 때는 `TestDBConfig.useTestcontainers = false`로 설정합니다.
- CI 매트릭스처럼 H2 옆에 실제 DB를 하나 더 붙일 때는 `EXPOSED_TEST_DB=POSTGRESQL` 또는 `EXPOSED_TEST_DB=MYSQL_V8`을 사용합니다.

## 호출자 소유 fixture 계약

애플리케이션 자체 DB 식별자와 연결 공급원을 재사용하려면 `jdbcTestDbFixture`로 `JdbcTestDbFixture<K>`를 만듭니다. 물리 테스트 DB마다 fixture를 한 번 만들고 테스트 스위트/JVM 동안 공유합니다. custom key를 전역 등록하지 않습니다. 생성 callback은 기존 caller-owned pool/연결 공급원을 사용하는 Exposed wrapper를 반환해야 하며, 호출마다 장기 pool을 새로 만들면 안 됩니다.

다음 예제는 애플리케이션의 `ApplicationDb`, `Orders`, 연결 공급원이 이미 준비되어 있다고 가정합니다.

```kotlin
val fixture = jdbcTestDbFixture(
    key = ApplicationDb.PRIMARY,
    createDatabase = { configure ->
        Database.connect(dataSource, databaseConfig = DatabaseConfig { configure() })
    },
)
withTables(fixture, Orders) { key ->
    // seed/FK/도메인 정리는 애플리케이션 wrapper에 둡니다.
}
```

- 기존 enum 함수·기본 인자·deprecated 호환 별칭을 유지합니다. DB/table/schema helper에 fixture overload를 제공합니다(JDBC suspend 변형 포함).
- 같은 fixture는 FIFO로 직렬화하고 다른 fixture는 독립 실행합니다. 활성 진입 토큰을 상속한 같은 fixture의 중첩 호출은 즉시 거부합니다. 종료된 진입은 후속 호출을 막지 않습니다.
- `database`는 초기화와 종료 hook 등록 전에는 null이며 성공 후 기본 wrapper를 노출합니다. 첫 `configure`도 별도 일시 wrapper를 만들고 트랜잭션 종료 후 provider 등록을 해제합니다. 일시 구성에서 기본 wrapper와 같은 인스턴스를 반환하면 거부합니다.
- 생성·종료 hook 등록 실패 뒤 초기화를 다시 시도할 수 있습니다. legacy `beforeConnection`은 실제 wrapper 생성마다 한 번 실행합니다. 외부 pool/container는 호출자 소유이며 provider가 닫지 않습니다.
- `currentJdbcTestDbFixture`는 트랜잭션 안에서 fixture를 식별하고 commit 후에도 유지합니다. legacy enum은 기존 `currentTestDB` 동작을 유지합니다. 본문은 `maxAttempts = 1`로 한 번 실행합니다.
- 취소는 그대로 전파하며 필수 cleanup만 보호합니다. 본문 실패가 우선이고 cleanup/recovery 실패는 발생 순서대로 suppressed에 남습니다.
- 전용 테스트 table/schema만 전달해야 합니다. 사전 drop과 cascade 정리가 수행됩니다. 부분 생성도 정리하되 `dropTables = false`는 종료 정리를 생략합니다. schema 미지원 dialect는 본문을 실행하지 않으며 DB 단절·의도적인 drop 실패에서는 잔여물이 생길 수 있습니다.
- provider 로그에는 custom key·URL·설정·callback 예외 메시지를 넣지 않습니다. driver/애플리케이션 로그는 호출자 정책입니다. coroutine debug stacktrace recovery가 예외를 복제할 수 있지만 helper는 본문 실패를 다른 실패로 교체하지 않습니다.
- 애플리케이션의 `bluetape4k-dependencies` BOM 아래 `testImplementation`으로 선언합니다. test runtime 지원과 application main runtime은 구분합니다. upstream이 로그만 남기는 Exposed 내부 cleanup 실패는 suppressed 보장 밖입니다(#817).

workshop/clinic 이전과 배포는 별도 작업이며 이 provider 변경만으로 #815 전체가 완료되지는 않습니다.
