# Module exposed-r2dbc-tests

[English](./README.md) | 한국어

## 개요

[Exposed R2DBC](https://github.com/JetBrains/Exposed) 기반 코드를 테스트하기 위한 공통 테스트 인프라 모듈입니다. 코루틴 친화적인 데이터베이스 fixture, dialect 선택, Testcontainers 부트스트랩, schema/table 정리 helper를 제공해 모듈 테스트가 연결 설정보다 동작 검증에 집중할 수 있게 합니다.

## 테스트 인프라

아키텍처 다이어그램은 테스트 작성자가 직접 만나는 표면부터 보여줍니다. 테스트 클래스는 `AbstractExposedR2dbcTest`를 상속하고, `enabledDialects()`가 제공하는 `TestDB` 값으로 실행되며, `withDb`, `withTables`, `withSchemas` 같은 suspend helper 안에서 검증 로직을 수행합니다.

![R2DBC test support architecture diagram](../../docs/images/readme-diagrams/exposed-r2dbc-tests-diagram-01.png)

### `withTables` 테스트 생명주기

생명주기 다이어그램은 가장 자주 쓰는 helper 흐름을 따라갑니다. `withTables`는 `withDb`에 위임하고, 같은 `TestDB` 작업을 직렬화하며, 요청한 테이블을 만들고, cleanup 전에 commit한 뒤, 일반 table drop이 실패하면 top-level suspend transaction에서 한 번 더 정리합니다.

![withTables R2DBC test lifecycle diagram](../../docs/images/readme-diagrams/exposed-r2dbc-tests-diagram-02.png)

## 의존성 추가

```kotlin
dependencies {
    testImplementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc-tests")
}
```

## 주요 기능

- **공통 테스트 베이스**: `AbstractExposedR2dbcTest`로 R2DBC 테스트 기본 구조 제공
- **다중 DB 지원**: H2, MySQL, MariaDB, PostgreSQL R2DBC 테스트 지원
- **Testcontainers 통합**: Docker 기반 실제 DB 테스트 지원
- **Coroutine 네이티브 helper**: 데이터베이스 helper는 `suspend` 함수이며 `runSuspendIO` 안에서 자연스럽게 사용할 수 있음
- **테이블/스키마 유틸**: 테스트용 엔티티/테이블 재사용

## 지원 데이터베이스

| 데이터베이스           | TestDB       | R2DBC Driver       |
|------------------|--------------|--------------------|
| H2               | `H2`         | `r2dbc-h2`         |
| H2 MySQL 모드      | `H2_MYSQL`   | `r2dbc-h2`         |
| H2 MariaDB 모드    | `H2_MARIADB` | `r2dbc-h2`         |
| H2 PostgreSQL 모드 | `H2_PSQL`    | `r2dbc-h2`         |
| MariaDB          | `MARIADB`    | `r2dbc-mariadb`    |
| MySQL 8.0        | `MYSQL_V8`   | `r2dbc-mysql`      |
| PostgreSQL       | `POSTGRESQL` | `r2dbc-postgresql` |

## 사용 예시

### 기본 테스트 작성

```kotlin
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals

object Users: LongIdTable("users") {
    val name = varchar("name", 50)
    val email = varchar("email", 100)
}

class UserRepositoryTest: AbstractExposedR2dbcTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `should insert and find user`(testDB: TestDB) = runSuspendIO {
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
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withDb
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlin.test.assertTrue

@ParameterizedTest
@MethodSource(ENABLE_DIALECTS_METHOD)
fun `should connect to database`(testDB: TestDB) = runSuspendIO {
    withDb(testDB) {
        // suspend 트랜잭션 내에서 실행
        val isConnected = true // 연결 확인 로직
        assertTrue(isConnected)
    }
}
```

### withTables - 테이블 자동 생성/삭제

```kotlin
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO

@ParameterizedTest
@MethodSource(ENABLE_DIALECTS_METHOD)
fun `should create and drop tables`(testDB: TestDB) = runSuspendIO {
    withTables(testDB, Users, Orders) {
        // 테스트 시작 전 테이블 자동 생성
        // 테스트 종료 후 테이블 자동 삭제

        Users.insert { /* ... */ }
        Orders.insert { /* ... */ }

        // 테스트 로직
    }
}
```

### 특정 DB만 테스트

```kotlin
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.junit5.coroutines.runSuspendIO

class PostgresOnlyTest: AbstractExposedR2dbcTest() {

    // PostgreSQL만 테스트
    companion object {
        @JvmStatic
        fun databases() = TestDB.ALL_POSTGRES
    }

    @ParameterizedTest
    @MethodSource("databases")
    fun `postgres specific test`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Users) {
            // PostgreSQL 전용 테스트
        }
    }
}
```

### DB 그룹별 테스트

```kotlin
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.junit5.coroutines.runSuspendIO

class MySQLLikeTest: AbstractExposedR2dbcTest() {

    companion object {
        // MySQL 계열 + H2 MySQL 모드
        @JvmStatic
        fun databases() = TestDB.ALL_MYSQL_LIKE

        // PostgreSQL + H2 PostgreSQL 모드
        @JvmStatic
        fun postgresDatabases() = TestDB.ALL_POSTGRES_LIKE
    }

    @ParameterizedTest
    @MethodSource("databases")
    fun `mysql compatible test`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Users) {
            // MySQL 호환 DB 테스트
        }
    }
}
```

### Flow 기반 스트리밍 쿼리

```kotlin
import kotlinx.coroutines.flow.toList
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlin.test.assertEquals

@ParameterizedTest
@MethodSource(ENABLE_DIALECTS_METHOD)
fun `should stream query results`(testDB: TestDB) = runSuspendIO {
    withTables(testDB, Users) {
        // 여러 레코드 삽입
        repeat(100) { i ->
            Users.insert {
                it[name] = "User$i"
                it[email] = "user$i@example.com"
            }
        }

        // Flow로 스트리밍 조회
        val users = Users.selectAll().toList()
        assertEquals(100, users.size)
    }
}
```

## TestDB 설정

```kotlin
object TestDBConfig {
    // true: Testcontainers 사용 (기본값)
    // false: 로컬에 DB 서버를 직접 설치한 경우
    var useTestcontainers = true

    // true: H2 메모리 DB만 사용 — 빠른 로컬 테스트
    // false: H2 + PostgreSQL + MySQL V8 사용 (Testcontainers 필요)
    var useFastDB = false
}
```

모듈 기본값은 `useFastDB = false`입니다. 따라서 별도 설정이 없으면
`enabledDialects()`는 H2, PostgreSQL, MySQL 8.0을 반환합니다. 빠른 H2 전용
경로가 필요할 때 `useFastDB = true`로 좁히세요. CI에서는
`EXPOSED_TEST_DB=POSTGRESQL` 또는 `EXPOSED_TEST_DB=MYSQL_V8`로 H2와 특정 실제
드라이버 하나만 실행하도록 제한할 수 있습니다. Testcontainers 기반
데이터베이스에는 Docker가 필요합니다.

## 테스트용 스키마/데이터

### 공유 테이블 스키마

| 파일                               | 설명               |
|----------------------------------|------------------|
| `shared/entities/BoardSchema.kt` | Board 테이블        |
| `shared/mapping/PersonSchema.kt` | Person 매핑 테이블    |
| `shared/mapping/OrderSchema.kt`  | Order 매핑 테이블     |
| `shared/samples/BankSchema.kt`   | Bank 계좌 테이블      |
| `shared/samples/UserCities.kt`   | User-City 관계 테이블 |
| `shared/dml/DMLTestData.kt`      | DML 테스트 데이터      |

## Testcontainers 구성

```kotlin
import io.bluetape4k.exposed.r2dbc.tests.Containers

// MariaDB 컨테이너
Containers.MariaDB

// MySQL 8.0 컨테이너
Containers.MySQL8

// PostgreSQL 컨테이너
Containers.Postgres
```

## JDBC vs R2DBC 테스트 비교

| 특징         | exposed-tests     | exposed-r2dbc-tests      |
|------------|-------------------|--------------------------|
| API        | JDBC              | R2DBC                    |
| 실행 모델      | 동기/비동기            | Coroutine 네이티브           |
| withDb     | `withDb`          | `suspend fun withDb`     |
| withTables | `withTables`      | `suspend fun withTables` |
| 트랜잭션       | `JdbcTransaction` | `R2dbcTransaction`       |

## 주요 기능 상세

| 파일                            | 설명                                                                                                                                                   |
|-------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| `AbstractExposedR2dbcTest.kt` | R2DBC 테스트 기본 클래스                                                                                                                                     |
| `TestDB.kt`                   | R2DBC 지원 DB 정의                                                                                                                                       |
| `TestDBConfig.kt`             | 테스트 환경 설정 (useTestcontainers, useFastDB)                                                                                                             |
| `Containers.kt`               | Testcontainers 컨테이너 관리                                                                                                                               |
| `withDb.kt`                   | R2DBC DB 연결 유틸                                                                                                                                       |
| `withTables.kt`               | R2DBC 테이블 유틸                                                                                                                                         |
| `withAutoCommit.kt`           | AutoCommit 모드 유틸                                                                                                                                     |
| `withSchemas.kt`              | Schema 유틸                                                                                                                                            |
| `Assertions.kt`               | 테스트 어설션 유틸 (`assertTrue`, `assertFalse`, `assertEquals`, `assertNotEquals`, `assertFailAndRollback`, `expectException`, `expectExceptionSuspending`) |
| `TestSupports.kt`             | 테스트 보조 유틸 (`inProperCase`, `currentDialectTest`, `insertAndSuspending` 등)                                                                            |

## R2DBC 연결 문자열 예시

```kotlin
// H2
"r2dbc:h2:mem:///regular;DB_CLOSE_DELAY=-1;"

// H2 MySQL 모드
"r2dbc:h2:mem:///mysql;DB_CLOSE_DELAY=-1;MODE=MySQL;"

// MariaDB
"r2dbc:mariadb://user:pass@host:3306/database"

// MySQL
"r2dbc:mysql://user:pass@host:3306/database"

// PostgreSQL
"r2dbc:postgresql://user:pass@host:5432/database"
```

## 참고 사항

- R2DBC helper는 `suspend` 함수이며 보통 `runSuspendIO` 안에서 호출합니다
- MySQL 5.7은 R2DBC 드라이버 호환성 문제로 제외됩니다
- Testcontainers 사용 시 Docker가 필요합니다
- Flow 기반 스트리밍 쿼리가 가능합니다

## 호출자 소유 fixture 계약

애플리케이션 자체 DB 식별자와 연결 공급원을 재사용하려면 `r2dbcTestDbFixture`로 `R2dbcTestDbFixture<K>`를 만듭니다. 물리 테스트 DB마다 fixture를 한 번 만들고 테스트 스위트/JVM 동안 공유합니다. custom key를 전역 등록하지 않습니다. 생성 callback은 기존 caller-owned pool/연결 공급원을 사용하는 Exposed wrapper를 반환해야 하며, 호출마다 장기 pool을 새로 만들면 안 됩니다.

다음 예제는 애플리케이션의 `ApplicationDb`, `Orders`, 연결 공급원이 이미 준비되어 있다고 가정합니다.

```kotlin
val fixture = r2dbcTestDbFixture(
    key = ApplicationDb.PRIMARY,
    createDatabase = { configure ->
        R2dbcDatabase.connect(
            connectionPool,
            R2dbcDatabaseConfig.Builder().apply {
                setUrl(connectionUrl)
                configure()
            },
        )
    },
)
withTables(fixture, Orders) { key ->
    // seed/FK/도메인 정리는 애플리케이션 wrapper에 둡니다.
}
```

- 기존 enum 함수·기본 인자·deprecated 호환 별칭을 유지합니다. DB/table/schema helper에 fixture overload를 제공합니다.
- 같은 fixture는 FIFO로 직렬화하고 다른 fixture는 독립 실행합니다. 활성 진입 토큰을 상속한 같은 fixture의 중첩 호출은 즉시 거부합니다. 종료된 진입은 후속 호출을 막지 않습니다.
- `database`는 초기화와 종료 hook 등록 전에는 null이며 성공 후 기본 wrapper를 노출합니다. 첫 `configure`도 별도 일시 wrapper를 만들고 트랜잭션 종료 후 provider 등록을 해제합니다. 일시 구성에서 기본 wrapper와 같은 인스턴스를 반환하면 거부합니다.
- 생성·종료 hook 등록 실패 뒤 초기화를 다시 시도할 수 있습니다. legacy `beforeConnection`은 실제 wrapper 생성마다 한 번 실행합니다. 외부 pool/container는 호출자 소유이며 provider가 닫지 않습니다.
- `currentR2dbcTestDbFixture`는 트랜잭션 안에서 fixture를 식별하고 commit 후에도 유지합니다. legacy enum은 기존 `currentTestDB` 동작을 유지합니다. 본문은 `maxAttempts = 1`로 한 번 실행합니다.
- 취소는 그대로 전파하며 필수 cleanup만 보호합니다. 본문 실패가 우선이고 cleanup/recovery 실패는 발생 순서대로 suppressed에 남습니다 단, 기존 R2DBC `withTables` 계약대로 본문 취소에는 cleanup/recovery suppressed를 추가하지 않습니다.
- 전용 테스트 table/schema만 전달해야 합니다. 사전 drop과 cascade 정리가 수행됩니다. 부분 생성도 정리하되 `dropTables = false`는 종료 정리를 생략합니다. schema 미지원 dialect는 본문을 실행하지 않으며 DB 단절·의도적인 drop 실패에서는 잔여물이 생길 수 있습니다.
- provider 로그에는 custom key·URL·설정·callback 예외 메시지를 넣지 않습니다. driver/애플리케이션 로그는 호출자 정책입니다. coroutine debug stacktrace recovery가 예외를 복제할 수 있지만 helper는 본문 실패를 다른 실패로 교체하지 않습니다.
- 애플리케이션의 `bluetape4k-dependencies` BOM 아래 `testImplementation`으로 선언합니다. test runtime 지원과 application main runtime은 구분합니다. upstream이 로그만 남기는 Exposed 내부 cleanup 실패는 suppressed 보장 밖입니다(#817).

workshop/clinic 이전과 배포는 별도 작업이며 이 provider 변경만으로 #815 전체가 완료되지는 않습니다.
