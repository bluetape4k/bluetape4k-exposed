# #816 tenant별 Exposed JDBC resource registry 설계

## 상태와 승인 범위

- 상태: 설계 명세 관점 리뷰 완료, 사용자 검토 대기. 구현은 시작하지 않았다.
- 이슈: <https://github.com/bluetape4k/bluetape4k-exposed/issues/816>
- 기준 ref: `1b3ffc7f0f9be221402507d548a0854f5eb22adb`
- 브랜치: `feat/issue-816-tenant-jdbc-registry`
- PR 대상: `bluetape4k/bluetape4k-exposed`의 `develop`. 머지는 별도 승인이다.
- 이번 제공자 PR은 `exposed/tenant-jdbc` 모듈, 공개 registry API, 테스트, 모듈 문서와 publication/CI 연결을 담당한다.
- `exposed-workshop`의 두 Spring MVC 예제 이전은 별도 이슈
  [exposed-workshop#269](https://github.com/bluetape4k/exposed-workshop/issues/269)에서 수행한다.
  제공자 artifact가 배포되기 전에는 downstream catalog나 예제를 수정하지 않는다.

## 문제와 현재 근거

`exposed-workshop`의 다음 두 예제는 package만 다른 `TenantDatabaseRegistry`를 각각 유지한다.

- `10-multi-tenant/05-database-per-tenant-spring-web`
- `10-multi-tenant/06-spring-security-tenant-authorization-spring-web`

두 구현은 tenant별 `HikariDataSource`와 Exposed `Database`를 생성하고,
`databaseFor`/`dataSourceFor`로 조회하며, 초기화 실패와 종료 시 pool을 정리한다.
차이는 cleanup 실패를 모으는 세부 구현뿐이다. 이 중복은 workshop 전용 설정 binding이나
인증 정책이 아니라 Exposed JDBC resource의 조회와 수명 관리에서 발생한다.

이 저장소의 `ktor/tenant-jdbc`는 현재 request tenant를 caller가 제공한
`(TenantId) -> Database` resolver에 연결한다. 이 adapter는 registry를 만들거나 닫지 않으며,
Ktor와 `bluetape4k-tenant` 타입에 의존한다. Spring MVC consumer가 이 모듈을 재사용하면
불필요한 Ktor 의존성과 tenant 타입 결합이 생기므로 공용 registry의 위치로 적합하지 않다.

기준 ref의 `:bluetape4k-exposed-jdbc:test`를 H2로 실행한 결과는
`210 passing / 25 pending`, 실패 0이다. pending 항목은 기존 dialect 조건부 테스트이며
신규 registry의 통과 증거로 계산하지 않는다.

## 목표와 비목표

### 목표

- caller가 tenant별로 독립 생성한 resource라는 전제 아래, 안정적인 `equals`/`hashCode`를 가진
  non-null tenant key `K`에서 `DataSource`와 Exposed `Database` 쌍을
  immutable hash map의 평균 기대 O(1) exact match로 찾는다.
- 생성에 성공한 resource의 수명을 registry가 한 번만 종료한다.
- 부분 초기화와 정상 종료의 예외 우선순위, cleanup 순서, idempotent close를 공개 계약으로 고정한다.
- 완성된 registry는 불변이며 여러 스레드의 동시 조회에 안전하다.
- Spring, Ktor, HikariCP 없이 사용할 수 있는 opt-in artifact를 제공한다.

### 비목표

- tenant header parsing, 기본 tenant, 필수 tenant 집합, 인증·인가, request/coroutine context를 정의하지 않는다.
- `DataSource` 설정 binding, pool 선택, credential 해석, secret 저장, health check를 제공하지 않는다.
- Exposed의 `setupConnection`, custom `DatabaseConfig`, custom transaction manager 같은 고급 연결 구성을
  이번 기본 registry API에 노출하지 않는다. 실제 consumer 요구가 확인되면 별도 확장 API로 설계한다.
- 전역 registry, thread-local routing, `AbstractRoutingDataSource`, implicit `Database` 선택을 추가하지 않는다.
- 활성 요청을 추적하는 lease나 graceful-drain coordinator를 제공하지 않는다.
- R2DBC registry를 이번 이슈에 포함하지 않는다. 별도 사용 사례와 lifecycle 근거 없이 JDBC 계약을 복제하지 않는다.
- upstream Exposed transaction cleanup 실패를 보존한다고 주장하지 않는다. 그 범위는 #817에서 추적한다.

## 대안과 결정

### 대안 A: workshop의 두 구현만 공통 소스셋으로 이동

변경 범위는 작지만 다른 consumer가 재사용할 artifact가 없고 Hikari·Spring 설정 타입 결합이 남는다.
채택하지 않는다.

### 대안 B: caller-owned map을 조회만 하는 registry

API는 단순하지만 resource를 누가 닫는지 모호하다. 부분 초기화 cleanup과 double-close를
각 consumer가 다시 구현해야 하므로 현재 중복의 핵심을 제거하지 못한다. 채택하지 않는다.

### 대안 C: registry가 Hikari 설정을 받아 pool과 Database를 직접 생성

workshop 이전은 쉬워지지만 HikariCP를 runtime API로 고정하고 Spring 설정 정책이 provider로
이동한다. 다른 `DataSource` 구현이나 이미 구성된 pool을 사용할 수 없으므로 채택하지 않는다.

### 결정: caller가 DataSource 생성·종료 callback을 제공하고 registry가 resource 조립을 소유

새 module은 tenant key 목록, `dataSourceFactory`, `disposeDataSource`를 받는다. factory가 `DataSource`를
반환한 순간부터 registry가 이를 소유하고, 같은 DataSource로 Exposed `Database`를 직접 만든다. caller가
임의의 Database를 주입하거나 registry 밖에서 완성 resource를 만들 수 없다. caller는 같은 pool을 별도로 닫지 않는다.

이 방식은 pool·framework 의존성을 추가하지 않으면서도 부분 초기화와 종료 오류 계약을
한곳에서 구현한다. factory가 DataSource를 반환하기 전에 획득한 자원은 아직 registry가 알 수
없으므로 반환 전 실패 cleanup은 factory 책임이고, 반환 이후 Database 구성·등록 해제·pool 종료는 registry 책임이다.

## 모듈과 의존성 경계

- 새 디렉터리: `exposed/tenant-jdbc`
- Gradle module: `:bluetape4k-exposed-tenant-jdbc`
- published artifact: `io.github.bluetape4k.exposed:bluetape4k-exposed-tenant-jdbc`
- public package: `io.bluetape4k.exposed.tenant.jdbc`
- main API dependency: Exposed JDBC의 `Database`와 JDK `javax.sql.DataSource`
- 금지된 main runtime dependency: Spring, Ktor, HikariCP, Micrometer, Reactor

`settings.gradle.kts`의 `exposed/` auto-discovery로 module을 포함하되, artifact가 실제 BOM과
publication metadata에 들어가는지 별도 검증한다. CI의 경로 필터와 module shard는 자동 포함을
가정하지 않고 새 module의 compile/test/Kover가 실행되도록 명시적으로 연결한다.

구현 계획은 `.github/workflows/ci.yml`과 `.github/workflows/nightly-tests.yml`의 `jdbc` path filter에
`exposed/tenant-jdbc/**`를 추가하고, 두 workflow의 `test-jdbc-h2` job에서 새 module의 `test`와
`koverXmlReport`를 실행하도록 고정한다. 새 Kover XML이 실제로 존재하고 비어 있지 않은지 검사한 뒤에만
coverage artifact를 올리며, `coverage-report` 집계 입력에도 포함한다. PR exact head에서는 `build`,
`test-jdbc-h2`, publication metadata audit, production ABI module-count, non-empty Kover와 CG-14의 리뷰·thread
검증을 통과해야 한다. Nightly workflow 등록은 CI contract, YAML parse, actionlint로 검증하고 정기 실행에서
계속 관찰한다. 수동 Full Nightly는 tenant 전용 추가 backend 경로를 검증하지 않으므로 merge 필수 조건이
아니다. 사용자가 명시적으로 요청하거나 미검증 backend 위험이 새로 확인될 때만 별도 dispatch 게이트로
실행한다.

## 공개 API 초안

이 절의 이름과 JVM descriptor는 구현 계획 전에 ABI 기준으로 다시 확인한다. 설계 의도는 다음과 같다.

```kotlin
package io.bluetape4k.exposed.tenant.jdbc

import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

interface TenantJdbcResource {
    val dataSource: DataSource
    val database: Database
}

class TenantJdbcResourceRegistry<K : Any> private constructor() : AutoCloseable {
    val configuredTenants: Set<K>

    fun resourceFor(tenant: K): TenantJdbcResource
    fun databaseFor(tenant: K): Database
    fun dataSourceFor(tenant: K): DataSource

    override fun close()

    companion object {
        @JvmStatic
        fun <K : Any, D : DataSource> create(
            tenants: Iterable<K>,
            dataSourceFactory: (K) -> D,
            disposeDataSource: (K, D) -> Unit,
        ): TenantJdbcResourceRegistry<K>
    }
}

class UnknownTenantJdbcResourceException :
    NoSuchElementException("Unknown tenant JDBC resource.")
```

### API 세부 계약

- `K : Any`로 null tenant key를 컴파일 단계에서 제외한다.
- `K`의 `equals`와 `hashCode`는 registry 수명 동안 안정적이어야 한다. 등록 뒤 key의 동등성이나 hash 결과를
  바꾸는 mutable key는 지원하지 않으며, caller는 immutable value·enum·string처럼 안정적인 key를 제공한다.
- `create`는 입력을 먼저 materialize하고 중복 key를 검사한 뒤 resource 생성을 시작한다.
  JVM caller가 `tenants`에 null element를 넣으면 DataSource를 하나도 만들기 전에 고정 메시지
  `Tenant key must not be null.`의 `IllegalArgumentException`으로 실패한다. 중복이 있으면 DataSource를 하나도 만들지 않고 고정 메시지 `Duplicate tenant key.`의
  `IllegalArgumentException`으로 실패한다. provider가 만든 어떤 오류 메시지도 key의 `toString()`을 호출하거나 포함하지 않는다.
- `tenants`는 caller 설정에서 온 유한하고 bounded된 입력이어야 한다. registry는 자원 획득 전에 이를 전부
  materialize하지만 임의의 hard limit을 두지 않으며, 설정 규모와 메모리 상한은 caller가 관리한다.
- 빈 tenant 목록은 허용한다. 필수 tenant 집합과 최소 개수는 application 설정 정책이므로 caller가 검증한다.
- 입력 순서는 resource 생성과 종료 순서의 기준이 된다. `Set`처럼 순서가 불명확한 입력을 사용한 caller는
  자신의 순회 순서를 수용한다.
- `configuredTenants`는 registry 내부 map의 live view가 아닌 변경 불가능한 tenant key 사본이다.
  순회 순서는 materialize한 입력 순서를 보존한다. key 객체 자체는 deep copy하지 않으므로, caller가 등록 뒤
  key 상태를 변경하지 않는다는 계약은 유지된다.
- `resourceFor`는 provider 내부 구현체의 public read-only interface view를 반환한다. 생성자,
  `disposeDataSource` action과 registry 상태 변경 API는 노출하지 않는다.
- unknown tenant는 고정 메시지의 `UnknownTenantJdbcResourceException`으로 즉시 실패한다.
  메시지는 `Unknown tenant JDBC resource.`이며 tenant key의 `toString()`은 예외 메시지나 provider 로그에 넣지 않는다.
- Java에서 lookup 메서드에 null을 넘기면 Kotlin의 non-null parameter 방어가 map 조회 전에 실패한다.
  이 오류에도 tenant 값은 포함되지 않는다.
- `databaseFor`와 `dataSourceFor`는 `resourceFor`의 동일 entry를 반환하며 별도 cache나 global lookup을 만들지 않는다.
- `D : DataSource`로 factory와 disposer의 concrete type을 연결해 Hikari 같은 subtype consumer가 runtime cast 없이
  종료 callback을 작성할 수 있게 한다. 공개 resource view는 backend-neutral `DataSource`만 노출한다.
- `dataSourceFactory`가 non-null DataSource를 반환하는 순간 registry로 소유권이 이전된다. JVM caller가 null을
  반환하면 고정 메시지 `dataSourceFactory returned null.`로 실패한다. 반환 전 획득 자원은 factory가 정리한다.
- registry는 반환된 DataSource로 `Database.connect(dataSource)`를 직접 호출한다. caller가 다른 `Database`를
  주입하는 공개 경로는 두지 않는다. 이 구성이 실패하면 `disposeDataSource(tenant, dataSource)`를 실행하고
  이미 등록한 resource도 역순으로 정리한다. 원래 실패가 fatal이면 이를 primary로 유지하고, 그렇지 않은데
  cleanup에서 fatal이 발생하면 첫 fatal을 primary로 승격한다.
- `Database.connect(dataSource)`는 Exposed `Database`와 transaction manager를 등록하는 구성 단계이며,
  실제 DB 연결이나 readiness를 검증하지 않는다. startup health check는 caller가 별도로 실행한다.
- `dataSourceFactory`와 `disposeDataSource`는 호출한 thread에서 동기적으로 실행한다. provider는 callback을
  병렬화하거나 timeout·retry·dispatcher를 추가하지 않는다. callback 실행 시간과 blocking 정책은 caller가 소유한다.
- 이 artifact는 Kotlin-first API를 제공한다. `@JvmStatic` factory는 JVM 호출 지점을 안정화하지만 Java source용
  SAM overload나 builder는 이번 범위에 추가하지 않는다. 실제 Java consumer 요구가 확인되면 별도 API로 설계한다.
- provider는 callback이 던진 예외 메시지를 로그로 남기지 않지만 수정하거나 감싸서 redaction하지도 않는다.
  secret을 포함하지 않는 예외를 만들고 외부 응답·로그 경계에서 정제하는 책임은 caller에게 있다.
- 편의용 Hikari overload, Spring properties overload, string-to-tenant parser는 추가하지 않는다.

예상 consumer wiring은 다음 형태다.

```kotlin
val registry = TenantJdbcResourceRegistry.create(
    tenants = TenantId.entries,
    dataSourceFactory = ::createHikariDataSource,
    disposeDataSource = { _, dataSource -> dataSource.close() },
)
```

`createHikariDataSource` 자체가 pool을 만든 뒤 실패할 수 있다면 그 함수가 내부 획득 자원을 정리한다.
factory가 DataSource를 반환한 이후의 Database 구성 실패는 registry가 DataSource와 이미 등록한 resource를
정리한다. 실제 workshop adapter는 `TenantId`, Hikari 설정 검증과 필수 tenant 검사를 그대로 소유한다.

## 생성과 종료 상태 기계

### 초기화

1. tenant 입력을 순서가 고정된 key 목록으로 만든다.
2. 중복 key를 검사한다. 중복이면 factory를 호출하지 않는다.
3. 각 key에 대해 `dataSourceFactory`를 한 번 호출한다. 반환 직후 reference identity(`===`)를 검사한다.
   새 identity면 현재 DataSource를 provisional owned resource로 추적한다. 이미 등록된 것과 같으면 새 소유권이나
   Database를 만들지 않고 고정 메시지 `Tenant JDBC DataSource is reused.`의 `IllegalArgumentException`으로 실패한다.
4. 새 DataSource면 `Database.connect(dataSource)`로 Database를 구성하고 provisional pair를 내부
   insertion-ordered map에 넣는다.
5. 모든 생성이 성공한 뒤에만 불변 map과 `OPEN` 상태의 registry를 공개한다.
6. DataSource handoff 이후 Database 구성, resource wrapper/map 삽입, 불변 map 또는 registry 조립 중 어느 단계라도
   실패하면 현재 provisional resource와 이미 registry가 소유한 resource를 생성 역순으로 정리하고 registry는 공개하지 않는다.

동일 DataSource reference identity(`===`) 재사용은 두 번째 반환값을 별도로 정리하지 않고, 이미 등록한 resource를 생성 역순으로
한 번만 cleanup한다. 서로 다른 proxy/decorator 객체가 같은 underlying pool을 공유하는지는 판별하지 않는다.
감춰진 underlying resource 공유는 tenant isolation과 종료 횟수를 깨뜨릴 수 있는 금지된 caller 계약이며 public
KDoc와 consumer fixture에 이 한계를 명시한다. 따라서 registry는 감춰진 resource isolation이나 authorization
자체를 보장하지 않는다.

### 정상 종료

- registry는 외부 관찰 기준으로 `OPEN`과 `CLOSED` 두 상태만 가진다. 구현은 `OPEN`, `CLOSING`,
  `CLOSED_SUCCESS`, `CLOSED_FAILURE(Throwable)`, `CLOSED_FATAL`에 해당하는 내부 상태와 completion signal을 사용한다.
- 첫 `close()`가 `OPEN`에서 `CLOSING`으로 전환하는 원자적 선형화 지점을 선점한다. 이 시점부터
  lookup은 `Tenant JDBC resource registry is closed.` 고정 메시지의 `IllegalStateException`으로 실패하며,
  첫 caller는 resource를 생성 역순으로 정리한다.
- cleanup 결과를 final state에 기록한 뒤 completion signal을 공개한다. final state와 cleanup side effect는
  signal을 기다린 다른 thread에 happens-before로 보장되어야 한다.
- 첫 close와 겹친 다른 `close()` 호출은 첫 cleanup이 끝날 때까지 기다린다. 성공이면 no-op으로 반환하고,
  non-fatal 실패이면 저장된 동일 aggregate 예외를 다시 던진다. 이후 close도 같은 결과를 관찰하며 cleanup을 재시도하지 않는다.
- cleanup owner가 fatal 오류를 기록해도 resource 순회를 중단하지 않고 전체 best-effort cleanup과 final state
  공개를 마친 뒤 owner에게만 원래 fatal 오류를 다시 던진다. concurrent waiter와 이후
  `close()`는 raw fatal을 다른 thread에 반복 투척하지 않고
  `Tenant JDBC resource registry closed after a fatal cleanup failure.` 고정 메시지의 `IllegalStateException`으로
  실패한다. fatal은 process termination 전제이며 caller가 이를 잡아 정상 서비스를 계속하지 않고 watchdog
  또는 supervisor 재시작 절차로 전환한다.
- cleanup owner thread의 dispose callback이 같은 registry의 `close()`를 재진입 호출하면 대기하지 않고 no-op으로
  반환한다. 다른 thread의 reentrant/concurrent close만 completion signal을 기다리게 하여 self-deadlock을 막는다.
- waiter가 대기 중 interrupt되면 cleanup 완료까지 uninterruptible하게 기다리되, 반환하거나 저장된 실패를
  다시 던지기 전에 현재 thread의 interrupt flag를 복원한다. 별도의 timeout이나 background cleanup은 제공하지 않는다.
- cleanup 실패 종류와 관계없이 registry가 이미 소유한 나머지 resource 정리를 best-effort로 계속한다.
- registry가 직접 만든 background thread, shutdown hook, global singleton은 없다.

### 예외 우선순위

| 경로 | 호출자에게 전달할 예외 |
|---|---|
| DataSource/Database 생성 실패 뒤 cleanup 성공 | 원래 생성 실패 |
| 생성 실패와 cleanup 실패가 모두 non-fatal | 원래 생성 실패를 유지하고 cleanup 실패를 실제 발생 순서대로 `suppressed`에 추가 |
| 명시적 `close()`에서 non-fatal 실패가 하나 이상 발생 | 첫 cleanup 실패를 유지하고 이후 실패를 `suppressed`에 추가 |
| 같은 예외 인스턴스를 다시 추가하려는 경우 | self-suppression을 건너뛰고 원래 예외 유지 |
| 첫 cleanup 성공 뒤 두 번째 이후 `close()` | no-op |
| 첫 cleanup 실패 뒤 동시·후속 `close()` | 저장된 동일 aggregate 예외를 다시 던지고 cleanup은 재시도하지 않음 |
| ordinary primary 뒤 fatal cleanup 실패 | 첫 fatal 오류를 primary로 승격하고 기존 primary와 나머지 오류를 발생 순서대로 `suppressed`에 추가 |
| 생성/factory 자체가 fatal 실패 | 이미 registry가 소유한 resource를 정리한 뒤 원래 fatal을 primary로 다시 던짐 |
| fatal cleanup 뒤 동시·후속 `close()` | raw fatal을 재전파하지 않고 고정 메시지의 `IllegalStateException`을 던짐 |

`VirtualMachineError`, `ThreadDeath`, `LinkageError`를 fatal로 분류한다. fatal은 process termination을 전제로
하지만, registry가 이미 인수한 resource를 버리지 않도록 cleanup을 best-effort로 끝까지 시도한 뒤 다시 던진다.
callback은 caller가 제공한 신뢰 가능한 bounded cleanup 코드여야 하며, 이 정책은 임의의 hostile callback을
격리하거나 timeout하는 보안 경계가 아니다. 기존 primary가 fatal이면 이를 유지하고, ordinary primary 뒤 cleanup
fatal이 발생하면 첫 fatal을 primary로 승격한다. 명시적 close 경로에서는 raw fatal을 상태에 보관하지 않고
`CLOSED_FATAL` marker와 completion signal을 공개한 뒤 owner에게 다시 던져 concurrent waiter가 멈추거나 같은
fatal을 받지 않게 한다. fatal이 없으면 최초 실패를 primary로 유지한다. 선택된 primary를
제외한 오류는 실제 발생 순서대로 `suppressed`에 추가하고 self-suppression은 건너뛴다. `InterruptedException`이
기록되면 전체 cleanup을 마친 뒤 interrupt flag를 복원한다. 비동기·suspend API가 아니므로
`CancellationException`은 별도 cancellation 프로토콜로 해석하지 않고 ordinary failure로 집계한다.
예외 wrapper를 새로 만들지 않아 선택된 원래 타입과 stack trace를 보존한다.

`CLOSED_FAILURE`에 저장한 non-fatal raw exception graph는 registry가 회수될 때까지 메모리에 남을 수 있다.
이는 close 실패를 후속 caller에게 재노출하기 위한 의도된 진단 보존이며, application은 이를 장기 singleton으로
붙잡거나 외부 로그·HTTP 응답에 그대로 출력하지 않는다.

부분 초기화 cleanup도 같은 집계 규칙을 사용한다. factory가 DataSource를 반환하기 전 획득한 현재 자원만
caller 책임이고, 이전 iteration에서 registry가 이미 인수한 resource는 fatal 여부와 관계없이 정리한다.
registry가 공개되지 않으므로 재시도 API는 없지만 획득·cleanup 실패 전체는 호출자에게 전달되는 예외 graph에 남는다.

정상·부분 cleanup은 provider가 `TransactionManager.closeAndUnregister(database)`로 Exposed 등록을 먼저
해제한 뒤 caller의 `disposeDataSource`를 실행한다. unregister가 non-fatal 실패해도 DataSource 정리를 시도하고
두 실패를 위 집계 규칙으로 보존한다. caller는 구체적인 pool 종료만 정의하며 provider는 pool 타입을 추측하지 않는다.

## 조회와 동시성

- registry map은 생성 완료 후 변경되지 않는다. 여러 스레드의 `configuredTenants`,
  `resourceFor`, `databaseFor`, `dataSourceFor` 동시 호출은 lock 없이 안전해야 한다.
- `configuredTenants`는 close 이후에도 진단용 key 사본을 그대로 반환한다.
- lookup의 선형화 지점은 내부 상태를 읽는 시점이다. `OPEN`이 아니면 map을 조회하지 않고 고정 메시지의
  `IllegalStateException`으로 실패하므로, 닫힌 registry의 unknown tenant도 상태 예외가 우선한다.
- close와 경합한 조회가 `OPEN`을 먼저 관찰하면 성공한 lookup으로 선형화되어 resource를 반환할 수 있다.
  그 직후 close가 시작되거나, lookup이 실제 반환하기 전에 dispose가 끝날 수도 있다. 성공 반환은 resource의
  사용 유효 기간을 보장하는 lease가 아니므로 caller는 application shutdown에서 새 요청을 차단하고 활성 작업을
  끝낸 뒤 close한다.
- close 전에 얻어 둔 `TenantJdbcResource`, `Database`, `DataSource` reference도 close 이후 사용 가능성을
  보장하지 않는다. public resource view는 registry lifecycle을 우회하는 소유권이나 lease를 부여하지 않는다.
- registry는 transaction 경계나 `Database` 자체의 thread safety를 확장하지 않는다.
- 성능 목표는 안정적인 `equals`/`hashCode` key에서 immutable hash map exact lookup의 평균 기대 O(1) 경로다.
  악의적이거나 충돌이 집중된 hash 분포에는 O(1)을 보장하지 않는다. microbenchmark나 수치 SLA는 이번 이슈의
  완료 조건이 아니다.
- provider는 lookup이나 close를 자체 로그·metric으로 기록하지 않는다. tenant 식별자와 운영 telemetry는 caller가 소유한다.
- 이 registry는 authorization boundary가 아니다. registry를 가진 caller는 구성된 모든 tenant resource를 조회할 수
  있으므로, 인증된 tenant와 요청 권한을 검증한 뒤 lookup하는 책임은 application adapter에 있다.

## 검증 기준

| ID | 수용 기준 | 필요한 증거 |
|---|---|---|
| AC-01 | generic tenant key로 resource/Database/DataSource 조회 | string·enum key compile test와 동일 entry identity 검증 |
| AC-02 | caller가 독립 생성한 tenant A/B resource가 서로 다른 database와 데이터를 사용하고 동일 DataSource reference identity 재사용을 거부 | H2 named database 두 개의 table/data 분리, `equals`가 같지만 다른 instance 허용과 같은 instance 거부, 동일 DataSource cleanup 1회, 감춰진 underlying 공유·authorization 비보장 KDoc/consumer contract 검토 |
| AC-03 | unknown/null/duplicate/empty 입력 계약과 provider 메시지 redaction 준수 | 전용 예외, Kotlin nullability와 JVM null 방어, secret-bearing `toString()` 미호출, factory 미호출, 빈 key 목록 테스트 |
| AC-04 | DataSource handoff와 다중 resource 부분 초기화 실패를 명시된 경계 안에서 정리 | registry event recorder, 반환 전 factory cleanup 예시, Database 구성 실패, fatal에서도 이전 registry-owned resource cleanup, 생성 전/후 소유권 경계 검증 |
| AC-05 | 생성·cleanup 실패가 정해진 ordinary/fatal 우선순위와 suppressed 순서를 따른다 | ordinary/fatal `Error`, fatal 중에도 owned cleanup 시도, waiter의 fixed state error, `InterruptedException`, secret-bearing raw callback 오류의 provider 로그·metric·wrapper 부재와 caller 외부 노출 금지, 동일 예외 self-suppression exact identity/order 테스트 |
| AC-06 | close가 Exposed 등록 해제와 pool 종료를 순서대로 수행하고 결과 idempotent | `closeAndUnregister` fatal 뒤에도 같은 resource의 pool close와 나머지 resource cleanup, 반복 create/close 등록 해제, throw-once disposer, 다중 실패, owner-thread 재진입 no-op, concurrent close 대기·interrupt flag 복원, 성공 no-op·실패 재노출, resource별 dispose 1회 테스트 |
| AC-07 | 완성 registry의 동시 조회가 같은 immutable entry를 반환 | 안정 key와 hash 충돌 key의 exact-match 테스트, barrier 기반 다중 thread 테스트, mutable collection 미노출 검사 |
| AC-08 | close 경합의 상태 관찰 경계가 문서와 구현에 일치 | `OPEN` 관찰 뒤 pause한 lookup, close 선점·dispose 완료 뒤 lookup 반환을 강제하는 latch 테스트, `CLOSED + unknown` 상태 우선순위와 lookup 비-lease 계약 검증 |
| AC-09 | framework/pool dependency가 main runtime에 유입되지 않음 | generated POM·Gradle module metadata·dependency report 검사 |
| AC-10 | 새 artifact가 settings/BOM/publication/CI와 module inventory에 포함 | project listing, assemble/test, `test-jdbc-h2`·non-empty Kover, publication metadata audit, production ABI module-count, CI path, root README와 AGENTS layout 검사 |
| AC-11 | Ktor resolver 및 workshop 예상 wiring이 source-compatible | `registry::databaseFor` compile fixture와 Hikari test-scope consumer fixture |
| AC-12 | public API와 KDoc가 책임 경계를 설명 | binary API dump 또는 equivalent ABI diff, Dokka/문서 검토, 한국어 용어 audit |

H2는 빠른 isolation과 lifecycle 테스트에 사용한다. 실제 PostgreSQL 검증이 필요한 동작은
새 registry가 SQL dialect나 transaction을 제어하지 않으므로 기본 필수 matrix에 넣지 않는다.
다만 consumer fixture나 구현 과정에서 driver별 차이가 발견되면 PostgreSQL Testcontainers 테스트를
순차 실행하고 그 근거를 계획에 추가한다. 신규 계약 테스트의 skipped는 PASS로 계산하지 않는다.

## 문서와 downstream handoff

- 새 module README와 public KDoc는 resource 소유권, factory 반환 전/후 경계, shutdown 순서, lookup이
  authorization 검사를 대신하지 않는다는 점, raw callback 예외를 로그·HTTP 응답에 직접 노출하지 않는다는 점을 설명한다.
- 운영 예제는 새 요청 차단, 활성 transaction drain, registry close 순서를 따르며 startup/shutdown timeout,
  callback 로그·metric·watchdog, 실제 DB readiness/health check는 caller가 구성한다고 명시한다. fatal 오류는
  정상 운영 계속이 아니라 process termination과 supervisor 재시작 대상으로 다룬다. dispose callback에서 다른
  registry를 동기 close하는 순환 의존은 금지한다.
- root README의 module 표, repository AGENTS layout, release-facing CHANGELOG에 새 artifact를 반영한다.
- central manual은 `bluetape4k.github.io`가 소유하므로 이 저장소에 별도 `docs/manual/` 트리를 만들지 않는다.
- 제공자 PR 본문은 `Closes #816`을 사용한다. 제공자 artifact와 #816의 계약이 완료되면 이슈를 닫되,
  workshop migration 완료로 오인하지 않도록 PR 생성 전 #816에 범위 분리와 #269 handoff를 기록한다.
- provider artifact가 Maven Central 또는 승인된 개발용 저장소에 게시된 뒤 #269에서 workshop catalog alias와
  두 consumer를 이전한다. downstream PR은 본문에 `Closes #269`를 사용한다.
- #269 시작 전 release checklist에 provider version/ref, Maven Central 또는 승인된 개발용 저장소 좌표의
  isolated consumer resolution 결과, downstream catalog 변경 승인 범위를 기록한다.
- 제공자 PR과 downstream PR을 한 branch나 한 merge 승인으로 묶지 않는다.

## 실패 모드와 복구

1. factory 반환 전 resource 누수: 아직 registry에 반환되지 않은 DataSource는 factory가 정리한다. provider가 획득하지 않은 자원을 닫았다고 주장하지 않는다.
2. 동일 자원을 여러 tenant가 공유: 같은 DataSource reference identity(`===`) 재사용은 거부한다. 서로 다른 proxy/decorator가 공유한 underlying resource를 판별한다고 가정하지 않으며 caller가 tenant별 독립 resource를 제공한다.
3. close 도중 일부 실패: cleanup은 fatal을 포함해 owned resource 전체에 best-effort로 시도한다. non-fatal final failure는 동시·후속 close에 다시 노출하고 fatal 원형은 owner에게만 전달한다. 자동 재시도는 하지 않는다.
4. 활성 transaction 중 close: registry가 drain을 제공하지 않으므로 application shutdown 순서를 고친다. lookup lease abstraction을 이 이슈에 추가하지 않는다.
5. framework dependency leakage: main dependency에서 제거하고 test fixture에만 둔다. POM과 module metadata 둘 다 다시 생성해 확인한다.
6. 새 module이 CI에서 누락: settings auto-discovery만 믿지 않고 workflow path와 module command를 명시한다.
7. downstream이 배포 전 artifact를 참조: #269를 대기 상태로 유지하고 확인된 published coordinate 이후 별도 작업한다.
8. 등록 뒤 tenant key의 hash가 변경됨: mutable key를 지원하지 않는 계약으로 두고 immutable key 사용을 KDoc와 테스트 fixture에서 강조한다.
9. close waiter가 interrupt됨: 첫 cleanup은 계속 진행하고 waiter는 완료를 확인한 뒤 interrupt flag를 복원한다. provider timeout이 필요하면 별도 API 근거를 먼저 마련한다.
10. dispose가 장시간 block되거나 같은 registry를 다시 닫음: callback timeout은 caller가 관리하고 cleanup owner의 재진입 close는 no-op으로 처리해 self-deadlock을 막는다.
11. callback 예외에 secret이 포함됨: provider는 예외를 로그로 복제하지 않고 원형을 보존한다. 안전한 예외 생성과 외부 경계 redaction은 caller가 담당한다.
12. 두 registry의 dispose가 서로를 동기 close함: completion wait cycle을 탐지하지 않으므로 caller가 registry 간 종료 순서를 정하고 dispose callback의 교차 close를 금지한다.

## 단계별 DoD

- [x] #816/#269 live 범위와 현재 중복 구현 확인
- [x] 격리 worktree와 H2 기준 테스트 확인
- [x] 제공자와 downstream 작업 경계 확정
- [x] 공개 API, lifecycle, 예외, 동시성, 의존성 설계 초안 작성
- [x] 여섯 관점 리뷰(독립 실행 실패 시 명시적 inline fallback)와 P0/P1 해소
- [ ] 작성 명세의 사용자 검토
- [ ] AC-01부터 AC-12까지 연결한 구현 계획 작성·승인
- [ ] RED/GREEN 구현과 module/ABI/POM/CI 검증
- [ ] 독립 코드 리뷰와 문서 자연스러움 검증
- [ ] 승인된 PR 생성과 exact-head CI
- [ ] 별도 머지 승인·머지·로컬 동기화·정리

현재 stop condition은 독립 관점 리뷰를 반영한 명세 커밋과 사용자 검토 요청이다.
사용자가 명세를 승인하기 전에는 구현 계획이나 production code를 작성하지 않는다.
