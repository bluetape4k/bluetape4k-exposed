# Issue #323 트랜잭션 인식 도메인 이벤트 발행자 구현 계획

> **에이전트 작업자 안내:** 필수 하위 스킬로 superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans를 사용해 이 계획을 작업 단위로 구현한다. 진행 상태는 체크박스(`- [ ]`) 문법으로 추적한다.

**목표:** 명령 트랜잭션 안에서 애그리거트 도메인 이벤트를 Spring에 전달하고 커밋 완료 후에만 지우는 명시적 Spring Boot JDBC bridge를 추가하며, DDD Spring Modulith 예제로 이 계약을 보여 준다.

**아키텍처:** `spring-boot/jdbc`가 public `ExposedAggregateEventPublisher` 하나와 public 자동 구성 클래스 하나를 담당한다. 발행자 상태는 Spring의 현재 synchronization 목록에서 찾은 private 트랜잭션 synchronization에만 둔다. 발행 전에 애그리거트 identity를 예약하고, 수명주기 위반이 발생하면 커밋을 poison 상태로 만들며, 완료 정리는 애그리거트별로 격리한다. `exposed/core`는 Spring 중립성을 유지하고 예제의 수동 발행 loop는 새 API로 교체한다.

**기술 스택:** Kotlin, Spring Framework 7 트랜잭션 synchronization, Spring Boot 4 자동 구성, JetBrains Exposed 1.3.1, Spring Modulith 2.0.6, H2, JUnit 5, bluetape4k assertion/logging, Logback 테스트 캡처, CairoSVG.

---

## 승인된 기준

- 이슈: `#323`, milestone `1.12.0`, assignee `debop`.
- 설계: `docs/superpowers/specs/2026-07-10-issue-323-domain-event-publisher-design.md`.
- 명세 리뷰: `docs/review/2026-07-10-issue-323-domain-event-publisher-spec-review.md`, 최종 `P0 = 0`, `P1 = 0`.
- 구현 전에 이미 통과한 baseline 명령:
  `./gradlew :bluetape4k-exposed-spring-boot-jdbc:test :bluetape4k-exposed-spring-modulith:test :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain`.
- 이 worktree에서는 CodeGraph를 사용할 수 없다(`0` files/nodes). 아래의 정확한 소스 경로는 저장소를 직접 검사해 확인했다.

## 파일과 소유권 매핑

| 작업 | 쓰기 범위 | 책임 |
|---|---|---|
| 0 | 읽기 전용 workflow 검사, 승인된 명세/계획/리뷰 산출물 | CI/Nightly 커버리지 누락을 조기에 거부하고 승인된 구현 기준을 고정한다. |
| 1 | `exposed/core/src/main/.../ddd/**`, `exposed/core/src/test/.../ddd/AbstractAggregateRootTest.kt` | Spring 중립 이벤트 불변성/reference 계약을 고정하고 문서화한다. |
| 2-4 | `spring-boot/jdbc/src/main/.../ddd/ExposedAggregateEventPublisher.kt`, `spring-boot/jdbc/src/test/.../ddd/ExposedAggregateEventPublisherTest.kt` | 트랜잭션 수명주기, poison 의미, correlation, 완료 logging을 구현하고 테스트한다. |
| 5 | JDBC 저장소 구성 extension/기반 구현, 애그리거트 발행자 자동 구성/import, 관련 테스트 | 기존 `transactionManagerRef` 계약을 실행 가능하게 만들고 보호된 기본 발행자 등록을 추가한다. |
| 6 | 작업 6에 나열된 `examples/ddd-spring-modulith-demo/**` | 수동 발행을 교체하고 통합/serializer 테스트를 확장한다. |
| 7 | README 로케일 쌍, `CHANGELOG.md`, 수명주기 SVG/PNG | public 동작, migration, 운영, timing을 문서화한다. |
| 8-9 | 리뷰/교훈 산출물만 | 최종 검증을 실행하고 근거를 기록한다. |
| 10 | 명시적 외부 상태 변경 승인 후 GitHub PR/CI 근거 | 병합 전에 라이브 메타데이터, 검사, 커버리지 산출물, 리뷰 thread 종료를 증명한다. |

작업 0은 구현 전 gate다. 작업 2-4는 구현 파일 하나를 공유하므로 순차로 실행해야 한다. 작업 5는 작업 3의 public 클래스에 의존하고, 작업 6은 작업 3과 5에 의존한다. 문서 작업은 API와 예제가 컴파일된 후에만 시작한다. 어떤 작업도 뒤 작업이 만드는 산출물에 의존하지 않는다.

## 작업 0: workflow 커버리지 검증과 승인 기준 고정

복잡도: 낮음
의존성: 승인된 설계와 단계 3-R 계획 리뷰
적용 항목: `bluetape4k-full-feature`, `verification-before-completion`

**파일:**
- 검사: `.github/workflows/ci.yml`
- 검사: `.github/workflows/nightly-tests.yml`
- 검증: 커밋된 승인 명세
- 커밋: 구현 계획과 계획 리뷰 산출물만

- [ ] **단계 1: 승인된 실행 gate와 checklist 적용 여부 기록**

실행 log에 사용자의 명시적인 구현 계획 승인을 인용한다. Full Feature checklist를 만들고 `WF` (workflow), `CL` (change lifecycle), `CG` (Common Gates `CG-01..17`), `A` (Full Feature `A-01..11`), `KT` (Kotlin)를 적용 가능 또는 사용 불가로 근거와 함께 기록한다. 발동한 `KT-TEST`와 `KT-SPR` checklist도 적용 대상으로 표시한다. CodeGraph 사용 가능 여부는 도구 근거로 별도 기록하며 `CG` checklist와 혼동하지 않는다. 아래 모든 작업은 실행 전에 `Action`, `Expected DoD`, `Failure/return point`를 기록하고, 다음으로 넘어가기 전에 명령/파일 근거가 있는 `Step DoD`를 기록한다. 승인이 없거나 적용 가능한 gate가 확인되지 않았으면 변경 전에 중단한다.

- [ ] **단계 2: workflow 커버리지 누락 조기 거부**

소스를 편집하기 전에 두 workflow를 모두 검사하고, `exposed/core/**`, JDBC와 Spring Modulith를 포함하는 `spring-boot/**`, 더 좁은 `spring-boot/jdbc/**`/`spring-boot/spring-modulith/**` 종속 job filter, `examples/**` 변경이 각각 해당 `test`와 `koverXmlReport` 작업을 실행하는 job으로 연결된다는 근거를 기록한다. Nightly에는 경로 filter가 없으므로 core, Spring Boot, Spring Modulith job이 해당 Nightly 범위에서 실행되고 `test-examples`는 weekly/full 범위에서 실행됨을 증명한다. 이 job들이 status/coverage `needs`에 포함되고 Kover XML이 업로드되는지 확인한다. 저장소의 report-only Kover 정책을 유지한다. 이 작업은 강제 coverage 임계값을 도입하지 않고 가시성과 routing을 검증한다.

```bash
actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml
ruby -ryaml -e '
  ARGV.each do |path|
    jobs = YAML.load_file(path).fetch("jobs")
    %w[test-core test-spring-boot test-spring-modulith test-examples coverage-report].each { |job| abort("#{path}: missing #{job}") unless jobs.key?(job) }
    status = path.end_with?("ci.yml") ? "ci-status" : "nightly-status"
    abort("#{path}: missing #{status}") unless jobs.key?(status)
  end
' .github/workflows/ci.yml .github/workflows/nightly-tests.yml
rg -n 'exposed/core/\*\*|spring-boot/\*\*|spring-boot/jdbc/\*\*|spring-boot/spring-modulith/\*\*|examples/\*\*|bluetape4k-exposed-core:(test|koverXmlReport)|bluetape4k-exposed-spring-boot-jdbc:(test|koverXmlReport)|bluetape4k-exposed-spring-modulith:(test|koverXmlReport)|examples-ddd-spring-modulith-demo:(test|koverXmlReport)|needs:|kover.*xml|coverage-' \
  .github/workflows/ci.yml .github/workflows/nightly-tests.yml
```

예상 결과: CI 경로 routing, Nightly 전체 범위 job 포함, 네 모듈의 test/Kover 등록, status/coverage 의존성, 커버리지 업로드 경로에 근거가 있다. 누락된 route가 있으면 작업 1 전에 중단하고 workflow 파일을 소유권 매핑에 추가한 뒤, RED workflow 검증 단계와 최소 workflow 수정을 담당 구현 작업에 넣는다.

- [ ] **단계 3: 리뷰된 구현 기준 고정**

계획 리뷰에 최종 `P0 = 0`, `P1 = 0`, 모든 P2/P3 해결 내용, 리뷰한 정확한 plan/spec blob ID가 기록됐는지 확인한다. 승인된 명세가 branch 이력에 이미 고정됐는지 검증한다. 계획/리뷰 파일은 새 파일이므로 추적되지 않은 내용까지 포함하는 범위 검사를 실행하고 Lore commit 하나로 커밋한 뒤 작업 1 전에 커밋된 blob ID를 검증한다.

```text
docs/superpowers/plans/2026-07-11-issue-323-domain-event-publisher-plan.md
docs/review/2026-07-11-issue-323-domain-event-publisher-plan-review.md
```

```bash
git hash-object docs/superpowers/specs/2026-07-10-issue-323-domain-event-publisher-design.md
git hash-object docs/superpowers/plans/2026-07-11-issue-323-domain-event-publisher-plan.md
check_new_file() {
  set +e
  output="$(git diff --no-index --check /dev/null "$1" 2>&1)"
  diff_status=$?
  set -e
  test "$diff_status" -eq 1 || { printf '%s\n' "unexpected diff status $diff_status: $1" "$output"; return 1; }
  test -z "$output" || { printf '%s\n' "$output"; return 1; }
}
set -e
check_new_file docs/superpowers/plans/2026-07-11-issue-323-domain-event-publisher-plan.md
check_new_file docs/review/2026-07-11-issue-323-domain-event-publisher-plan-review.md
git add -- \
  docs/superpowers/plans/2026-07-11-issue-323-domain-event-publisher-plan.md \
  docs/review/2026-07-11-issue-323-domain-event-publisher-plan-review.md
git diff --cached --check
git commit \
  -m 'docs: make transaction-safe event handoff executable' \
  -m $'Constraint: Transactional listeners require in-transaction publication with committed completion cleanup.\nRejected: Publish from afterCommit | transactional listener registration is already closed\nRejected: Make Kover workflow fail-closed | workspace policy keeps coverage report-only; issue evidence verifies non-empty XML locally and in PR artifacts\nConfidence: high\nScope-risk: broad\nDirective: Execute the reviewed plan in dependency order and rerun affected review lenses after any contract change.\nTested: Plan self-review, 7-Tier plan review, actionlint, workflow routing inspection, spec traceability, git diff --check\nNot-tested: Implementation and post-change module tests'
test "$(git rev-parse HEAD:docs/superpowers/specs/2026-07-10-issue-323-domain-event-publisher-design.md)" = \
  "$(git hash-object docs/superpowers/specs/2026-07-10-issue-323-domain-event-publisher-design.md)"
test "$(git rev-parse HEAD:docs/superpowers/plans/2026-07-11-issue-323-domain-event-publisher-plan.md)" = \
  "$(git hash-object docs/superpowers/plans/2026-07-11-issue-323-domain-event-publisher-plan.md)"
test "$(git rev-parse HEAD:docs/review/2026-07-11-issue-323-domain-event-publisher-plan-review.md)" = \
  "$(git hash-object docs/review/2026-07-11-issue-323-domain-event-publisher-plan-review.md)"
git rev-parse HEAD:docs/superpowers/plans/2026-07-11-issue-323-domain-event-publisher-plan.md
git rev-parse HEAD:docs/review/2026-07-11-issue-323-domain-event-publisher-plan-review.md
git status --short
```

결과 baseline commit SHA를 실행 log에 기록한다. commit은 자기 SHA를 포함할 수 없으므로 리뷰 산출물에는 커밋 전 spec/plan blob을 기록한다. 고정된 spec commit과 이 plan/review commit이 불변 구현 baseline을 이룬다. 이후 계획을 변경하면 소스 작업에 조용히 섞지 말고 영향을 받는 단계 3-R 관점을 다시 실행해 새로 리뷰된 baseline을 만든다.

## 작업 1: Spring 중립 애그리거트 계약 고정

복잡도: 낮음
의존성: 작업 0 PASS와 커밋된 리뷰 기준
적용 항목: `test-driven-development`, `bluetape4k-code-patterns`, `ecc-kotlin-testing`

**파일:**
- 수정: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/AggregateRoot.kt`
- 수정: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/AbstractAggregateRoot.kt`
- 수정: `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/DomainEvent.kt`
- 수정: `exposed/core/src/test/kotlin/io/bluetape4k/exposed/core/ddd/AbstractAggregateRootTest.kt`

- [ ] **단계 1: 안정적인 이벤트 reference 특성 테스트 추가**

이 테스트를 `AbstractAggregateRootTest`에 추가한다.

```kotlin
@Test
fun `domainEvents preserves event object references until clear`() {
    val order = TestOrder(OrderId(1L))
    val placed = OrderPlaced(order.id)
    val confirmed = OrderConfirmed(order.id)
    order.place(placed)
    order.place(confirmed)

    val first = order.domainEvents()
    val second = order.domainEvents()

    (first !== second).shouldBeTrue()
    (first[0] === placed).shouldBeTrue()
    (first[1] === confirmed).shouldBeTrue()
    (second[0] === placed).shouldBeTrue()
    (second[1] === confirmed).shouldBeTrue()
    first shouldBeEqualTo second

    @Suppress("UNCHECKED_CAST")
    (first as MutableList<DomainEvent<OrderId>>).clear()
    val afterMisuse = order.domainEvents()
    afterMisuse shouldHaveSize 2
    (afterMisuse[0] === placed).shouldBeTrue()
    (afterMisuse[1] === confirmed).shouldBeTrue()
}
```

이는 특성화 테스트이며 KDoc 편집 전에도 통과해야 한다. production 동작 변경은 필요하지 않다.

- [ ] **단계 2: 대상 core 테스트 실행**

실행:

```bash
./gradlew :bluetape4k-exposed-core:test \
  --tests 'io.bluetape4k.exposed.core.ddd.AbstractAggregateRootTest' \
  --no-configuration-cache --no-daemon --console=plain
```

예상 결과: PASS. 스냅숏은 같은 이벤트 객체 reference를 담되 호출자에게 보이는 서로 다른 `List` 인스턴스이며 호출자 변경으로부터 격리됨을 증명한다.

- [ ] **단계 3: Spring 의존성을 추가하지 않고 영어 KDoc 갱신**

public 계약 세 개를 다음 내용으로 갱신한다.

```text
AggregateRoot.domainEvents(): side-effect-free immutable snapshot, recording order and event object references stable until clear.
AggregateRoot.clearDomainEvents(): forbidden between ExposedAggregateEventPublisher registration and transaction completion.
AggregateRoot.drainDomainEvents(...): incompatible with that bridge because it clears before completion.
AbstractAggregateRoot: single command/transaction owner, no concurrent or overlapping REQUIRES_NEW reuse.
DomainEvent: payload must be deeply immutable after recording/registration.
```

`exposed/core`에서 Spring 타입을 import하거나 link하지 않는다. KDoc에는 bridge 이름을 일반 코드 텍스트로 적는다.

- [ ] **단계 4: 의존성 경계 검증과 커밋**

실행:

```bash
! rg -n 'import org\.springframework|import org\.springframework\.modulith|org\.javers' \
  exposed/core/src/main exposed/core/build.gradle.kts
./gradlew :bluetape4k-exposed-core:test --no-configuration-cache --no-daemon --console=plain
git diff --check
```

예상 결과: `rg` 명령에 일치 항목이 없고 core는 Spring 및 JaVers 중립성을 유지하며 core 테스트와 diff 검사가 통과한다. 감사 이력, 스냅숏 영속화, JaVers commit 의미는 이 발행자 계약의 명시적인 범위 밖이다.

작업 1 파일만 다음으로 시작하는 Lore 메시지로 커밋한다.

```text
docs: define aggregate event handoff invariants
```

rollback 지점: 이 commit에는 KDoc과 통과하는 특성화 테스트만 있으므로 독립적으로 되돌릴 수 있다.

## 작업 2: RED 트랜잭션 수명주기 테스트 작성

복잡도: 높음
의존성: 작업 1
적용 항목: `test-driven-development`, `bluetape4k-code-patterns`, `ecc-kotlin-exposed`, `ecc-kotlin-testing`

**파일:**
- 생성: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/ddd/ExposedAggregateEventPublisherTest.kt`

- [ ] **단계 1: 결정적 H2 트랜잭션 fixture 추가**

다음 재사용 fixture로 테스트 클래스를 만든다.

```kotlin
package io.bluetape4k.spring.data.exposed.jdbc.ddd

import io.bluetape4k.exposed.core.ddd.AbstractAggregateRoot
import io.bluetape4k.exposed.core.ddd.DomainEvent
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.MDC
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.Serializable
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedAggregateEventPublisherTest {

    private val dataSource: EmbeddedDatabase = EmbeddedDatabaseBuilder()
        .generateUniqueName(true)
        .setType(EmbeddedDatabaseType.H2)
        .build()
    private val transactionManager: PlatformTransactionManager =
        DataSourceTransactionManager(dataSource)
    private val transactionTemplate = TransactionTemplate(transactionManager)
    private val jdbcTemplate = JdbcTemplate(dataSource).also {
        it.execute("CREATE TABLE DOMAIN_EVENT_TEST (ID BIGINT PRIMARY KEY)")
    }

    @AfterEach
    fun cleanup() {
        val synchronizationActive = TransactionSynchronizationManager.isSynchronizationActive()
        try {
            synchronizationActive.shouldBeFalse()
        } finally {
            TransactionSynchronizationManager.clear()
            MDC.clear()
            jdbcTemplate.update("DELETE FROM DOMAIN_EVENT_TEST")
        }
    }

    @AfterAll
    fun shutdownDatabase() {
        dataSource.shutdown()
    }

    @JvmInline
    value class TestId(val value: Long) : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    data class TestEvent(
        override val aggregateId: TestId,
        val sequence: Int,
        override val occurredAt: Instant = Instant.parse("2026-07-11T00:00:00Z"),
    ) : DomainEvent<TestId>, Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    open class TestAggregate(
        override val id: TestId,
    ) : AbstractAggregateRoot<TestId>() {
        fun record(sequence: Int): TestEvent =
            TestEvent(id, sequence).also(::recordDomainEvent)
    }
}
```

이후 테스트에서 추가하는 모든 `AnnotationConfigApplicationContext`, `EmbeddedDatabase`, Logback appender는 수명주기에 안전해야 한다. context는 `use`로 감싸고, database는 `finally`에서 종료하며, 캡처 전에 `appender.start()`를 호출하고 `finally`에서 분리한 뒤 `appender.stop()`을 호출한다. 어떤 테스트도 다음 case에 MDC나 트랜잭션 synchronization을 활성 상태로 남겨서는 안 된다.

- [ ] **단계 2: commit, rollback, 순서, 빈 no-op의 RED 테스트 추가**

다음의 정확한 assertion으로 테스트를 추가한다.

```kotlin
@Test
fun `empty aggregate is a no-op outside a transaction`() {
    val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher { error("must not publish") })

    publisher.publishAfterSave(TestAggregate(TestId(1L)))
}

@Test
fun `commit publishes in order and clears after completion`() {
    val published = mutableListOf<TestEvent>()
    val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher { published += it as TestEvent })
    val aggregate = TestAggregate(TestId(1L)).apply { record(1); record(2) }

    transactionTemplate.executeWithoutResult {
        jdbcTemplate.update("INSERT INTO DOMAIN_EVENT_TEST(ID) VALUES (?)", aggregate.id.value)
        publisher.publishAfterSave(aggregate)
        published.map(TestEvent::sequence) shouldBeEqualTo listOf(1, 2)
        aggregate.domainEvents() shouldHaveSize 2
    }

    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM DOMAIN_EVENT_TEST", Long::class.java) shouldBeEqualTo 1L
    aggregate.domainEvents().isEmpty().shouldBeTrue()
}

@Test
fun `rollback preserves events and rolls back persistence`() {
    val published = mutableListOf<TestEvent>()
    val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher { published += it as TestEvent })
    val aggregate = TestAggregate(TestId(2L)).apply { record(1) }

    transactionTemplate.executeWithoutResult { status ->
        jdbcTemplate.update("INSERT INTO DOMAIN_EVENT_TEST(ID) VALUES (?)", aggregate.id.value)
        publisher.publishAfterSave(aggregate)
        status.setRollbackOnly()
    }

    published shouldHaveSize 1
    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM DOMAIN_EVENT_TEST", Long::class.java) shouldBeEqualTo 0L
    aggregate.domainEvents() shouldHaveSize 1
}

@Test
fun `event-bearing aggregate requires synchronization and an actual transaction`() {
    val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
    val aggregate = TestAggregate(TestId(3L)).apply { record(1) }

    assertFailsWith<IllegalStateException> {
        publisher.publishAfterSave(aggregate)
    }

    aggregate.domainEvents() shouldHaveSize 1
}

@Test
fun `synchronization without an actual transaction is rejected`() {
    val publisher = ExposedAggregateEventPublisher(ApplicationEventPublisher {})
    val aggregate = TestAggregate(TestId(4L)).apply { record(1) }

    TransactionSynchronizationManager.initSynchronization()
    try {
        assertFailsWith<IllegalStateException> {
            publisher.publishAfterSave(aggregate)
        }
    } finally {
        TransactionSynchronizationManager.clearSynchronization()
    }

    aggregate.domainEvents() shouldHaveSize 1
}
```

rollback 테스트는 기록용 발행자를 통해 즉시 Spring에 전달되는 동작을 의도적으로 관찰한다.

- [ ] **단계 3: 구현 전에 실제 `AFTER_COMMIT` listener 테스트 추가**

고유한 H2 `EmbeddedDatabase`, `DataSourceTransactionManager`, `TransactionTemplate`, `@TransactionalEventListener`가 붙은 `AfterCommitListener` bean을 정의하는 중첩 `@Configuration(proxyBeanMethods = false)`와 `@EnableTransactionManagement`를 추가한다. 새로 갱신한 context를 명시적으로 사용한다.

```kotlin
AnnotationConfigApplicationContext(ListenerTestConfiguration::class.java).use { context ->
    val transactionTemplate = context.getBean(TransactionTemplate::class.java)
    val listener = context.getBean(AfterCommitListener::class.java)
    val publisher = ExposedAggregateEventPublisher(context)
    // Commit case: listener size is 0 inside and 1 after return; buffer clears.
    // Rollback case in a fresh context: listener remains 0; buffer remains.
}
```

commit과 rollback은 새 context/database를 사용하는 독립 테스트로 유지한다. context 자체가 `ApplicationEventPublisher`이므로 `getBean(ApplicationEventPublisher::class.java)`를 호출하지 않는다. 모든 context는 `use`로 닫는다.

- [ ] **단계 4: RED 테스트 실행**

실행:

```bash
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.ddd.ExposedAggregateEventPublisherTest' \
  --no-configuration-cache --no-daemon --console=plain
```

예상 결과: 실제 `AFTER_COMMIT` 테스트를 포함해 `ExposedAggregateEventPublisher`가 없으므로 Kotlin 컴파일에서 FAIL한다.

RED 상태만으로는 커밋하지 않는다.

## 작업 3: 최소 트랜잭션 안전 발행자 구현

복잡도: 높음
의존성: 작업 2 RED 근거
적용 항목: `test-driven-development`, `bluetape4k-code-patterns`, `ecc-kotlin-exposed`

**파일:**
- 생성: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/ddd/ExposedAggregateEventPublisher.kt`
- 수정: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/ddd/ExposedAggregateEventPublisherTest.kt`

- [ ] **단계 1: 최소 public 발행자와 호출별 완료 synchronization만 추가**

집중된 파일 하나에 다음 형태로 구현한다.

```kotlin
class ExposedAggregateEventPublisher(
    private val applicationEventPublisher: ApplicationEventPublisher,
) {
    fun <ID : Any> publishAfterSave(aggregate: AggregateRoot<ID>) {
        val events = aggregate.domainEvents()
        if (events.isEmpty()) return

        check(TransactionSynchronizationManager.isSynchronizationActive()) {
            "Domain event handoff requires active transaction synchronization"
        }
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Domain event handoff requires an actual active transaction"
        }

        TransactionSynchronizationManager.registerSynchronization(
            MinimalAggregateCompletionSynchronization(aggregate)
        )
        events.forEach(applicationEventPublisher::publishEvent)
    }
}
```

내부 구현은 다음을 사용해야 한다.

```kotlin
private class MinimalAggregateCompletionSynchronization(
    private val aggregate: AggregateRoot<*>,
) : TransactionSynchronization {
    override fun afterCompletion(status: Int) {
        if (status == TransactionSynchronization.STATUS_COMMITTED) {
            aggregate.clearDomainEvents()
        }
    }
}
```

작업 3 구현은 의도적으로 작업 2의 RED case만 지원한다. 즉 빈 no-op, 활성 트랜잭션 검사, 즉시 순서 보장 전달, commit 시 비움, rollback 시 보존, 실제 기본 `AFTER_COMMIT` 시점이다. 아직 identity 예약, synchronization 재사용, 변경 검증, poison 상태, 완료 logging, correlation 캡처, 최종 수명주기 KDoc을 추가하지 않는다. 이 변경은 RED 근거 이후 작업 4에서 수행한다.

작업 3에서는 한 줄짜리 영문 요약 KDoc만 추가한다. 작업 4에서 최종 계약과 실행 가능한 예제로 교체한다.

```kotlin
/** Hands aggregate domain events to Spring inside the current command transaction. */
```

- [ ] **단계 2: GREEN 검증 실행**

작업 2와 같은 집중 테스트 명령을 실행한다.

예상 결과: 빈 항목, commit, rollback, 순서, 활성 트랜잭션, 실제 `AFTER_COMMIT` listener 테스트가 PASS한다.

- [ ] **단계 3: 최소 수명주기 구현 커밋**

`git diff --check`를 실행한 뒤 발행자와 해당 테스트만 다음으로 시작하는 Lore 메시지로 커밋한다.

```text
feat: hand aggregate events to Spring transactions
```

rollback 지점: 이 commit을 되돌리면 자동 구성이나 예제에 영향을 주지 않고 새 API만 제거된다.

## 작업 4: identity, poison, 완료, logging 의미 강화

복잡도: 높음
의존성: 작업 3
적용 항목: `test-driven-development`, `bluetape4k-code-patterns`, `ecc-kotlin-testing`

**파일:**
- 수정: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/ddd/ExposedAggregateEventPublisher.kt`
- 수정: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/ddd/ExposedAggregateEventPublisherTest.kt`

- [ ] **단계 1: RED 수명주기 및 fail-closed 테스트 추가**

승인된 각 case의 테스트를 추가한다.

```text
same aggregate registered twice -> second call throws, publishes no second snapshot, beforeCommit rejects commit
manual clear or drain after registration followed by second call -> reserved identity rejects before empty no-op
new event recorded after registration -> beforeCommit rejects by size/reference mismatch and buffer remains
ApplicationEventPublisher throws -> original exception propagates and caught exception still cannot allow commit
ApplicationEventPublisher throws AssertionError -> rethrown object is the original by ===; caller catches it, beforeCommit still rejects commit, persistence rolls back, buffer remains
ApplicationEventPublisher throws on the second event -> the first synchronous handoff is observable, commit still fails, buffer remains
synchronous listener re-enters with same aggregate -> identity was already reserved, no recursion, commit poisoned
throwing default AFTER_COMMIT listener -> command callback count 1, listener count 1, transaction call returns without retry, row count 1, buffer empty
three pre-existing ordered sentinel synchronizations plus multiple aggregates -> exactly one publisher synchronization, reused for all aggregates
snapshot instrumentation -> internal same-module accessor returns the exact domainEvents result object by identity; no publisher-side toList/map/filter/sorted copy
normal counting aggregate -> domainEvents call count is 1 after registration and 2 after beforeCommit
duplicate call -> domainEvents call count remains 1 and no second publication occurs
two aggregates in sentinel test -> each domainEvents count follows 1 after registration to 2 after beforeCommit
commit then same-thread next transaction -> new synchronization, no duplicate reservation, new event commits
rollback then same-thread next transaction -> retained aggregate registers with a new synchronization and commits
inner REQUIRES_NEW commit plus outer rollback -> inner row commits/buffer clears; outer row rolls back/buffer remains
inner REQUIRES_NEW rollback plus outer commit -> inner row absent/buffer remains; outer row commits/buffer clears
both REQUIRES_NEW cases -> inner synchronization differs from suspended outer synchronization and the original outer synchronization is restored
same aggregate instance across overlapping REQUIRES_NEW -> documented unsupported; no supporting test path is added
```

같은 테스트 파일에서 counting/throwing 애그리거트 subclass를 사용한다. Poison과 변경 테스트는 `TransactionTemplate.executeWithoutResult`를 `assertFailsWith<IllegalStateException>`로 감싼다. Spring은 synchronization의 안정적인 `IllegalStateException`을 `beforeCommit`에서 전파한다.

`Registration`과 읽기 전용 `internal fun retainedSnapshotForTest(aggregate: AggregateRoot<*>): List<*>?` accessor는 JDBC 모듈 내부로 유지한다. 스냅숏 보존 테스트는 accessor 결과와 계측된 애그리거트 스냅숏을 `===`로 비교한다. public 테스트 hook은 추가하지 않는다.

- [ ] **단계 2: RED 완료 및 구조화 log 테스트 추가**

발행자 구현 logger에 Logback `ListAppender<ILoggingEvent>`를 연결하고 다음을 검증한다.

```text
STATUS_COMMITTED + clear failure -> other aggregates still clear, one aggregate-event-cleanup-failed error row
STATUS_UNKNOWN -> no aggregate clears, one aggregate-event-completion-unknown row per registration
rollback -> no clear and no completion anomaly row
registration-time traceId/spanId/requestId retained even if caller MDC changes before completion
empty value rejected; exactly 128 allowed characters accepted; 129 rejected
newline, tab, control, Unicode, whitespace, and disallowed punctuation rejected
traceId, spanId, and requestId each accepted independently; every non-allowlisted key excluded
log MDC contains only category, aggregateType, eventType, eventCount, and valid correlation keys even when completion-time MDC contains arbitrary values
captured ILoggingEvent has null throwableProxy, absent arguments and markers, and only approved key/value fields
message, formatted message, MDC, arguments, key/value fields, and throwable proxy exclude payload, aggregate ID, throwable message, raw headers, arbitrary MDC, secret markers, and PII markers
caller MDC is restored after each anomaly log
```

`eventType`은 애그리거트 등록 하나에 대해 기록 순서를 유지하고 중복을 제거한 fully qualified 이벤트 클래스 이름의 쉼표 구분 집합으로 정의한다. anomaly row를 방출할 때만 `LinkedHashSet`으로 O(E) 단일 pass에서 지연 생성하며 정렬하지 않는다. `eventCount`는 보존한 스냅숏 크기이고 `aggregateType`은 애그리거트의 fully qualified 클래스 이름이다. 정상 발행, commit, rollback에서는 event-type metadata를 할당하거나 순회해서는 안 된다.

- [ ] **단계 3: RED 수명주기와 logging 테스트 실행**

production 코드를 변경하기 전에 작업 4의 대상 테스트 클래스를 실행한다.

```bash
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.ddd.ExposedAggregateEventPublisherTest' \
  --no-configuration-cache --no-daemon --console=plain
```

예상 결과: 표적 실패를 통해 중복/변경 검사, 잡힌 `Exception`과 `AssertionError`의 poisoning/identity, 정확한 `1 -> 2` 스냅숏 횟수, `REQUIRES_NEW` 격리, 완료 logging, correlation 검증, sentinel synchronization 재사용, 스냅숏 보존이 아직 구현되지 않았음을 증명한다. 실패한 테스트 이름을 기록한다. 어느 앞선 작업이 동작을 제공했는지 설명하지 않은 compile-only 또는 이미 GREEN인 결과는 인정하지 않는다.

- [ ] **단계 4: identity 예약과 poison 동작 구현**

다음의 정확한 연산 순서를 사용한다.

```text
if synchronization active -> find current owner synchronization -> reject reserved identity
obtain domainEvents snapshot -> return if empty
require synchronization active -> require actual transaction active
find or register owner synchronization -> reject reserved identity again
capture allowlisted correlation -> reserve identity -> publish events in order
publication Throwable -> poison -> rethrow the same instance
beforeCommit -> throw stored poison first -> verify every snapshot by size and element identity
```

`rejectReserved`는 예외를 던지기 전에 안정적인 poison 사유를 저장한다. 반복 호출에서는 `domainEvents()`를 다시 호출해서는 안 된다. synchronization 조회는 `TransactionSynchronizationManager.getSynchronizations()`만 순회하고 발행자 소유자를 `===`로 비교한다. `bindResource`를 호출하거나 빈 전역 map을 유지하지 않는다.

작업 3의 최소 synchronization을 발행자/현재 트랜잭션마다 하나의 `internal AggregateEventTransactionSynchronization`으로 교체한다. 이 객체는 `IdentityHashMap<AggregateRoot<*>, Registration>`, poison 상태, 완료 정리를 소유한다. `Registration`은 발행자 복사 없이 정확한 불변 스냅숏 객체, 애그리거트 reference/class, 검증/clear lambda, 등록 시점 correlation을 보존한다. 읽기 전용 내부 스냅숏 accessor는 같은 모듈의 identity 테스트에만 둔다.

`currentSynchronization()`은 synchronization이 비활성일 때 `getSynchronizations()`를 한 번도 호출하지 않고, 활성일 때 정확히 한 번 호출해야 한다. 반환된 list를 cache하고 모든 이벤트/등록 loop 밖에서 scan한다. sentinel 테스트는 이 제한된 scan을 증명하고 소스 검사는 cache된 연산 순서를 확인한다.

임시 KDoc을 최종 영문 계약으로 교체한다. 같은 트랜잭션에서의 save/handoff, 빈 no-op, 활성 트랜잭션 검사, 즉시 동기 실행과 기본 `AFTER_COMMIT` 시점 비교, commit 시 비움/rollback 시 보존, poison과 마지막 단일 호출 규칙, 불변 이벤트 reference, 지원하지 않는 NESTED/savepoint와 동일 인스턴스의 중첩 `REQUIRES_NEW`, listener 쓰기의 `REQUIRES_NEW`, `@throws IllegalStateException`을 포함해야 한다. 다음 실행 가능한 사용 block을 포함한다.

```kotlin
/**
 * Publishes an aggregate's immutable domain-event snapshot inside the current command transaction.
 *
 * Save the aggregate and call this method exactly once in the same active transaction:
 * ```kotlin
 * transactionTemplate.executeWithoutResult {
 *     orderRepository.save(order)
 *     aggregateEventPublisher.publishAfterSave(order)
 * }
 * ```
 * Empty aggregates are a no-op. Synchronous listeners run immediately; default `AFTER_COMMIT` listeners run
 * only after commit. Committed completion clears the registered buffer, while rollback or unknown completion
 * preserves it. Publication, duplicate registration, or snapshot mutation poisons the transaction even when
 * caller code catches the immediate failure. Event instances and payloads must remain deeply immutable.
 *
 * `PROPAGATION_NESTED`/savepoint rollback and same-instance reuse across overlapping `REQUIRES_NEW` transactions
 * are unsupported. Listener database writes after commit require a new transaction.
 *
 * @throws IllegalStateException when the transaction, identity, or snapshot lifecycle contract is violated.
 */
```

해당 KDoc 안의 실행 가능한 block은 다음과 같다.

```kotlin
transactionTemplate.executeWithoutResult {
    orderRepository.save(order)
    aggregateEventPublisher.publishAfterSave(order)
}
```

KDoc은 실패를 구분해야 한다. 동기 발행 실패는 같은 `Throwable`로 즉시 다시 던진다. 호출자 코드가 수명주기/발행 실패를 잡으면 저장된 poison이 `beforeCommit`에서 안정적인 `IllegalStateException`을 발생시켜 commit이 계속 실패한다.

- [ ] **단계 5: 제한된 correlation과 완료 logging 구현**

등록할 때는 다음 키만 캡처한다.

```kotlin
private val correlationKeys = listOf("traceId", "spanId", "requestId")
private val safeCorrelation = Regex("[A-Za-z0-9._:-]{1,128}")
```

`MDC.get(key)?.takeIf(safeCorrelation::matches)`를 사용한다. `errorMdc`는 관련 없는 완료 시점 MDC도 보존하므로 이상 행에 사용하지 않는다. 다음 격리 계약을 따르는 private logging helper 하나를 구현한다.

```kotlin
private inline fun withSanitizedMdc(
    fields: Map<String, String>,
    block: () -> Unit,
) {
    val previous = MDC.getCopyOfContextMap()
    try {
        MDC.clear()
        fields.forEach(MDC::put)
        block()
    } finally {
        MDC.clear()
        if (previous != null) {
            MDC.setContextMap(previous)
        }
    }
}
```

이 범위 안에서는 안정적인 category 전용 메시지로 일반 `logger.error(...)`를 호출한다. 포착한 clear 예외나 그 message/cause를 logger에 전달하지 않는다. 테스트는 임의의 완료 시점 MDC가 캡처된 행에 없고 logging 후 복원됨을 증명해야 한다. 각 `ListAppender`는 사용 전에 시작하고 `finally`에서 분리한 뒤 중지한다. 렌더링된 메시지만이 아니라 전체 `ILoggingEvent`를 검사한다. 이벤트 metadata 도출에는 두 이상 분기에서만 도달할 수 있어야 한다.

`afterCompletion`은 다음과 같이 동작해야 한다.

```text
COMMITTED -> attempt every clear independently; log each failure; always discard registry
UNKNOWN -> log every registration; preserve buffers; always discard registry
ROLLED_BACK or other known non-commit -> preserve buffers; always discard registry
```

- [ ] **단계 6: 대상 테스트와 모듈 테스트 실행**

실행:

```bash
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.ddd.ExposedAggregateEventPublisherTest' \
  --no-configuration-cache --no-daemon --console=plain --rerun-tasks
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
  --no-configuration-cache --no-daemon --console=plain
```

예상 결과: 원본 Throwable identity와 정상/중복/sentinel의 정확한 스냅숏 횟수를 포함해 두 명령 모두 PASS한다. sleep, 임시 thread, Testcontainers, 동시 명령 간 공유 애그리거트 인스턴스는 없어야 한다.

- [ ] **단계 7: 강화된 수명주기 커밋**

`git diff --check`를 실행한 다음, 다음으로 시작하는 Lore 메시지로 작업 4의 두 파일을 커밋한다.

```text
test: harden aggregate event transaction lifecycle
```

rollback 지점: synchronization 계약이 호환되지 않는 것으로 확인되면 작업 4와 작업 3을 함께 되돌린다. 너무 일찍 비우거나, 포착된 발행 실패가 커밋되도록 허용하는 불완전한 발행자를 남기지 않는다.

## 작업 5: manager 선택 수정과 보호된 자동 구성 추가

복잡도: 중간
의존성: 작업 4
적용 항목: `test-driven-development`, `bluetape4k-code-patterns`, `ecc-springboot-kotlin`, `ecc-kotlin-testing`

**파일:**
- 생성: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/config/ExposedAggregateEventPublisherAutoConfigurationTest.kt`
- 생성: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/ddd/MultiManagerDocumentationExample.kt`
- 생성: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/config/ExposedAggregateEventPublisherAutoConfiguration.kt`
- 수정: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/config/ExposedJdbcRepositoryConfigurationExtension.kt`
- 수정: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/support/SimpleExposedJdbcRepository.kt`
- 수정: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/config/ExposedSpringDataAutoConfigurationTest.kt`
- 수정: `spring-boot/jdbc/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **단계 1: 기존 production 코드에 대한 RED manager 선택 테스트 작성**

아직 생성하지 않은 발행자 자동 구성을 참조하지 않는 독립적인 `MultiManagerDocumentationExample.kt`를 작성한다. 여기에는 `Orders : LongIdTable`, `OrderEntity : LongEntity`, `OrderRepository : ExposedJdbcRepository<OrderEntity, Long>`, 애그리거트/이벤트, 서로 구분되는 H2 저장소 두 개, Exposed `SpringTransactionManager` 빈 두 개를 정의한다. 두 manager에는 의도적으로 단일 autowire 후보가 없으므로 `ExposedAggregateEventPublisher`를 명시적으로 구성한다.

production 편집 전에 독립적인 RED 근거 두 가지를 추가한다.

1. `ExposedSpringDataAutoConfigurationTest`에서 생성된 repository factory bean definition을 검사하고, `@EnableExposedJdbcRepositories(transactionManagerRef = "secondTransactionManager")`를 사용했을 때 `transactionManager` 속성이 `secondTransactionManager`인지 검증한다.
2. `MultiManagerDocumentationExampleTest`에서 첫 번째와 두 번째 저장소에 서로 다른 행 수를 준비하고, 명시적 트랜잭션 밖에서 `repository.count()`를 호출해 결과가 두 번째 저장소에만 일치하는지 검증한다. 그다음 proxy를 통해 `repository.deleteAll()`을 호출하고 두 번째 저장소만 변경됐는지 검증한다. `OrderEntity.from(...)`이 proxy보다 먼저 실행되고 `save()`가 기존 DAO entity를 반환하므로 `save()`는 manager 선택의 근거가 아니다.

fixture에는 production 형태의 명령 예제도 포함한다.

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
            OrderEntity.from(aggregate)
            aggregateEventPublisher.publishAfterSave(aggregate)
            if (rollback) status.setRollbackOnly()
        }
    }
}
```

성공한 명령 사례는 두 번째 저장소만 커밋되고 버퍼가 비워짐을 증명한다. rollback 사례는 두 번째 쓰기가 사라지고 버퍼가 보존됨을 증명한다. production 형태의 구성/service 구간은 `// issue-323-multi-manager:start/end`로 구분한다. 작업 7은 이 정확한 구간을 복사하고 비교한다. context와 database는 `finally`에서 닫는다.

- [ ] **단계 2: manager 선택 동작 RED만 실행**

```bash
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.config.ExposedSpringDataAutoConfigurationTest' \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.ddd.MultiManagerDocumentationExampleTest' \
  --no-configuration-cache --no-daemon --console=plain
```

예상 결과: Kotlin 컴파일은 성공하지만 기존 annotation 값이 전달되지 않고 기반 저장소가 `springTransactionManager`를 하드 코딩하므로 bean definition 속성 assertion 또는 proxy `count()`/`deleteAll()` 저장소 선택 assertion이 실패한다. 정확히 실패한 assertion을 기록한다. 컴파일 실패는 이 단계의 RED 근거로 인정하지 않는다.

- [ ] **단계 3: manager 선택 테스트만 GREEN으로 전환**

- Spring Data JDBC/JPA extension 패턴을 따라 `ExposedJdbcRepositoryConfigurationExtension`의 annotation-source 후처리 hook을 override하고, `transactionManagerRef`를 `ExposedJdbcRepositoryFactoryBean`의 `transactionManager` 속성으로 전달한다.
- factory가 선택한 manager가 proxy 연산을 제어하도록 `SimpleExposedJdbcRepository`의 트랜잭션 annotation에서 명시적인 `transactionManager = EXPOSED_TRANSACTION_MANAGER` qualifier를 제거한다. read-only/write 의미와 annotation 기본값 `springTransactionManager`는 유지한다. 이로 인해 상수가 사용되지 않으면 제거하거나 적절한 위치로 옮긴다.
- 변경한 public 구성 표면의 영문 KDoc에 `transactionManagerRef`가 repository proxy를 제어한다고 명시한다.

단계 2의 정확한 명령을 다시 실행한다. 예상 결과: PASS. 서로 구분되는 저장소를 대상으로 `count()`와 `deleteAll()`이 두 번째 manager 사용을 증명한다. 이는 승인된 명세가 요구하는 기존 선언 동작을 복구한다. 더 광범위한 repository 재설계가 필요하면 중단하고 승인된 설계로 돌아간다.

- [ ] **단계 4: RED `ApplicationContextRunner` 자동 구성 커버리지 작성**

manager 선택 테스트가 GREEN이 된 후에만 `ApplicationContextRunner`와 `AutoConfigurations.of(ExposedAggregateEventPublisherAutoConfiguration::class.java)`를 사용해 다음 행렬을 검증하는 `ExposedAggregateEventPublisherAutoConfigurationTest`를 작성한다.

```text
no PlatformTransactionManager -> no publisher bean
one manager -> one publisher bean
two managers without @Primary -> no publisher bean
two managers with one @Primary -> one publisher bean
application-provided publisher -> auto-configuration backs off
FilteredClassLoader("org.springframework.modulith") -> publisher still created with one manager
FilteredClassLoader(AggregateRoot::class.java) -> context starts and no publisher bean exists
FilteredClassLoader(TransactionSynchronizationManager::class.java) -> context starts and no publisher bean exists
ApplicationEventPublisher class condition -> verify ConditionalOnClass annotation metadata contains the exact class
```

H2 `DataSourceTransactionManager` 빈은 조건 후보로만 사용하고 repository 연산은 계속 Exposed `SpringTransactionManager`를 사용한다. 새 자동 구성보다 `ExposedSpringDataAutoConfiguration`을 먼저 로드하고 기본 manager와 publisher 빈이 존재하는지 검증하는 순서 테스트를 추가한다.

- [ ] **단계 5: 격리된 자동 구성 RED 실행**

```bash
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.config.ExposedAggregateEventPublisherAutoConfigurationTest' \
  --no-configuration-cache --no-daemon --console=plain
```

예상 결과: `ExposedAggregateEventPublisherAutoConfiguration`이 존재하지 않으므로 Kotlin 컴파일에서만 FAIL한다. manager 선택 suite는 이미 GREEN이며 이 컴파일 실패 뒤에 가려지지 않는다.

- [ ] **단계 6: 발행자 자동 구성 구현과 등록**

`@AutoConfiguration(after = [ExposedSpringDataAutoConfiguration::class])`, `AggregateRoot`, `ApplicationEventPublisher`, `TransactionSynchronizationManager`에 대한 class 조건, `@ConditionalOnSingleCandidate(PlatformTransactionManager::class)`, missing-bean으로 보호되는 `ExposedAggregateEventPublisher` 빈을 사용해 클래스를 작성한다. 이 빈은 `ApplicationEventPublisher`만 주입하며 manager를 식별하지 않는다. `AutoConfiguration.imports`에서 `ExposedSpringDataAutoConfiguration` 바로 다음에 등록한다.

하나가 `@Primary`인 복수 manager를 포함한 단일 autowire 후보 의미와, repository/명령 경계를 활성 트랜잭션에 맞춰야 하는 호출자 책임을 설명하는 영문 KDoc을 추가한다.

- [ ] **단계 7: 결합된 GREEN 테스트 실행과 커밋**

```bash
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.config.ExposedAggregateEventPublisherAutoConfigurationTest' \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.config.ExposedSpringDataAutoConfigurationTest' \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.ddd.MultiManagerDocumentationExampleTest' \
  --tests 'io.bluetape4k.spring.data.exposed.jdbc.ddd.ExposedAggregateEventPublisherTest' \
  --no-configuration-cache --no-daemon --console=plain
git diff --check
```

예상 결과: PASS. 다음으로 시작하는 Lore 메시지로 작업 5 파일을 커밋한다.

```text
feat: auto-configure aggregate event publisher safely
```

rollback 지점: 수동 생성 지원을 유지하면서 발행자 자동 구성만 되돌릴 수 있다. 다만 public annotation이 구현되지 않은 동작을 주장하지 않도록 `transactionManagerRef` 수정과 회귀 테스트는 함께 이동하거나 되돌린다.

## 작업 6: DDD Spring Modulith 예제 migration

복잡도: 중간
의존성: 작업 5
적용 항목: `test-driven-development`, `bluetape4k-code-patterns`, `ecc-kotlin-exposed`, `ecc-springboot-kotlin`, `ecc-kotlin-testing`

**파일:**
- 수정: `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/OrderApplicationService.kt`
- 수정: `examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/OrderDomain.kt`
- 수정: `examples/ddd-spring-modulith-demo/src/test/kotlin/io/bluetape4k/exposed/examples/modulith/DddSpringModulithDemoApplicationTest.kt`

- [ ] **단계 1: 테스트부터 갱신**

기존 성공 명령 테스트에 다음 검증을 추가한다.

```kotlin
accepted.domainEvents().isEmpty().shouldBeTrue()
```

기존 persistence, 완료된 publication, reservation assertion은 유지한다. 실패한 handoff fixture가 다음 객체를 생성하도록 갱신한다.

```kotlin
aggregateEventPublisher = ExposedAggregateEventPublisher(
    ApplicationEventPublisher {
        throw IllegalStateException("Synthetic publication handoff failure")
    }
)
```

serializer 직접 호출만 검사하지 말고 실제 Spring/Modulith publication을 실행하는 serializer 신뢰 경계 통합 테스트를 추가한다.

```kotlin
@Test
fun `serializer rejects unsupported event type without exposing payload`() {
    withApplicationContext() { context ->
        val secret = "UNSUPPORTED-SECRET-MUST-NOT-LEAK"
        val logCapture = attachRootAndNonAdditiveLoggers()

        try {
            val error = assertFailsWith<IllegalArgumentException> {
                transactionTemplate.executeWithoutResult {
                    context.publishEvent(UnsupportedEvent(secret))
                }
            }
            error.assertNoSecretInMessageOrCauseChain(secret)
            publicationRepository.count() shouldBeEqualTo 0L
            logCapture.allEvents().forEach { event ->
                event.assertNoSecretInAnyField(secret)
            }
        } finally {
            logCapture.detachStopAndRestoreLoggerState()
        }
    }
}

private data class UnsupportedEvent(val secret: String) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

테스트가 실행되는 동안 시작된 `ListAppender`를 Logback root logger에 연결한다. 활성 context에서 non-additive logger가 있으면 찾아서 그곳에도 연결하고, `finally`에서 원래의 additivity/appender 상태를 복원한다. publication repository가 자체 logger를 가진다고 가정하지 않는다. 구체적인 helper로 캡처한 모든 `ILoggingEvent` 필드, 즉 message, formatted message, MDC, argument array, key/value pair, marker list, 전체 throwable/cause chain을 검사한다. 포착한 예외의 message/cause chain도 검사한다. secret 출현 횟수와 publication 행 수가 모두 0이어야 한다. serializer 직접 거부 assertion은 보조 unit coverage로만 유지한다.

기존 rollback, 민감한 payload, 재시작 replay, module boundary, idempotency 테스트는 계속 필수다.

- [ ] **단계 2: RED 예제 테스트 실행**

실행:

```bash
./gradlew :examples-ddd-spring-modulith-demo:test \
  --no-configuration-cache --no-daemon --console=plain
```

예상 결과: 기존 `OrderApplicationService` 생성자가 아직 `ExposedAggregateEventPublisher`가 아니라 `ApplicationEventPublisher`를 요구하므로 갱신한 실패 handoff fixture/service 호출 위치에서 Kotlin 컴파일이 실패한다. 성공 후 빈 버퍼 assertion을 RED 근거로 주장하지 않는다. 기존 수동 clear가 이미 그 assertion을 만족한다. 수동 경로를 제거한 후에만 수명주기 동작이 GREEN 근거가 된다.

- [ ] **단계 3: 수동 loop를 public bridge로 교체**

service 생성자와 트랜잭션 본문을 다음과 같이 변경한다.

```kotlin
class OrderApplicationService(
    private val orderRepository: OrderRepository,
    private val aggregateEventPublisher: ExposedAggregateEventPublisher,
    private val transactionTemplate: TransactionTemplate,
) {
    fun accept(command: AcceptOrderCommand, failAfterPublish: Boolean = false): Order {
        val order = Order.accept(command)
        return try {
            transactionTemplate.execute {
                orderRepository.save(order)
                aggregateEventPublisher.publishAfterSave(order)
                if (failAfterPublish) {
                    throw IllegalStateException("Failing after event publication for rollback verification")
                }
                order
            } ?: error("Order transaction returned no aggregate")
        } catch (e: Exception) {
            if (order.domainEvents().isNotEmpty()) {
                throw OrderHandoffFailedException(order, e)
            }
            throw e
        }
    }
}
```

수동 `domainEvents().forEach` publication loop와 트랜잭션 후 `clearDomainEvents()` 호출을 모두 제거한다.

`OrderHandoffFailedException`이 안정적인 메시지 `order-event-handoff-failed`를 사용하도록 변경한다. 호출자가 직접 검사할 수 있도록 aggregate property와 cause는 유지하되 메시지에 aggregate ID나 중첩 예외 텍스트를 포함하지 않는다.

- [ ] **단계 4: GREEN 예제와 모듈 간 테스트 실행**

실행:

```bash
./gradlew \
  :bluetape4k-exposed-spring-boot-jdbc:test \
  :bluetape4k-exposed-spring-modulith:test \
  :examples-ddd-spring-modulith-demo:test \
  --no-configuration-cache --no-daemon --console=plain
! rg -n 'domainEvents\(\)\.forEach|saved\.clearDomainEvents\(\)|eventPublisher\.publishEvent' \
  examples/ddd-spring-modulith-demo/src/main
```

예상 결과: Gradle이 PASS하고, `! rg`로 실행한 소스 검색은 일치 항목 없이 종료한다. rollback 시 애그리거트 버퍼는 보존되며 order, listener 부수 효과, publication 행은 남지 않는다.

- [ ] **단계 5: 예제 migration 커밋**

`git diff --check`를 실행한 다음, 다음으로 시작하는 Lore 메시지로 예제 파일 세 개를 커밋한다.

```text
refactor: adopt transaction-aware event handoff in DDD demo
```

rollback 지점: 되돌릴 때는 기존 수동 loop와 수동 clear 전체를 함께 복원해야 한다. 한 애플리케이션 인스턴스에서 기존 경로와 새 경로를 함께 실행하지 않는다.

## 작업 7: README 로케일, changelog, 수명주기 diagram 갱신

복잡도: 중간
의존성: 작업 6
적용 항목: `bluetape4k-maintenance`, `bluetape4k-blog`, `bluetape4k-diagram`

**파일:**
- 수정: `spring-boot/jdbc/README.md`
- 수정: `spring-boot/jdbc/README.ko.md`
- 수정: `spring-boot/spring-modulith/README.md`
- 수정: `spring-boot/spring-modulith/README.ko.md`
- 수정: `examples/ddd-spring-modulith-demo/README.md`
- 수정: `examples/ddd-spring-modulith-demo/README.ko.md`
- 수정: `CHANGELOG.md`
- 생성: `docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.svg`
- 생성: `docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.png`

- [ ] **단계 1: 원문과 동등한 영어/한국어 JDBC 문서 추가**

이 이슈가 소유하는 모든 README 절을 `<!-- issue-323-section:start -->` / `<!-- issue-323-section:end -->`로 감싼다. 로케일 간 링크가 하나의 안정적인 anchor를 사용하도록 JDBC 로케일 제목 `Transaction-Aware Domain Events` / `트랜잭션 인식 도메인 이벤트` 바로 앞에 `<a id="transaction-aware-domain-events"></a>`를 추가한다. 다음 내용을 다룬다.

```text
dependency and auto-configuration conditions
save -> publishAfterSave inside the same command transaction
empty no-op versus event-bearing active-transaction failure
immediate Spring handoff versus default AFTER_COMMIT listener execution
commit clear, rollback preserve, poison, duplicate, mutation, and synchronous listener behavior
plain Spring Boot versus optional Spring Modulith
deeply immutable events and stable object references
single final call, unsupported NESTED/savepoint, unsupported same-instance overlapping REQUIRES_NEW
multi-manager @Primary semantics plus an executable @Qualifier/transactionManagerRef example
listener database writes requiring REQUIRES_NEW
serializer/publication-store trust boundary, idempotent consumers, and no outbox/exactly-once claim
outcome/retry table, anomaly categories, allowlisted correlation, and four-state reconciliation
replacement-only migration and binary rollback after evidence preservation/repair
R2DBC exclusion
JaVers audit/history boundary
```

결과 표는 `<!-- issue-323-outcome-table:start -->` / `<!-- issue-323-outcome-table:end -->`로 감싼다. JDBC 로케일 쌍은 persistence/buffer/retry 결정이 동일한 다음 다섯 결과 행을 재현해야 한다.

| 결과 | 저장 상태 | 버퍼 | 명령 재시도 |
|---|---|---|---|
| 활성 트랜잭션 없음 또는 같은 트랜잭션 전제 조건 위반 | 미확정 | 보존 | 자동 재시도 금지, 먼저 정합성을 복구한다 |
| 전체 rollback 또는 poison된 전달 | rollback됨 | 보존 | 새 트랜잭션에서만 허용, 동기 부수 효과는 중복 제거가 필요할 수 있다 |
| 커밋된 listener 실패 | 커밋됨 | 비움 | 명령 재시도 금지, listener 재시도/재생을 사용한다 |
| 커밋된 정리 실패 | 커밋됨 | 남을 수 있음 | 재시도 금지, 애그리거트 인스턴스를 폐기한다 |
| `STATUS_UNKNOWN` | 미확정 | 보존 | 자동 재시도 금지, 먼저 정합성을 복구한다 |

대조 절은 `<!-- issue-323-reconciliation:start -->` / `<!-- issue-323-reconciliation:end -->`로 감싼다. 상태/동작 매핑과 순서를 산문과 독립적으로 검사할 수 있도록 번역한 각 bullet 앞에 아래의 안정적인 semantic marker를 붙인다. retry 의미를 바꾸지 않고 다음 네 상태와 동작을 재현해야 한다.

```text
<!-- issue-323-reconciliation:state=present-present;action=listener-recovery;command-retry=false -->
persistence present + publication present -> do not replay command; use Modulith replay or listener recovery
<!-- issue-323-reconciliation:state=present-absent;action=idempotent-repair;command-retry=false -->
persistence present + publication absent -> do not replay command; run application-owned idempotent repair from persisted state
<!-- issue-323-reconciliation:state=absent-absent;action=fresh-command-after-side-effect-check;command-retry=conditional -->
persistence absent + publication absent -> retry only as a new command after ruling out irreversible synchronous side effects
<!-- issue-323-reconciliation:state=absent-present;action=quarantine-and-compensate;command-retry=false -->
persistence absent + publication present -> quarantine invariant breach and compensate manually; replay neither path
```

application owner를 명시하는 `Production rollout checklist` / `프로덕션 롤아웃 체크리스트`를 추가한다. canary 전에 두 anomaly category에 대한 alert, 전달된 allowlist correlation field 하나 이상, audit/trace에서 persistence key를 찾는 방법, database와 publication table의 읽기 권한을 준비해야 한다. 또한 영속화된 애그리거트 하나, 내구성 있는 publication 하나, listener 부수 효과 하나, anomaly 행 0개를 증명하는 canary가 필요하다. 네 실패 단계 앞에는 `<!-- issue-323-rollout:01-stop -->`, `<!-- issue-323-rollout:02-preserve -->`, `<!-- issue-323-rollout:03-reconcile-repair -->`, `<!-- issue-323-rollout:04-binary-rollback-version-defect-only -->`를 붙인다. 순서는 정확히 `stop rollout -> preserve logs/records -> reconcile and repair canary -> full binary rollback only for a version defect`이다.

correlation이 없을 때의 복구 규칙은 의미를 그대로 유지해 추가한다. allowlist에 있는 correlation field가 하나도 없으면 영향을 받은 시간 구간을 격리하고 application audit record를 사용하며 자동 복구를 금지한다.

JDBC README 두 로케일과 예제 README 두 로케일에는 publication store 제어를 명시해야 한다. 최소 권한 database 접근, application infrastructure가 허용하는 저장·전송 암호화, 무결성 보호, 보존/삭제 정책, payload 최소화, 저장된 이벤트 class 이름의 노출을 다룬다. audit history, snapshot persistence, JaVers commit 의미는 새 발행자가 의존해서는 안 된다고 명시한다.

두 로케일 파일에 새 PNG를 삽입한다.

- [ ] **단계 2: 예제와 Modulith README 로케일 쌍 갱신**

예제 README 쌍의 수동 publication/clear 지침을 `ExposedAggregateEventPublisher`로 교체한다. 작업 5의 컴파일된 multi-manager Kotlin 예제를 코드 block을 변경하지 않고 JDBC 로케일 파일 양쪽에 복사하며, `<!-- issue-323-multi-manager:start -->` / `<!-- issue-323-multi-manager:end -->`로 감싼다. 재시작 replay, publication table 보호, idempotency, serializer 지침을 보존한다. Spring Modulith README 쌍에는 JDBC 발행자 절과 예제로 연결되는 짧은 cross-link를 추가하되 전체 수명주기 계약을 중복하지 않는다.

- [ ] **단계 3: changelog 항목 추가**

`CHANGELOG.md`의 `Unreleased -> Added` 아래에 이슈 #323, public JDBC publisher, 보호된 자동 구성, committed cleanup, 예제 적용을 기록한다. 이 기능 작업에서 지난 release version이나 `WIP.md`는 편집하지 않는다.

- [ ] **단계 4: 수명주기 sequence asset 하나 생성과 렌더링**

먼저 다음 reference PNG를 전체 크기로 연다.

```text
/Users/debop/work/bluetape4k/bluetape4k-wiki/docs/diagrams/best-practices/assets/sequence-workflow-sample.png
docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-sequence-01.png
```

새 diagram에는 aggregate, repository, publisher, Spring transaction, 기본 transactional listener, optional Modulith, commit, rollback, committed cleanup을 표시해야 한다. 번호 label은 보여야 하고 `alt` branch는 투명해야 한다.

단일 asset loop를 실행한다.

```bash
xmllint --noout docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.svg
/Users/debop/.local/bin/cairosvg \
  docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.svg \
  -o docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.png -s 2
python3 /Users/debop/.codex/skills/bluetape4k-diagram/scripts/diagram-sequence-style-audit.py \
  docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/scripts/diagram-connector-audit.py \
  docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/scripts/diagram-geometry-audit.py --fail-diagonal \
  docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/scripts/diagram-endpoint-audit.py \
  docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/scripts/diagram-mixed-corner-audit.py \
  docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.svg
```

마지막 좌표 변경 후 PNG를 전체 크기로 검사하고 크기, 보이는 label 수, branch 투명도, marker 색상 일치, connector 수, audit 실패 0건을 기록한다.

- [ ] **단계 5: 로케일/원문 동등성 검증과 커밋**

실행:

```bash
for file in \
  spring-boot/jdbc/README.md spring-boot/jdbc/README.ko.md \
  spring-boot/spring-modulith/README.md spring-boot/spring-modulith/README.ko.md \
  examples/ddd-spring-modulith-demo/README.md examples/ddd-spring-modulith-demo/README.ko.md; do
  rg -q 'issue-323-section:start' "$file" || exit 1
  rg -q 'ExposedAggregateEventPublisher' "$file" || exit 1
done
for file in spring-boot/jdbc/README.md spring-boot/jdbc/README.ko.md; do
  for token in publishAfterSave AFTER_COMMIT STATUS_UNKNOWN REQUIRES_NEW PROPAGATION_NESTED JaVers; do
    rg -q "$token" "$file" || exit 1
  done
done
for file in examples/ddd-spring-modulith-demo/README.md examples/ddd-spring-modulith-demo/README.ko.md; do
  for token in publishAfterSave AFTER_COMMIT; do
    rg -q "$token" "$file" || exit 1
  done
done

sed -n '/\/\/ issue-323-multi-manager:start/,/\/\/ issue-323-multi-manager:end/p' \
  spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/ddd/MultiManagerDocumentationExample.kt \
  | sed '/\/\/ issue-323-multi-manager:/d' > /tmp/issue-323-multi-manager.source
for locale in README.md README.ko.md; do
  sed -n '/issue-323-multi-manager:start/,/issue-323-multi-manager:end/p' \
    "spring-boot/jdbc/$locale" \
    | sed '/issue-323-multi-manager:/d; /^```/d' > "/tmp/$locale.multi-manager"
  diff -u /tmp/issue-323-multi-manager.source "/tmp/$locale.multi-manager"
done
diff -u /tmp/README.md.multi-manager /tmp/README.ko.md.multi-manager

printf '%s\n' \
  'spring-boot/jdbc/README.md|spring-boot/jdbc/README.ko.md' \
  'spring-boot/spring-modulith/README.md|spring-boot/spring-modulith/README.ko.md' \
  'examples/ddd-spring-modulith-demo/README.md|examples/ddd-spring-modulith-demo/README.ko.md' \
  | while IFS='|' read -r english korean; do
  for file in "$english" "$korean"; do
    sed -n '/issue-323-section:start/,/issue-323-section:end/p' "$file" \
      | ruby -ne '$_.scan(/!?\[[^\]]*\]\(([^)]+)\)/) { |m| puts m[0].sub("README.ko.md", "README.md") }' \
      | sort > "/tmp/$(echo "$file" | tr / _).links"
  done
  diff -u "/tmp/$(echo "$english" | tr / _).links" "/tmp/$(echo "$korean" | tr / _).links"
done

ruby -e '
  files = ARGV
  files.each do |file|
    section = File.read(file)[/<!-- issue-323-section:start -->(.*?)<!-- issue-323-section:end -->/m, 1] or abort("missing issue section: #{file}")
    section.scan(/!?\[[^\]]*\]\(([^)]+)\)/).flatten.each do |link|
      next if link.match?(/\A(?:https?:|mailto:|#)/)
      path, anchor = link.split("#", 2)
      target = File.expand_path(path, File.dirname(file))
      abort("missing link target: #{file} -> #{link}") unless File.file?(target)
      if anchor
        body = File.read(target)
        abort("missing anchor: #{file} -> #{link}") unless body.include?(%Q{id="#{anchor}"})
      end
    end
  end
' spring-boot/jdbc/README.md spring-boot/jdbc/README.ko.md \
  spring-boot/spring-modulith/README.md spring-boot/spring-modulith/README.ko.md \
  examples/ddd-spring-modulith-demo/README.md examples/ddd-spring-modulith-demo/README.ko.md

test -s docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.svg
test -s docs/images/readme-diagrams/spring-boot-exposed-jdbc-domain-event-sequence-01.png
for file in spring-boot/jdbc/README.md spring-boot/jdbc/README.ko.md; do
  rg -Fq 'spring-boot-exposed-jdbc-domain-event-sequence-01.png' "$file" || exit 1
done

for file in spring-boot/jdbc/README.md spring-boot/jdbc/README.ko.md; do
  test "$(sed -n '/issue-323-outcome-table:start/,/issue-323-outcome-table:end/p' "$file" | rg -c '^\|' )" -eq 7
  test "$(sed -n '/issue-323-reconciliation:start/,/issue-323-reconciliation:end/p' "$file" | rg -c '^- ' )" -eq 4
  rg -q 'aggregate-event-cleanup-failed' "$file" || exit 1
  rg -q 'aggregate-event-completion-unknown' "$file" || exit 1
  rg -qi 'stop rollout|rollout.*stop|롤아웃.*중지' "$file" || exit 1
  rg -qi 'preserve|보존' "$file" || exit 1
  rg -qi 'reconcile|대조' "$file" || exit 1
  rg -qi 'repair|복구' "$file" || exit 1
  rg -qi 'binary rollback|바이너리 롤백' "$file" || exit 1
  rg -qi 'no allowlisted correlation|허용.*correlation.*없|허용.*상관.*없' "$file" || exit 1
  rg -qi 'quarantine|격리' "$file" || exit 1
  rg -qi 'audit|감사' "$file" || exit 1
  rg -qi 'automatic repair.*forbid|자동 복구.*금지' "$file" || exit 1
  rg -qi 'application owner|애플리케이션 소유자' "$file" || exit 1
  rg -qi 'database.*read access|데이터베이스.*읽기 권한' "$file" || exit 1
  rg -qi 'publication.*read access|publication.*읽기 권한|발행.*읽기 권한' "$file" || exit 1
  rg -qi 'one persisted aggregate|persisted aggregate.*1|영속.*aggregate.*1|영속.*애그리거트.*1' "$file" || exit 1
  rg -qi 'one durable publication|durable publication.*1|내구.*발행.*1' "$file" || exit 1
  rg -qi 'one listener side effect|listener side effect.*1|리스너 부수 효과.*1' "$file" || exit 1
  rg -qi 'zero anomaly|anomaly.*0|이상.*0' "$file" || exit 1
done

ruby -e '
  expected_reconciliation = [
    "<!-- issue-323-reconciliation:state=present-present;action=listener-recovery;command-retry=false -->",
    "<!-- issue-323-reconciliation:state=present-absent;action=idempotent-repair;command-retry=false -->",
    "<!-- issue-323-reconciliation:state=absent-absent;action=fresh-command-after-side-effect-check;command-retry=conditional -->",
    "<!-- issue-323-reconciliation:state=absent-present;action=quarantine-and-compensate;command-retry=false -->",
  ]
  expected_rollout = [
    "<!-- issue-323-rollout:01-stop -->",
    "<!-- issue-323-rollout:02-preserve -->",
    "<!-- issue-323-rollout:03-reconcile-repair -->",
    "<!-- issue-323-rollout:04-binary-rollback-version-defect-only -->",
  ]
  ARGV.each do |file|
    body = File.read(file)
    reconciliation = body.scan(/<!-- issue-323-reconciliation:(?!start|end)[^>]+-->/)
    rollout = body.scan(/<!-- issue-323-rollout:[^>]+-->/)
    abort("reconciliation order/mapping mismatch: #{file}") unless reconciliation == expected_reconciliation
    abort("rollout order mismatch: #{file}") unless rollout == expected_rollout
  end
' spring-boot/jdbc/README.md spring-boot/jdbc/README.ko.md

for file in \
  spring-boot/jdbc/README.md spring-boot/jdbc/README.ko.md \
  examples/ddd-spring-modulith-demo/README.md examples/ddd-spring-modulith-demo/README.ko.md; do
  rg -qi 'access control|least[- ]privilege|접근 제어|최소 권한' "$file" || exit 1
  rg -qi 'encryption at rest|encrypt.*at rest|저장.*암호화' "$file" || exit 1
  rg -qi 'encryption in transit|encrypt.*in transit|전송.*암호화' "$file" || exit 1
  rg -qi 'integrity|무결성' "$file" || exit 1
  rg -qi 'retention|deletion|보존|삭제' "$file" || exit 1
  rg -qi 'payload minimization|페이로드 최소화' "$file" || exit 1
  rg -qi 'event class name|stored class name|이벤트 클래스 이름' "$file" || exit 1
done
git diff --check
```

예상 결과: Kotlin block, link/image target, 결과 행 다섯 개, 대조 상태/동작 매핑 네 개, rollout 순서, 명시된 모든 publication store 제어가 각 로케일 쌍에서 원문과 동등하다. 예제와 cross-link는 새 API를 사용하고 diff 검사가 통과한다. token과 marker 검사로는 번역 의미를 증명할 수 없으므로 표 cell과 semantic marker 주변 산문을 포함해 이 이슈가 소유하는 모든 번역 절을 승인된 영문 결정과 수동으로 비교한다.

다음으로 시작하는 Lore 메시지로 작업 7 파일을 커밋한다.

```text
docs: explain aggregate event transaction lifecycle
```

rollback 지점: SVG와 PNG는 하나의 asset 쌍이며 README embed와 함께 되돌리거나 유지해야 한다.

## 작업 8: 최종 구현 검증과 위험 스캔

복잡도: 높음
의존성: 작업 1-7
적용 항목: `verification-before-completion`, `bluetape4k-full-feature`, `bluetape4k-code-patterns`

**파일:**
- 검증 결과가 담당 작업으로 돌아가라고 요구하지 않는 한 소스를 편집하지 않는다.

- [ ] **단계 1: Kotlin 영향과 diagnostics 검사**

최종 컴파일 전에 사용 가능한 IDE/LSP 도구로 변경한 모든 Kotlin public symbol의 reference를 검사한다. 계획 수립 중 CodeGraph가 비어 있었으므로 여전히 비어 있다면 그 공백을 기록하고 정확한 `rg` import/call-site 검사를 사용한다. 가능하면 IDE diagnostics를 실행하고 변경한 파일에 해결되지 않은 deprecation warning을 남기지 않는다.

- [ ] **단계 2: 대상 compile과 test 명령을 순차 실행**

실행:

```bash
./gradlew \
  :bluetape4k-exposed-core:test \
  :bluetape4k-exposed-spring-boot-jdbc:test \
  :bluetape4k-exposed-spring-modulith:test \
  :examples-ddd-spring-modulith-demo:test \
  --no-configuration-cache --no-daemon --console=plain

./gradlew \
  :bluetape4k-exposed-core:koverXmlReport \
  :bluetape4k-exposed-spring-boot-jdbc:koverXmlReport \
  :bluetape4k-exposed-spring-modulith:koverXmlReport \
  :examples-ddd-spring-modulith-demo:koverXmlReport \
  --no-configuration-cache --no-daemon --console=plain

test -s exposed/core/build/reports/kover/report.xml
test -s spring-boot/jdbc/build/reports/kover/report.xml
test -s spring-boot/spring-modulith/build/reports/kover/report.xml
test -s examples/ddd-spring-modulith-demo/build/reports/kover/report.xml
```

예상 결과: 비어 있지 않은 Kover XML report 네 개와 함께 PASS한다. H2 기반 검사는 한 번의 Gradle 호출로 실행하며 Testcontainers job을 병렬로 실행하지 않는다. 기존 CI의 `continue-on-error` report-only 동작은 저장소 정책이므로 #323에서 변경하지 않는다. 다만 local 또는 PR coverage artifact가 없으면 이 이슈의 근거 gate는 계속 차단된다.

- [ ] **단계 3: 정적 경계와 workflow 검사 실행**

실행:

```bash
! rg -n 'import org\.springframework|import org\.springframework\.modulith|org\.javers' \
  exposed/core/src/main exposed/core/build.gradle.kts
! rg -n 'org\.javers|org\.springframework\.modulith' \
  spring-boot/jdbc/src/main spring-boot/jdbc/build.gradle.kts
./gradlew :bluetape4k-exposed-spring-boot-jdbc:dependencies --configuration compileClasspath \
  --no-configuration-cache --no-daemon --console=plain | tee /tmp/issue-323-jdbc-compile-classpath
! rg -n 'org\.javers|spring-modulith' /tmp/issue-323-jdbc-compile-classpath
! rg -n 'domainEvents\(\)\.forEach|saved\.clearDomainEvents\(\)|eventPublisher\.publishEvent' \
  examples/ddd-spring-modulith-demo/src/main
rg -n 'exposed/core/\*\*|spring-boot/\*\*|spring-boot/jdbc/\*\*|spring-boot/spring-modulith/\*\*|examples/\*\*|bluetape4k-exposed-core:(test|koverXmlReport)|examples-ddd-spring-modulith-demo:(test|koverXmlReport)|bluetape4k-exposed-spring-boot-jdbc:(test|koverXmlReport)|bluetape4k-exposed-spring-modulith:(test|koverXmlReport)|needs:|kover.*xml|coverage-' \
  .github/workflows/ci.yml .github/workflows/nightly-tests.yml
git diff --check
```

예상 결과: 금지된 dependency와 기존 path 검색에 일치 항목이 없다. JDBC compile classpath에는 JaVers와 Spring Modulith가 모두 없다. 정확한 workflow block으로 CI path route, Nightly 전체 범위 job, 해당 test/Kover task, status/coverage `needs`, coverage upload를 증명한다. task 이름만 검색한 결과로 workflow coverage를 주장하지 않는다.

- [ ] **단계 4: 성능/안정성 스캔 실행**

소스와 테스트로 다음을 증명한다.

```text
one immutable snapshot object retained per event-bearing aggregate; sentinel/snapshot-retention tests pass and no publisher copy exists
one beforeCommit snapshot and identity comparison per aggregate
getSynchronizations() zero times when synchronization is inactive and exactly once when active; cache and scan outside event/registration loops; three sentinel synchronizations plus multiple aggregates still produce one reused publisher synchronization
IdentityHashMap average O(1) reservation; no bean-global lock or mutable registry
publisher call cost O(E + S log S) and O(E + S) temporary/reference storage for E events and S current synchronizations
source path contains no publisher-side toList/map/filter/sorted, lock, or global mutable registry
event-type aggregation is reachable only from cleanup-failure/STATUS_UNKNOWN anomaly logging, never normal publication/commit/rollback
no blocking call, polling, sleep, thread, container, or retry loop added
completion always discards registry state
clear failures are isolated; rollback and unknown completion preserve buffers
REQUIRES_NEW uses Spring synchronization suspension with distinct aggregate instances
```

각 항목의 file/line 근거를 기록하고 연산을 명시적으로 매핑한다. event publication과 identity 검증은 `E`, synchronization list 한 번의 scan과 Spring의 synchronization ordering은 `S log S`, 보존한 snapshot과 현재 synchronization reference는 `E + S`다. sentinel, snapshot retention, anomaly log, 정확한 호출 횟수 테스트를 다시 실행한다. 정상 애그리거트와 각 sentinel 애그리거트는 `1 -> 2`로 전이하고 중복 거부는 `1`에 머무름을 증명한다.

P0/P1 문제가 나타나면 담당 작업으로 돌아가 RED 회귀 테스트를 추가하고 수정한다. 작업 8을 단계 1부터 다시 실행하고 영향을 받은 review lens만 재실행한다.

- [ ] **단계 5: 전체 diff 리뷰**

원시 `git diff origin/develop...HEAD`와 커밋되지 않은 diff 검사를 함께 사용한다. 이슈 #323 파일만 있고, public KDoc은 영문이며, README 쌍은 원문과 동등하고, SVG/PNG 쌍이 일치하며, 생성된 build output이 추적되지 않는지 확인한다.

## 작업 9: 리뷰, 교훈, PR 전 근거 기록

복잡도: 중간
의존성: 작업 8 PASS
적용 항목: `bluetape4k-full-feature`, `requesting-code-review`, `verification-before-completion`

**파일:**
- 생성: `docs/review/2026-07-11-issue-323-domain-event-publisher-implementation-review.md`
- 생성: `docs/lessons/2026-07-11-issue-323-domain-event-publisher.md`

- [ ] **단계 1: 여섯 구현 리뷰 관점과 주 통합 리뷰 실행**

정확한 branch diff를 core contract -> publisher -> auto-configuration -> example -> docs/diagram 의존성 순서로 리뷰한다. P0/P1/P2/P3, 해결한 편집, 재실행 lane, 성능/안정성 근거, 최종 `P0 = 0`, `P1 = 0`을 기록한다. P2/P3는 수정하거나, 근거와 함께 연기하거나, 후속 작업으로 등록해야 한다.

- [ ] **단계 2: 명세/계획 검증기 실행**

모든 설계 acceptance criterion을 구현 파일, 테스트 이름, 문서 절, 명령 결과에 매핑한다. 누락된 criterion은 담당 구현 작업으로 되돌리며 산문으로만 적은 예외로 처리하지 않는다.

- [ ] **단계 3: 영속 교훈 작성**

context, 선택한 transaction 내부 handoff, `afterCommit` publication을 거부한 이유, identity/poison 통찰, 완료 불확실성, 테스트 근거, 리뷰에서 놓친 내용, 향후 규칙을 기록한다. 향후 규칙은 committed completion 전에 애그리거트 이벤트를 절대 비우지 않고 Spring thread-local synchronization으로 manager identity를 주장하지 않는 것이다.

- [ ] **단계 4: 리뷰와 교훈 산출물 커밋**

`git diff --check`를 실행한 다음, 다음으로 시작하는 Lore 메시지로 두 산출물을 커밋한다.

```text
docs: capture aggregate event handoff evidence
```

- [ ] **단계 5: 외부 상태 변경 권한 gate 준비**

이 시점에 구현은 PR 준비가 끝난 상태다. 이슈 #323 metadata를 다시 읽고 정확한 branch/PR payload를 준비한 뒤, 명시적인 push/PR 권한이 생길 때까지 중단한다. merge, PR이 유발한 CI 이외의 workflow dispatch, branch 삭제, worktree 정리는 별도의 권한 경계로 남는다.

## 작업 10: 라이브 PR, CI, 커버리지, 리뷰 gate 증명

복잡도: 중간
의존성: 작업 9 PASS와 명시적인 push/PR 승인
적용 항목: `bluetape4k-full-feature`, `verification-before-completion`

**파일:**
- 라이브 근거가 담당 작업으로 돌아가라고 요구하지 않는 한 소스를 편집하지 않는다.

- [ ] **단계 1: 권한이 있을 때만 push와 이슈 연결 PR 생성**

리뷰한 branch를 push하고 `develop`을 대상으로 PR을 생성한다. 이슈 #323의 assignee `debop`, milestone `1.12.0`, 관련 label을 복사한다. PR 본문은 #323을 연결/종료하고 test와 coverage 근거를 포함하며 마지막 Markdown 절 `## DoD Status`로 끝나야 한다. 생성 명령 출력에 의존하지 말고 `gh pr view --json`으로 live metadata와 본문을 검증한다.

- [ ] **단계 2: CI 감시와 원시 재시도 근거 검사**

`ci-status --watch` 또는 `gh pr checks --watch`를 사용한 다음 core, Spring Boot JDBC, Spring Modulith, example의 원시 job log를 검사한다. log에 `Attempt N failed`가 하나라도 있으면 최종 conclusion이 성공이어도 중단한다. lifecycle/container/timing 원인을 조사하고 영향을 받은 작업으로 돌아가 관련 local suite를 다시 실행한 후 새 CI run을 시작한다.

```bash
gh run view <run-id> --json status,conclusion,jobs,url
gh run view <run-id> --log | tee /tmp/issue-323-ci.log
! rg -n 'Attempt [1-5] failed' /tmp/issue-323-ci.log
```

- [ ] **단계 3: 비어 있지 않은 라이브 커버리지 산출물 검증**

PR run의 coverage artifact를 내려받고 core, JDBC, Spring Modulith, example의 `report.xml` 파일이 존재하며 비어 있지 않음을 증명한다. 이 이슈 수준 artifact gate는 저장소의 report-only Kover workflow 정책을 보완하지만 다시 정의하지 않는다.

```bash
coverage_dir="$(mktemp -d /tmp/issue-323-coverage.XXXXXX)"
gh run download <run-id> --pattern 'coverage-*' --dir "$coverage_dir"
find "$coverage_dir" -name 'report.xml' -size +0 -print | tee /tmp/issue-323-coverage-files
test -s "$coverage_dir/coverage-core/exposed/core/build/reports/kover/report.xml"
test -s "$coverage_dir/coverage-spring-boot/spring-boot/jdbc/build/reports/kover/report.xml"
test -s "$coverage_dir/coverage-spring-modulith/spring-boot/spring-modulith/build/reports/kover/report.xml"
test -s "$coverage_dir/coverage-examples/examples/ddd-spring-modulith-demo/build/reports/kover/report.xml"
```

- [ ] **단계 4: 리뷰와 병합 gate 재개방**

CI가 green이 되면 PR review와 모든 review thread를 다시 읽는다. 해결되지 않았거나 더 새로운 comment가 있으면 담당 작업으로 돌아가 영향을 받은 테스트/review lens를 다시 실행해야 한다. issue/PR metadata와 live PR 본문을 마지막으로 한 번 더 검증한다. 명시적인 merge 권한이 없으면 merge 전에 중단한다. merge 전략은 GitHub rebase merge를 유지한다.

## 명세-작업 추적성

| 설계 요구 사항 | 계획 작업 |
|---|---|
| Spring 중립적이며 불변이고 안정적인 DDD 계약 | 1 |
| 명시적인 public JDBC publisher와 활성 트랜잭션 검사 | 2-3 |
| 즉시 handoff와 기본 AFTER_COMMIT 동작 | 2-3 |
| identity 예약, 변경 감지, poison, 단일 synchronization | 4 |
| commit 시 clear, rollback/unknown 시 보존, cleanup 격리 | 3-4 |
| 안전한 anomaly field와 correlation allowlist | 4 |
| REQUIRES_NEW 격리, nested/savepoint 제외 | 4, 7 |
| 단일 후보 자동 구성과 실행 가능한 `transactionManagerRef` multi-manager 사용 | 5 |
| Spring Modulith 없는 일반 Spring Boot | 5 |
| 예제 migration, rollback, 내구성 publication, serializer 신뢰 경계 | 6 |
| README 로케일 동등성, JaVers 경계, migration/runbook | 7 |
| 수명주기 SVG/PNG | 7 |
| CI/Nightly/Kover 가시성 | 0, 8, 10 |
| 7-Tier 구현 리뷰와 영속적인 교훈 | 9 |
| live PR metadata, retry log 검사, coverage artifact, review thread | 10 |

## 위험 예측과 중단 조건

| 위험 | 신호 | 완화 / 재실행 지점 |
|---|---|---|
| transactional listener에 너무 늦게 publication됨 | AFTER_COMMIT listener가 아무것도 받지 못함 | `ApplicationEventPublisher` 호출을 작업 3 트랜잭션 안에 유지하고 작업 2-3 테스트를 재실행한다. |
| 호출자가 publication/중복 오류를 잡은 뒤 트랜잭션이 커밋됨 | handoff 오류 후 DB 행이 커밋됨 | synchronization을 poison 상태로 만들고 `beforeCommit`을 실패시킨 뒤 작업 4 fail-closed 테스트를 재실행한다. |
| save/handoff 후 애그리거트가 변경됨 | snapshot 크기/reference 불일치 | commit 전에 실패하고 버퍼를 보존한 뒤 작업 4로 돌아간다. |
| suspension/completion 사이에 registry가 누출됨 | 이후 트랜잭션이나 REQUIRES_NEW 트랜잭션에서 중복이 거부됨 | 상태를 synchronization list에만 유지하고 작업 4 수명주기 행렬을 재실행한다. |
| commit 후 cleanup 실패가 명령 retry를 유발함 | cleanup anomaly를 rollback으로 오인함 | 구조화된 committed-cleanup log와 README no-retry 표를 작성하고 작업 4와 7로 돌아간다. |
| 민감한 payload가 log/publication store에 들어감 | 캡처한 log나 직렬화된 행에 secret marker가 나타남 | 테스트를 실패시키고 계속하기 전에 작업 4 또는 6으로 돌아간다. |
| 자동 구성이 manager identity를 암시함 | multi-manager 테스트가 주입된 manager를 요구함 | 빈을 manager 중립적으로 유지하고 작업 5로 돌아간다. |
| repository가 `transactionManagerRef`를 무시함 | 첫 번째 저장소가 변경되거나 두 번째 저장소가 그대로임 | annotation 값을 repository factory로 전달하고 base class의 hard-coded qualifier를 제거한 뒤 작업 5 RED/GREEN 테스트를 재실행한다. |
| 예제의 기존 publication 경로와 새 경로가 공존함 | 소스 검색에서 수동 loop/clear를 찾음 | 작업 6에서 기존 호출 두 개를 모두 제거하고 이중 publication 상태로 문서 작업을 진행하지 않는다. |
| diagram이 구현과 불일치함 | 전체 크기 PNG의 timing이 테스트/소스와 다름 | SVG를 수정하고 작업 7의 모든 diagram gate를 재실행한다. |

안전한 동작에 R2DBC, savepoint callback 지원, 내구성 outbox, 새 metric/callback SPI, manager/DataSource identity 증명, 마지막 단일 호출 계약 변경이 필요하다면 구현을 즉시 중단하고 승인된 설계로 돌아간다. 이는 구현 세부 사항이 아니라 중대한 범위 변경이다.
