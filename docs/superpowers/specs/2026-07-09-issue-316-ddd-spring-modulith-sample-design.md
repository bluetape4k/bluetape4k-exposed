# Issue #316 - DDD Spring Modulith Exposed 샘플 설계

## 배경

Issue [#316](https://github.com/bluetape4k/bluetape4k-exposed/issues/316)
은 다음을 결합한 public example을 요구한다:

- bluetape4k Exposed repository를 사용하는 DDD aggregate persistence.
- Spring Modulith application-module boundary.
- `@ApplicationModuleListener`를 사용하는 domain/application event publication.
- `:bluetape4k-exposed-spring-modulith`를 통한 durable publication state.
- 새 public example을 추가할 때 English 및 Korean README documentation.

현재 repository 증거:

- `settings.gradle.kts`는 `examples/` 아래 directory를 자동 등록하므로
  `examples/ddd-spring-modulith-demo`는
  `:examples-ddd-spring-modulith-demo`가 된다.
- `.github/workflows/ci.yml`과 `.github/workflows/nightly-tests.yml`은 명시적인
  Gradle task list로 example test를 실행하므로 새 example을 두
  `test-examples` job과 Kover report command에 모두 추가해야 한다.
- `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/`는 이미
  Spring-neutral `AggregateRoot`, `AbstractAggregateRoot`, `DomainEvent`
  contract를 제공한다.
- `spring-boot/spring-modulith`는 이미 Exposed-backed Spring Modulith
  event-publication repository를 제공하며 실제 `@ApplicationModuleListener`
  path를 테스트한다.
- 관련 `exposed-workshop` issue #145와 PR #157은 positive 및 negative
  `ApplicationModules.verify()` test와 동일한 `orders :: events`
  named-interface pattern을 사용했다.
- Context7 Spring Modulith documentation은
  `ApplicationModules.of(Application::class.java).verify()`,
  `@ApplicationModule(allowedDependencies = "order :: *")`, Kotlin
  `@PackageInfo` metadata class, `@NamedInterface`,
  `@ApplicationModuleListener`를 현재 pattern으로 확인한다.

## 승인된 방향

`examples/ddd-spring-modulith-demo`를 생성한다.

사용자는 #370 merge 후 다음 issue 계획을 승인했다. #326은 이전에 제외 가능성이
질문되었고 더 넓은 Ktor/R2DBC/cache 확장에 해당하므로 계속 deferred 상태다.
#316은 최근 Spring Modulith 및 DDD 작업의 더 좁은 후속 작업이다.

## 아키텍처

이 example은 두 Spring Modulith application module을 사용해 order-to-shipping
handoff를 모델링한다:

- `orders`: order command를 수락하고 aggregate를 생성한 뒤 Exposed로 persist하고
  `OrderAcceptedEvent`를 기록한다.
- `shipping`: `@ApplicationModuleListener(id = "shipping.reserve-order")`로
  `OrderAcceptedEvent`를 수신하고 자체 Exposed table에 idempotent shipping
  reservation을 persist한다.

`orders`에서 export하는 유일한 dependency surface는
`@NamedInterface("events")`로 표시한 `orders.events`다. `shipping` module은
`@PackageInfo`가 붙은 Kotlin `ModuleMetadata` class를 통해
`@ApplicationModule(allowedDependencies = ["orders :: events"])`를 선언한다.

aggregate는 `AbstractAggregateRoot<OrderId>`를 사용하고
`DomainEvent<OrderId>` payload를 emit한다. command service는 Spring transaction
안에서 aggregate를 persist하고 기록된 domain event를 snapshot한 뒤 Spring의
`ApplicationEventPublisher`로 snapshot을 publish한다. aggregate buffer는
transaction이 Spring Modulith publication handoff를 성공적으로 수락한 뒤에만
비운다. `publishEvent(...)` 호출은 Spring Modulith publication recording으로의
handoff이지 일반적인 durable outbox가 아니다.

Exposed Modulith publication repository는 durable listener publication state를
소유한다. listener는 stable listener id를 사용하고 order id로 reservation을
deduplicate해야 restart republication이나 duplicate delivery가 두 번째
reservation을 만들지 않는다. sample config는
`bluetape4k.spring.modulith.exposed.initialize-schema=true`로 schema
initialization을 활성화하고 test-specific publication table name을 사용해
test를 격리한다. 이 flag는 H2 sample/test 실행 전용이다. production deployment는
schema auto-initialization을 비활성화하고 Flyway, Liquibase 또는 동등한 migration
process로 DDL을 관리해야 한다.

## 설계 대안

### 옵션 A - `examples/ddd-spring-modulith-demo` 아래의 새 집중 example

선택.

장점:

- issue #316에 정확히 맞고 기존 public example 옆에서 example을 쉽게 찾을 수
  있다.
- workshop code를 복사하는 대신 현재 repo module을 재사용한다.
- CI와 Nightly가 실행할 명확한 단일 Gradle project를 제공한다.
- Ktor/R2DBC/cache 확장을 이 PR 범위에서 제외한다.

단점:

- 새 example module과 workflow registration surface를 추가한다.
- bilingual README 및 diagram validation이 필요하다.

### 옵션 B - `spring-boot/spring-modulith` test만 확장

거부.

장점:

- diff가 작고 local verification이 빠르다.

단점:

- issue가 요청한 public DDD sample을 만들지 못한다.
- module-boundary guidance가 user-facing documentation이 아닌 library test에
  숨겨진다.
- example registration 및 README convention을 검증하지 못한다.

### 옵션 C - `exposed-workshop` #145 module을 재사용하거나 복사

direct copy는 거부하고 prior art로 일부만 차용한다.

장점:

- 검증된 `orders :: events` boundary shape와 negative verifier fixture가 있다.

단점:

- workshop code는 workshop package name과 dependency를 사용한다.
- 이 repository는 plain Exposed와 Modulith만이 아니라 bluetape4k-exposed DDD
  contract와 `:bluetape4k-exposed-spring-modulith`를 보여 줘야 한다.

## package 및 file 구조

주요 package:

```text
io.bluetape4k.exposed.examples.modulith
```

예상 file:

- `examples/ddd-spring-modulith-demo/build.gradle.kts`
- `examples/ddd-spring-modulith-demo/README.md`
- `examples/ddd-spring-modulith-demo/README.ko.md`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/.../DddSpringModulithDemoApplication.kt`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/.../orders/**`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/.../orders/events/**`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/.../orders/internal/**`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/.../shipping/**`
- `examples/ddd-spring-modulith-demo/src/main/kotlin/.../shipping/internal/**`
- `examples/ddd-spring-modulith-demo/src/test/kotlin/.../DddSpringModulithDemoApplicationTest.kt`
- `examples/ddd-spring-modulith-demo/src/test/kotlin/io/bluetape4k/exposed/examples/modulithinvalid/**`
- `examples/ddd-spring-modulith-demo/src/test/resources/junit-platform.properties`
- `examples/ddd-spring-modulith-demo/src/test/resources/logback-test.xml`
- `docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.svg`
- `docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.png`

## domain model

domain은 의도적으로 작게 유지한다:

- `OrderId`: opaque value object.
- `OrderStatus`: `ACCEPTED`.
- `Order`: aggregate root.
- `AcceptOrderCommand`: input command.
- `OrderAcceptedEvent`: `orders.events`의 exported event이며 field는
  `aggregateId: OrderId`, `eventId: String`, `occurredAt: Instant`다.
- `ShippingReservation`: order id를 key로 하는 consumer-side
  projection/reservation.

규칙:

- `orderKey`와 `customerId`는 non-blank command input으로만 허용한다.
- event payload는 opaque sample identifier와 minimal fact만 전달한다.
  `OrderAcceptedEvent`는 publication table에 customer-facing identifier,
  natural order key, address, email, token, credential, secret, full aggregate
  snapshot을 persist하면 안 된다.
- Exposed table과 repository는 소유 module 내부다.
- valid application의 `shipping`은 `orders.internal`을 import하면 안 된다.
- shipping listener는 idempotent여야 한다. 같은 order id에 대한 반복
  `OrderAcceptedEvent` delivery는 reservation 하나만 남겨야 한다.
- test-only invalid fixture는 `shipping`에서 `orders.internal`을 import하고
  `ApplicationModules.verify()`가 dependency를 거부함을 입증해야 한다.

모든 data class는 `Serializable`을 구현하고 `serialVersionUID`를 정의해야 한다.
example의 reader-facing API에 포함되는 새 public class와 function에는 English
KDoc이 필요하다.

## persistence 및 transaction boundary

sample은 local-first test와 docs를 위해 PostgreSQL compatibility mode의 H2를
사용한다. 이 example에는 external credential이나 Testcontainers가 필요하지
않다.

command service는 Spring transaction 안에서 실행된다:

1. order aggregate를 생성하거나 수락한다.
2. Exposed JDBC repository로 aggregate를 persist한다.
3. `domainEvents()`로 aggregate domain event를 한 번 snapshot한다.
4. Spring `ApplicationEventPublisher`로 snapshot을 publish해 Spring Modulith가
   같은 command transaction에서 listener publication을 기록하게 한다.
5. transaction이 성공적으로 반환된 후 aggregate event buffer를 비운다.
   publish, handoff recording 또는 주변 transaction이 실패하면 caller-owned
   retry/discard handling을 위해 aggregate event buffer를 그대로 유지한다.

의도한 boundary는 명확하다. order persistence와 Spring Modulith publication-row
creation은 command transaction의 일부다. shipping listener side effect는
command transaction이 event를 수락한 뒤 실행되며 command commit의 필수 조건이
되어서는 안 된다. event snapshot 후 commit 전에 command transaction이
rollback하면 order, shipping reservation, publication row가 남지 않음을 test로
입증해야 한다.

Spring Modulith는 `:bluetape4k-exposed-spring-modulith` repository를 통해
publication row를 기록하고 shipping listener를 호출한다. integration test는
Exposed-backed store를 기준으로 shipping reservation과 publication repository
state를 모두 검증한다. happy path에는 exported event 하나와 listener 하나만
있어야 하며 test는 publication state transition 하나를 단언해야 한다. 예상 DB
overhead는 order insert 하나, Spring Modulith가 소유하는 publication
insert/update pair 하나, shipping insert 하나다. listener test는 asynchronous
publication completion 주변에서 bounded waiting을 사용하고, 기존 5 second
local test window를 대상으로 하며, 무제한 sleep이나 즉시 read 대신 thread-safe한
context별 state를 사용해야 한다.

test는 변경 가능한 database state를 모두 격리해야 한다. 허용되는 방법은 Spring
context마다 unique H2 database를 사용하거나 order, shipping reservation,
publication row에 randomized table name을 사용하는 것이다. test resource는 이
module에서 JUnit parallel execution을 비활성화해야 한다. configured table name은
`[A-Z][A-Z0-9_]*`에 맞는 static code/test-owned identifier여야 하며 request
parameter, user input, untrusted environment value에서 파생하면 안 된다.

negative Modulith fixture는 valid application base package 밖의 별도 package
root를 사용해야 한다. 예를 들면
`io.bluetape4k.exposed.examples.modulithinvalid`다. invalid fixture가 valid
application scan을 오염시키지 않도록 positive와 negative verification은 별도의
`ApplicationModules.of(...)` entrypoint를 호출해야 한다.

publication table은 trusted app-owned internal state이며 external input channel이
아니다. README guidance는 publication table에 대한 database write access가
application/migration owner로 제한되고 event serializer가 unsafe
polymorphic/default typing을 피해야 함을 명시해야 한다. example은
`orders.events` 아래의 stable DTO event class 하나를 사용한다.

## build 및 runtime wiring

`examples/ddd-spring-modulith-demo/build.gradle.kts`에는 다음이 포함되어야 한다:

- Spring Boot dependency platform과 Spring Modulith BOM.
- `application` 및 Kotlin Spring plugin.
- `implementation(project(":bluetape4k-exposed-core"))`.
- `implementation(project(":bluetape4k-exposed-spring-boot-jdbc"))`.
- `implementation(project(":bluetape4k-exposed-spring-modulith"))`.

- Exposed JDBC, Java time, Spring transaction, HikariCP, Spring Boot starter
  JDBC, Spring Modulith starter/core/events, H2 runtime.
- Spring Boot test, Spring Modulith test/core,
  `bluetape4k-junit5`, `bluetape4k-assertions`, bounded listener polling에
  필요한 Awaitility용 test dependency.

Spring context는 다음을 노출해야 한다:

- `DataSource`.
- Exposed Modulith auto-configuration과 호환되는 `springTransactionManager`.
- `OrderAcceptedEvent`용 `EventSerializer`.
- `:bluetape4k-exposed-spring-modulith`의 `EventPublicationRepository`.

## 운영 resource 및 runbook

example은 다음 local resource를 소유한다:

| resource | owner | local initialization | cleanup / rollback |
| --- | --- | --- | --- |
| H2 `DataSource` | sample Spring context | Spring Boot test/application property | Spring context를 닫고 context마다 unique database name 사용 |
| Orders table | `orders.internal` repository | sample schema initializer | test cleanup에서 drop/delete; rollback 후 row 없음 |
| Shipping reservations table | `shipping.internal` repository | sample schema initializer | test cleanup에서 drop/delete; idempotent order key가 duplicate 방지 |
| Modulith publication/archive table | `:bluetape4k-exposed-spring-modulith` | H2 sample/test 전용 `initialize-schema=true` | `incomplete`, `completed`, `failed`, `unloadable` row를 query; production은 migration 사용 |

운영 documentation에는 다음이 포함되어야 한다:

- `bluetape4k.spring.modulith.exposed.initialize-schema=true`는 sample/local
  전용이다. production에서는 비활성화하고 migration으로 DDL을 관리해야 한다.
- stable listener id: `shipping.reserve-order`.
- local diagnosis를 위한 publication table/status query guidance.
- Micrometer meter name `bluetape4k.exposed.modulith.publications`와
  `incomplete`, `completed`, `failed`, `unloadable` state.
- failure triage:
  - listener가 호출되지 않음: listener id, module scan, publication row를
    확인한다.
  - publication이 incomplete/failed: publication completion date와 listener
    exception path를 검사한다.
  - unloadable event type: row를 app-owned repair data로 취급하고 class
    name/package migration을 확인하며 신뢰할 수 없는 external row를
    deserialize하지 않는다.

## documentation 및 diagram

새 public example에는 language switch가 있는 `README.md`와 `README.ko.md`를
모두 추가한다. docs는 다음을 설명한다:

- 이 pattern을 사용할 시점.
- DDD, Spring Modulith, Exposed, Exposed Modulith store가 각각 소유하는 boundary.
- example test 실행 방법.
- `orders.events`만 exported named interface인 이유.
- event payload를 minimal하고 non-sensitive하게 유지해야 하는 이유.
- supported/not supported boundary: JDBC-only, R2DBC 또는 suspend API 없음,
  exactly-once guarantee 없음, Spring Modulith publication row 외 durable
  outbox 없음, stable listener id, idempotent consumer, unloadable event
  DTO/package rename risk, cross-module repository 직접 접근 없음.
- direct service/repository call에서의 migration: repository는 internal로
  유지하고 `orders.events`만 export하며 shipping side effect를 idempotent
  `@ApplicationModuleListener(id = "...")`로 옮기고
  `ApplicationModules.verify()`로 boundary를 검증한다.

이 example은 API syntax뿐 아니라 cross-module ownership을 다루므로 README
architecture diagram이 필요하다. diagram에는 다음을 표시한다:

- `orders` module ownership.
- exported `orders.events` named interface.
- `shipping` module allowed dependency.
- Exposed order 및 shipping table.
- Exposed-backed Spring Modulith publication table.
- order transaction, publication row creation, listener invocation, completion
  state, retryable/incomplete state의 번호가 매겨진 flow. architecture diagram이
  너무 복잡해지면 두 번째 sequence diagram을 추가해도 된다.

diagram은 `bluetape4k-diagram` 규칙을 따르고 CairoSVG로 SVG를 PNG로 렌더링하며
XML/render/visual inspection 증거를 통과해야 한다.

## workflow 등록

`settings.gradle.kts`가 새 example을 자동 등록하지만 다음 surface는 여전히
명시적인 검증 또는 수정이 필요하다:

- `./gradlew projects`에 `:examples-ddd-spring-modulith-demo`가 표시되어야 한다.
- `.github/workflows/ci.yml`의 `test-examples` task list와 Kover report list에
  새 project가 포함되어야 한다.
- `.github/workflows/nightly-tests.yml`의 `test-examples` task list와 Kover
  report list에 새 project가 포함되어야 한다.
- root `README.md`와 `README.ko.md`에는 example discovery table이 있으므로
  실제 Gradle verification command와 함께 새 DDD/Spring Modulith example
  row를 추가해야 한다.
- example module에는 Maven publication 또는 BOM/catalog constraint가 필요하지
  않다.

## 위험 및 완화

| 위험 | 완화 |
| --- | --- |
| invalid fixture가 import되지 않아 module verification이 통과함 | 별도 invalid application root를 사용하고 `ApplicationModules.of(...).verify()`가 `orders`와 `internal`을 포함한 `Violations`를 던지는지 단언한다. |
| event publication test가 durable publication state가 아닌 listener state만 관찰함 | integration test에서 `EventPublicationRepository` 또는 configured Exposed publication table을 query한다. |
| publication row가 customer data를 노출함 | `OrderAcceptedEvent`를 opaque `OrderId`, `eventId`, `occurredAt`로 제한하고 serialized row에 `customerId`, natural order key, secret-like payload가 없는지 단언한다. |
| publication table을 untrusted input으로 취급함 | app-owned internal state이며 write access가 제한되고 stable DTO serialization만 사용한다고 문서화한다. |
| test schema creation을 production guidance로 오해함 | README에서 `initialize-schema=true`를 H2/sample 전용으로 표시하고 migration-managed production DDL을 권장한다. |
| restart republication이 duplicate shipping row를 생성함 | stable `@ApplicationModuleListener(id = "shipping.reserve-order")`를 사용하고 order id로 reservation을 unique하게 만들며 duplicate delivery/republication을 테스트한다. |
| rollback이 order 없는 publication row를 남김 | event snapshot/publish 시도 후 실패하는 rollback-path test를 추가하고 order, shipping, publication state가 비어 있는지 검증한다. |
| example이 실수로 direct cross-context repository access를 가르침 | repository를 `internal` package에 두고 `orders.events`만 허용된 surface라고 문서화한다. |
| CI가 새 module을 누락함 | CI 및 Nightly Gradle task를 명시적으로 추가하고 `./gradlew projects`로 검증한다. |
| diagram이 source와 drift함 | 이 spec과 source package name으로 diagram을 만들고 PR 전에 PNG를 렌더링하고 검사한다. |

## 인수 기준

- `:examples-ddd-spring-modulith-demo`가 존재하고 Gradle에 등록된다.
- application context가 Exposed-backed `EventPublicationRepository`를 노출한다.
- valid application이 `ApplicationModules.verify()`를 통과한다.
- invalid test fixture가 `orders.internal` dependency 검증에 실패한다.
- order acceptance flow가 order를 persist하고 `OrderAcceptedEvent`를 publish하며
  shipping reservation을 기록하고 Exposed-backed Modulith publication store를
  사용한다.
- happy path는 event 하나, listener 하나, publication row/state transition 하나를
  사용한다. 이 educational example에서는 benchmark와 stress test를 의도적으로
  범위에서 제외한다.
- serialized publication row에는 opaque event data만 있고 customer-facing
  identifier, natural order key, address, email, token, credential, full
  aggregate snapshot이 없다.
- duplicate 또는 restart-republished `OrderAcceptedEvent` delivery 후에도
  shipping reservation은 정확히 하나이고 publication state는 completed다.
- command transaction이 commit 전에 실패하면 rollback-path test가 order,
  shipping reservation, publication row를 남기지 않는다.
- handoff failure 또는 rollback-path test가 transactionally recorded Modulith
  handoff가 수락되기 전에 aggregate event를 clear하지 않음을 입증한다.
- README.md와 README.ko.md가 pattern을 설명하고 rendered diagram에 link한다.
- CI와 Nightly example job에 새 module이 포함된다.
- PR 전에 대상 test/build, workflow syntax, diagram validation, diff hygiene
  증거를 수집한다.

## 검증 계획

- RED test:
  `./gradlew :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain`
- named test:
  - `application modules allow shipping to depend only on order events`
  - `boundary verifier rejects shipping dependency on order internals`
  - `accepting an order persists reservation through Modulith publication`
  - `publication row stores only opaque event data`
  - `duplicate order accepted events keep shipping reservation idempotent`
  - `restart republishes incomplete order event without duplicate reservation`
  - `failed command transaction leaves no order reservation or publication row`
  - `failed handoff keeps aggregate domain events recorded`
- 대상 검증:
  `./gradlew :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain --rerun-tasks`
  `./gradlew :examples-ddd-spring-modulith-demo:build --no-configuration-cache --no-daemon --console=plain --rerun-tasks --warning-mode all`
- 등록:
  `./gradlew projects --no-configuration-cache --no-daemon --console=plain`
- workflow syntax:
  `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
- workflow registration grep:
  `rg -n ":examples-ddd-spring-modulith-demo:(test|koverXmlReport)" .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
- diagram:
  `xmllint --noout docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.svg`
  `/Users/debop/.local/bin/cairosvg docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.svg -o docs/images/readme-diagrams/examples-ddd-spring-modulith-demo-architecture.png -s 2`
- diff hygiene:
  `git diff --check`
