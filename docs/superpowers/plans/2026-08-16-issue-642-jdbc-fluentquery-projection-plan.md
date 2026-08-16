# 이슈 #642 JDBC `FluentQuery` projection SQL pushdown 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use `$bluetape-workflow`,
> `$bluetape-kotlin-patterns`, and `test-driven-development`. Complete each task
> in order; do not start production code before observing its RED test.

**Goal:** `ExposedJdbcRepository.findBy(Example) { ... }`의 projection, property
selection, sort, limit, page, count, exists, cursor stream을 JDBC SQL로 pushdown하고
모든 QBE terminal의 predicate/cardinality 계약을 일치시킨다.

**Architecture:** callback-scoped immutable `JdbcFluentQueryPlan`은 원본
`Example`, result shape, exact property snapshot, sort, limit, transaction identity만
보관한다. 단일 `JdbcExamplePredicateCompiler`가 attached DAO probe를 bound
`Op<Boolean>`으로 변환하고, `JdbcFluentQueryExecutor`만 mutable Exposed `Query`를
만든다. Entity는 full-row/identity-cache 경로, interface/DTO는 selected-row mapper
경로를 사용한다. cursor stream은 `Query.iterator()`를 우회하고
`JdbcTransaction.execQuery`에서 JDBC resource lease를 직접 소유한다.

**Tech Stack:** Kotlin 2.4, JDK 25, Spring Data Commons 4.1,
Spring Boot 4, Exposed 1.4.0 JDBC/DAO, JUnit 5, bluetape4k-assertions, MockK, H2,
PostgreSQL, MySQL V8, Gradle.

---

## 실행 순서와 파일 책임

### Task 0: workflow state root와 component evidence를 고정

**Files:**

- State root: `/Users/debop/work/bluetape4k/bluetape4k-exposed/.bluetape`
- Existing run: `.bluetape/runs/20260816T024443Z-2d3cf954/`
- Existing owner handle: `.bluetape/handles/issue-642-fluentquery.owner`
- Create runtime input: `.bluetape/handles/issue-642-implementation-lane.json`
- Create runtime input: `.bluetape/handles/issue-642-topology.json`
- Create runtime evidence: `.bluetape/handles/issue-642-implementation-assignment-evidence.json`
- Create runtime evidence: `.bluetape/handles/issue-642-implementation-lifecycle-evidence.json`
- Create runtime evidence: `.bluetape/handles/issue-642-topology-evidence.json`

- [x] **Step 1: canonical state root와 실행 중인 run을 확인한다**

  feature worktree의 `.bluetape`를 새로 초기화하지 않는다. workflow helper는
  canonical checkout에서 실행하며 run `20260816T024443Z-2d3cf954`의 receipt
  checksum을 CAS `--expected-head`로 사용한다.

- [ ] **Step 2: main implementation owner lane을 생성한다**

  구현 전에 logical lane `implementation`, agent `root`, write scope를 Issue #642
  승인 경로로 한정한 input을 다음 exact schema로 만든다. 실행 직전에 `date -u`로
  `observed_at`, startup deadline, command deadline을 계산하고 `apply_patch`로 schema의
  세 timestamp를 concrete UTC 값으로 기록한다. 고정된 과거 deadline이나 shell
  redirection으로 파일을 만들지 않는다. assignment/lifecycle
  evidence는 각각 `kind=assignment`와 `kind=lifecycle`인 bounded 배열이며 secret이나
  raw output 없이 이 계획의 구현 책임과 main-session 시작 사실만 기록한다.

  ```json
  {"lane_id":"implementation","agent_id":"root","assignment":"Issue #642 JDBC FluentQuery projection SQL pushdown을 승인된 plan 순서로 TDD 구현·검증한다.","write_scope":["spring-boot/jdbc/src/main/kotlin","spring-boot/jdbc/src/test","spring-boot/jdbc/README.md","spring-boot/jdbc/README.ko.md","scripts/validate_module_readme_parity.rb","scripts/validate_module_readme_parity_test.rb","CHANGELOG.md","WIP.md","docs/review","docs/superpowers","docs/lessons"],"fallback":"main session","observed_at":"2026-08-16T04:50:52Z","startup_ack_deadline":"2026-08-16T05:00:52Z","command_deadline":"2026-08-16T10:50:52Z"}
  ```

  ```bash
  FLOW=/Users/debop/.codex/skills/bluetape-workflow/scripts/bluetape-flow.py
  VERIFY_JSON=$("$FLOW" verify --run-id 20260816T024443Z-2d3cf954)
  EXPECTED_HEAD=$(jq -er '.checksum' <<<"$VERIFY_JSON")
  LANE_CREATE_JSON=$("$FLOW" lane-create --run-id 20260816T024443Z-2d3cf954 \
    --owner-file .bluetape/handles/issue-642-fluentquery.owner \
    --expected-head "$EXPECTED_HEAD" \
    --evidence .bluetape/handles/issue-642-implementation-assignment-evidence.json \
    --input .bluetape/handles/issue-642-implementation-lane.json)
  EXPECTED_HEAD=$(jq -er '.checksum' <<<"$LANE_CREATE_JSON")
  NOW=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  LANE_START_JSON=$("$FLOW" lane-start --run-id 20260816T024443Z-2d3cf954 \
    --owner-file .bluetape/handles/issue-642-fluentquery.owner \
    --expected-head "$EXPECTED_HEAD" \
    --evidence .bluetape/handles/issue-642-implementation-lifecycle-evidence.json \
    --lane-id implementation --agent-id root --at "$NOW")
  EXPECTED_HEAD=$(jq -er '.checksum' <<<"$LANE_START_JSON")
  NOW=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  STARTUP_ACK_JSON=$("$FLOW" startup-ack --run-id 20260816T024443Z-2d3cf954 \
    --owner-file .bluetape/handles/issue-642-fluentquery.owner \
    --expected-head "$EXPECTED_HEAD" \
    --evidence .bluetape/handles/issue-642-implementation-lifecycle-evidence.json \
    --lane-id implementation --agent-id root --at "$NOW")
  EXPECTED_HEAD=$(jq -er '.checksum' <<<"$STARTUP_ACK_JSON")
  ```

- [ ] **Step 3: complete component topology snapshot을 등록한다**

  topology input은 다음 exact shape의 단일 active component 배열이다.

  ```json
  [{"id":"spring-boot-jdbc","required":true,"description":"Issue #642 JDBC FluentQuery projection and SQL pushdown implementation","owner_lane":"implementation","required_checks":["targeted-tests","module-tests","multi-db-tests","kotlin-static-analysis","abi-compatibility","docs-parity","pre-pr-review"],"dependencies":[],"evidence_refs":[],"coverage_state":"missing"}]
  ```

  ```bash
  TOPOLOGY_JSON=$("$FLOW" topology-register --run-id 20260816T024443Z-2d3cf954 \
    --owner-file .bluetape/handles/issue-642-fluentquery.owner \
    --expected-head "$EXPECTED_HEAD" \
    --evidence .bluetape/handles/issue-642-topology-evidence.json \
    --input .bluetape/handles/issue-642-topology.json)
  EXPECTED_HEAD=$(jq -er '.checksum' <<<"$TOPOLOGY_JSON")
  ```

  topology 등록 실패나 owner lane 불일치가 있으면 Task 1로 진행하지 않는다. 이후
  모든 workflow mutation은 canonical checkout에서 바로 앞 receipt checksum을
  CAS `--expected-head`로 사용한다.

### Task 1: public ABI baseline을 실행 가능한 fixture로 고정

**Files:**

- Create: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/support/ExposedJdbcRepositoryAbiCompatibilityTest.kt`
- Create: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/support/ExposedJdbcRepositoryKotlinConsumerFixture.kt`
- Create: `spring-boot/jdbc/src/test/java/io/bluetape4k/spring/data/exposed/jdbc/support/ExposedJdbcRepositoryJavaConsumerFixture.java`
- Create: `spring-boot/jdbc/src/test/resources/abi/simple-exposed-jdbc-repository-public.txt`
- Reference: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/support/ExposedJdbcRepositoryFactory.kt`
- Reference: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/support/SimpleExposedJdbcRepository.kt`

- [ ] **Step 1: 기존 JVM descriptor를 고정한다**

  reflection과 실제 Java/Kotlin consumer compile fixture로 다음 public
  descriptor가 유지되는지 검증한다.

  - `SimpleExposedJdbcRepository(ExposedEntityInformation)`
  - `ExposedJdbcRepositoryFactory()`
  - 기존 `ExposedJdbcRepository` method set

  reflection으로 수집한 sorted public constructor/method descriptor를 checked-in
  resource와 exact 비교해 method 추가/삭제/descriptor 변경을 검출한다.

- [ ] **Step 2: classfile descriptor를 `javap`로 고정한다**

  `compileKotlin`, `compileTestKotlin`, `compileTestJava`를 실행한 뒤 main classfile에
  대해 다음을 확인한다.

  ```bash
  javap -classpath spring-boot/jdbc/build/classes/kotlin/main -s \
    io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository
  javap -classpath spring-boot/jdbc/build/classes/kotlin/main -s \
    io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedJdbcRepositoryFactory
  ```

  reflection test는 repository constructor descriptor
  `(Lio/bluetape4k/spring/data/exposed/jdbc/repository/support/ExposedEntityInformation;)V`와
  factory constructor descriptor `()V`를 exact string으로 고정한다. `javap -s`
  출력에도 두 descriptor가 있고 새 public overload가 없어야 한다.

- [ ] **Step 3: ABI baseline PASS를 확인한다**

  ```bash
  ./gradlew :bluetape4k-exposed-spring-boot-jdbc:compileTestJava \
    :bluetape4k-exposed-spring-boot-jdbc:test \
    --tests '*ExposedJdbcRepositoryAbiCompatibilityTest' \
    --rerun-tasks --no-configuration-cache --console=plain
  ```

  이 task는 기존 호환성 baseline을 PASS로 고정한다. factory collaborator wiring의
  RED/GREEN은 production wiring과 같은 Task 4 안에서 닫는다. fixture compile,
  reflection descriptor, `javap` 중 하나라도 실패하면 Task 2로 진행하지 않는다.

### Task 2: immutable plan과 strict property/projection mapper를 TDD 구현

**Files:**

- Create: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/support/JdbcFluentQueryPlan.kt`
- Create: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/support/JdbcPersistentPropertyResolver.kt`
- Create: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/support/JdbcProjectionMapper.kt`
- Modify: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/mapping/ExposedMappingContext.kt`
- Create: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/support/JdbcFluentQueryPlanTest.kt`
- Create: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/support/JdbcPersistentPropertyResolverTest.kt`
- Create: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/support/JdbcProjectionMapperTest.kt`
- Create: `spring-boot/jdbc/src/test/java/io/bluetape4k/spring/data/exposed/jdbc/support/UserNameRecord.java`

- [ ] **Step 1: plan state transition RED tests를 작성한다**

  `as` last-wins, `project` immutable snapshot/last-wins, order independence,
  `sortBy` append, unsorted no-op, null/negative rejection, `limit(0)` unlimited,
  callback/thread/transaction escape를 검증한다.

- [ ] **Step 2: strict property와 projection shape RED tests를 작성한다**

  하나의 internal `JdbcPersistentPropertyResolver`가 plan projection, mapper,
  QBE compiler, sort의 logical property resolution을 담당한다. exact
  camelCase/snake_case resolution, unknown/ambiguous/nested rejection,
  Entity partial selection rejection, closed/open interface 판정, required property
  missing/extra rejection을 SQL 없이 검증한다. Exposed delegated DAO getter가 기존
  field-only discovery에서 누락되면 `ExposedMappingContext`가 root table column과
  일치하는 declared bean property만 persistent metadata로 보강한다.

- [ ] **Step 3: mapper RED tests를 작성한다**

  Kotlin data class, Java record, getter-only interface, inherited getter,
  `EntityID` unwrap, primitive/Kotlin non-null/nullable reference, enum/temporal exact
  type을 검증한다. constructor 없음/모호함/parameter-name 누락, arbitrary
  conversion, mapping failure redaction은 deterministic exception이어야 한다.

- [ ] **Step 4: RED를 관찰한다**

  ```bash
  ./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
    --tests '*JdbcFluentQueryPlanTest' \
    --tests '*JdbcPersistentPropertyResolverTest' \
    --tests '*JdbcProjectionMapperTest' \
    --rerun-tasks --no-configuration-cache --console=plain
  ```

- [ ] **Step 5: 최소 GREEN 구현을 추가한다**

  plan은 immutable data만 보관하고 resolver 외의 구현은 독자적인 이름 해석을
  만들지 않는다. `ReturnedType.getInputProperties()`는 input
  이름에만 사용하고 DTO constructor는
  `PreferredConstructorDiscoverer.discover()`로 탐색한다. mapper source에는
  `ResultRow`, `EntityID`, DAO Entity가 남지 않게 eager copy/validation한다.

- [ ] **Step 6: pure unit tests GREEN을 확인한다**

  Step 4 명령의 대상 테스트가 모두 PASS해야 한다.

### Task 3: 모든 QBE terminal이 공유하는 predicate compiler를 TDD 구현

**Files:**

- Create: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/support/JdbcExamplePredicateCompiler.kt`
- Create: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/support/JdbcExamplePredicateCompilerTest.kt`
- Modify: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/QueryByExampleTestJdbc.kt`

- [ ] **Step 1: attached probe RED matrix를 작성한다**

  같은 Exposed transaction에서 이미 조회된 persisted DAO만 허용한다. 신규,
  다른 transaction, detached probe는 property getter/SQL 전에
  `InvalidDataAccessApiUsageException`이어야 한다. ID는 항상 제외하고 모든
  property가 ignored/null-ignore이면 `Op.TRUE`이다.

- [ ] **Step 2: matcher RED matrix를 작성한다**

  `matchingAll`/`matchingAny`, flat ignored path, null include/ignore, exact,
  containing, starting, ending, property transformer를 검증한다. `%`, `_`, escape
  character는 literal LIKE pattern으로 escape한다. regex, nested path, ignore-case,
  unsupported string matcher는 값 접근과 SQL 전에 실패해야 한다.

- [ ] **Step 3: 단일 accessor read와 redaction을 검증한다**

  각 included property getter와 transformer가 정확히 한 번 호출되는지 확인하고,
  exception/log에는 probe 값, ID, raw SQL, physical table/column이 없어야 한다.

- [ ] **Step 4: RED를 관찰한다**

  ```bash
  ./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
    --tests '*JdbcExamplePredicateCompilerTest' \
    --tests '*QueryByExampleTestJdbc' \
    --rerun-tasks --no-configuration-cache --console=plain
  ```

- [ ] **Step 5: 최소 compiler를 구현하고 GREEN을 확인한다**

  structural validation → attachment → single read → null handler → property/default
  matcher → transformer → bound `Op` 순서를 지킨다. 모든 terminal이 재사용할 수
  있는 internal compiler만 추가한다.

### Task 4: terminal SQL executor와 repository 연결을 TDD 구현

**Files:**

- Create: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/support/JdbcFluentQueryExecutor.kt`
- Create: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/JdbcFluentQueryIntegrationTest.kt`
- Modify: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/support/SimpleExposedJdbcRepository.kt`
- Modify: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/support/ExposedJdbcRepositoryFactory.kt`

- [ ] **Step 1: factory/direct collaborator wiring RED tests를 작성한다**

  `ExposedJdbcRepositoryFactory`가 factory-owned 단일
  `ExposedMappingContext`, protected `getProjectionFactory()`, factory 생성 모드를
  non-public repository creator에 전달하는지 검증한다. direct one-argument
  constructor는 local mapping context, `SpelAwareProxyProjectionFactory`, direct 생성
  모드를 사용한다. 두 경로 모두 새 public constructor overload 없이 기존 JVM
  descriptor를 유지해야 한다.

- [ ] **Step 2: projection/Entity terminal RED tests를 작성한다**

  interface/data class/Java record가 필요한 column만 select하고, Entity path는 full
  row와 identity cache를 보존하며 selected-row path는 cache를 변경하지 않는지
  검증한다. callback에서 plan 자체를 반환하거나 callback 뒤에 terminal을 호출하면
  SQL 전에 실패해야 한다.

- [ ] **Step 3: sort/limit/cardinality/page RED tests를 작성한다**

  sort append와 Pageable override, unsupported sort option, limit last-wins/0,
  first `LIMIT 1`, one `LIMIT 2`, all positive limit, lazy page count, count/exists의
  sort/projection/limit 무시를 검증한다. `findOne`/`oneValue` 다건은
  `IncorrectResultSizeDataAccessException`이어야 한다.

- [ ] **Step 4: SQL shape와 statement budget을 고정한다**

  test `SqlLogger`로 selected columns, order, limit, offset, root ID exists, fresh
  count SQL을 확인한다. first/one/all/count/exists는 1 statement, page는 최대 2
  statements이고 last-page optimization은 count를 생략해야 한다.

- [ ] **Step 5: custom query fail-fast RED tests를 작성한다**

  root-table filter-only query는 content/count 의미를 공유한다. join, group/having,
  distinct, aggregate, custom order/limit/offset, `forUpdate` shape는 SQL 전에
  거부한다.

- [ ] **Step 6: RED를 관찰한다**

  ```bash
  ./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
    --tests '*JdbcFluentQueryIntegrationTest' \
    --tests '*QueryByExampleTestJdbc' \
    --rerun-tasks --no-configuration-cache --console=plain
  ```

- [ ] **Step 7: executor와 repository delegation을 최소 구현한다**

  mutable `Query` 생성/변경은 executor에만 둔다. 기존 in-memory
  `SimpleFluentQuery`, reflection QBE builder, comparator를 제거하고 모든
  `QueryByExampleExecutor` method를 compiler/executor에 연결한다. factory는 자신이
  소유한 mapping context와 protected `ProjectionFactory`를 non-public repository
  creator로 전달하되 기존 public constructor descriptor를 유지한다.

- [ ] **Step 8: GREEN과 기존 CRUD/PartTree 회귀를 확인한다**

  Step 6 및 `SimpleExposedJdbcRepositoryTest`, `PartTreeExposedJdbcQueryTest`가
  PASS해야 한다.

### Task 5: JDBC cursor stream을 TDD 구현

**Files:**

- Create: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/support/JdbcResultRowStream.kt`
- Create: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/support/JdbcResultRowStreamTest.kt`
- Modify: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/JdbcFluentQueryIntegrationTest.kt`

- [ ] **Step 1: resource ownership RED tests를 작성한다**

  MockK JDBC `ResultSet`/statement와 H2 actual cursor에서 lazy one-row advance,
  exhaustion, explicit close, short-circuit, mapper exception, double close를
  검증한다. 모든 종료 경로에서 ResultSet/statement close count는 정확히 1이다.

- [ ] **Step 2: transaction/thread RED tests를 작성한다**

  같은 owner thread와 caller-owned transaction에서는 소비 가능하고, wrong
  thread, closed/wrong transaction, factory-owned new Spring transaction, direct
  constructor transaction 밖에서는 cursor open/advance 전에 실패해야 한다.

  non-public creator가 `FACTORY`/`DIRECT` 생성 모드를 repository에 전달한다.
  factory 모드는 stream terminal 진입 시 Spring repository interceptor의
  `TransactionAspectSupport.currentTransactionStatus().isNewTransaction`을 기록한다.
  `isNewTransaction=true`이면 method 반환과 함께 transaction이 종료되므로 cursor를
  SQL 전에 거부하고, `false`인 joined outer transaction만 허용한다. direct 모드는
  Spring 상태에 의존하지 않고 caller-owned Exposed transaction identity를 요구한다.
  joined factory / new factory / direct-with-transaction / direct-without-transaction을
  별도 테스트로 고정한다.

- [ ] **Step 3: driver constraint RED tests를 작성한다**

  `supportsMultipleResultSets=false` test double에서도 upfront list fallback이 없어야
  한다. 열린 cursor 소비 중 동일 transaction의 nested SQL은 지원하지 않으며
  명확한 close/exception 동작을 확인한다.

- [ ] **Step 4: RED를 관찰한다**

  ```bash
  ./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
    --tests '*JdbcResultRowStreamTest' \
    --tests '*JdbcFluentQueryIntegrationTest' \
    --rerun-tasks --no-configuration-cache --console=plain
  ```

- [ ] **Step 5: direct JDBC lease를 최소 구현한다**

  `Query.iterator()`를 사용하지 않는다. `JdbcTransaction.execQuery` callback에서
  받은 `ResultSet`과 `resultSet.statement`를 single-use lease가 소유한다.
  `ResultRow` adapter의 `@OptIn(InternalApi::class)`는 이 파일에만 격리한다.
  `Stream.onClose`, exhaustion, mapper failure가 동일 idempotent close를 호출한다.

- [ ] **Step 6: cursor tests GREEN을 확인한다**

  Step 4 명령에서 upfront materialization이 없고 resource leak이 0이어야 한다.

### Task 6: 대표 dialect와 factory/direct 경로를 순차 검증

**Files:**

- Modify: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/MultiDbExposedJdbcRepositoryTest.kt`
- Modify: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/JdbcFluentQueryIntegrationTest.kt`

- [ ] **Step 1: factory/direct parity를 동일 probe와 transaction으로 검증한다**

  factory-created repository와 direct one-argument repository에 동일한 attached
  persisted probe와 같은 transaction fixture를 적용한다. closed getter interface,
  Kotlin data class, Java record의 selected-column 결과와 unknown/missing/open
  projection 예외 type/message/redaction이 같아야 한다. direct 경로는 명시적
  `transaction {}` 안에서만 실행하고 factory 경로는 joined outer transaction과
  factory-owned new transaction의 차이까지 검증한다.

- [ ] **Step 2: H2 전체 semantic matrix를 실행한다**

  ```bash
  EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
    --tests '*JdbcFluentQuery*' --tests '*QueryByExample*' \
    --rerun-tasks --no-configuration-cache --console=plain
  ```

- [ ] **Step 3: PostgreSQL 대표 subset을 실행한다**

  ```bash
  EXPOSED_TEST_DB=POSTGRESQL ./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
    --tests '*MultiDbExposedJdbcRepositoryTest' \
    --rerun-tasks --no-configuration-cache --console=plain
  ```

- [ ] **Step 4: MySQL V8 대표 subset을 실행한다**

  ```bash
  EXPOSED_TEST_DB=MYSQL_V8 ./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
    --tests '*MultiDbExposedJdbcRepositoryTest' \
    --rerun-tasks --no-configuration-cache --console=plain
  ```

  PostgreSQL/MySQL은 selected columns, LIKE escape, null/conversion,
  limit/offset/count/exists, cursor close만 대표 검증하며 순차 실행한다.

### Task 7: KDoc, EN/KO README, unreleased 기록을 동기화

**Files:**

- Modify: `spring-boot/jdbc/README.md`
- Modify: `spring-boot/jdbc/README.ko.md`
- Modify: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/ExposedJdbcRepository.kt`
- Modify: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/support/SimpleExposedJdbcRepository.kt`
- Modify: `CHANGELOG.md`
- Modify: `WIP.md`
- Create: `scripts/validate_module_readme_parity.rb`
- Create: `scripts/validate_module_readme_parity_test.rb`
- Exclude: `docs/manual/**`

- [ ] **Step 1: caller workflow를 EN/KO parity로 작성한다**

  attached persisted probe, closed interface, Kotlin data class, Java record,
  `as`/`project`, sort append/Pageable override, first/one/all/page 예제를 동일 의미로
  제공한다. getter-only closed projection과 `@Value`/SpEL open projection을 나란히
  보여준다.

- [ ] **Step 2: transaction/cursor 오용을 방지한다**

  factory-created repository를 권장하고 direct constructor에는 caller-owned
  `transaction {}`가 필요하다고 명시한다. cursor는 outer transaction/same thread/
  try-with-resources 또는 `use`가 필수이며 소비 중 nested SQL은 금지한다.

- [ ] **Step 3: 1.13.0 unreleased change를 기록한다**

  기존 eager cast/no-op behavior, 새 fail-fast 경계, attached-probe/cursor contract를
  `CHANGELOG.md`와 `WIP.md`에 한국어로 기록한다. 아직 1.13.0 release가 아니므로
  안정 버전이 고정된 `docs/manual/**`는 변경하지 않는다.

- [ ] **Step 4: writer/parity 검증을 실행한다**

  두 README의 새 FluentQuery 계약 구간에 동일한 marker를 두고, 전용 Ruby
  validator가 marker 존재/유일성, code fence 수, inline technical identifier set,
  local Markdown link target 존재를 검사한다. 두 marker 구간에는 language-neutral
  contract keys `attached-probe`, `closed-projection`, `open-projection-rejected`,
  `first-one-all-page-count-exists`, `cursor-outer-transaction`,
  `cursor-same-thread`, `cursor-explicit-close`를 모두 기록하며 validator가 key set을
  exact 비교한다. validator 자체는 fixture 기반 positive/negative test로 고정한다.

  ```bash
  ruby scripts/validate_module_readme_parity_test.rb
  ruby scripts/validate_module_readme_parity.rb \
    spring-boot/jdbc/README.md spring-boot/jdbc/README.ko.md
  git diff --check
  git diff --quiet -- docs/manual/**
  ```

  마지막 명령은 worktree guard이며 exit code 0이어야 한다. 최종 commit 뒤에는
  `git diff --quiet "$(git merge-base origin/develop HEAD)"..HEAD -- docs/manual/**`
  로 base-to-head stable manual 부재도 별도 확인한다.

### Task 8: 전체 검증, 독립 pre-PR review, workflow DoD

**Files:**

- Create: `docs/lessons/2026-08-16-issue-642-jdbc-fluentquery-projection.md`
- Review: all Issue #642 changed paths
- Workflow evidence: canonical checkout
  `/Users/debop/work/bluetape4k/bluetape4k-exposed/.bluetape/runs/20260816T024443Z-2d3cf954/`
- Create runtime inputs: `.bluetape/handles/issue-642-check-*.json`
- Create runtime input: `.bluetape/handles/issue-642-component-evidence-input.json`
- Create runtime input: `.bluetape/handles/issue-642-changed-paths.json`
- Create runtime evidence: `.bluetape/handles/issue-642-implementation-result-evidence.json`
- Create runtime evidence: `.bluetape/handles/issue-642-verification-evidence.json`
- Create runtime evidence: `.bluetape/handles/issue-642-main-verification-evidence.json`

- [ ] **Step 1: targeted와 module test를 fresh 실행한다**

  ```bash
  ./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
    --rerun-tasks --no-configuration-cache --console=plain
  ```

- [ ] **Step 2: compile, Detekt, API/ABI를 검증한다**

  ```bash
  ./gradlew :bluetape4k-exposed-spring-boot-jdbc:compileKotlin \
    :bluetape4k-exposed-spring-boot-jdbc:compileTestKotlin \
    :bluetape4k-exposed-spring-boot-jdbc:compileTestJava \
    :bluetape4k-exposed-spring-boot-jdbc:detekt \
    --rerun-tasks --no-configuration-cache --console=plain

  javap -classpath spring-boot/jdbc/build/classes/kotlin/main -s \
    io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository
  javap -classpath spring-boot/jdbc/build/classes/kotlin/main -s \
    io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedJdbcRepositoryFactory
  ```

  `javap`/reflection/Java-Kotlin compile fixture에서 기존 descriptor와 public method
  surface가 유지되고 새 public overload가 없어야 한다.

- [ ] **Step 3: 6개 관점 pre-PR review를 수렴한다**

  performance, security, developer/API, stability, operability, user/caller와 main
  integration review를 최신 exact diff에 수행하고 P0=0/P1=0을 확인한다.

- [ ] **Step 4: lesson과 workflow receipt를 닫는다**

  재사용 가능한 cursor/transaction/projection guard를 lesson에 기록한다.
  Task 0에서 등록한 `spring-boot-jdbc` topology는 재등록하지 않는다. implementation
  lane을 exact changed-path evidence로 완료한 뒤 `targeted-tests`, `module-tests`,
  `multi-db-tests`, `kotlin-static-analysis`, `abi-compatibility`, `docs-parity`,
  `pre-pr-review` 각각에 다음 fixed input shape를 사용해 `check-result`를 순차 기록한다.

  ```json
  {"component_id":"spring-boot-jdbc","check_id":"<required-check>","passed":true,"reason":"fresh exact command passed"}
  ```

  각 mutation은 직전 checksum을 CAS로 사용한다. 모든 check가 PASS이고 owner lane이
  completed인 뒤 input `{"component_id":"spring-boot-jdbc"}`와 bounded verification
  evidence로 `component-evidence`를 기록한다. pre-complete `completion-check`에서는
  `missing_main_verification=true`가 정상이다. 그 외 missing lane/component,
  failed check, incomplete replacement, unresolved failed lane이 모두 0인지 `jq -e`로
  검사한 뒤에만 `complete`를 수행한다. 하나라도 누락되면 receipt를 완료하지 않고
  해당 검증 단계로 돌아간다.

  `.bluetape/handles/issue-642-changed-paths.json`은 fresh
  `git diff --name-only "$(git merge-base origin/develop HEAD)"` 결과를 JSON string
  array로 정확히 기록한다. 일곱 check input은 파일명과 같은 `check_id`를 쓰며 모두
  `{"component_id":"spring-boot-jdbc","check_id":"...","passed":true,
  "reason":"fresh exact command passed"}` shape이다. 다음 명령을 canonical
  checkout에서 실행한다.

  ```bash
  FLOW=/Users/debop/.codex/skills/bluetape-workflow/scripts/bluetape-flow.py
  VERIFY_JSON=$("$FLOW" verify --run-id 20260816T024443Z-2d3cf954)
  EXPECTED_HEAD=$(jq -er '.checksum' <<<"$VERIFY_JSON")
  NOW=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  LANE_COMPLETE_JSON=$("$FLOW" lane-complete \
    --run-id 20260816T024443Z-2d3cf954 \
    --owner-file .bluetape/handles/issue-642-fluentquery.owner \
    --expected-head "$EXPECTED_HEAD" \
    --evidence .bluetape/handles/issue-642-implementation-result-evidence.json \
    --lane-id implementation --agent-id root --at "$NOW" \
    --changed-paths .bluetape/handles/issue-642-changed-paths.json)
  EXPECTED_HEAD=$(jq -er '.checksum' <<<"$LANE_COMPLETE_JSON")

  for CHECK_ID in targeted-tests module-tests multi-db-tests \
    kotlin-static-analysis abi-compatibility docs-parity pre-pr-review; do
    CHECK_JSON=$("$FLOW" check-result \
      --run-id 20260816T024443Z-2d3cf954 \
      --owner-file .bluetape/handles/issue-642-fluentquery.owner \
      --expected-head "$EXPECTED_HEAD" \
      --evidence .bluetape/handles/issue-642-verification-evidence.json \
      --input ".bluetape/handles/issue-642-check-${CHECK_ID}.json")
    EXPECTED_HEAD=$(jq -er '.checksum' <<<"$CHECK_JSON")
  done

  COMPONENT_JSON=$("$FLOW" component-evidence \
    --run-id 20260816T024443Z-2d3cf954 \
    --owner-file .bluetape/handles/issue-642-fluentquery.owner \
    --expected-head "$EXPECTED_HEAD" \
    --evidence .bluetape/handles/issue-642-verification-evidence.json \
    --input .bluetape/handles/issue-642-component-evidence-input.json)
  EXPECTED_HEAD=$(jq -er '.checksum' <<<"$COMPONENT_JSON")
  COMPLETION_JSON=$("$FLOW" completion-check \
    --run-id 20260816T024443Z-2d3cf954)
  if ! jq -e '
    .ok == true and
    .complete == false and
    .missing_main_verification == true and
    (.missing_lanes | length == 0) and
    (.missing_components | length == 0) and
    (.failed_checks | length == 0) and
    (.incomplete_replacements | length == 0) and
    (.unresolved_failed_lanes | length == 0)
  ' <<<"$COMPLETION_JSON"; then
    exit 1
  fi
  NOW=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  "$FLOW" complete --run-id 20260816T024443Z-2d3cf954 \
    --owner-file .bluetape/handles/issue-642-fluentquery.owner \
    --expected-head "$EXPECTED_HEAD" \
    --evidence .bluetape/handles/issue-642-main-verification-evidence.json \
    --at "$NOW"
  ```

### Task 9: Lore commit, PR, CI, merge 별도 gate

- [ ] **Step 1: Lore protocol로 의도 중심 commit을 만든다**

  한국어 intent line과 `Constraint`, `Rejected`, `Confidence`, `Scope-risk`,
  `Directive`, `Tested`, `Not-tested` trailer를 사용한다.

- [ ] **Step 2: 승인된 repo/base/head로 PR을 생성한다**

  repo `bluetape4k/bluetape4k-exposed`, base `develop`, head
  `feat/spring-jdbc-fluentquery`를 read back하고 한국어 본문 끝에
  `## DoD Status`를 둔다.

- [ ] **Step 3: exact-head CI를 확인한다**

  job-level check, aggregate result, review/thread, mergeability를 최신 head에 대해
  확인한다. `git diff --quiet "$(git merge-base origin/develop HEAD)"..HEAD --
  docs/manual/**`도 다시 실행한다. 실패하면 원인을 재현하고 수정 후 새 head로 다시
  검증한다.

- [ ] **Step 4: merge는 fresh 승인 뒤에만 수행한다**

  CI 성공은 merge authority가 아니다. 최신 exact head 보고 후 사용자의 fresh
  merge 승인을 받아야 하며 auto-merge는 사용하지 않는다. merge 후 develop local
  sync와 proven-clean worktree/branch cleanup을 별도로 수행한다.

## 롤백 및 재실행

- pure plan/mapper/compiler 실패는 해당 internal 파일과 단위 테스트만 되돌린다.
- executor 실패는 `SimpleExposedJdbcRepository` delegation을 기존 구현으로
  복구하되 새 테스트는 원인 재현용으로 유지한다.
- cursor 실패는 cursor terminal만 차단하고 다른 projection terminal을 훼손하지
  않는다. materialized stream fallback은 Issue acceptance와 Spring Data cursor
  contract를 위반하므로 롤백 대안으로 사용하지 않는다.
- public constructor, factory, mapping context 변경은 ABI test를 먼저 복구한 뒤
  targeted → H2 → PostgreSQL → MySQL 순으로 재실행한다.
- `docs/manual/**`와 다른 module은 롤백 범위가 아니다.

## Traceability

| 설계 수용 기준 | 계획 단계 | 검증 증거 |
| --- | --- | --- |
| closed interface/DTO projection | Task 2, 4 | pure mapper + H2 SQL shape |
| exact `project(properties)` selected column | Task 2, 4 | plan/resolver + `SqlLogger` |
| QBE compiler 일관성 | Task 3, 4 | all terminal matrix |
| sort/limit/page/count/exists pushdown | Task 4, 6 | statement budget + dialect subset |
| Entity identity cache 보존 | Task 4 | identity/cache integration test |
| unsafe custom query fail-fast | Task 4 | no-SQL rejection test |
| callback/transaction/thread scope | Task 2, 5 | escape + cursor lifecycle tests |
| cursor no-materialization/close | Task 5, 6 | mock close count + actual DB cursor |
| factory/direct projection 및 예외 parity | Task 4, 5, 6 | 동일 probe/transaction parity matrix |
| exception/log redaction | Task 2, 3 | sensitive-value negative assertions |
| public API/ABI 보존 | Task 1, 4, 8 | reflection, `javap`, Java/Kotlin compile fixture |
| EN/KO/KDoc caller contract | Task 7 | writer/parity/link checks |
| stable manual 보존 | Task 7, 8 | target-path diff audit |

## Writer gate

- `SPW-01`: PASS — Issue #642, Epic #658 slot, exact module, branch, JDK 25,
  Exposed 1.4.0/Spring Data 4.1 contract와 release/manual 경계를 고정했다.
- `SPW-02`: PASS — design → plan review → RED/GREEN → multi-DB → docs → pre-PR
  review → PR/merge gate의 dependency order와 stop condition을 포함했다.
- `SPW-03`: PASS — 한국어 user-facing prose와 technical token/command 보존을
  확인했다.
- `SPW-04`: PASS — 설계 수용 기준을 exact file/task/command/evidence에 매핑했다.
- `SPW-05`: PASS — checklist, code fence, 표, rollback, `docs/manual/**` 제외,
  `## DoD Status` PR 규칙을 read back했다.

## DoD Status

- [x] P0=0/P1=0 확정 설계를 입력으로 사용
- [x] TDD 순서와 exact file ownership 정의
- [x] targeted/H2/PostgreSQL/MySQL/Detekt/API·ABI 검증 정의
- [x] 문서와 안정 manual 경계 정의
- [x] 구현 계획 독립 검토 P0=0/P1=0
- [x] TDD 구현 및 전체 검증
- [x] 독립 pre-PR 검토 P0=0/P1=0
- [ ] Lore commit과 PR 생성
- [ ] exact-head CI 및 fresh 승인 뒤 merge·sync·cleanup

상태: `PENDING` — 설계·계획·TDD 구현·local 검증 gate는 완료됐고 PR 및
exact-head CI가 남아 있다.
