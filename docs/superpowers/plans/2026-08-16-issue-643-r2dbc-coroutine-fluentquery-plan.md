# Issue #643 R2DBC 코루틴 QueryByExample·FluentQuery 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use `$bluetape-workflow`, `$bluetape-full-feature`, `$bluetape-kotlin-patterns`, `test-driven-development`, and `subagent-driven-development`. Complete each task in order; do not start production code before observing its RED test.

**Goal:** `:bluetape4k-exposed-spring-boot-r2dbc`에 코루틴 네이티브 `Example<T>` 조회와 immutable `FluentQuery` plan을 추가한다. 기존 `ExposedR2dbcRepository`와 공개 4인자 `SimpleExposedR2dbcRepository` 생성자는 그대로 유지하고, 새 opt-in 부모 인터페이스를 선택한 repository만 `findOne`·`findAll`·`count`·`exists`·`findBy`를 사용하게 한다.

**Architecture:** Spring Data의 Reactor 계약을 재사용하지 않는다. public API는 `suspend`, `Flow`, `Page`, `Slice`와 Kotlin `KClass`만 노출한다. `findBy` 진입점은 probe와 matcher를 SQL 전에 검증하고 detached immutable snapshot을 만든다. `R2dbcFluentQueryPlan`은 snapshot, canonical property, sort, limit, projection shape만 보관하며 transaction, `ResultRow`, `R2dbcResult`, mutable probe는 보관하지 않는다. `R2dbcFluentQueryExecutor`만 Exposed R2DBC query와 `suspendTransaction`을 만들고, terminal lease로 동일 outer transaction의 동시 connection 사용을 차단한다. factory 경로는 internal collaborator로 domain type과 `ProjectionFactory`를 전달하고, direct 공개 생성자는 기존 시그니처와 기본 mapper 경계를 보존한다.

**Tech Stack:** Kotlin 2.4, JDK 25, Spring Data Commons 4.1, Spring Boot 4, JetBrains Exposed 1.4.0 R2DBC, kotlinx.coroutines `Flow`, JUnit 5, bluetape4k assertions, MockK, H2 R2DBC, PostgreSQL R2DBC, MySQL 8 R2DBC, Gradle, `javap` ABI fixture.

---

## 범위와 고정 계약

- Issue: [#643](https://github.com/bluetape4k/bluetape4k-exposed/issues/643)
- 대상 모듈: `:bluetape4k-exposed-spring-boot-r2dbc`
- 대상 릴리스: `1.13.0` 개발선. 현재 안정 릴리스는 `1.12.1`이므로 `docs/manual/**`는 변경하지 않는다.
- 선택한 기능: 코루틴 네이티브 계약 1번만 구현한다.
- `ExposedJdbcRepository`는 이번 slot에서 변경하거나 새 QBE executor와 결합하지 않는다. JDBC QBE parity 또는 shared abstraction은 별도 issue에서 동일 ABI·transaction 증거를 갖춘 뒤 다룬다.
- 구현하지 않는 기능: `ReactiveQueryByExampleExecutor`, `ReactiveFluentQuery`, `Mono`, `Flux`, Reactor 이중 facade, raw `@Query`, nested/open/SpEL projection, arbitrary `ConversionService` coercion, repository 소유 `R2dbcDatabase` 주입.
- 기존 `ExposedR2dbcRepository`에는 abstract method를 추가하지 않는다. 새 `ExposedR2dbcQueryByExampleRepository<R, ID>`와 `ExposedCoroutineQueryByExampleExecutor<T>`가 opt-in surface다.
- `SimpleExposedR2dbcRepository(table, toDomainMapper, persistValuesProvider, idExtractor)` 공개 constructor descriptor는 변경하지 않는다.
- caller가 선택한 database와 transaction은 그대로 소유한다. 다중 DB 호출은 `suspendTransaction(database) { ... }` 안에서 수행하며 Spring `transactionManagerRef` bridge는 도입하지 않는다.
- Exposed 1.4.0 outer transaction에서 `useNestedTransactions=false`인 경우 기존 transaction/connection을 재사용하고 commit/close하지 않는다. `useNestedTransactions=true`인 outer 호출은 savepoint/SQL 전에 `InvalidDataAccessApiUsageException`으로 거부한다.
- `all()`과 `findAll(...)`은 cold `Flow`다. 생성 시 SQL을 실행하지 않고, 수집마다 새 query/result를 만든다. callback 밖으로 안전하게 탈출할 수 있는 lazy 결과는 이 `Flow`뿐이다.

## 파일 책임 지도

### Public API

- Create: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/ExposedCoroutineQueryByExampleExecutor.kt`
  - `suspend findOne`, `Flow findAll`, `Flow findAll(example, sort)`, `suspend count/exists`, `suspend findBy`를 정의한다.
- Create: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/ExposedCoroutineFluentQuery.kt`
  - `sortBy`, `limit`, `asType(KClass)`, `project`, `one`, `first`, `all`, `page`, `slice`, `count`, `exists` 계약을 정의한다.
- Create: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/ExposedR2dbcQueryByExampleRepository.kt`
  - `@NoRepositoryBean`을 유지하면서 기존 CRUD 계약과 새 QBE executor를 opt-in으로 결합한다. Spring scan은 이 부모 자체를 factory 대상에 등록하지 않고 concrete opt-in repository만 등록한다.
- Modify only KDoc: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/ExposedR2dbcRepository.kt`
  - base ABI를 바꾸지 않고 opt-in child contract와 선택 기준을 링크한다.

### Internal execution

- Create: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/support/R2dbcDiagnosticSanitizer.kt`
  - property token을 control/format/line/paragraph separator 제거 후 128자로 제한하고, 내부 `R2dbcQbeOperation` 고정 enum allowlist에서 선택한 low-cardinality operation/metric label만 오류에 남긴다. 동적·미등록 label은 거부한다.
- Create: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/support/R2dbcBindValueSnapshotter.kt`
  - immutable scalar whitelist와 array/`ByteBuffer`/collection/map defensive deep copy를 제공한다. 복제할 수 없는 custom mutable value는 SQL 전에 거부한다.
- Create: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/support/R2dbcExampleSnapshot.kt`
  - matcher, canonical property, null 정책, transformer 결과, detached bind 값의 순서가 고정된 snapshot을 보관한다. 원본 `Example`/probe는 저장하지 않는다.
- Create: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/support/R2dbcPersistentPropertyResolver.kt`
  - domain getter/constructor metadata와 `IdTable` column의 exact, camelCase/snake_case 유일 대응을 해석한다. unknown/ambiguous/nested를 조기 거부한다.
- Create: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/support/R2dbcExamplePredicateCompiler.kt`
  - snapshot만 읽어 `Op<Boolean>`와 bound parameter를 만든다. matcher 해석을 terminal별로 중복하지 않는다.
- Create: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/support/R2dbcProjectionMapper.kt`
  - full domain mapping과 분리된 selected-row mapper다. closed getter interface, Kotlin data class, Java record의 detached value만 반환한다.
- Create: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/support/R2dbcFluentQueryPlan.kt`
  - callback scope와 immutable fluent transition(`sortBy` 누적, `limit`/`asType`/`project` last-wins)을 관리한다.
- Create: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/support/R2dbcTransactionLease.kt`
  - 동일 outer `R2dbcTransaction`의 terminal/Flow collection을 직렬화하고 success/exception/cancellation 모두 `finally`에서 release한다.
- Create: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/support/R2dbcFluentQueryExecutor.kt`
  - transaction 진입, predicate, selected columns, cardinality, page/slice, exists/count, cold Flow와 lease를 단일 실행 경계로 소유한다. 내부 `R2dbcQbeConstructionMode { FACTORY, DIRECT }` enum으로 construction 경계를 고정한다.
- Modify: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/support/SimpleExposedR2dbcRepository.kt`
  - QBE executor 위임과 internal collaborator registry를 추가하되 기존 CRUD와 공개 constructor를 유지한다.
- Modify: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/support/ExposedR2dbcRepositoryFactory.kt`
  - factory-owned domain type, mapper, `ProjectionFactory`를 internal 경로로 전달하고 QBE method를 PartTree/declared query보다 먼저 direct dispatch한다.

### Tests and fixtures

- Create unit tests:
  - `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/support/R2dbcDiagnosticSanitizerTest.kt`
  - `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/support/R2dbcBindValueSnapshotterTest.kt`
  - `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/support/R2dbcFluentQueryPlanTest.kt`
  - `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/support/R2dbcPersistentPropertyResolverTest.kt`
  - `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/support/R2dbcExamplePredicateCompilerTest.kt`
  - `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/support/R2dbcProjectionMapperTest.kt`
  - `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/support/R2dbcTransactionLeaseTest.kt`
  - `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/support/ExposedR2dbcRepositoryFactoryQbeTest.kt`
- Create integration tests:
  - `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/R2dbcFluentQueryIntegrationTest.kt` (Spring factory path, H2 semantic matrix, SQL logger shape, transaction/cancellation)
  - `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/R2dbcFluentQueryMultiDbTest.kt` (direct path, PostgreSQL/MySQL V8 sequential representative matrix)
- Modify fixture:
  - `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/UserR2dbcRepository.kt` — QBE coverage가 필요한 test repository만 새 opt-in 부모를 상속한다.
  - Create `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/domain/UserProjectionFixtures.kt` — closed interface와 data-class projection fixture.
- Create Java projection fixture: `spring-boot/r2dbc/src/test/java/io/bluetape4k/spring/data/exposed/r2dbc/domain/UserNameRecord.java`
- Create ABI fixtures:
  - `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/support/ExposedR2dbcRepositoryAbiCompatibilityTest.kt`
  - `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/support/ExposedR2dbcRepositoryKotlinConsumerFixture.kt`
  - `spring-boot/r2dbc/src/test/java/io/bluetape4k/spring/data/exposed/r2dbc/support/ExposedR2dbcRepositoryJavaConsumerFixture.java`
  - `spring-boot/r2dbc/src/test/resources/abi/exposed-r2dbc-repository-public.txt`

### Reader-facing records

- Modify: `spring-boot/r2dbc/README.md`, `spring-boot/r2dbc/README.ko.md` — coroutine-only QBE, matcher 지원/거부, projection, cold Flow, outer transaction, nested transaction fail-fast, callback scope, cancellation을 동일 계약으로 설명한다.
- Modify KDoc in the three new public interfaces and `SimpleExposedR2dbcRepository`.
- Create: `scripts/validate_r2dbc_fluentquery_readme_parity.rb`
- Create: `scripts/validate_r2dbc_fluentquery_readme_parity_test.rb` — EN/KO marker의 contract key, code-fence language, technical identifier, local-link parity를 exact 비교한다.
- Modify: `CHANGELOG.md`, `WIP.md` — `[Unreleased]`/`1.13.0` 개발선에 Issue #643을 한국어로 기록한다.
- Create after implementation: `docs/lessons/2026-08-16-issue-643-r2dbc-coroutine-fluentquery.md` — transaction ownership, cold Flow, snapshot, projection, lease guard를 재사용 가능한 lesson으로 남긴다.
- Exclude: `docs/manual/**`, module registration, dependency catalog, CI workflow, schema migration, new dependency.

## Task 0: 구현 전 workflow와 baseline 고정

**Files/evidence:** existing run `.bluetape/runs/20260816T123827Z-076242ba/`, existing owner handle `.bluetape/handles/issue-643-r2dbc-fluentquery-worktree-20260816.owner`, component `spring-boot-r2dbc`, and plan-main lane created for this plan.

- [ ] `bluetape-flow.py verify --run-id 20260816T123827Z-076242ba`가 trusted receipt checksum을 반환하는지 확인한다. canonical checkout과 feature worktree의 state root를 혼동하지 않는다.
- [ ] 구현 승인 후에만 `implementation` lane을 새로 만들고 `lane-start`와 `startup-ack`를 수행한다. plan-main lane은 plan/review artifact만 소유한다.
- [ ] active component topology는 `spring-boot-r2dbc` 하나로 유지한다. 현재 run의 required checks는 이미 등록된 `spec-review`, `plan-review`, `implementation-verification`, `pre-pr-review`, `ci-review`를 그대로 사용하며 component를 재등록하지 않는다. PostgreSQL/MySQL `multi-db-tests`는 현재 CI workflow에 독립 producer가 없으므로 별도 required check로 선언하지 않고, Task 6의 순차 backend evidence를 `implementation-verification` 입력으로 강제한다. backend unavailable/timeout 또는 0 test count는 해당 evidence를 incomplete/failed로 분류한다.
- [ ] implementation 시작 전 baseline을 실행한다.

  ```bash
  ./gradlew :bluetape4k-exposed-spring-boot-r2dbc:compileKotlin \
    :bluetape4k-exposed-spring-boot-r2dbc:compileTestKotlin \
    :bluetape4k-exposed-spring-boot-r2dbc:test \
    --tests '*SimpleExposedR2dbcRepositoryTest' \
    --tests '*PartTreeExposedR2dbcQueryTest' \
    --tests '*DeclaredExposedR2dbcQueryTest' \
    --no-configuration-cache --console=plain
  ```

  기존 CRUD/derived/declared test가 실패하면 새 QBE 작업을 시작하지 않고 baseline 원인을 기록한다. Docker backend가 unavailable이면 H2 baseline과 backend skip을 분리해 기록하며 skip을 PASS로 세지 않는다.

## Task 1: public opt-in API와 ABI를 RED/GREEN으로 고정

**Files:** the three public interface files, ABI fixtures listed above, and no change to `ExposedR2dbcRepository.kt` method set.

- [ ] **RED — API compile fixture를 먼저 작성한다.** Kotlin fixture는 `ExposedR2dbcQueryByExampleRepository<User, Long>`를 상속하고 `findOne(example)`, `findAll(example)`, `findAll(example, Sort.by("name"))`, `findBy(example) { query -> query.asType(UserNameView::class).project("name").all() }`를 컴파일한다. Java fixture는 `UserNameRecord` class가 projection target으로 classpath에 존재하는지 확인하되 Java 전용 `Class<R>` overload를 요구하지 않는다.
- [ ] **RED — ABI snapshot을 작성한다.** `javap -s`와 reflection으로 기존 `ExposedR2dbcRepository` method descriptor와 `SimpleExposedR2dbcRepository` 4인자 constructor descriptor를 checked-in resource와 비교한다. 새 child interface의 method set도 별도 resource로 고정한다.
- [ ] 실패를 관찰한다.

  ```bash
  ./gradlew :bluetape4k-exposed-spring-boot-r2dbc:compileTestKotlin \
    :bluetape4k-exposed-spring-boot-r2dbc:compileTestJava \
    :bluetape4k-exposed-spring-boot-r2dbc:test \
    --tests '*ExposedR2dbcRepositoryAbiCompatibilityTest' \
    --rerun-tasks --no-configuration-cache --console=plain
  ```

- [ ] **GREEN — public API를 추가한다.** `Example<T>`만 받는 coroutine executor와 `KClass` 기반 `asType`를 구현한다. `asType`의 Java `Class` overload, Reactor return type, base interface abstract method는 추가하지 않는다. `project`는 `vararg properties: String`으로 받고 빈 호출은 projection type의 required input 자동 선택/reset으로 해석한다. 새 QBE 부모 interface와 executor에는 Spring scan 대상이 아님을 보장하는 `@NoRepositoryBean`을 유지한다.
- [ ] 동일 명령과 `javap`를 다시 실행해 기존 descriptor가 동일하고 새 API descriptor가 설계 문서와 일치하는지 확인한다. ABI fixture가 깨지면 다음 task로 진행하지 않는다.

## Task 2: detached snapshot, fluent plan, diagnostic guard를 TDD 구현

**Files:** `R2dbcDiagnosticSanitizer.kt`, `R2dbcBindValueSnapshotter.kt`, `R2dbcExampleSnapshot.kt`, `R2dbcFluentQueryPlan.kt`, `R2dbcTransactionLease.kt` and their unit tests.

- [ ] **RED — immutable bind matrix를 작성한다.** String/primitive wrapper/`BigInteger`/`BigDecimal`/`UUID`/enum/Java-Kotlin time은 그대로 보관하고, primitive/object array, `ByteBuffer`, collection, map은 recursive defensive copy를 만든다. custom mutable type, unsupported `Number`, transformer가 반환한 복제 불가 value는 `InvalidDataAccessApiUsageException`으로 SQL 전에 실패해야 한다. factory와 direct 양쪽 모두 `expectedDomainType`과 probe runtime type이 정확히 일치하는지 getter/transformer/SQL 전에 검증하고, raw/unchecked `Example` 및 subtype mismatch는 동일한 예외로 거부한다.
- [ ] **RED — cancellation/error 경계를 고정한다.** `CancellationException`과 `Error`는 동일 object로 전파하고 일반 mapping/getter/transformer 예외만 안전한 `MappingException`으로 바꾼다. sanitized exception에는 raw value, probe, ID, constructor secret, reflective wrapper, cause/suppressed graph가 없어야 한다. operation/metric label은 내부 `R2dbcQbeOperation` 고정 enum의 allowlist만 허용하고 동적·미등록 label은 `IllegalArgumentException`으로 SQL 전에 거부한다. property 진단 token은 control/format/separator 제거와 128자 제한을 통과한다.
- [ ] **RED — plan transition을 고정한다.** `sortBy`는 기존 sort 뒤에 누적하고, `limit`/`asType`/`project`는 last-wins, `limit(0)`은 unlimited, null/empty sort와 음수 limit은 즉시 `IllegalArgumentException`이어야 한다. 빈 `project()`는 projection type의 required input 자동 선택/reset으로 해석하고, non-empty `project`는 source property exact-set 규칙을 적용한다. plan은 original `Example`/mutable probe/transaction을 보관하지 않는다.
- [ ] **RED — scope와 lease를 고정한다.** callback 종료 후 fluent mutation/terminal은 `InvalidDataAccessApiUsageException`이어야 한다. callback 안에서 만든 `all()` Flow는 snapshot으로 수집할 수 있다. 같은 outer transaction의 두 terminal/두 Flow 수집은 동시에 거부하고 success, ordinary failure, cancellation 모두 lease를 release한다.
- [ ] pure RED를 확인한다.

  ```bash
  ./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test \
    --tests '*R2dbcDiagnosticSanitizerTest' \
    --tests '*R2dbcBindValueSnapshotterTest' \
    --tests '*R2dbcFluentQueryPlanTest' \
    --tests '*R2dbcTransactionLeaseTest' \
    --rerun-tasks --no-configuration-cache --console=plain
  ```

- [ ] **GREEN — 최소 구현을 추가한다.** snapshot을 immutable data로 만들고, transaction identity나 mutable object를 참조하지 않는다. lease registry는 transaction identity 단위로만 공유하고 weak/explicit cleanup으로 수명 누수를 막는다. executor가 lease를 소유할 때만 registry를 만지며 public SPI를 추가하지 않는다.
- [ ] 동일 unit test가 PASS하고 `R2dbcDiagnosticSanitizerTest`가 separator/control 문자 제거, 128자 제한, 고정 operation/metric enum allowlist 및 동적 label 거부를 직접 검증하며 `git diff --check`가 깨끗한지 확인한다.

## Task 3: property resolver와 QBE predicate compiler를 TDD 구현

**Files:** `R2dbcPersistentPropertyResolver.kt`, `R2dbcExamplePredicateCompiler.kt`, their tests.

- [ ] **RED — property metadata를 고정한다.** repository domain type의 Kotlin property/Java bean getter와 `IdTable` column을 exact name 우선, unique camelCase/snake_case fallback 순서로 해석한다. `id`는 `table.id`에 매핑하고, nested path, unknown, ambiguous token 및 지원하지 않는 sort(null handling/ignoreCase/nested)은 `InvalidDataAccessApiUsageException`으로 SQL 전에 거부한다. resolver는 factory path에서 `(domainType, table)` 단위로 cache하고, factory/direct 양쪽에서 `expectedDomainType`과 첫 `Example` probe runtime type의 exact equality를 고정한 뒤 다른 타입·subtype을 거부한다. mismatch 회귀 테스트는 getter/transformer/SQL 호출 횟수 0을 확인한다.
- [ ] **RED — matcher parity를 고정한다.** `matchingAll`/`matchingAny`, `NullHandler.IGNORE`/`INCLUDE`, explicit ignore, property transformer, `DEFAULT`/`EXACT`/`CONTAINING`/`STARTING`/`ENDING`만 지원한다. global/property ignore-case, `REGEX`, unsupported string matcher는 `UnsupportedOperationException`으로 getter/SQL 전에 실패한다. `%`, `_`, escape 문자는 literal LIKE value로 escape하고 prepared binding을 사용한다.
- [ ] **RED — accessor 실행 횟수와 null 우선순위를 고정한다.** 구조 검증 뒤 각 getter와 transformer는 한 번만 실행한다. transformer가 empty인 non-null property는 제외하고, raw null + `NullHandler.INCLUDE`는 transformer empty여도 `IS NULL`을 유지한다. `CancellationException`/`Error`는 wrapping하지 않는다.
- [ ] pure compiler RED를 확인한다.

  ```bash
  ./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test \
    --tests '*R2dbcPersistentPropertyResolverTest' \
    --tests '*R2dbcExamplePredicateCompilerTest' \
    --rerun-tasks --no-configuration-cache --console=plain
  ```

- [ ] **GREEN — 단일 compiler를 구현한다.** snapshot에서만 value를 읽어 bound `Op<Boolean>`를 만들고, terminal별 별도 matcher 해석이나 raw SQL 문자열 결합을 추가하지 않는다. 모든 matcher/projection/sort 진단은 sanitizer를 통과한다. unsupported matcher는 `UnsupportedOperationException`, property 구조/지원 경계 위반은 `InvalidDataAccessApiUsageException`으로 구분한다.
- [ ] unit test가 PASS하고 QBE가 생성한 오류의 value/SQL/DB URL 노출이 없는지 negative assertion을 확인한다.

## Task 4: projection mapper와 terminal executor를 TDD 구현

**Files:** `R2dbcProjectionMapper.kt`, `R2dbcFluentQueryExecutor.kt`, `R2dbcTransactionLease.kt` integration points, and integration test.

- [ ] **RED — mapper shape을 고정한다.** full domain result는 기존 `toDomain(ResultRow)`와 전체 column을 사용한다. `asType` projection은 selected source column만 query에 넣고, closed getter interface는 `SpelAwareProxyProjectionFactory`, data class/Java record는 이름 있는 preferred constructor로 detached object를 만든다. `EntityID`는 raw ID로 unwrap한다. open/SpEL/nested/partial-domain projection shape와 unsupported projection은 `UnsupportedOperationException`, unknown/ambiguous/nested source property는 `InvalidDataAccessApiUsageException`, constructor 이름 누락과 non-null parameter에 null을 넣는 경우는 sanitized `MappingException`으로 SQL 전에 실패한다. projection constructor/accessor가 secret-bearing 예외를 던져도 reflective wrapper, raw argument, cause/suppressed graph를 제거한 sanitized mapping exception만 반환한다.
- [ ] **RED — terminal cardinality를 고정한다.** 직접 `findOne(example)`과 fluent `one()`은 fluent limit을 무시하고 최대 2건을 읽어 0건 `null`, 1건 value, 2건 이상 `IncorrectResultSizeDataAccessException`을 반환한다. `first()`는 `LIMIT 1`; `all()`은 Flow; `count`/`exists`는 projection/sort/limit을 무시하고 `exists`는 ID만 `LIMIT 1`로 선택한다. 0/1/2건을 직접 `findOne`과 `one()` 양쪽에서 회귀 검증한다.
- [ ] **RED — paging을 고정한다.** `page(pageable)`는 pageable sort/size를 fluent sort/limit보다 우선하고, unpaged 또는 short page에서 content 크기로 total을 추론하며 필요할 때만 count를 실행한다. `slice(pageable)`는 count 없이 `pageSize + 1`을 읽는다. page content와 count는 하나의 logical transaction 안에서 실행하되 `READ COMMITTED` statement snapshot 차이를 보장하지 않는다.
- [ ] **RED — unpaged와 fluent limit의 total을 분리한다.** `Pageable.unpaged()`인데
  fluent plan에 positive `limit`이 남아 있으면 content 크기는 전체 predicate cardinality가
  아니므로 total을 content 크기로 추론하지 않는다. 이 조합은 predicate-only count를
  추가 실행해 정확한 total을 만들고 최대 2 statements를 사용한다. fluent limit이 없는
  unpaged page만 content 크기로 total을 추론할 수 있다. `limit(10)` + 100건 fixture를
  반드시 회귀 테스트한다.
- [ ] **RED — SQL shape/transaction lifecycle을 고정한다.** QBE는 `suspendTransaction` 호출 전에 `TransactionManager.currentOrNull()`로 active outer `R2dbcTransaction`을 판별한다. outer가 있으면 `useNestedTransactions=true`는 SQL/savepoint 전에 `InvalidDataAccessApiUsageException`으로 거부하고, `false`는 Exposed가 제공하는 nested/current context를 사용하되 caller transaction의 `maxAttempts`/commit/close를 건드리지 않는다. outer가 없을 때만 top-level transaction 내부에서 streaming 설정을 적용한다. H2 `SqlLogger`로 selected column, `ORDER BY`, `LIMIT`, `OFFSET`, ID-only exists, conditional count를 확인한다. Flow 생성 시 SQL이 없고 수집마다 fresh result가 생긴다.
- [ ] **RED — cancellation/lease/retry를 고정한다.** 수집 취소가 원래 `CancellationException` identity, lease release, pool reuse를 검증한다. timeout과 caller cancellation을 같은 일반 예외로 취급하지 않는다. 동일 outer transaction의 병렬 terminal은 SQL 전에 거부한다. Exposed 1.4.0이 top-level `R2dbcException`을 transaction block 전체 재시도할 수 있으므로 streaming `all()`/`findAll()`은 첫 SQL 전에 해당 top-level transaction의 `maxAttempts = 1`을 설정해 부분 방출 뒤 중복 방출을 막는다. caller-owned outer transaction의 `maxAttempts`나 `DatabaseConfig`는 변경하지 않으며, 재시도는 호출자가 전체 Flow 수집을 감싸서 수행한다.
- [ ] RED integration을 확인한다.

  ```bash
  EXPOSED_TEST_DB=H2 ./gradlew \
    :bluetape4k-exposed-spring-boot-r2dbc:test \
    --tests '*R2dbcProjectionMapperTest' \
    --tests '*R2dbcFluentQueryIntegrationTest' \
    --rerun-tasks --no-configuration-cache --console=plain
  ```

- [ ] **GREEN — executor를 구현한다.** `suspendTransaction` 내부에서만 mutable Exposed query를 만들고, `flow {}`에서 row를 순차적으로 map/emit한다. `channelFlow`, background producer, upfront list fallback을 사용하지 않는다. preflight에서 active outer를 확인한 뒤 outer가 없을 때만 top-level streaming transaction의 statement 실행 전에 `maxAttempts = 1`을 설정하고, outer transaction에서는 caller 설정을 보존한다. driver `R2dbcException`이 row 방출 뒤 발생해도 Exposed 자동 재시도로 block을 다시 실행하지 않고 한 번만 전파한다. non-streaming `first/one/page/slice/count/exists`의 retry/backoff/timeout은 Exposed와 caller 설정에 위임하고 QBE가 새 deadline·retry registry를 만들지 않는다. top-level 호출은 Exposed default/primary database 규칙을 따르고, 다중 DB는 caller가 연 `suspendTransaction(database)` context를 그대로 사용한다.
- [ ] projection constructor/accessor shape cache는 repository lifetime 동안 재사용하되
  canonical `(domainType, resultType, orderedPropertySet)` key와 bounded 128-entry
  eviction을 사용한다. 고카디널리티 property set으로 메모리가 무한히 증가하지 않아야
  하며, eviction 뒤 shape를 재생성해도 mapping 결과는 동일해야 한다.
- [ ] callback direct path와 Flow path가 동일 plan snapshot을 사용하고, callback 밖 fluent 객체는 실패하지만 callback 안에서 생성한 Flow는 수집되는지 확인한다.
- [ ] H2 integration이 PASS하고 statement budget이 `first/one/all/count/exists` 1회, page 최대 2회(추론 시 1회)인지 확인한다. retry-injection fixture에서 first-row emission 후 driver `R2dbcException`이 발생해도 top-level Flow가 재실행되지 않고 중복 row가 없으며, 예외가 caller에 한 번 전달되는지 확인한다. 별도 terminal retry fixture는 configured `maxAttempts`/backoff를 Exposed가 적용하는 횟수와 timeout-vs-cancellation 분류를 기록하고, QBE가 outer 설정을 덮어쓰지 않는지 확인한다.

## Task 5: factory/direct repository wiring과 derived-query 회귀를 구현

**Files:** `SimpleExposedR2dbcRepository.kt`, `ExposedR2dbcRepositoryFactory.kt`, `ExposedR2dbcRepositoryFactoryBean.kt` KDoc only if needed, existing PartTree/Declared query tests, and new factory tests.

- [ ] **RED — collaborator/constructor fixture를 고정한다.** factory path는 `(domainType, table, mapper, ProjectionFactory, FACTORY)`를 `internal` collaborator/companion factory 경로로 전달하고 factory executor에 `expectedDomainType`을 고정한다. direct public constructor path는 기존 4인자 descriptor와 `SpelAwareProxyProjectionFactory`, `DIRECT` mode를 사용해야 한다. 새 collaborator constructor와 factory method는 `internal` 또는 `private` visibility로만 제공하고, `javap`/reflection에서 기존 public 4인자 descriptor 외 public constructor가 없음을 확인한다. repository-owned database field는 허용하지 않는다. 두 경로 모두 probe type mismatch/subtype를 SQL·getter·transformer 전에 거부한다.
- [ ] **RED — dispatch 우선순위를 고정한다.** `ExposedR2dbcRepositoryFactory`의 JDK proxy가 QBE method를 implementation direct dispatch로 먼저 처리하고, 그 뒤에 interface default, 마지막에 declared/PartTree query를 처리하는지 `ExposedR2dbcRepositoryFactoryQbeTest`에서 검증한다. `findBy(Example)`를 method-name query로 잘못 파싱하면 실패한다.
- [ ] **GREEN — internal collaborator registry를 연결한다.** JDBC 모듈의 implementation을 복사하거나 공유하지 않고 R2DBC 전용 resolver/compiler/executor를 연결한다. 기존 CRUD, derived query, declared query와 `streamAll`의 transaction contract는 변경하지 않는다. factory/direct mismatch 회귀는 probe getter·transformer 및 Exposed SQL logger가 모두 0회임을 검증하고, 기존 module의 허용된 `kotlinx.coroutines.reactor.mono` health bridge는 제외한 채 새 QBE public API와 신규 파일에서만 `ReactiveQueryByExampleExecutor`/`ReactiveFluentQuery`/`Mono`/`Flux` surface를 검사한다.
- [ ] 아래 회귀 명령을 PASS시킨다. 새 factory QBE dispatch/scan 테스트를 기존 CRUD/PartTree/Declared/config 회귀와 같은 순서로 실행한다.

  ```bash
  ./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test \
    --tests '*SimpleExposedR2dbcRepositoryTest' \
    --tests '*PartTreeExposedR2dbcQueryTest' \
    --tests '*DeclaredExposedR2dbcQueryTest' \
    --tests '*ExposedSuspendRepositoryConfigurationExtensionTest' \
    --tests '*ExposedR2dbcRepositoryFactoryQbeTest' \
    --rerun-tasks --no-configuration-cache --console=plain
  ```

- [ ] 새 QBE public API·executor·fluent/support 파일과 README EN/KO 계약 구간에만 `rg -n "ReactiveQueryByExampleExecutor|ReactiveFluentQuery|Mono<|Flux<"`를 실행해 0건을 확인한다. 기존 `ExposedR2dbcCacheHealthAutoConfiguration.kt`의 허용된 `kotlinx.coroutines.reactor.mono` bridge와 기존 비-QBE 코드는 검사 대상에서 제외하며, exclusion 목록을 검증 결과에 기록한다.

## Task 6: H2·PostgreSQL·MySQL R2DBC matrix와 transaction adversarial 검증

**Files:** `R2dbcFluentQueryIntegrationTest.kt`, `R2dbcFluentQueryMultiDbTest.kt`, test fixtures, and no production changes outside the support/API files above.

- [ ] H2 factory path에서 exact/contains/starts/ends, all/any, null include/ignore, literal LIKE hostile input, sort append, limit last-wins, direct `findOne`과 fluent `one()`의 0/1/2건 cardinality, first/one/all/page/slice/count/exists, selected projection, cold Flow 재수집, callback scope, direct dispatch, error redaction을 검증한다. unsupported matcher/projection은 `UnsupportedOperationException`, 지원하지 않는 sort와 unknown/ambiguous/nested/callback/parallel은 `InvalidDataAccessApiUsageException`, cardinality 초과는 `IncorrectResultSizeDataAccessException`, mapping failure는 sanitized `MappingException`, `null`/empty sort와 음수 limit은 `IllegalArgumentException`인지 exact assertion한다. 빈 `project()`는 required input 자동 선택/reset 경로로 검증한다.
- [ ] H2에서 `Pageable.unpaged()` + positive fluent limit의 정확한 total과 최대 2
  statements를 검증한다. PostgreSQL/MySQL representative 검증은 SQL shape와 결과
  semantics를 확인하며 `EXPLAIN` plan 또는 특정 index 선택은 이 기능의 보장 범위가
  아님을 명시한다. 인덱스 설계와 optimizer 선택은 caller/database 운영 범위다.
- [ ] caller-owned outer `suspendTransaction(database)` 안에서 미커밋 row를 보고, Flow를 transaction 밖에서 수집하면 새 top-level transaction을 사용하는지 검증한다. `useNestedTransactions=false` outer는 동일 transaction/connection을 사용하고 caller close/commit 경계를 건드리지 않아야 한다. `true` outer는 SQL statement와 savepoint가 생성되기 전에 실패해야 한다. outer의 `maxAttempts`/`DatabaseConfig`는 QBE가 덮어쓰지 않는지 확인한다.
- [ ] cancellation barrier로 mid-collection 취소를 재현하고, 원래 `CancellationException` identity, lease release, pool reuse를 검증한다. timeout과 caller cancellation을 같은 일반 예외로 취급하지 않는다. terminal retry fixture에서 Exposed configured attempt/backoff와 timeout-vs-cancellation 분류를 별도로 기록하며, QBE가 outer `maxAttempts`/deadline을 변경하지 않는지 확인한다.
- [ ] direct `SimpleExposedR2dbcRepository` path에서도 첫 Example runtime type 고정, projection parity, transaction 밖 top-level 실행을 검증한다. factory path에서도 expected domain type과 probe의 exact equality 및 subtype mismatch를 반복 검증한다.
- [ ] backend를 병렬 실행하지 않고 순서대로 검증한다.

  ```bash
  EXPOSED_TEST_DB=H2 ./gradlew \
    :bluetape4k-exposed-spring-boot-r2dbc:test \
    --tests '*R2dbcFluentQueryIntegrationTest' \
    --rerun-tasks --no-configuration-cache --console=plain

  EXPOSED_TEST_DB=POSTGRESQL ./gradlew \
    :bluetape4k-exposed-spring-boot-r2dbc:test \
    --tests '*R2dbcFluentQueryMultiDbTest' \
    --no-parallel --max-workers=1 --rerun-tasks --no-configuration-cache --console=plain

  EXPOSED_TEST_DB=MYSQL_V8 ./gradlew \
    :bluetape4k-exposed-spring-boot-r2dbc:test \
    --tests '*R2dbcFluentQueryMultiDbTest' \
    --no-parallel --max-workers=1 --rerun-tasks --no-configuration-cache --console=plain
  ```

  각 명령은 실행 test count와 backend unavailable skip을 별도로 기록한다. 대상 테스트가 0건이면 성공으로 간주하지 않는다.

## Task 7: ABI, static analysis, docs/KDoc, EN/KO parity

**Files:** ABI fixtures, public KDoc, both module README files, the two new parity scripts, `CHANGELOG.md`, `WIP.md`; never modify `docs/manual/**`.

- [ ] ABI fixture와 Kotlin/Java compile consumer를 다시 실행한다.

  ```bash
  ./gradlew :bluetape4k-exposed-spring-boot-r2dbc:compileKotlin \
    :bluetape4k-exposed-spring-boot-r2dbc:compileTestKotlin \
    :bluetape4k-exposed-spring-boot-r2dbc:compileTestJava \
    :bluetape4k-exposed-spring-boot-r2dbc:test \
    --tests '*ExposedR2dbcRepositoryAbiCompatibilityTest' \
    --rerun-tasks --no-configuration-cache --console=plain

  javap -classpath spring-boot/r2dbc/build/classes/kotlin/main -s \
    io.bluetape4k.spring.data.exposed.r2dbc.repository.ExposedR2dbcRepository \
    io.bluetape4k.spring.data.exposed.r2dbc.repository.support.SimpleExposedR2dbcRepository
  ```

- [ ] Detekt와 Kotlin compiler 경고를 확인한다.

  ```bash
  ./gradlew :bluetape4k-exposed-spring-boot-r2dbc:detekt \
    --rerun-tasks --no-configuration-cache --console=plain
  ```

- [ ] README EN/KO에 동일한 marker/key set을 추가한다. 필수 contract key는 `coroutine-only`, `suspend-terminal`, `cold-flow`, `flow-collection-context`, `outer-transaction`, `database-selection`, `nested-transactions-rejected`, `closed-projection`, `open-projection-rejected`, `matcher-projection-matrix`, `find-one-cardinality`, `first-one-all-page-slice-count-exists`, `error-taxonomy`, `callback-scope`, `cancellation`, `streaming-retry-no-duplicate`, `terminal-retry-delegated`이다. 예제는 `suspend fun`과 `suspendTransaction(database)` 문맥을 포함하고, Flow 생성은 SQL/transaction을 시작하지 않으며 수집 시점의 active outer/default database를 사용한다는 점을 설명한다. 다른 database를 선택하려면 Flow 수집 자체를 `suspendTransaction(database) { flow.collect { ... } }` 안에서 수행한다. top-level streaming Flow는 Exposed 자동 재시도로 중복 방출되지 않도록 `maxAttempts = 1`을 사용하며 caller-owned outer transaction 설정은 변경하지 않는다는 경계를 설명한다. non-streaming terminal retry/backoff/timeout은 Exposed와 caller 설정에 위임한다. matcher/projection/sort 지원·거부 row와 `IllegalArgumentException`/`UnsupportedOperationException`/`InvalidDataAccessApiUsageException`/`MappingException`/`IncorrectResultSizeDataAccessException`/원본 `CancellationException` taxonomy를 EN/KO에 동일하게 기록한다. `project` source property와 projection required input의 exact-set 규칙, 빈 `project()`의 required input 자동 선택/reset semantics도 동일한 의미로 설명한다. English/한국어 식별자·code-fence·local link parity를 번역으로 바꾸지 않는다.
- [ ] parity validator를 추가하고 fixture 기반 negative test를 먼저 작성한다. marker/key/fence/link뿐 아니라 matcher·projection matrix row key, database/Flow collection context, error taxonomy token, retry ownership token을 canonical fixture로 추출해 EN/KO exact parity를 확인한다.

  ```bash
  ruby scripts/validate_r2dbc_fluentquery_readme_parity_test.rb
  ruby scripts/validate_r2dbc_fluentquery_readme_parity.rb \
    spring-boot/r2dbc/README.md spring-boot/r2dbc/README.ko.md
  git diff --check
  git diff --quiet -- docs/manual/**
  ```

- [ ] `CHANGELOG.md`의 `[Unreleased]`에 `추가됨`/`변경됨`으로 Issue #643을 기록하고, `WIP.md`의 Epic #658 stacked train 상태에 R2DBC 후속 slot을 추가한다. 배포되지 않은 `1.13.0` 개발 기록만 변경하며 manual release version을 승격하지 않는다.

## Task 8: 6관점 plan review와 implementation DoD 설계

**Files:** `docs/review/2026-08-16-issue-643-r2dbc-coroutine-fluentquery-plan-review.md` and workflow evidence under the existing run. Review the exact plan commit and all paths named by this plan; do not modify production code in this task.

- [ ] performance: selected columns, ID-only exists, conditional count, cold Flow, cache boundaries, top-level parallelism과 same-outer lease의 비용을 검토한다.
- [ ] stability: Exposed 1.4.0 `useNestedTransactions` on/off, cancellation cleanup, pool reuse, Flow re-collection, page statement snapshot과 lease release를 검토한다.
- [ ] security: prepared binding, LIKE escape, arbitrary conversion 거부, immutable bind snapshot, diagnostic sanitization, cause graph 제거, Reactor surface 부재를 검토한다.
- [ ] operability: DB/transaction ownership, logger boundary, low-cardinality labels, backend skip/timeout 분류, no new registry/metric/pool을 검토한다.
- [ ] developer/API: base ABI, public 4인자 constructor, opt-in migration, `KClass`-only API, factory/direct metadata와 dispatch 우선순위를 검토한다.
- [ ] user/caller: suspend 사용 예, Flow 생성·수집 경계, outer DB 선택, projection/matcher 지원표, 오류 taxonomy, README EN/KO parity를 검토한다.
- [ ] 모든 P0/P1 finding을 plan 파일의 task/acceptance/test에 반영한 뒤 재검토한다. 최종 plan review는 P0=0, P1=0이어야 한다. P1이 남으면 plan approval을 `PENDING`으로 유지하고 구현을 시작하지 않는다.
- [ ] writer gate를 기록한다: SPW-01 audience/purpose/source, SPW-02 plan contract, SPW-03 Korean technical register 및 KO-01..06, SPW-04 spec-to-plan traceability, SPW-05 final Markdown read-back.

## Task 9: implementation verification, lesson, workflow receipt

**Files/evidence:** implementation lane exact changed paths, component checks, `docs/lessons/2026-08-16-issue-643-r2dbc-coroutine-fluentquery.md`, and no PR mutation until this task is complete.

- [ ] full module test를 실행한다.

  ```bash
  ./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test \
    --rerun-tasks --no-configuration-cache --console=plain
  ```

- [ ] `git diff --name-only "$(git merge-base origin/develop HEAD)"`를 fresh JSON string array로 기록하고, changed path가 plan write scope 밖으로 확장되지 않았는지 확인한다. `docs/manual/**` diff는 0이어야 한다.
- [ ] workflow helper로 implementation lane을 exact changed-path evidence와 함께 complete한다. 이어서 `spec-review`, `plan-review`, `implementation-verification`, `pre-pr-review`, `ci-review` required check를 순서대로 `check-result`에 기록한다. `implementation-verification` evidence에는 H2와 PostgreSQL/MySQL 순차 실행의 실제 test count, unavailable/timeout 분류, 0-test hard-fail 결과를 포함한다. `component-evidence`와 `completion-check`가 missing/failed/incomplete 없이 수렴하기 전에는 run completion을 기록하지 않는다.
- [ ] lesson에는 구현 결과와 검증 명령, backend별 test count/skip, 재발 방지 규칙을 한국어로 기록한다. lesson의 `## DoD Status`는 plan review와 implementation verification을 혼동하지 않게 분리한다.
- [ ] 이 task가 끝나도 PR 생성/merge는 별도 gate다. PR이 필요할 때에만 `$bluetape-workflow` PR 본문 규칙을 적용하고, CI 성공을 merge 권한으로 간주하지 않는다.

## Acceptance-to-plan traceability

| 설계 인수 조건 | 구현 task | 검증 증거 |
| --- | --- | --- |
| public surface에 Reactor 타입 없음 | Task 1, 5, 7 | API compile, `rg`, ABI resource |
| base repository ABI/4인자 constructor 보존 | Task 1, 5, 7 | reflection, `javap`, Java/Kotlin fixture |
| coroutine-native matcher/terminal API | Task 1, 3, 4 | public compile, compiler/executor unit test |
| immutable probe/bind snapshot | Task 2, 3 | mutation/deep-copy/unsafe-value tests |
| callback scope와 Flow escape | Task 2, 4, 6 | scope/Flow collection tests |
| Exposed 1.4.0 top-level/outer transaction contract | Task 4, 6 | H2 outer/nested/savepoint tests |
| cold Flow fresh query/result | Task 4, 6 | repeated collection and SQL logger count |
| same outer transaction parallel lease | Task 2, 4, 6 | barrier/concurrency tests |
| matcher parity와 LIKE literal escape | Task 3, 6 | compiler + H2/PostgreSQL/MySQL tests |
| detached projection selected-column mapping | Task 4, 6 | mapper and selected SQL tests |
| first/one/all/page/slice/count/exists semantics | Task 4, 6 | cardinality/page/statement budget tests |
| direct findOne cardinality parity | Task 4, 6, 7 | 0/1/2-row exact exception tests and README/KDoc marker |
| unpaged + fluent limit total correctness | Task 4, 6 | 100-row/limit-10 page regression and 2-statement budget |
| cancellation/error/log redaction | Task 2, 3, 6 | identity/cause/sensitive token negative tests |
| streaming retry 중복 방지 | Task 4, 6, 7 | top-level `maxAttempts=1`, first-row driver failure, outer config 보존 |
| bounded projection-shape cache | Task 4 | 128-entry cardinality/eviction test |
| factory/direct dispatch parity | Task 5, 6 | proxy and direct repository tests |
| public error taxonomy | Task 3, 4, 6, 7 | exact exception class/redaction assertions and EN/KO matrix |
| Flow collection database context | Task 4, 6, 7 | collect-time outer/default DB test and parity marker |
| terminal retry/timeout ownership | Task 4, 6, 7 | configured attempt/backoff evidence and caller/Exposed delegation docs |
| README/KDoc EN/KO parity | Task 7 | validator, links, `git diff --check` |
| stable manual unchanged | Task 7, 9 | target-path diff audit |

## Rollback and rerun

- Task 2/3 pure component failure는 해당 internal class와 unit test만 되돌리고 public API나 factory wiring을 건드리지 않은 상태로 재실행한다.
- Task 4 executor failure는 `SimpleExposedR2dbcRepository` QBE delegation을 차단하고 기존 CRUD/derived/declared query를 복구한다. upfront materialization이나 Reactor fallback을 롤백 대안으로 사용하지 않는다.
- Task 5 factory/ABI failure는 internal collaborator registry를 제거한 뒤 4인자 constructor와 기존 direct proxy 테스트를 먼저 green으로 복구한다.
- 특정 backend failure는 해당 backend 명령만 `--no-parallel --max-workers=1`로 재실행한다. H2 결과로 PostgreSQL/MySQL을 대체하지 않는다.
- 문서 parity failure는 marker/key/identifier/link를 고치고 source-to-claim을 다시 읽는다. `docs/manual/**`를 수정해 해결하지 않는다.
- 구현 이후 PR/merge/branch cleanup은 별도 승인된 delivery 단계이며, 이 계획은 plan approval과 implementation DoD에서 멈춘다.

## Writer gate와 현재 DoD

- SPW-01: 계획 대상 독자, Issue #643, exact module, Exposed 1.4.0/Spring Data 4.1, source/design/review/README 경계를 고정한다.
- SPW-02: public API, file map, dependency order, RED/GREEN command, rollback, verification, approval gate를 포함한다.
- SPW-03: 한국어 기술 문체를 사용하고 API/identifier/command/version/URL을 보존한다.
- SPW-04: 승인된 설계의 각 acceptance를 task와 fresh evidence에 매핑한다.
- SPW-05: plan/review commit 전에 Markdown heading/table/list/code fence와 `docs/manual/**` exclusion을 read-back한다.
- KO-01..06: 사실·식별자 보존, 근거 없는 강조 제거, 번역투 제거, 용어 일관성, 비유 배제, EN/KO reader-facing surface 전수 확인을 수행한다.

## DoD Status

- [x] Issue #643 승인 설계와 coroutine-only 범위를 입력으로 고정했다.
- [x] 실제 R2DBC source/test/build/README와 Exposed 1.4.0 transaction 계약을 파일 책임에 대조했다.
- [x] TDD RED/GREEN 순서, exact files, commands, acceptance traceability, rollback/rerun 경계를 작성했다.
- [x] 6관점 plan review에서 P0=0/P1=0을 확인한다. 3차 rereview는 P0=0/P1=0/P2=0으로 종료했다.
- [x] 보완된 design, plan, plan review artifact를 Lore commit으로 고정한다.
- [ ] 사용자에게 plan 승인 후 구현 시작을 요청한다.
- [ ] production code, tests, docs, PR, CI, merge, sync, cleanup (계획 이후 단계).

상태: `PENDING` — 계획·설계 보완과 검토 artifact는 고정됐지만 별도 implementation approval 전에는 구현을 시작하지 않는다.
