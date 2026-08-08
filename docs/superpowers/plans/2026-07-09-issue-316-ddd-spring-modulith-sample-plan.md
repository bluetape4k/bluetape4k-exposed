# Issue #316 DDD Spring Modulith 샘플 구현 계획

> **에이전트 작업자:** REQUIRED SUB-SKILL: 이 계획을 작업 단위로 구현하려면
> `superpowers:subagent-driven-development`(권장) 또는
> `superpowers:executing-plans`를 사용한다. 단계 추적에는 checkbox
> (`- [ ]`) 문법을 사용한다.

**목표:** module 경계, aggregate persistence, durable Modulith publication row,
idempotent listener 처리, bilingual 문서, CI/Nightly 등록을 보여 주는 public
DDD + Spring Modulith + Exposed 예제를 추가한다.

**아키텍처:** `examples/ddd-spring-modulith-demo`를
`:examples-ddd-spring-modulith-demo`로 추가한다. 유효한 app에는 `orders`와
`shipping` module이 있으며 `shipping`은 `orders :: events`에만 의존할 수 있다.
command path는 트랜잭션 안에서 aggregate event를 snapshot하고, 같은 트랜잭션에서
Spring Modulith publication handoff를 기록하며, 트랜잭션이 성공적으로 반환된 뒤에만
aggregate event를 비운다.

**기술 스택:** Kotlin, Spring Boot, Spring Modulith, JetBrains Exposed JDBC, H2,
`:bluetape4k-exposed-core`, `:bluetape4k-exposed-spring-boot-jdbc`,
`:bluetape4k-exposed-spring-modulith`, JUnit 5, bluetape4k assertions, Awaitility,
CairoSVG.

---

## 파일

생성:

- `examples/ddd-spring-modulith-demo/build.gradle.kts`
- `examples/ddd-spring-modulith-demo/README.md`
- `examples/ddd-spring-modulith-demo/README.ko.md`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/DddSpringModulithDemoApplication.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/ModuleMetadata.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/OrderDomain.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/OrderApplicationService.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/events/ModuleMetadata.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/events/OrderAcceptedEvent.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/internal/OrderRepository.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/shipping/ModuleMetadata.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/shipping/ShippingReservationHandler.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/shipping/internal/ShippingReservationRepository.kt`
- `examples/ddd-spring-modulith-demo/src/test/kotlin/io/bluetape4k/exposed/examples/modulith/DddSpringModulithDemoApplicationTest.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulithinvalid/InvalidBoundaryApplication.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulithinvalid/orders/**`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulithinvalid/shipping/**`
- `examples/ddd-spring-modulith-demo/src/test/resources/junit-platform.properties`
- `examples/ddd-spring-modulith-demo/src/test/resources/logback-test.xml`
- `docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.svg`
- `docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.png`

수정:

- `README.md`
- `README.ko.md`
- `.github/workflows/ci.yml`
- `.github/workflows/nightly-tests.yml`

## 작업 1: module 스캐폴드 및 RED 테스트

complexity: medium

`bluetape4k-code-patterns`, `ecc-kotlin-exposed`, `ecc-springboot-kotlin`,
`ecc-kotlin-testing`을 적용한다.

- [ ] 다음 dependency로 `build.gradle.kts`를 생성한다:

```kotlin
plugins {
    kotlin("plugin.spring")
    application
}

application {
    mainClass.set("io.bluetape4k.exposed.examples.modulith.DddSpringModulithDemoApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(platform(libs.spring.modulith.bom))

    implementation(project(":bluetape4k-exposed-core"))
    implementation(project(":bluetape4k-exposed-spring-boot-jdbc"))
    implementation(project(":bluetape4k-exposed-spring-modulith"))

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.spring7.transaction)
    implementation(libs.hikaricp)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation(libs.spring.modulith.events.jackson)

    runtimeOnly(libs.h2.v2)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(libs.awaitility.kotlin)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
}
```

- [ ] 기존 example resource를 기준으로 test resource를 추가한다:
  - `junit.jupiter.execution.parallel.enabled=false`
  - `junit.jupiter.testinstance.lifecycle.default=per_class`
  - `io.bluetape4k.exposed.examples.modulith` 및 Exposed용 logger.
- [ ] 테스트를 작성하기 전에 최소 invalid-boundary test fixture 골격을 생성한다:
  - `io.bluetape4k.exposed.examples.modulithinvalid.InvalidBoundaryApplication`
  - invalid root 아래에 충분히 유효한 `orders` 및 `shipping` module metadata
  - `orders.internal` type을 import하는 invalid `shipping` type 하나
  - fixture를 `io.bluetape4k.exposed.examples.modulith` 바깥에 둔다.
  - Gradle은 `--tests` filter를 적용하기 전에 모든 test source를 compile하므로,
    첫 RED 실행부터 이 fixture가 compile되어야 한다.
  - Spring Modulith가 test-output package를 직접 scan하지 못하면 sibling
    `modulithinvalid` package 아래 `src/main/kotlin`에 둔다. 유효한 application
    scan은 계속 `io.bluetape4k.exposed.examples.modulith`에서 시작한다.
- [ ] 먼저 다음 test name으로 `DddSpringModulithDemoApplicationTest`를 작성한다:
  - `application modules allow shipping to depend only on order events`
  - `boundary verifier rejects shipping dependency on order internals`
  - `application context exposes Exposed backed publication repository`
  - `accepting an order persists reservation through Modulith publication`
  - `publication row stores only opaque event data`
  - `duplicate order accepted events keep shipping reservation idempotent`
  - `restart republishes incomplete order event without duplicate reservation`
  - `failed command transaction leaves no order reservation or publication row`
  - `failed handoff keeps aggregate domain events recorded`
- [ ] 정확한 state assertion으로 test를 확장한다:
  - happy path: orders `=1`, shipping reservation `=1`, completed publication row
    `=1`, incomplete/failed row `=0`.
  - duplicate delivery: shipping reservation은 `=1`로 유지한다.
  - restart republication: shipping reservation은 `=1`로 유지하고 publication은
    완료된다.
  - rollback: order row `=0`, shipping row `=0`, publication row `=0`.
  - 필요한 곳에 repository/table `count()` helper를 추가한다.
- [ ] restart republication을 실행 가능하게 만든다:
  - 같은 H2 database name과 동일한 static publication/order/shipping table name을
    사용하는 2-context test를 만든다.
  - 첫 context는 실제 DDD `publishEvent` path로 order를 수락하고 shipping
    reservation 하나를 만든 뒤, publication table helper로 completion date를
    지워 일치하는 publication row를 deterministic하게 incomplete로 되돌린다.
  - 첫 context를 닫는다.
  - 두 번째 context는
    `spring.modulith.events.republish-outstanding-events-on-restart=true`로 시작한다.
  - bounded polling을 사용한 뒤 publication이 완료되고 shipping reservation
    count가 `1`로 유지되는지 단언한다.
- [ ] transaction 및 handoff failure를 실행 가능하게 만든다:
  - rollback test: 실제 `ApplicationEventPublisher`와 `TransactionTemplate`
    path 내부의 test-only `failAfterPublish` transaction probe를 사용한다.
    rollback 후 orders `=0`, shipping reservation `=0`, publication row `=0`인지
    단언한다.
  - handoff-failure test: acceptance 전에 handoff가 실패하면 aggregate의
    `domainEvents()`가 비어 있지 않음을 증명하는 용도로만 예외를 던지는
    `ApplicationEventPublisher`/handoff bean을 사용한다.
- [ ] listener 완료와 publication state 관찰에는 `atMost(5 seconds)`를 사용하는
  Awaitility 또는 동등한 bounded polling을 사용한다.
  - 무제한 sleep을 사용하지 않는다.
  - raw `Thread.sleep`을 사용하지 않는다.
  - `publishEvent` 직후 즉시 read로 listener 효과를 단언하지 않는다.
- [ ] `publication row stores only opaque event data`에서는 서로 다른 `orderKey`,
  `customerId`, secret-like sentinel string을 포함한 input을 수락하고 raw
  publication `SERIALIZED_EVENT`를 조회해 다음을 단언한다:
  - input identifier 또는 sentinel string이 하나도 나타나지 않는다.
  - payload는 `aggregateId`, `eventId`, `occurredAt`로 제한된다.
  - payload에 `@class`, `@type` 또는 polymorphic type marker가 없다.
- [ ] bluetape4k assertion만 사용한다:
  - `assertFailsWith<Violations> { ... }`
  - `actual shouldBeEqualTo expected`
  - `collection shouldHaveSize n`
  - `value.shouldNotBeNull()`
- [ ] Run RED:

```bash
./gradlew :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain
```

예상 결과: production symbol이 없거나 구현되지 않았기 때문에 실패한다.

## 작업 2: 유효한 Modulith application 구현

complexity: medium

- [ ] auto-configuration과 Exposed Modulith property를 활성화한 Spring Boot
  application root로 `DddSpringModulithDemoApplication.kt`를 추가한다.
- [ ] `orders/ModuleMetadata.kt`를 추가한다:

```kotlin
package io.bluetape4k.exposed.examples.modulith.orders

import org.springframework.modulith.PackageInfo

@PackageInfo
class ModuleMetadata
```

- [ ] `@NamedInterface("events")`가 있는 `orders/events/ModuleMetadata.kt`를
  추가한다.
- [ ] 다음 내용으로 `shipping/ModuleMetadata.kt`를 추가한다:

```kotlin
@ApplicationModule(allowedDependencies = ["orders :: events"])
@PackageInfo
class ModuleMetadata
```

- [ ] `orders/OrderDomain.kt`에 `OrderId`, `AcceptOrderCommand`, `Order`,
  `OrderStatus`를 구현한다.
  - data/value class는 `Serializable`을 구현하고 `serialVersionUID`를 정의한다.
  - command input validation에는 `requireNotBlank`을 사용한다.
  - `Order`는 `AbstractAggregateRoot<OrderId>`를 확장한다.
  - `Order.accept(...)`는 `OrderAcceptedEvent`를 기록한다.
- [ ] `orders.events`에 `aggregateId`, `eventId`, `occurredAt`만 포함하는
  `OrderAcceptedEvent`를 구현한다.
- [ ] Exposed table과 다음 repository method를 갖는
  `orders.internal.OrderRepository`를 구현한다:
  - `createSchema()`
  - `deleteAll()`
  - `save(order: Order)`
  - `findByOrderId(orderId: OrderId)`
- [ ] `OrderApplicationService`를 구현한다:
  - transaction boundary에는 `TransactionTemplate`을 사용한다.
  - `domainEvents()`로 event를 snapshot한다.
  - aggregate를 persist한다.
  - 각 event를 `ApplicationEventPublisher`를 통해 publish한다.
  - service shape은 `TransactionTemplate` callback 안에서 snapshot, persist,
    publish를 수행하고 callback이 성공적으로 완료된 뒤에만 반환한다. aggregate
    event buffer는 transaction result path 바깥에서 비운다.
  - transaction 또는 handoff가 실패하면 event를 기록된 상태로 둔다.

## 작업 3: shipping listener 및 publication wiring 구현

complexity: medium

- [ ] Exposed table과 order id를 key로 하는 idempotent insert를 갖는
  `shipping.internal.ShippingReservationRepository`를 구현한다.
  - `order_id`는 primary 또는 unique key다.
  - 가능하면 `insertIgnore`/upsert을 사용하고, 그렇지 않으면 SQLState `23xxx`
    duplicate-key failure를 성공적인 idempotent handling으로 처리한다.
  - 같은 `OrderAcceptedEvent`로 handler/repository를 두 번 호출하고 row count
    `1`을 단언해 직접 duplicate handling을 테스트한다.
- [ ] `ShippingReservationHandler`를 구현한다:
  - `@ApplicationModuleListener(id = "shipping.reserve-order")`
  - Handles `OrderAcceptedEvent`.
  - Inserts at most one reservation per order id.
- [ ] application configuration bean을 구현한다:
  - `DataSource`.
  - `springTransactionManager`.
  - `OrderAcceptedEvent` 전용의 좁은 `EventSerializer`.
  - order 및 shipping table용 schema initializer.
- [ ] `OrderAcceptedEvent` serializer는 다음 조건을 만족해야 한다:
  - deserialization에는 `OrderAcceptedEvent::class.java`만 허용한다.
  - type metadata가 없는 deterministic JSON을 출력한다.
  - 알 수 없는 event type/class를 거부한다.
  - `activateDefaultTyping`, default typing, unsafe polymorphic configuration을
    명시적으로 사용하지 않는다.
- [ ] example에는 static safe table name을 사용하고 일반 test isolation에는
  unique H2 database name을 사용한다.
- [ ] test database isolation은 필수다:
  - 일반 test는 static safe table name과 unique H2 database name을 사용한다.
  - restart republication test는 같은 H2 database name/table name을 재사용하고
    `DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`를 포함한다.
  - test helper가 order, shipping reservation, publication, archive table의
    cleanup/drop을 소유한다.
- [ ] implementation/test에서는 bounded async observation이 필수다:
  - `await.atMost(Duration.ofSeconds(5)).untilAsserted { ... }` 또는 동등한
    latch helper를 사용한다.
  - reservation insert와 publication completion을 모두 기다린다.
  - `publishEvent` 직후 즉시 read한다고 가정하지 않는다.
- [ ] lifecycle cleanup:
  - `.use {}` 또는 try/finally로 Spring context를 닫는다.
  - `@BeforeEach` 또는 `@AfterEach`에서 listener probe/latch를 reset한다.
  - Spring context shutdown을 통해 Hikari `DataSource`를 닫는다.
  - table state cleanup은 test helper의 책임이다.
- [ ] 유효한 app의 event/publication subset만 GREEN인지 검증한다. invalid
  fixture가 존재하기 전에는 full module GREEN을 기다린다:

```bash
./gradlew :examples-ddd-spring-modulith-demo:test --tests '*accepting an order persists reservation through Modulith publication' --no-configuration-cache --no-daemon --console=plain --rerun-tasks
```

## 작업 4: invalid boundary fixture 완성

complexity: low

- [ ] boundary verifier assertion에 필요한 경우에만 작업 1의 invalid fixture를
  완성하거나 조정한다.
- [ ] positive verification이 scan할 수 없도록 invalid root를
  `io.bluetape4k.exposed.examples.modulith` 바깥에 둔다.
- [ ] `orders`와 `internal`을 포함한 `Violations`와 함께 negative test가
  실패하는지 검증한다.
- [ ] invalid fixture가 생긴 뒤 전체 example test suite를 실행한다:

```bash
./gradlew :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain --rerun-tasks
```

## 작업 5: README 및 diagram

complexity: medium

`bluetape4k-diagram`을 적용한다.

- [ ] language switch가 있는 `README.md`와 `README.ko.md`를 추가한다.
- [ ] 다음 절을 포함한다:
  - overview
  - architecture
  - supported / not supported
  - transaction 및 publication boundary
  - direct service/repository call에서의 migration
  - operational diagnostics
  - test 실행
- [ ] supported / not supported에 다음을 명시한다:
  - JDBC-only example.
  - R2DBC 또는 suspend API implementation 없음.
  - exactly-once guarantee 없음.
  - Spring Modulith publication row 외의 durable outbox 없음.
  - stable listener id와 idempotent consumer가 필요함.
  - unloadable event DTO/package rename risk는 application repair의 책임임.
  - cross-module repository 직접 접근 없음.
  - 이 PR에는 benchmark, stress, throughput, latency 주장을 추가하지 않으며,
    검증은 bounded functional integration과 정확한 row-count/state assertion으로
    제한함.
- [ ] security/operation guidance에 다음을 명시한다:
  - publication table은 app-owned internal state임.
  - publication row에 대한 write access를 제한해야 함.
  - row는 external input channel이 아님.
  - unloadable row는 operator repair data임.
  - event serializer는 unsafe polymorphic/default typing을 피해야 함.
  - `initialize-schema=true`는 H2/sample/local 전용이며 production DDL은
    Flyway, Liquibase 또는 동등한 migration이 소유함.
- [ ] migration guidance에 before/after path를 제시한다:
  - before: shipping에서 order repository/service internal로 직접 호출.
  - after: repository는 internal로 유지하고 `orders.events`만 export하며,
    side effect를 `@ApplicationModuleListener(id = "shipping.reserve-order")`로
    옮기고 `ApplicationModules.verify()`로 검증.
- [ ] 복사해 실행할 수 있는 path를 추가한다:
  - command: `./gradlew :examples-ddd-spring-modulith-demo:test`
  - 확인할 source package: `orders`, `orders.events`, `shipping`,
    `orders.internal`, `shipping.internal`
  - 예상 결과: order 하나, exported event 하나, publication row transition 하나,
    shipping reservation 하나.
- [ ] root README 행을 추가한다:
  - `README.md`: `examples-ddd-spring-modulith-demo`
  - `README.ko.md`: 이에 대응하는 Korean 항목
  - verification command: `./gradlew :examples-ddd-spring-modulith-demo:test`
- [ ] 새 example로 연결되는 See Also link를
  `spring-boot/spring-modulith/README.md`와 `README.ko.md`에 추가한다.
- [ ] `docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.svg`를
  생성한다.
  - module, named interface, table, publication store, 번호가 매겨진
    transaction/publication/listener/completion flow를 표시한다.
- [ ] 렌더링한 PNG를 새 README 양쪽에 embed하고 relative path를 검증한다.
- [ ] full-size PNG inspection에서 다음을 확인해야 한다:
  - label overflow가 없음.
  - connector/card collision이 없음.
  - lane/title spacing을 읽을 수 있음.
  - diagram이 실제 module, named interface, table, publication store,
    retry/incomplete path를 명명함.
- [ ] diagram을 검증한다:

```bash
xmllint --noout docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.svg
/Users/debop/.local/bin/cairosvg docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.svg -o docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.png -s 2
```

- [ ] 생성한 PNG를 full size로 검사한다.

## 작업 6: CI 및 Nightly 등록

complexity: low

- [ ] `.github/workflows/ci.yml`을 수정한다:
  - `:examples-ddd-spring-modulith-demo:test` 추가
  - `:examples-ddd-spring-modulith-demo:koverXmlReport` 추가
- [ ] 같은 task를 추가하도록 `.github/workflows/nightly-tests.yml`을 수정한다.
- [ ] 관련 없는 workflow job은 건드리지 않는다.
- [ ] 다음을 검증한다:

```bash
actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml
rg -n ":examples-ddd-spring-modulith-demo:(test|koverXmlReport)" .github/workflows/ci.yml .github/workflows/nightly-tests.yml
```

## 작업 7: 전체 검증

complexity: medium

- [ ] 다음을 실행한다:

```bash
./gradlew :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain --rerun-tasks
./gradlew :examples-ddd-spring-modulith-demo:build --no-configuration-cache --no-daemon --console=plain --rerun-tasks --warning-mode all
./gradlew projects --no-configuration-cache --no-daemon --console=plain
git diff --check
```

- [ ] `./gradlew projects`에 `:examples-ddd-spring-modulith-demo`가 표시되는지
  확인한다.
- [ ] public docs가 실제 source name과 grep-match되는지 확인한다:

```bash
rg -n "shipping.reserve-order|OrderAcceptedEvent|examples-ddd-spring-modulith-demo|EventPublicationRepository" README.md README.ko.md examples/ddd-spring-modulith-demo
rg -n "initialize-schema|Flyway|Liquibase|app-owned|restricted write|unsafe polymorphic|default typing|minimal|non-sensitive|customerId|orderKey|shipping.reserve-order|bluetape4k.exposed.modulith.publications" README.md README.ko.md examples/ddd-spring-modulith-demo/README.md examples/ddd-spring-modulith-demo/README.ko.md
```

- [ ] broad OR grep만 사용하지 말고 새 README file별로 필수 documentation term을
  검증한다:
  - `examples/ddd-spring-modulith-demo/README.md`
  - `examples/ddd-spring-modulith-demo/README.ko.md`
  - 필수 concept: `initialize-schema`, `Flyway` 또는 `Liquibase`, app-owned/internal
    publication table, restricted write access, unsafe polymorphic/default typing,
    minimal non-sensitive payload, `customerId`와 `orderKey` 제외,
    `shipping.reserve-order`, `bluetape4k.exposed.modulith.publications`.
- [ ] 검증 메모: benchmark, stress, throughput, latency 주장을 추가하지 말고,
  정확한 row count/state transition과 bounded functional integration만 검증한다.

## 작업 8: 검토, lessons, commit, PR

complexity: medium

- [ ] spec와 이 plan을 대상으로 Step 5 verifier를 실행한다.
- [ ] 6개 perspective lane과 current-session integration을 포함한 Step 6-R
  code review를 실행한다.
- [ ] `docs/lessons/2026-07-09-issue-316-ddd-spring-modulith-sample.md`를
  추가한다.
- [ ] release readiness scope를 확인한다:
  - Maven publication 변경 없음.
  - BOM/catalog 변경 없음.
  - publish/release workflow 변경 없음.
  - 새 project는 non-published example module로 유지됨.
- [ ] 검증 통과 후 Lore trailer와 함께 commit한다.
- [ ] branch를 push하고 PR을 생성한다:
  - body에 `Fixes #316` 포함
  - PR 생성 전에 `gh issue view 316 --json assignees,labels,milestone`로
    live issue metadata를 읽는다.
  - live issue assignee, milestone, label을 PR에 반영한다.
  - PR body는 `## DoD Status`로 끝난다.
- [ ] live PR metadata와 body를 검증한다:

```bash
gh pr view <number> --json body,assignees,labels,milestone,statusCheckRollup,mergeStateStatus,reviewDecision
gh issue view 316 --json number,state,assignees,labels,milestone,closedByPullRequestsReferences
```

- [ ] PR CI gate:
  - CI Status 성공을 기다리거나 정확한 blocker를 기록한다.
  - `gh pr view <number> --json statusCheckRollup,mergeStateStatus,reviewDecision`를
    검증한다.
  - 명시적인 rationale와 함께 downgrade하지 않는 한 branch에 대해 Nightly full을
    dispatch한다:

```bash
gh workflow run nightly-tests.yml --ref feat/issue-316-ddd-modulith-sample -f scope=full
gh run view <run-id> --json status,conclusion,jobs,url
```

  - CI check 증거, Nightly dispatch URL/run URL, downgrade rationale(있는 경우)를
    PR `## DoD Status`에 기록한다.

## Rollback / 재실행 지점

- RED test가 missing implementation 때문에 실패하지 않으면 production code보다
  먼저 test를 수정한다.
- Modulith verification이 valid app에서 invalid fixture를 scan하면 invalid
  package root를 더 멀리 옮기고 boundary test만 다시 실행한다.
- workflow YAML validation이 실패하면 중지하고 YAML을 수정한 뒤 Gradle
  CI-equivalent check를 실행한다.
