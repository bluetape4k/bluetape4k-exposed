# Tenant JDBC Resource Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** tenant key별 `DataSource`와 Exposed `Database`의 생성·조회·종료를 framework 중립적인 단일 registry가 안전하게 소유하도록 `bluetape4k-exposed-tenant-jdbc` artifact를 추가한다.

**Architecture:** caller가 tenant 목록과 `DataSource` factory/disposer를 제공하면 registry가 `Database.connect(dataSource)`로 immutable resource map을 조립한다. 조회는 stable key의 hash map exact match를 사용하고, 종료는 atomic state와 completion signal로 한 caller만 역순 cleanup을 수행하며 모든 동시 caller가 같은 최종 결과를 관찰하게 한다. Spring, Ktor, HikariCP, health/readiness, authorization, active-request drain은 caller 경계에 남긴다.

**Tech Stack:** Kotlin/JVM, JetBrains Exposed 1.5.0 JDBC, Gradle Kotlin DSL, JUnit 5, bluetape4k assertions, H2, HikariCP(test scope), AtomicReference, CountDownLatch, Kover, Kotlin binary compatibility validator

---

## 기준과 stop condition

- 승인된 설계: `docs/superpowers/specs/2026-09-07-issue-816-tenant-jdbc-registry-design.md`
- GitHub issue: <https://github.com/bluetape4k/bluetape4k-exposed/issues/816>
- provider branch/base: `feat/issue-816-tenant-jdbc-registry` → `develop`
- downstream migration: <https://github.com/bluetape4k/exposed-workshop/issues/269>
- #817 upstream 제보는 사용자 결정에 따라 이 계획의 범위에서 제외한다. #817의 GitHub 상태는 별도 명시적 지시 없이 바꾸지 않는다.
- 구현 stop condition은 production code, 테스트, ABI, publication metadata, CI/Nightly wiring, README/KDoc, CHANGELOG, 독립 코드 리뷰가 모두 완료되고 P0/P1이 0인 커밋이다. PR 생성, merge, release, downstream migration은 각각 별도 권한 게이트다. 수동 Full Nightly는 tenant 전용 미검증 경로가 확인되거나 사용자가 명시적으로 요청할 때만 별도 dispatch한다.

## 파일 구조와 책임

| 경로 | 책임 |
|---|---|
| `exposed/tenant-jdbc/build.gradle.kts` | framework/pool 비의존 production classpath와 H2/Hikari test classpath |
| `exposed/tenant-jdbc/src/main/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResource.kt` | caller가 읽기만 하는 resource pair 공개 view |
| `exposed/tenant-jdbc/src/main/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResourceExceptions.kt` | unknown tenant 고정 메시지 예외 |
| `exposed/tenant-jdbc/src/main/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResourceRegistry.kt` | 입력 검증, resource 조립, exact lookup, close state machine |
| `exposed/tenant-jdbc/src/test/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcTestFixtures.kt` | 추적용 DataSource, failure/event recorder, H2 fixture |
| `exposed/tenant-jdbc/src/test/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResourceRegistryTest.kt` | 입력·조회·identity·H2 isolation 계약 |
| `exposed/tenant-jdbc/src/test/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResourceRegistryFailureTest.kt` | handoff, 예외 우선순위, suppressed, fatal cleanup 계약 |
| `exposed/tenant-jdbc/src/test/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResourceRegistryConcurrencyTest.kt` | lookup/close 경합, 재진입, waiter interrupt, happens-before 계약 |
| `exposed/tenant-jdbc/src/test/java/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResourceRegistryJavaInteropTest.java` | Java caller의 null tenant/factory/lookup 방어 계약 |
| `exposed/tenant-jdbc/src/test/resources/junit-platform.properties` | 신규 테스트의 병렬 실행을 비활성화하여 전역 Exposed manager 간섭 방지 |
| `exposed/tenant-jdbc/src/test/resources/logback-test.xml` | 테스트 로그 최소화와 secret-bearing callback message 비복제 확인 |
| `exposed/tenant-jdbc/README.md`, `README.ko.md` | 의존성, 사용법, 소유권, 운영 shutdown, 비보장 범위 |
| `api/bluetape4k-exposed-tenant-jdbc.api` | 신규 public JVM ABI baseline |
| `build.gradle.kts` | publishable production ABI module 수 45 고정 |
| `.github/workflows/ci.yml`, `.github/workflows/nightly-tests.yml` | JDBC path filter, test/Kover, non-empty XML, ABI count 45 |
| `README.md`, `README.ko.md`, `AGENTS.md`, `CHANGELOG.md` | root module inventory, quick-start, layout, release-facing 변경 기록 |

### Task 1: 신규 module을 등록하고 production dependency 경계를 잠근다

**Files:**
- Create: `exposed/tenant-jdbc/build.gradle.kts`
- Create: `exposed/tenant-jdbc/src/test/resources/junit-platform.properties`
- Create: `exposed/tenant-jdbc/src/test/resources/logback-test.xml`
- Modify: `build.gradle.kts:1143-1149`

- [ ] **Step 1: Gradle module 파일을 만든다**

`exposed/tenant-jdbc/build.gradle.kts`를 다음 dependency 경계로 만든다.

```kotlin
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(platform(bt4k.exposed.bom))
    api(bt4k.exposed.jdbc)

    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.h2.v2)
    testImplementation(bt4k.hikaricp)
}
```

`settings.gradle.kts`는 `includeModules("exposed", withBaseDir = false, prefix = "bluetape4k-exposed-", excludeDirNames = setOf("bom"))`로 새 디렉터리를 자동 포함하므로 수정하지 않는다. `exposed/bom/build.gradle.kts`도 publishable subproject를 자동 constraint로 수집하므로 수정하지 않는다.

- [ ] **Step 2: 격리된 test runtime 설정을 만든다**

`junit-platform.properties`에는 다음을 기록한다.

```properties
junit.jupiter.execution.parallel.enabled=false
```

`logback-test.xml`에는 다음을 기록한다.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <root level="WARN"/>
</configuration>
```

- [ ] **Step 3: production ABI inventory의 fail-closed 수를 갱신한다**

`build.gradle.kts`의 두 `44`를 `45`로 바꾼다.

```kotlin
check(productionAbiProjects.size == 45) {
    "Production ABI publication inventory must contain 45 JVM modules, found ${productionAbiProjects.size}"
}
```

- [ ] **Step 4: module 등록과 dependency graph를 검증한다**

Run:

```bash
./gradlew projects :bluetape4k-exposed-tenant-jdbc:dependencies \
  --configuration runtimeClasspath --no-daemon --no-configuration-cache
```

Expected: project 목록에 `:bluetape4k-exposed-tenant-jdbc`가 나타나고 runtime graph에 Exposed JDBC는 있지만 Spring, Ktor, HikariCP, Micrometer, Reactor는 없다.

- [ ] **Step 5: 첫 Lore commit을 만든다**

```bash
git add exposed/tenant-jdbc/build.gradle.kts exposed/tenant-jdbc/src/test/resources build.gradle.kts
git commit -m "tenant JDBC registry의 독립 배포 경계를 고정한다" \
  -m "Constraint: production runtime에는 Exposed JDBC와 JDK DataSource만 허용한다
Rejected: 기존 Ktor tenant adapter 재사용 | Spring MVC consumer에 Ktor와 tenant 타입 결합이 생긴다
Confidence: high
Scope-risk: moderate
Directive: settings와 BOM의 자동 발견을 유지하되 ABI 수와 CI shard는 명시적으로 갱신한다
Tested: Gradle project discovery와 runtimeClasspath
Not-tested: registry 동작은 후속 TDD task에서 검증한다"
```

### Task 2: 공개 API와 입력·조회 계약을 RED/GREEN으로 고정한다

**Files:**
- Create: `exposed/tenant-jdbc/src/main/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResource.kt`
- Create: `exposed/tenant-jdbc/src/main/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResourceExceptions.kt`
- Create: `exposed/tenant-jdbc/src/main/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResourceRegistry.kt`
- Create: `exposed/tenant-jdbc/src/test/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcTestFixtures.kt`
- Create: `exposed/tenant-jdbc/src/test/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResourceRegistryTest.kt`
- Create: `exposed/tenant-jdbc/src/test/java/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResourceRegistryJavaInteropTest.java`

- [ ] **Step 1: 조회·validation RED 테스트를 작성한다**

`TenantJdbcResourceRegistryTest`에 다음 이름의 테스트를 실제 assertion과 함께 작성한다.

```kotlin
@Test
fun `string과 enum tenant가 같은 resource identity를 반환한다`() {
    val registry = registryOf("alpha", "beta")
    registry.use {
        it.resourceFor("alpha").database shouldBeSameInstanceAs it.databaseFor("alpha")
        it.resourceFor("alpha").dataSource shouldBeSameInstanceAs it.dataSourceFor("alpha")
        it.configuredTenants.toList() shouldBeEqualTo listOf("alpha", "beta")
    }
}

@Test
fun `unknown tenant는 key 문자열 없이 고정 예외로 실패한다`() {
    val configured = SecretTenant("configured-secret")
    val unknown = SecretTenant("credential-value")
    val registry = TenantJdbcResourceRegistry.create(
        tenants = listOf(configured),
        dataSourceFactory = { dataSource("configured") },
        disposeDataSource = { _, _ -> },
    )
    registry.use {
        val failure = assertFailsWith<UnknownTenantJdbcResourceException> {
            it.resourceFor(unknown)
        }
        failure.message shouldBeEqualTo "Unknown tenant JDBC resource."
        unknown.toStringCalls.get() shouldBeEqualTo 0
    }
}

@Test
fun `중복 tenant는 factory 호출 전에 실패한다`() {
    val calls = AtomicInteger()
    assertFailsWith<IllegalArgumentException> {
        TenantJdbcResourceRegistry.create(listOf("a", "a"), { calls.incrementAndGet(); dataSource("a") }, ::dispose)
    }.message shouldBeEqualTo "Duplicate tenant key."
    calls.get() shouldBeEqualTo 0
}
```

같은 Kotlin 파일에 empty registry, immutable `configuredTenants`, stable hash collision exact match, enum key compile, secret-bearing `toString()` 호출 횟수 0을 각각 독립 test로 검증한다. `TenantJdbcTestFixtures.kt`에는 `@file:JvmName("TenantJdbcTestFixtures")`를 선언하고 `SecretTenant`, constant-hash key, H2 `JdbcDataSource`를 만드는 public test-source 함수 `dataSource`, `TrackingDataSource`, `registryOf`를 완전한 구현으로 둔다.

`TenantJdbcResourceRegistryJavaInteropTest.java`에는 platform type을 통한 null 방어를 다음처럼 고정한다.

```java
@Test
void rejectsNullTenantBeforeCallingFactory() {
    AtomicInteger calls = new AtomicInteger();
    IllegalArgumentException failure = assertThrows(
        IllegalArgumentException.class,
        () -> TenantJdbcResourceRegistry.create(
            Arrays.asList("a", null),
            tenant -> {
                calls.incrementAndGet();
                return TenantJdbcTestFixtures.dataSource("java-null-key");
            },
            (tenant, dataSource) -> Unit.INSTANCE
        )
    );
    assertEquals("Tenant key must not be null.", failure.getMessage());
    assertEquals(0, calls.get());
}

@Test
void rejectsNullFactoryResultAndNullLookup() {
    IllegalArgumentException factoryFailure = assertThrows(
        IllegalArgumentException.class,
        () -> TenantJdbcResourceRegistry.create(
            List.of("a"),
            tenant -> null,
            (tenant, dataSource) -> Unit.INSTANCE
        )
    );
    assertEquals("dataSourceFactory returned null.", factoryFailure.getMessage());

    TenantJdbcResourceRegistry<String> registry = TenantJdbcResourceRegistry.create(
        List.of("a"),
        tenant -> TenantJdbcTestFixtures.dataSource("java-null-lookup"),
        (tenant, dataSource) -> Unit.INSTANCE
    );
    try (registry) {
        assertThrows(NullPointerException.class, () -> registry.resourceFor(null));
    }
}
```

- [ ] **Step 2: 테스트가 공개 타입 부재로 RED인지 확인한다**

Run:

```bash
./gradlew :bluetape4k-exposed-tenant-jdbc:test \
  --tests '*TenantJdbcResourceRegistry*Test' --no-daemon --no-configuration-cache
```

Expected: `TenantJdbcResourceRegistry`, `TenantJdbcResource`, `UnknownTenantJdbcResourceException` unresolved reference로 compile 실패한다.

- [ ] **Step 3: 공개 resource와 exception을 최소 구현한다**

`TenantJdbcResource.kt`:

```kotlin
package io.bluetape4k.exposed.tenant.jdbc

import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

interface TenantJdbcResource {
    val dataSource: DataSource
    val database: Database
}
```

`TenantJdbcResourceExceptions.kt`:

```kotlin
package io.bluetape4k.exposed.tenant.jdbc

class UnknownTenantJdbcResourceException :
    NoSuchElementException("Unknown tenant JDBC resource.")
```

- [ ] **Step 4: registry의 validation·조립·lookup 골격을 구현한다**

`TenantJdbcResourceRegistry.kt`는 public signature를 다음과 같이 고정한다.

```kotlin
class TenantJdbcResourceRegistry<K : Any> private constructor(
    private val resources: Map<K, OwnedTenantJdbcResource>,
    val configuredTenants: Set<K>,
) : AutoCloseable {
    fun resourceFor(tenant: K): TenantJdbcResource
    fun databaseFor(tenant: K): Database = resourceFor(tenant).database
    fun dataSourceFor(tenant: K): DataSource = resourceFor(tenant).dataSource
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
```

입력은 `Iterable<*>` view에서 null을 검사해 insertion-order `List<K>`로 복사하고, `LinkedHashSet`으로 duplicate를 resource 획득 전에 거부한다. 각 internal resource는 `tenant`, public `DataSource`, `Database`, type-safe disposer closure를 보관하며 public interface만 반환한다. 조립이 끝나면 `Collections.unmodifiableMap(LinkedHashMap(resources))`와 `Collections.unmodifiableSet(LinkedHashSet(keys))`를 생성한다. 이 task의 최소 close는 생성 역순으로 unregister와 dispose를 한 번 수행한다. lookup은 state가 `OPEN`일 때만 map을 읽고, closed 계열이면 `IllegalStateException("Tenant JDBC resource registry is closed.")`을 던진다. same-reference DataSource 탐지는 Task 3에서 RED test를 본 뒤 추가한다.

테스트 전용 internal factory는 production public ABI를 늘리지 않고 다음 dependency를 주입한다.

```kotlin
internal data class TenantJdbcRegistryHooks(
    val connectDatabase: (DataSource) -> Database = { dataSource -> Database.connect(dataSource) },
    val unregisterDatabase: (Database) -> Unit = { database -> TransactionManager.closeAndUnregister(database) },
    val beforeResourceCommit: () -> Unit = {},
    val beforeRegistryPublish: () -> Unit = {},
    val beforeCloseWait: () -> Unit = {},
)
```

public `create`는 default hook만 사용하고, internal `createForTesting`은 failure/fatal 순서를 결정적으로 만들 때만 사용한다.

같은 test에 Ktor resolver가 요구하는 함수 타입과의 source compatibility를 다음 assignment로 고정한다.

```kotlin
val resolver: (String) -> Database = registry::databaseFor
resolver("alpha") shouldBeSameInstanceAs registry.databaseFor("alpha")
```

- [ ] **Step 5: targeted test가 GREEN인지 확인한다**

Run:

```bash
./gradlew :bluetape4k-exposed-tenant-jdbc:test \
  --tests '*TenantJdbcResourceRegistry*Test' --no-daemon --no-configuration-cache
```

Expected: 실패 0, skipped 0. `build/test-results/test/TEST-*.xml`에서 tests 수가 1 이상이고 `<failure>`와 `<skipped>`가 없다.

- [ ] **Step 6: 공개 API commit을 만든다**

```bash
git add exposed/tenant-jdbc/src/main exposed/tenant-jdbc/src/test
git commit -m "tenant별 JDBC resource 조회 계약을 단일화한다" \
  -m "Constraint: tenant key와 callback 예외의 민감한 문자열을 provider가 복제하지 않는다
Rejected: caller가 Database까지 주입 | DataSource와 Exposed 등록 수명 소유자가 분리된다
Confidence: high
Scope-risk: moderate
Directive: lookup은 fallback이나 전역 Database 선택 없이 exact match를 유지한다
Tested: 입력 검증, identity, unknown tenant, immutable key view, H2 lookup
Not-tested: concurrent close와 failure aggregation은 후속 task에서 검증한다"
```

### Task 3: 실제 H2 isolation과 DataSource 소유권 경계를 검증한다

**Files:**
- Modify: `exposed/tenant-jdbc/src/test/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcTestFixtures.kt`
- Modify: `exposed/tenant-jdbc/src/test/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResourceRegistryTest.kt`

- [ ] **Step 1: 두 H2 database의 RED integration test를 작성한다**

```kotlin
@Test
fun `tenant별 H2 database의 schema와 data가 격리된다`() {
    val registry = TenantJdbcResourceRegistry.create(
        tenants = listOf("a", "b"),
        dataSourceFactory = { tenant -> h2DataSource("tenant_$tenant") },
        disposeDataSource = { _, dataSource -> dataSource.close() },
    )
    registry.use {
        transaction(it.databaseFor("a")) { exec("create table marker(value varchar(16))"); exec("insert into marker values ('a')") }
        transaction(it.databaseFor("b")) { exec("create table marker(value varchar(16))"); exec("insert into marker values ('b')") }
        marker(it.databaseFor("a")) shouldBeEqualTo "a"
        marker(it.databaseFor("b")) shouldBeEqualTo "b"
    }
}
```

`h2DataSource`는 test-scope `HikariDataSource`를 tenant별 고유 `jdbc:h2:mem:` URL로 만들고 disposer가 `close()`를 한 번 호출했는지 event recorder에 남긴다. `equals`만 같은 별도 DataSource 두 개는 허용하고 같은 reference를 두 tenant에 반환하면 두 번째 소유권을 만들지 않으며 첫 resource만 한 번 cleanup하는 테스트도 추가한다.

public default hook의 실제 Exposed 등록 해제는 다음 serial integration test로 별도 검증한다. disposer 안에서 `TransactionManager.managerFor(database)`가 이미 실패하는지 확인하여 unregister가 pool dispose보다 앞선다는 순서도 함께 고정한다.

```kotlin
@Test
fun `default hook은 Exposed manager를 해제한 뒤 datasource를 dispose한다`() {
    repeat(20) { round ->
        lateinit var database: Database
        val registry = TenantJdbcResourceRegistry.create(
            tenants = listOf("tenant-$round"),
            dataSourceFactory = { h2DataSource("manager_$round") },
            disposeDataSource = { _, dataSource ->
                try {
                    assertFailsWith<IllegalStateException> { TransactionManager.managerFor(database) }
                } finally {
                    dataSource.close()
                }
            },
        )
        database = registry.databaseFor("tenant-$round")
        try {
            TransactionManager.managerFor(database)
        } finally {
            registry.close()
        }
        assertFailsWith<IllegalStateException> { TransactionManager.managerFor(database) }
    }
}
```

- [ ] **Step 2: isolation test가 실패하는 지점을 확인한다**

Run:

```bash
./gradlew :bluetape4k-exposed-tenant-jdbc:test \
  --tests '*TenantJdbcResourceRegistryTest*격리된다*' \
  --tests '*TenantJdbcResourceRegistryTest*reference*' \
  --no-daemon --no-configuration-cache
```

Expected: DataSource ownership 또는 H2 helper가 완성되기 전 RED가 나타난다.

- [ ] **Step 3: identity 검사와 reverse cleanup을 완성한다**

factory가 반환한 직후 `requireNotNull`로 `dataSourceFactory returned null.`을 고정하고 identity map에 이미 존재하면 `Tenant JDBC DataSource is reused.`로 실패한다. 새 identity만 provisional resource로 표시하고 `Database.connect(dataSource)` 성공 뒤 map에 넣는다. 조립 실패 시 현재 provisional resource와 기존 resource를 생성 역순으로 정리한다. same-reference 재사용 실패에서는 두 번째 disposer를 호출하지 않는다.

```kotlin
val seenDataSources = IdentityHashMap<DataSource, Unit>()
val dataSource = requireNotNull(dataSourceFactory(tenant)) {
    "dataSourceFactory returned null."
}
require(seenDataSources.put(dataSource, Unit) == null) {
    "Tenant JDBC DataSource is reused."
}
```

- [ ] **Step 4: isolation과 ownership test를 GREEN으로 만든다**

Run:

```bash
./gradlew :bluetape4k-exposed-tenant-jdbc:test \
  --tests '*TenantJdbcResourceRegistryTest' --no-daemon --no-configuration-cache
```

Expected: tenant A/B marker가 교차하지 않고 각 Hikari pool의 close event가 정확히 한 번 기록되며 skipped가 0이다.

- [ ] **Step 5: 실제 Exposed manager 해제 test를 20회 반복해 GREEN인지 확인한다**

Run:

```bash
./gradlew :bluetape4k-exposed-tenant-jdbc:test \
  --tests '*TenantJdbcResourceRegistryTest*manager*' \
  --rerun-tasks --no-daemon --no-configuration-cache
```

Expected: 각 round에서 close 전 manager 조회가 성공하고 disposer 진입 시점과 close 후에는 `managerFor`가 실패하며 Hikari pool이 닫힌다.

### Task 4: 부분 초기화와 failure aggregation을 RED/GREEN으로 구현한다

**Files:**
- Modify: `exposed/tenant-jdbc/src/main/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResourceRegistry.kt`
- Create: `exposed/tenant-jdbc/src/test/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResourceRegistryFailureTest.kt`

- [ ] **Step 1: ordinary/fatal failure matrix RED 테스트를 작성한다**

각 테스트는 internal hook과 event recorder로 다음 exact sequence를 assertion한다.

```kotlin
@Test
fun `connect 실패는 현재 datasource와 이전 resource를 역순 정리한다`() {
    val connectFailure = IllegalStateException("connect-secret")
    val events = mutableListOf<String>()
    val failure = assertFailsWith<IllegalStateException> {
        createForTesting(
            tenants = listOf("a", "b"),
            factory = trackingFactory(events),
            disposer = trackingDisposer(events),
            hooks = hooksThatFailConnectFor("b", connectFailure, events),
        )
    }
    failure shouldBeSameInstanceAs connectFailure
    events shouldBeEqualTo listOf("create:a", "connect:a", "create:b", "connect:b", "dispose:b", "unregister:a", "dispose:a")
}
```

별도 test checkbox로 factory 반환 전 실패의 현재 자원은 registry가 dispose하지 않음, ordinary primary 유지와 발생 순서 suppressed, 첫 cleanup fatal의 primary 승격, 원래 fatal primary 유지, `VirtualMachineError`·`ThreadDeath`·`LinkageError` 각각의 best-effort cleanup, self-suppression identity 건너뜀, `InterruptedException` 뒤 interrupt flag 복원, `CancellationException`을 ordinary failure로 집계, provider logger/metric/wrapper 부재를 한 계약씩 검증한다.

- [ ] **Step 1a: resource commit과 registry publish failure RED 테스트를 작성한다**

`beforeResourceCommit`이 두 번째 resource에서 던지면 현재 database unregister→dispose 뒤 첫 resource unregister→dispose가 이어지는지 검증한다. `beforeRegistryPublish`가 던지면 완성된 모든 resource가 역순 정리되는지 검증한다. 이 두 internal hook은 construction path의 no-op seam이며 public ABI에는 노출하지 않는다.

```kotlin
val hooks = TenantJdbcRegistryHooks(
    beforeResourceCommit = { if (commitCalls.incrementAndGet() == 2) throw assemblyFailure },
    beforeRegistryPublish = {},
)
```

- [ ] **Step 1b: secret-bearing callback이 provider telemetry에 복제되지 않는 test를 작성한다**

test-scope Logback `ListAppender<ILoggingEvent>`를 root logger에 붙이고 `callback-secret-marker`를 message로 가진 disposer failure를 발생시킨다. 반환된 exception은 동일 instance인지 확인하되 captured formatted message와 throwable proxy에는 marker가 없어야 한다. production runtime graph에 Micrometer가 없고 registry source에 logger/metric field가 없는지도 Task 7 dependency/source guard로 확인한다.

```kotlin
val marker = "callback-secret-marker"
val appender = ListAppender<ILoggingEvent>().apply { start() }
val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
root.addAppender(appender)
try {
    val failure = assertFails { registryWithDisposeFailure(IllegalStateException(marker)).close() }
    failure.message shouldBeEqualTo marker
    appender.list.none { marker in it.formattedMessage } shouldBeTrue
    appender.list.none { event -> event.throwableProxy?.message?.contains(marker) == true } shouldBeTrue
} finally {
    root.detachAppender(appender)
    appender.stop()
}
```

- [ ] **Step 2: failure test의 RED를 확인한다**

Run:

```bash
./gradlew :bluetape4k-exposed-tenant-jdbc:test \
  --tests '*TenantJdbcResourceRegistryFailureTest' --no-daemon --no-configuration-cache
```

Expected: cleanup 순서 또는 exception graph assertion이 실패한다.

- [ ] **Step 3: identity 기반 failure accumulator를 구현한다**

internal `FailureAccumulator`는 발생 순서의 unique Throwable identity 목록과 interrupt 관찰 여부를 보관한다. primary는 기존 primary가 fatal이면 그대로, 아니면 첫 fatal, fatal이 없으면 최초 failure다. 선택된 primary를 제외한 identity를 발생 순서대로 `addSuppressed`하고 동일 instance는 다시 추가하지 않는다. `isFatal`은 정확히 다음 타입만 true다.

```kotlin
private fun Throwable.isFatal(): Boolean =
    this is VirtualMachineError || this is ThreadDeath || this is LinkageError
```

cleanup 함수는 각 resource에서 `unregisterDatabase(database)`를 먼저 시도하고 실패 여부와 무관하게 disposer를 시도한 뒤 다음 resource로 진행한다. 전체 종료 직전에 accumulator가 관찰한 `InterruptedException`이 있으면 `Thread.currentThread().interrupt()`를 호출한다.

`beforeResourceCommit`은 Database 생성 직후 map insertion 전에, `beforeRegistryPublish`는 모든 resource 조립 뒤 immutable map/registry 반환 전에 호출한다. 어느 hook이 실패해도 동일 accumulator와 provisional tracking을 거쳐 이미 인수한 자원을 정리한다. `CancellationException`은 `isFatal()`에 포함하지 않고 ordinary primary/suppressed identity test를 통과시킨다.

- [ ] **Step 4: failure matrix를 GREEN으로 만든다**

Run:

```bash
./gradlew :bluetape4k-exposed-tenant-jdbc:test \
  --tests '*TenantJdbcResourceRegistryFailureTest' --no-daemon --no-configuration-cache
```

Expected: failure identity, primary type, suppressed identity/order, cleanup event 순서, interrupt flag가 모두 통과하고 skipped가 0이다.

- [ ] **Step 5: failure semantics commit을 만든다**

```bash
git add exposed/tenant-jdbc/src/main exposed/tenant-jdbc/src/test
git commit -m "부분 초기화와 종료 실패의 원인을 보존한다" \
  -m "Constraint: fatal 오류에서도 이미 인수한 resource는 best-effort로 모두 정리한다
Rejected: 첫 cleanup 오류에서 즉시 중단 | 뒤 resource와 pool이 누수된다
Confidence: high
Scope-risk: moderate
Directive: primary와 suppressed는 exception instance identity와 발생 순서를 보존한다
Tested: ordinary, fatal, interrupt, self-suppression, reverse cleanup matrix
Not-tested: 동시 close의 result publication은 다음 task에서 검증한다"
```

### Task 5: concurrent close state machine과 lookup 경합을 RED/GREEN으로 구현한다

**Files:**
- Modify: `exposed/tenant-jdbc/src/main/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResourceRegistry.kt`
- Create: `exposed/tenant-jdbc/src/test/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResourceRegistryConcurrencyTest.kt`

- [ ] **Step 1: deterministic latch 기반 RED 테스트를 작성한다**

```kotlin
@Test
fun `동시 close caller는 한 cleanup과 같은 non-fatal 결과를 관찰한다`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val waiterObservedClosing = CountDownLatch(1)
    val failure = IllegalStateException("dispose-failure")
    val registry = blockingFailureRegistry(entered, release, waiterObservedClosing, failure)
    val ownerResult = AtomicReference<Throwable?>()
    val waiterResult = AtomicReference<Throwable?>()
    val owner = thread { ownerResult.set(catching { registry.close() }) }
    var waiter: Thread? = null
    try {
        entered.await(5, TimeUnit.SECONDS) shouldBeTrue
        val startedWaiter = thread { waiterResult.set(catching { registry.close() }) }
        waiter = startedWaiter
        waiterObservedClosing.await(5, TimeUnit.SECONDS) shouldBeTrue
        release.countDown()
        owner.join(Duration.ofSeconds(5))
        startedWaiter.join(Duration.ofSeconds(5))
        ownerResult.get() shouldBeSameInstanceAs failure
        waiterResult.get() shouldBeSameInstanceAs failure
        registry.disposeCalls.get() shouldBeEqualTo 1
    } finally {
        release.countDown()
        owner.interrupt()
        waiter?.interrupt()
        owner.join(Duration.ofSeconds(5))
        waiter?.join(Duration.ofSeconds(5))
    }
    owner.isAlive shouldBeFalse
    waiter?.let { it.isAlive shouldBeFalse }
}
```

`beforeCloseWait` hook은 non-owner caller가 `Closing`을 읽은 직후 `waiterObservedClosing.countDown()`을 호출한다. 같은 파일에 successful repeated close no-op, cleanup owner disposer의 same-registry reentrant close no-op, waiter interrupt flag 복원, fatal owner raw throwable·waiter fixed `IllegalStateException`, close가 선점한 뒤 lookup의 closed 우선순위, final cleanup side effect visibility를 각각 독립 test로 검증한다. 모든 `await`는 5초 timeout assertion을 사용하고, `finally`에서 blocking latch를 해제하며, 이 test가 만든 thread만 `join(Duration.ofSeconds(5))`와 `isAlive == false`로 종료를 확인한다.

- [ ] **Step 1a: `OPEN` lookup의 비-lease 경합 RED 테스트를 작성한다**

hot-path hook을 추가하지 않는다. 대신 `hashCode()` 진입과 해제를 latch로 제어하는 `BlockingHashKey`를 test fixture에 만들고, lookup 구현이 state를 읽은 뒤 map hash를 계산한다는 순서를 이용한다.

```kotlin
val key = BlockingHashKey("a")
val registry = registryOf(key)
val gates = key.blockNextHash()
val returned = AtomicReference<TenantJdbcResource?>()
val lookup = thread { returned.set(registry.resourceFor(key)) }
try {
    gates.entered.await(5, TimeUnit.SECONDS) shouldBeTrue
    registry.close()
    gates.release.countDown()
    lookup.join(Duration.ofSeconds(5))
    returned.get() shouldNotBeNull()
    assertFailsWith<IllegalStateException> { registry.resourceFor(key) }
} finally {
    gates.release.countDown()
    lookup.interrupt()
    lookup.join(Duration.ofSeconds(5))
    registry.close()
}
lookup.isAlive shouldBeFalse
```

fixture의 constructor/registration 중 hash 호출은 gate를 활성화하지 않는다. `key.blockNextHash()`를 호출한 뒤 lookup thread를 시작해 lookup map access만 pause하며, `finally`에서 `releaseHash.countDown()`과 thread interrupt/join을 수행한다.

- [ ] **Step 1b: bounded concurrent lookup stress RED 테스트를 작성한다**

32개 test-owned thread가 timed `CyclicBarrier`를 통과한 뒤 각 10,000회 `resourceFor`, `databaseFor`, `dataSourceFor`를 호출한다. 모든 반환값은 사전에 얻은 동일 entry identity여야 하고 error queue는 비어야 한다. 제출한 모든 `Future`를 보관해 각각 `get(10, SECONDS)`으로 완료와 failure cause를 확인하고, `finally`에서 executor에 `shutdownNow()`를 호출한 뒤 `awaitTermination(5, SECONDS) shouldBeTrue`를 검증한다. 마지막에는 모든 worker 결과와 identity assertion 및 빈 error queue를 확인한다. timeout 또는 종료 실패 시 test-owned thread stack을 failure evidence로 첨부한다. 수치 latency SLA와 microbenchmark는 설계상 비목표이므로 추가하지 않고, 이 structural stress와 immutable-map code review를 성능 증거로 기록한다.

- [ ] **Step 2: concurrency test의 RED를 확인한다**

Run:

```bash
./gradlew :bluetape4k-exposed-tenant-jdbc:test \
  --tests '*TenantJdbcResourceRegistryConcurrencyTest' --no-daemon --no-configuration-cache
```

Expected: 단일 cleanup, waiter block, interrupt restore, fatal waiter mapping 중 구현되지 않은 assertion이 실패한다.

- [ ] **Step 3: atomic close state와 completion signal을 구현한다**

internal state는 `Open`, `Closing(ownerThread, CountDownLatch)`, `ClosedSuccess`, `ClosedFailure(Throwable)`, `ClosedFatal`로 둔다. `close()`는 `AtomicReference.compareAndSet(Open, Closing)`에 성공한 caller만 cleanup한다. owner-thread 재진입은 즉시 반환하고 다른 `Closing` observer는 latch를 uninterruptible loop로 기다린 뒤 interrupt flag를 복원하고 final state를 다시 읽는다. cleanup owner는 final state를 먼저 publish하고 `countDown()`한 뒤 성공 반환, 동일 non-fatal throwable 재투척, 또는 raw fatal 재투척을 수행한다. waiter와 후속 caller는 `ClosedFatal`에서 `IllegalStateException("Tenant JDBC resource registry closed after a fatal cleanup failure.")`만 받는다.

```kotlin
if (state.owner === Thread.currentThread()) return
hooks.beforeCloseWait()
var interrupted = false
while (true) {
    try {
        state.completion.await()
        break
    } catch (_: InterruptedException) {
        interrupted = true
    }
}
if (interrupted) Thread.currentThread().interrupt()
```

lookup은 state read를 선형화 지점으로 삼는다. `Open`을 읽었으면 map result를 반환하고, 나머지 상태면 map lookup 전에 closed 예외를 던진다. lease나 drain counter를 추가하지 않는다.

- [ ] **Step 4: concurrency test를 반복 실행한다**

Run:

```bash
for attempt in 1 2 3 4 5; do
  ./gradlew :bluetape4k-exposed-tenant-jdbc:test \
    --tests '*TenantJdbcResourceRegistryConcurrencyTest' \
    --rerun-tasks --no-daemon --no-configuration-cache || exit 1
done
```

Expected: 5회 모두 실패 0, skipped 0, timeout 0이며 각 test가 만든 owner/waiter/lookup worker의 `isAlive`가 false다.

- [ ] **Step 5: lifecycle commit을 만든다**

```bash
git add exposed/tenant-jdbc/src/main exposed/tenant-jdbc/src/test
git commit -m "동시 종료가 같은 lifecycle 결과를 관찰하게 한다" \
  -m "Constraint: cleanup callback은 caller thread에서 동기 실행하고 timeout은 caller가 소유한다
Rejected: background executor와 shutdown hook | lifecycle과 thread 소유권이 provider로 확장된다
Confidence: high
Scope-risk: broad
Directive: OPEN 관찰 lookup은 lease가 아니며 shutdown 전에 caller가 요청 차단과 drain을 완료해야 한다
Tested: close 경쟁, 재진입, waiter interrupt, fatal mapping, lookup race 5회
Not-tested: hostile callback의 무기한 block은 지원 범위 밖이다"
```

### Task 6: 공개 KDoc과 bilingual module/root 문서를 맞춘다

**Files:**
- Modify: `exposed/tenant-jdbc/src/main/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResource.kt`
- Modify: `exposed/tenant-jdbc/src/main/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResourceExceptions.kt`
- Modify: `exposed/tenant-jdbc/src/main/kotlin/io/bluetape4k/exposed/tenant/jdbc/TenantJdbcResourceRegistry.kt`
- Create: `exposed/tenant-jdbc/README.md`
- Create: `exposed/tenant-jdbc/README.ko.md`
- Modify: `README.md:200-245`
- Modify: `README.ko.md:200-245`
- Modify: `AGENTS.md` layout table
- Modify: `CHANGELOG.md` `[Unreleased]`의 `추가`

- [ ] **Step 1: public API의 한국어 KDoc을 작성한다**

KDoc에 `tenants`가 caller가 제한한 finite·bounded 입력이어야 함, factory 반환 전/후 소유권, `Database.connect`가 실제 readiness를 검사하지 않음, `closeAndUnregister`→disposer 순서, stable immutable key, same-reference만 감지, lookup이 authorization·lease가 아님, callback blocking/secret/redaction/timeout 책임, close failure의 raw exception graph가 registry 회수 전까지 메모리에 보존될 수 있음, fatal 시 supervisor restart를 명시한다. 모든 public symbol과 public parameter/return/exception을 Dokka가 경고 없이 해석할 수 있게 링크한다.

- [ ] **Step 2: module README 두 언어를 같은 구조로 작성한다**

두 README는 `Purpose`, `Dependency`, `Create and lookup`, `Shutdown order`, `Failure semantics`, `Caller responsibilities`, `Unsupported behavior`를 같은 순서로 둔다. dependency 예제는 BOM version만 선언하고 개별 artifact version은 쓰지 않는다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-tenant-jdbc")
}

val registry = TenantJdbcResourceRegistry.create(
    tenants = TenantId.entries,
    dataSourceFactory = ::createHikariDataSource,
    disposeDataSource = { _, dataSource -> dataSource.close() },
)

check(requestedTenantId in authenticatedPrincipal.allowedTenantIds) {
    "Forbidden tenant access."
}
val database = registry.databaseFor(requestedTenantId)
```

인증된 identity와 requested tenant가 같다고 가정하지 않고 application authorization이 별도 인가를 완료한 뒤 lookup한다. 운영 종료 순서는 `새 요청 차단 → 활성 transaction drain → registry.close()`로 고정한다. cross-registry dispose callback 순환 close를 금지하고, tenant 입력은 finite·bounded여야 하며 key는 registry 수명 동안 immutable/stable해야 한다고 명시한다. readiness/health, retry, timeout, metric, log, credential, authorization, shutdown watchdog은 caller 책임이다. close failure의 raw exception graph는 registry가 회수될 때까지 callback message와 stack을 메모리에 보존할 수 있으므로 application log나 HTTP response에 직접 노출하지 않는다.

- [ ] **Step 3: root inventory와 변경 로그를 갱신한다**

root README 두 언어의 Gradle quick start와 module 설명에 새 artifact를 추가한다. `AGENTS.md` layout에 `tenant-jdbc/`를 추가한다. `CHANGELOG.md`의 `[Unreleased] > 추가`에 #816 링크와 resource lifecycle 요약을 넣는다. central manual tree는 만들지 않는다.

- [ ] **Step 4: 문서 parity와 자연스러움을 검사한다**

Run:

```bash
git diff --check
rg -n "bluetape4k-exposed-tenant-jdbc|TenantJdbcResourceRegistry" \
  README.md README.ko.md exposed/tenant-jdbc/README.md exposed/tenant-jdbc/README.ko.md \
  AGENTS.md CHANGELOG.md
rg -n "finite|bounded|인가|authorization|exception graph|예외 graph" \
  exposed/tenant-jdbc/README.md exposed/tenant-jdbc/README.ko.md
```

Expected: 여섯 파일 모두 artifact/API를 포함하고 dangling whitespace가 없다. `bluetape-writer` SPW-01~05로 제목·문장 리듬·용어·영문 잔존·독자 흐름을 검사하고 고친다.

### Task 7: ABI, publication metadata, BOM을 fail-closed로 검증한다

**Files:**
- Create: `api/bluetape4k-exposed-tenant-jdbc.api`
- Modify: `build.gradle.kts` only if generated inventory reveals a path/count defect

- [ ] **Step 1: compile과 detekt를 먼저 통과시킨다**

Run:

```bash
./gradlew :bluetape4k-exposed-tenant-jdbc:compileKotlin \
  :bluetape4k-exposed-tenant-jdbc:compileTestKotlin \
  :bluetape4k-exposed-tenant-jdbc:detekt \
  --no-daemon --no-configuration-cache
if rg -n 'Logger|LoggerFactory|MeterRegistry|Counter|Timer' \
  exposed/tenant-jdbc/src/main/kotlin; then
  echo 'tenant-jdbc provider must not own logging or metrics' >&2
  exit 1
fi
```

Expected: deprecated pre-`v1` Exposed import, receiver shadowing, public undocumented symbol, detekt error가 0이다.

- [ ] **Step 2: 신규 module ABI baseline만 생성하고 검토한다**

Run:

```bash
./gradlew :bluetape4k-exposed-tenant-jdbc:updateKotlinAbi \
  --no-daemon --no-configuration-cache
git diff -- api/bluetape4k-exposed-tenant-jdbc.api
```

Expected: public dump에는 `TenantJdbcResource`, `TenantJdbcResourceRegistry<K>`, `UnknownTenantJdbcResourceException`, lookup/create/close만 있고 internal hooks/state/disposer 구현은 없다.

- [ ] **Step 3: 전체 ABI inventory를 별도 invocation으로 검사한다**

Run:

```bash
./gradlew checkProductionAbi --no-daemon --no-configuration-cache --no-build-cache
```

Expected: `modules=45/45`, `baselines=45/45`, `actualDumps=45/45`, `orphanBaselines=0`, `orphanActuals=0`, `emptyBaselines=0`.

- [ ] **Step 4: POM, Gradle module metadata, BOM constraint를 생성·검사한다**

Run:

```bash
./gradlew :bluetape4k-exposed-tenant-jdbc:generatePomFileForBluetapeExposedPublication \
  :bluetape4k-exposed-tenant-jdbc:generateMetadataFileForBluetapeExposedPublication \
  :bluetape4k-exposed-bom:generatePomFileForBluetapeExposedPublication \
  exportPublicationInventory -PsnapshotVersion=-SNAPSHOT \
  --no-daemon --no-configuration-cache --no-build-cache
ruby scripts/publication/validate_module_metadata.rb
ruby scripts/publication/validate_poms.rb
```

Expected: artifact POM/module metadata는 Exposed JDBC를 노출하고 forbidden framework/pool dependency를 포함하지 않는다. BOM POM과 publication inventory에는 `bluetape4k-exposed-tenant-jdbc`가 정확히 한 번 나타난다.

- [ ] **Step 5: ABI/publication commit을 만든다**

```bash
git add api/bluetape4k-exposed-tenant-jdbc.api build.gradle.kts
git commit -m "tenant JDBC registry의 배포 ABI를 고정한다" \
  -m "Constraint: 신규 artifact는 전체 BOM과 production ABI inventory에서 fail-closed로 검증한다
Rejected: module test만으로 배포 준비 판정 | POM, BOM, ABI 누락을 탐지하지 못한다
Confidence: high
Scope-risk: moderate
Directive: public signature 변경 시 신규 baseline만 갱신하고 기존 44개 baseline drift를 허용하지 않는다
Tested: ABI 45/45, POM, Gradle metadata, BOM constraint, publication inventory
Not-tested: Maven Central publication은 release gate 밖이다"
```

### Task 8: CI와 Nightly workflow의 JDBC shard에 신규 module을 연결한다

**Files:**
- Modify: `.github/workflows/ci.yml:169-174,356-368,648-691`
- Modify: `.github/workflows/nightly-tests.yml:445-488,714-724`
- Modify: `scripts/ci/validate_ci_matrix_contract.py`
- Modify: `scripts/ci/validate_ci_matrix_contract_test.py`

- [ ] **Step 1: CI contract RED assertion을 추가한다**

`validate_ci_matrix_contract.py`에 `validate_tenant_jdbc_job(workflow: str) -> List[str]`를 추가해 `test-jdbc-h2` job 범위에서 다음 task와 XML을 모두 요구하게 한다. 기존 `validate()`는 CI의 global-change 검증 뒤 이 함수 결과를 합친다. test file은 CI와 Nightly 내용을 각각 이 함수에 전달하여 두 workflow를 검증한다.

```python
required_tenant_jdbc_tokens = (
    ":bluetape4k-exposed-tenant-jdbc:test",
    ":bluetape4k-exposed-tenant-jdbc:koverXmlReport",
    "exposed/tenant-jdbc/build/reports/kover/report.xml",
)
```

test fixture는 CI와 Nightly의 현재 workflow가 각각 error 0인지 확인하고, 세 token을 하나씩 제거한 임시 workflow가 해당 token을 이름으로 포함한 error를 반환하는지 parameterized subtest로 확인한다.

- [ ] **Step 2: CI validator가 RED인지 확인한다**

Run:

```bash
python3 scripts/ci/validate_ci_matrix_contract_test.py
python3 scripts/ci/validate_ci_matrix_contract.py
```

Expected: helper unit test와 live CI validator 모두 신규 module token 부재를 보고 실패한다. Nightly 검증은 unit test가 `validate_tenant_jdbc_job`을 직접 호출하므로 Nightly에 없는 `changes` job을 요구하지 않는다.

- [ ] **Step 3: CI와 Nightly JDBC job을 갱신한다**

`ci.yml`의 `jdbc` path filter에 `exposed/tenant-jdbc/**`를 추가한다. 두 workflow의 H2 test command에 `:bluetape4k-exposed-tenant-jdbc:test`, Kover command에 `:bluetape4k-exposed-tenant-jdbc:koverXmlReport`를 추가한다. Kover 생성 다음 step에서 아래 검사를 실행하고 upload path에 module directory를 명시한다.

```bash
test -s exposed/tenant-jdbc/build/reports/kover/report.xml
grep -F '<class name=' exposed/tenant-jdbc/build/reports/kover/report.xml
```

CI와 Nightly의 ABI grep을 각각 `modules=45/45`, `baselines=45/45`, `actualDumps=45/45`로 바꾼다. 기존 `coverage-report`는 `coverage-*`를 다운로드하므로 job 이름을 새로 만들지 않고 `coverage-jdbc-h2` artifact에 신규 XML을 포함한다.

- [ ] **Step 4: workflow syntax와 matrix contract를 GREEN으로 만든다**

Run:

```bash
python3 scripts/ci/validate_ci_matrix_contract_test.py
python3 scripts/ci/validate_ci_matrix_contract.py
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/ci.yml"); YAML.load_file(".github/workflows/nightly-tests.yml")'
```

Expected: validator/tests exit 0이고 두 YAML이 parse된다.

- [ ] **Step 5: CI wiring commit을 만든다**

```bash
git add .github/workflows/ci.yml .github/workflows/nightly-tests.yml scripts/ci
git commit -m "tenant JDBC registry가 JDBC 검증 shard를 빠짐없이 통과하게 한다" \
  -m "Constraint: 새 publishable module은 CI와 Nightly workflow 양쪽의 H2 shard에서 test와 non-empty Kover를 남겨야 한다
Rejected: settings 자동 발견에만 의존 | path-filtered CI에서 신규 테스트가 실행되지 않는다
Confidence: high
Scope-risk: moderate
Directive: coverage-jdbc-h2 artifact와 aggregate job 이름을 유지해 기존 needs graph를 깨지 않는다
Tested: CI matrix validator, YAML parse, module Kover XML contract
Not-tested: GitHub-hosted exact-head run은 PR과 dispatch gate에서 확인한다"
```

### Task 9: 통합 검증과 독립 code review를 완료한다

**Files:**
- Create: `docs/review/2026-09-07-issue-816-tenant-jdbc-registry-implementation-review.md`
- Create: `docs/superpowers/verification/2026-09-07-issue-816-tenant-jdbc-registry.md`
- Modify: implementation files identified by review findings

- [ ] **Step 1: 신규 module test를 fresh하게 실행하고 JUnit 수를 읽는다**

Run:

```bash
./gradlew :bluetape4k-exposed-tenant-jdbc:cleanTest \
  :bluetape4k-exposed-tenant-jdbc:test \
  :bluetape4k-exposed-tenant-jdbc:koverXmlReport \
  --no-daemon --no-configuration-cache --rerun-tasks
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
files = glob.glob('exposed/tenant-jdbc/build/test-results/test/TEST-*.xml')
assert files
tests = failures = errors = skipped = 0
for path in files:
    root = ET.parse(path).getroot()
    tests += int(root.attrib.get('tests', 0))
    failures += int(root.attrib.get('failures', 0))
    errors += int(root.attrib.get('errors', 0))
    skipped += int(root.attrib.get('skipped', 0))
assert tests > 0 and failures == errors == skipped == 0, (tests, failures, errors, skipped)
print(f'tests={tests} failures={failures} errors={errors} skipped={skipped}')
PY
```

Expected: tests가 1 이상이고 failures/errors/skipped가 모두 0이며 Kover XML이 non-empty다.

- [ ] **Step 2: provider와 기존 JDBC/Ktor consumer compile contract를 검사한다**

Run:

```bash
./gradlew :bluetape4k-exposed-tenant-jdbc:build \
  :bluetape4k-exposed-jdbc:test \
  :bluetape4k-exposed-ktor-tenant-jdbc:compileTestKotlin \
  checkProductionAbi detekt \
  --no-daemon --no-configuration-cache --no-parallel --max-workers=1
```

Expected: 신규 module과 기존 JDBC tests가 실패 0이고 `registry::databaseFor`를 사용하는 compile fixture가 source-compatible하다. 기존 Ktor adapter에는 production dependency를 추가하지 않는다.

- [ ] **Step 3: publication과 문서 검증을 다시 실행한다**

Run:

```bash
./gradlew generateMetadataFileForBluetapeExposedPublication \
  generatePomFileForBluetapeExposedPublication exportPublicationInventory \
  -PsnapshotVersion=-SNAPSHOT --no-daemon --no-configuration-cache --no-build-cache
ruby -I scripts/publication scripts/publication/gradle_module_metadata_audit_test.rb
ruby scripts/publication/publication_pom_audit_test.rb
ruby scripts/publication/validate_module_metadata.rb
ruby scripts/publication/validate_poms.rb
git diff --check
```

Expected: publication audit 전체 exit 0, diff whitespace error 0.

- [ ] **Step 4: 여섯 관점 독립 code review와 main integration review를 수행한다**

exact `git rev-parse HEAD`와 `git diff origin/develop...HEAD`를 performance, stability, security, operator/ops, developer/API, user/caller lane에 각각 제공한다. 각 lane은 파일/행 근거가 있는 P0/P1/P2/P3만 반환한다. 독립 리뷰가 실행되지 않거나 usable verdict를 내지 못하면 이유를 기록하고 해당 관점만 `inline exact-diff fallback`으로 명시한다. main session은 중복을 합치고 spec AC-01~AC-12, tests, ABI/API, CI, dependency, docs를 대조한다. P0/P1은 수정 후 영향 lane과 targeted test를 다시 실행하며 0이 될 때까지 review gate를 닫지 않는다. P2/P3는 현재 수정하거나 별도 issue와 근거를 남긴다.

- [ ] **Step 5: review와 verification artifact를 한국어로 작성한다**

두 artifact에 exact head SHA, changed files, JUnit 수, Kover XML 존재/크기, ABI 45/45, publication audit, dependency graph, 각 review provenance, P0/P1=0 여부, P2/P3 처분, 미실행 PR/merge/release와 수동 Full Nightly 비필수 판단을 기록한다. `bluetape-writer` SPW-01~05 audit 결과도 포함한다.

- [ ] **Step 6: implementation 완료 commit을 만든다**

```bash
git add docs/review/2026-09-07-issue-816-tenant-jdbc-registry-implementation-review.md \
  docs/superpowers/verification/2026-09-07-issue-816-tenant-jdbc-registry.md \
  exposed/tenant-jdbc README.md README.ko.md AGENTS.md CHANGELOG.md api \
  build.gradle.kts .github/workflows scripts/ci
git commit -m "tenant JDBC registry의 검증 가능한 수명 계약을 완성한다" \
  -m "Constraint: provider 완료와 workshop migration, PR, merge, release는 서로 다른 게이트다
Rejected: smoke test만으로 완료 선언 | lifecycle race와 publication 누락을 증명하지 못한다
Confidence: high
Scope-risk: broad
Directive: PR 본문은 Closes #816을 사용하고 exact-head required CI와 리뷰·thread 검증 없이 머지하지 않는다
Tested: module/JDBC tests, concurrency repeat, detekt, ABI 45/45, POM, metadata, BOM, CI contract, 6관점 review
Not-tested: GitHub exact-head CI는 PR 생성 후 확인하며 수동 Full Nightly는 tenant delivery 필수 조건이 아니다"
```

## AC-to-task 추적표

| 수용 기준 | 구현/검증 task |
|---|---|
| AC-01 generic key와 동일 resource identity | Task 2 |
| AC-02 H2 isolation, DataSource identity 재사용 거부 | Task 3 |
| AC-03 unknown/null/duplicate/empty/redaction | Task 2 |
| AC-04 handoff와 부분 초기화 cleanup | Task 3, Task 4 |
| AC-05 ordinary/fatal/suppressed/interrupt | Task 4 |
| AC-06 unregister→dispose, idempotent/concurrent close | Task 4, Task 5 |
| AC-07 immutable concurrent lookup | Task 2, Task 5 |
| AC-08 close 경합과 lookup 비-lease | Task 5, Task 6 |
| AC-09 framework/pool dependency 부재 | Task 1, Task 7 |
| AC-10 settings/BOM/publication/CI/ABI inventory | Task 1, Task 7, Task 8 |
| AC-11 `registry::databaseFor`와 Hikari test consumer | Task 3, Task 9 |
| AC-12 ABI/KDoc/README/한국어 audit | Task 6, Task 7, Task 9 |

## 위험, rollback, 재실행 지점

| 위험 | 감지 신호 | 완화와 재실행 |
|---|---|---|
| Exposed global transaction manager 등록 누수 | 반복 create/close 뒤 manager lookup 또는 후속 H2 test 간섭 | unregister→dispose event를 고치고 Task 4와 신규 module 전체 test 재실행 |
| close waiter deadlock | bounded join/Awaitility timeout | owner-thread 재진입 분기와 final-state-before-countDown 순서를 고치고 Task 5를 5회 재실행 |
| fatal이 ordinary failure에 눌림 | primary type/identity assertion 실패 | identity accumulator 선택 규칙을 고치고 Task 4 전체 matrix 재실행 |
| tenant key/secret이 예외·로그에 노출 | `SecretTenant.toStringCalls` 증가 또는 captured log hit | provider formatting/logging 제거 후 Task 2/4와 문서 security lens 재실행 |
| framework/pool dependency 유입 | runtimeClasspath/POM/module metadata에 금지 좌표 | dependency scope를 test로 내리고 Task 1/7 publication 검증 재실행 |
| 새 module이 selective CI에서 빠짐 | path filter나 required token validator 실패 | Task 8 workflow와 validator를 함께 수정하고 YAML/contract 재실행 |
| API가 internal lifecycle type을 노출 | ABI dump에 hook/state/disposer 등장 | visibility/signature 수정 후 Task 2/7과 developer/API lens 재실행 |
| active transaction 중 resource 종료 | 운영 중 connection failure | provider에 lease를 추가하지 않고 caller shutdown을 요청 차단→drain→close로 고치며 README/KDoc 재검토 |
| underlying pool을 다른 wrapper가 공유 | 두 tenant가 서로 다른 DataSource identity지만 같은 pool을 닫음 | unsupported caller contract를 유지하고 tenant별 독립 pool을 구성; 자동 unwrap이나 reflection은 추가하지 않음 |

publish 전 rollback은 `exposed/tenant-jdbc`, 신규 ABI baseline, root 문서, build count, CI/Nightly token을 해당 Lore commit 역순으로 되돌리는 것이다. publish 후에는 artifact를 삭제하거나 같은 version을 덮어쓰지 않는다. consumer가 이미 채택했다면 마지막 정상 version을 고정하고 explicit tenant→Database map으로 복귀한 뒤 새 patch version에서 수정한다. workshop #269는 provider coordinate가 Maven Central 또는 승인된 개발 저장소에서 isolated resolution을 통과하기 전 시작하지 않는다.

## 구현 완료 후 별도 게이트

1. PR 생성 승인을 받으면 base `develop`, head `feat/issue-816-tenant-jdbc-registry`를 live read-back하고 PR 본문에 `Closes #816`과 마지막 `## DoD Status`를 사용한다.
2. PR exact head CI의 terminal job, JUnit 수, review/thread, mergeability를 확인한다.
3. 수동 Full Nightly는 merge gate로 요구하지 않는다. 사용자가 명시적으로 요청하거나 tenant가 포함되지 않은 backend 검증 공백이 새로 확인될 때만 별도 승인 후 실행한다.
4. 머지는 exact head를 다시 읽은 뒤 fresh explicit approval을 받는다. auto-merge는 사용하지 않는다.
5. release와 exposed-workshop #269 migration은 provider artifact publication을 확인한 다음 별도 checklist와 승인으로 진행한다.
