# bluetape4k-exposed-tenant-jdbc

[English](./README.md) | 한국어

JetBrains Exposed JDBC를 위한 framework-neutral tenant resource lifecycle
모듈입니다. `TenantJdbcResourceRegistry<K>`는 설정된 각 tenant에 대해
`DataSource`/Exposed `Database` 쌍을 만들고 exact-match 조회를 제공하며,
factory가 `DataSource`를 registry에 넘긴 뒤부터 종료를 소유합니다. 이
registry는 opt-in resource owner이며 인증·인가, readiness, 요청 drain을
담당하지 않습니다.

## Purpose

Spring, Ktor, HikariCP, Micrometer, Reactor에 registry를 결합하지 않고
고정된 tenant별 JDBC resource 집합이 필요한 애플리케이션에서 사용합니다.
registry는 materialize한 tenant 순서를 생성 순서와 역순 cleanup에 유지하며,
`resourceFor`, `databaseFor`, `dataSourceFor`에서 같은 불변 resource entry를
반환합니다.

`Database.connect(dataSource)`는 Exposed `Database`를 구성하고 등록할 뿐,
database에 실제로 연결되거나 readiness 상태임을 증명하지 않습니다.
애플리케이션 소유 health check를 별도로 실행하세요.

## Dependency

중앙 BOM을 한 번 선언합니다. registry artifact는 호환되는 Exposed 버전을
BOM이 관리하도록 개별 버전을 쓰지 않습니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-tenant-jdbc")
}
```

이 artifact는 `2.1.0-SNAPSHOT` 개발선에서 추가됩니다. 해당 개발선이
배포되기 전 안정판 `2.0.0` BOM에는 포함되지 않습니다.

## Create and lookup

유한하고 bounded한 tenant 집합과 `DataSource` factory/disposer는
애플리케이션이 제공합니다. 아래 `createHikariDataSource`는 애플리케이션이
소유한 함수 예시이며 HikariCP와 설정은 이 모듈의 책임이 아닙니다.

```kotlin
import io.github.bluetape4k.exposed.tenant.jdbc.TenantJdbcResourceRegistry
import org.jetbrains.exposed.v1.jdbc.Database

enum class TenantId { ACME, GLOBEX }

val registry = TenantJdbcResourceRegistry.create(
    tenants = TenantId.entries,
    dataSourceFactory = ::createHikariDataSource,
    disposeDataSource = { _, dataSource -> dataSource.close() },
)

fun databaseFor(
    requestedTenantId: TenantId,
    allowedTenantIds: Set<TenantId>,
): Database {
    check(requestedTenantId in allowedTenantIds) {
        "Forbidden tenant access."
    }
    return registry.databaseFor(requestedTenantId)
}
```

`resourceFor`, `databaseFor`, `dataSourceFor`를 호출하기 전에 요청 tenant의
인가를 완료하세요. 인증된 identity가 registry의 모든 tenant를 사용할 수
있다는 뜻은 아닙니다. 알 수 없는 tenant도 기본 resource로 fallback하지
않습니다. lookup은 exact map 조회이지 lease가 아니므로, 종료가 시작된 뒤
반환된 resource를 계속 사용할 수 있다는 보장은 없습니다.

알 수 없는 tenant는 고정 메시지 `Unknown tenant JDBC resource.`의
`UnknownTenantJdbcResourceException`으로 실패합니다. 종료 후 lookup은 tenant가
등록되었는지 확인하기 전에 고정 상태 `IllegalStateException`으로 실패합니다.

`K`의 `equals`/`hashCode`는 registry 수명 동안 안정적이어야 합니다.
불변 value type, enum, string을 권장합니다. registry는 resource를 하나라도
얻기 전에 iterable을 materialize하므로 null key와 중복 key는 factory 호출
전에 실패합니다.

## Shutdown order

종료 경계는 애플리케이션이 소유하며 다음 순서를 지켜야 합니다.

1. 새 요청을 차단하고 새로운 tenant 작업이 시작되지 않게 합니다.
2. 이미 registry resource를 얻은 활성 transaction과 작업을 drain합니다.
3. 애플리케이션 shutdown 경로에서 `registry.close()`를 정확히 한 번
   호출합니다.

첫 `close()`는 cleanup 전에 registry를 closed 상태로 바꾸므로 이후 lookup은
즉시 실패합니다. 각 resource에 대해 registry는 먼저 Exposed의
`TransactionManager.closeAndUnregister(database)`를 호출한 뒤 호출자가
제공한 `disposeDataSource` callback을 실행합니다. resource는 생성 역순으로
정리합니다. 동시에 실행된 close 호출은 owner의 완료를 기다린 뒤 같은 결과를
관찰하며 cleanup을 반복하지 않습니다. disposer가 다른 registry를 동기적으로
순환 close하지 않도록 구성하세요.

## Failure semantics

- `tenants`는 애플리케이션 설정에서 유한하고 bounded해야 합니다. registry는
  iterable을 materialize하지만 임의의 hard limit을 두지 않으므로 설정 규모와
  메모리 상한은 호출자가 관리합니다.
- null key는
  `IllegalArgumentException("Tenant key must not be null.")`로 실패합니다.
  중복 key는 `IllegalArgumentException("Duplicate tenant key.")`로 실패합니다.
  두 검사는 factory 호출 전에 수행하며 key의 `toString()`을 호출하지 않습니다.
- factory가 null을 반환하면
  `IllegalArgumentException("dataSourceFactory returned null.")`로 실패합니다.
- 두 tenant에 같은 `DataSource` 객체를 반환하면
  `IllegalArgumentException("Tenant JDBC DataSource is reused.")`로 실패합니다.
  두 번째 reference는 dispose하지 않으며 이미 소유한 resource만 역순으로 한
  번 정리합니다. 서로 다른 proxy 객체가 같은 underlying pool을 공유하는지는
  감지하지 못하므로 호출자 계약 위반입니다.
- handoff 뒤 `Database.connect` 또는 resource 조립 단계가 실패하면 현재와
  이전에 소유한 resource를 역순으로 정리합니다. fatal cleanup failure를
  승격해야 하는 경우를 제외하면 원래 실패를 primary로 유지하고, 나머지
  non-fatal cleanup 실패는 `suppressed` 예외로 보존합니다.
- factory 또는 조립 단계의 fatal failure는 registry가 이미 소유한 모든
  resource의 best-effort cleanup을 시도한 뒤 다시 던집니다.
- `close()`는 non-fatal disposer/unregister 실패 뒤에도 best-effort cleanup을
  계속합니다. 첫 cleanup 실패를 보존하고 이후 `close()`는 같은 aggregate를
  다시 보고하며 cleanup을 재시도하지 않습니다. `VirtualMachineError`,
  `ThreadDeath`, `LinkageError` 같은 fatal failure도 소유 resource 정리를
  시도한 뒤 cleanup owner에 다시 던집니다. 동시·후속 caller는 raw fatal 대신
  고정 fatal-state `IllegalStateException`을 관찰합니다.
- 실패한 registry가 계속 참조되는 동안 callback 예외 graph의 message와
  stack trace가 메모리에 남을 수 있습니다. provider는 이를 log로 남기거나
  redaction하지 않습니다. 애플리케이션 경계에서 정제한 뒤 log, metric,
  HTTP response에 사용하세요.

## Caller responsibilities

애플리케이션 또는 adapter가 다음을 소유합니다.

- 인증과 인가: lookup 전에 요청 tenant가 인증된 principal의 허용 목록에
  있는지 검사합니다.
- 불변이고 stable한 key를 사용하는 유한·bounded tenant 설정;
- pool 선택, credential, `DataSource` 생성, factory 반환 전 획득한 resource의
  cleanup;
- registry가 소유권을 인수한 뒤 실행할 동기 disposer callback;
- database readiness/health check, callback timeout·retry 정책, shutdown watchdog;
- transaction admission, 요청 drain, dispatcher/thread lifecycle;
- tenant 식별자, credential, raw callback failure를 노출하지 않는 redacted
  log와 metric; 그리고
- fatal cleanup failure 뒤 supervisor 또는 process 재시작 처리.

factory가 `DataSource`를 반환한 뒤에는 registry가 cleanup을 소유합니다.
호출자가 같은 `DataSource`를 별도로 닫아서는 안 됩니다.

## Unsupported behavior

이 artifact는 다음을 제공하지 않습니다.

- tenant header parsing, string-to-tenant 변환, 인증, 인가;
- 기본 tenant, fallback database, global registry, implicit database 선택;
- Spring Boot, Ktor, HikariCP, Micrometer, Reactor, R2DBC 통합;
- readiness/health probe, retry, timeout, metric, logging, credential, graceful
  drain/lease coordinator;
- caller가 주입하는 `Database`, `setupConnection`, custom `DatabaseConfig`,
  custom transaction-manager wiring;
- Java SAM overload 또는 builder API; 그리고
- 서로 다른 proxy 객체 뒤에 숨은 shared underlying pool 감지.

이 책임들은 애플리케이션 또는 framework adapter 경계에 남겨 둡니다.
