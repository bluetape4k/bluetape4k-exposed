# Spring Data Common Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `spring-boot/jdbc`와 `spring-boot/r2dbc`가 공유하던 Spring Data SPI를 `:bluetape4k-exposed-spring-boot-common`으로 분리하고, R2DBC 런타임에서 JDBC 어댑터와 `spring-jdbc`가 완전히 제거되도록 하면서 JDBC 바이너리 호환성과 문서·BOM·ABI 계약을 유지한다.

**Architecture:** 공통 모듈은 Spring Data Commons 기반의 annotation, mapping, query planning, sort 변환만 소유한다. JDBC 모듈은 JDBC executor, `ExposedEntityInformation`, transaction/auto-configuration과 기존 JDBC 패키지의 deprecated facade를 소유한다. R2DBC 모듈은 suspend executor와 R2DBC auto-configuration만 소유하고 common 모듈에만 의존한다. 기존 `io.bluetape4k.spring.data.exposed.jdbc.*` public symbol은 JDBC artifact에서 유지하며 Kotlin `typealias`는 binary bridge로 사용하지 않는다.

**Tech Stack:** Kotlin, Spring Boot 4, Spring Data Commons 4.1, JetBrains Exposed, Exposed JDBC/R2DBC, Gradle Kotlin DSL, JUnit 5, MockK, bluetape4k assertions, Kotlin ABI validator, Detekt, Dokka/manual inventory.

---

## 실행 전제와 게이트

- [ ] 이 계획과 `docs/superpowers/specs/2026-08-25-spring-data-common-design.md` 및 계획 검토 결과를 읽고, 사용자가 계획을 승인할 때까지 production code를 변경하지 않는다.
- [ ] 1인 개발자 환경의 `main-session` 단일 lane에서 각 작업을 순서대로 수행한다. 여섯 리뷰 관점은 동일 실행자가 독립적으로 순차 검토하되, 각 결과와 근거를 별도 표에 남긴다.
- [ ] 모든 Gradle 실행은 repository의 context-mode 실행 경로를 사용하고, Testcontainers 검증은 PostgreSQL/MySQL/Redis 경로를 동시에 실행하지 않는다.
- [ ] 모든 파일 변경 전 `bluetape-flow.py mutation-check --session-id 310620da-071b-4410-a0b9-ff574a23dd22 --target '<path>'`를 통과시키고 receipt checksum을 갱신한다.
- [ ] 코드·KDoc·README·manual·계획·리뷰·커밋·PR prose는 한국어로 작성하고, 코드/API 이름·명령·URL·기계 토큰은 원문을 유지한다.
- [ ] production source에 `println`, `System.out`, `System.err`를 추가하지 않는다. 진단은 기존 bluetape logging을 사용한다.

## Task 1: 모듈 골격과 공개 빌드 계약 등록

**Files:** `settings.gradle.kts`, `spring-boot/common/build.gradle.kts`, `spring-boot/common/README.md`, `spring-boot/common/README.ko.md`, `spring-boot/common/src/test/resources/`, `exposed/bom/` 관련 catalog/constraints 파일.

- [ ] `settings.gradle.kts`에 `includeMappedModule("spring-boot/common", "bluetape4k-exposed-spring-boot-common")`를 JDBC와 R2DBC 사이에 추가한다.
- [ ] 기존 `spring-boot/jdbc/build.gradle.kts`의 platform 사용 방식을 그대로 따르되 common 모듈에는 `bt4k.spring.boot4.dependencies`와 `bt4k.exposed.bom`을 API-visible로 선언한다. 코루틴 BOM은 common production API가 사용하지 않으므로 추가하지 않는다.
- [ ] common 모듈에 `spring-data-commons`, `kotlin.reflect`, `bt4k.bluetape4k.logging`, `bt4k.exposed.core`, `libs.exposed.dao`만 production API로 선언하고 JDBC/R2DBC/Spring JDBC/DataSource/pool 의존성은 선언하지 않는다.
- [ ] 기존 테스트 convention(`bt4k.bluetape4k.junit5`, `bt4k.bluetape4k.assertions`, Spring Boot test, MockK, H2)을 적용하고, common 모듈이 publishable project로 인식되는지 확인한다. assertion library는 transitive 의존성에 기대지 않고 직접 선언한다.
- [ ] BOM constraint/catalog에 common artifact를 추가하고 개별 Bluetape 버전을 직접 고정하지 않는다. dependency insight로 common이 BOM을 통해 버전을 받는지 확인한다.
- [ ] common README 두 언어에 좌표, BOM 사용 예, 소유 범위, JDBC/R2DBC 의존 방향을 기록한다. 예시에는 개별 Bluetape library version을 넣지 않는다.
- [ ] `:bluetape4k-exposed-spring-boot-common:compileKotlin`과 `checkKotlinAbi`를 실행해 빈 모듈이 publish/ABI pipeline에 연결되는지 확인한다.

## Task 2: common annotation·mapping을 테스트 우선으로 추가

**Files:**

- `spring-boot/common/src/main/kotlin/io/bluetape4k/spring/data/exposed/common/annotation/ExposedEntity.kt`
- `spring-boot/common/src/main/kotlin/io/bluetape4k/spring/data/exposed/common/annotation/Query.kt`
- `spring-boot/common/src/main/kotlin/io/bluetape4k/spring/data/exposed/common/mapping/ExposedMappingContext.kt`
- `spring-boot/common/src/main/kotlin/io/bluetape4k/spring/data/exposed/common/mapping/DefaultExposedPersistentEntity.kt`
- `spring-boot/common/src/main/kotlin/io/bluetape4k/spring/data/exposed/common/mapping/DefaultExposedPersistentProperty.kt`
- `spring-boot/common/src/main/kotlin/io/bluetape4k/spring/data/exposed/common/mapping/ExposedPersistentEntity.kt`
- `spring-boot/common/src/main/kotlin/io/bluetape4k/spring/data/exposed/common/mapping/ExposedPersistentProperty.kt`
- `spring-boot/common/src/test/kotlin/io/bluetape4k/spring/data/exposed/common/annotation/AnnotationMetadataTest.kt`
- `spring-boot/common/src/test/kotlin/io/bluetape4k/spring/data/exposed/common/mapping/ExposedMappingContextTest.kt`

- [ ] 먼저 `ExposedMappingContext`의 entity discovery, table mapping, persistent property, unsupported property와 annotation metadata의 성공·실패 기대값을 bluetape4k assertions(`shouldBeEqualTo`, `shouldNotBeNull`, `shouldBeTrue`, `assertFailsWith`)으로 고정한다.
- [ ] JDBC 구현에서 공통 의미를 가진 코드를 이동하되 package를 `io.bluetape4k.spring.data.exposed.common.*`으로 바꾸고, Exposed transaction/Database 접근을 mapping layer에 추가하지 않는다.
- [ ] nullable/immutable Kotlin property와 Kotlin reflection metadata를 `!!` 없이 처리하며, Korean KDoc으로 annotation target·기본값·지원하지 않는 경우를 설명한다.
- [ ] mapping context가 동일 type을 반복 조회할 때 기존 캐시 semantics와 allocation 특성을 보존하고, classpath scan 범위를 넓히지 않는다.
- [ ] 동일 mapping context를 여러 coroutine/thread에서 동시에 조회하는 회귀 테스트를 추가해 캐시 publication, 중복 entity 생성, race 예외가 발생하지 않음을 확인한다.
- [ ] common mapping test를 단독 실행해 JDBC/R2DBC adapter class가 test runtime에 없어도 통과하는지 확인한다.

## Task 3: common query planning·sort 변환을 테스트 우선으로 추가

**Files:**

- `spring-boot/common/src/main/kotlin/io/bluetape4k/spring/data/exposed/common/repository/query/ParameterMetadataProvider.kt`
- `spring-boot/common/src/main/kotlin/io/bluetape4k/spring/data/exposed/common/repository/query/ExposedQueryCreator.kt`
- `spring-boot/common/src/main/kotlin/io/bluetape4k/spring/data/exposed/common/repository/support/ExposedSortSupport.kt`
- `spring-boot/common/src/test/kotlin/io/bluetape4k/spring/data/exposed/common/repository/query/ParameterMetadataProviderTest.kt`
- `spring-boot/common/src/test/kotlin/io/bluetape4k/spring/data/exposed/common/repository/query/ExposedQueryCreatorTest.kt`
- `spring-boot/common/src/test/kotlin/io/bluetape4k/spring/data/exposed/common/repository/support/ExposedSortSupportTest.kt`

- [ ] 현재 JDBC query/sort 테스트를 기준으로 PartTree equality, comparison, null, collection, LIKE escape, parameter binding, camelCase-to-snake_case, ASC/DESC, unknown property의 결과와 예외를 먼저 고정한다.
- [ ] `ParameterMetadataProvider`와 `ExposedQueryCreator`의 기존 Spring Data Commons 연동 semantics를 common package로 이동한다. receiver shadowing을 만들지 않고 기존 Exposed DSL extension을 재사용한다.
- [ ] `Sort.toExposedOrderBy`는 common의 canonical top-level extension으로 만들고, SQL identifier 변환 규칙을 한 곳에서만 구현한다.
- [ ] hot path에서 새 round trip·blocking call·reflection scan을 추가하지 않으며, 기존 LIKE escaping과 order allocation 수준을 유지한다. benchmark가 없는 영역은 기존 targeted test와 allocation/round-trip 불변 근거로 기록한다.
- [ ] 저장소에 Spring Data 전용 JMH benchmark가 있는지 먼저 확인하고, 없으면 새 benchmark 의존성을 추가하지 않는다. 대신 SQL statement count가 늘지 않는 query/sort regression과 반복 metadata lookup test를 실행해 성능 근거를 남기고 benchmark N/A 사유를 review artifact에 기록한다.
- [ ] common query/sort test를 JDBC/R2DBC adapter 없이 단독 실행하고, bluetape4k assertion API를 사용했는지 source 검사를 통과시킨다.

## Task 4: JDBC adapter와 legacy facade를 분리

**Files:** `spring-boot/jdbc/build.gradle.kts`, `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/{annotation,mapping,repository/query,repository/support,config}/**`, 관련 JDBC test와 `api/bluetape4k-exposed-spring-boot-jdbc.api`.

- [ ] JDBC build에 `api(project(":bluetape4k-exposed-spring-boot-common"))`를 추가하고 common이 제공하는 mapping/query/sort 구현의 중복 production source를 제거하거나 approved facade로 축소한다.
- [ ] JDBC test configuration에 `testImplementation(bt4k.bluetape4k.assertions)`를 직접 선언하고, 새/수정 테스트의 결과·예외 검증을 JUnit raw assertion보다 bluetape4k assertion extension으로 작성한다.
- [ ] JDBC-only로 남길 `ExposedEntityInformation`, `ExposedEntityInformationImpl`, JDBC repository factory/executor, `ExposedSpringDataAutoConfiguration`, `DatabaseConfig`, `SpringTransactionManager`, JDBC diagnostics는 JDBC package에서 유지한다.
- [ ] 기존 `jdbc.annotation.ExposedEntity`, `jdbc.annotation.Query`, mapping/query/sort public symbol은 JDBC artifact에서 유지한다. 각 symbol에 한국어 `@Deprecated` KDoc와 common import migration 예시를 제공하며, legacy annotation은 JDBC scanner가 common annotation과 함께 인식하도록 한다.
- [ ] constructor와 erased JVM descriptor가 필요한 facade는 명시적인 forwarding class/function으로 구현하고 Kotlin `typealias`를 사용하지 않는다. delegate가 정확한 descriptor를 보장하지 못하는 경우 기존 implementation을 유지하고 공통 helper만 호출한다.
- [ ] JDBC 내부 production import를 common canonical package로 전환하되, 외부 JDBC caller가 기존 import로 계속 링크되는지 ABI fixture/consumer test로 확인한다.
- [ ] JDBC auto-configuration의 bean condition/order와 transaction boundary를 보존하고, common mapping context를 재등록할 때 duplicate bean이 생기지 않도록 `@ConditionalOnMissingBean`을 검증한다.
- [ ] JDBC query/repository/sort/annotation test를 기존 DB matrix 순서로 실행하고, legacy facade와 common API를 함께 쓰는 fixture를 추가한다.
- [ ] legacy facade가 common delegate로 위임할 때 stack trace·예외 타입·nullability가 기존 caller 기대를 바꾸지 않는 negative/compatibility test를 추가한다.

## Task 5: R2DBC adapter의 JDBC 의존 제거

**Files:** `spring-boot/r2dbc/build.gradle.kts`, `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/config/ExposedR2dbcSpringDataAutoConfiguration.kt`, `.../repository/query/{ExposedR2dbcQueryMethod.kt,PartTreeExposedR2dbcQuery.kt}`, `.../repository/config/ExposedSuspendRepositoryConfigurationExtension.kt`, `.../repository/support/SimpleExposedR2dbcRepository.kt`, 관련 R2DBC tests/API.

- [ ] R2DBC build의 JDBC project dependency를 `api(project(":bluetape4k-exposed-spring-boot-common"))`로 교체하고, `spring-jdbc`, JDBC adapter, DataSource가 compile/runtime graph에 나타나지 않는 dependency guard를 추가한다.
- [ ] R2DBC test configuration에도 `testImplementation(bt4k.bluetape4k.assertions)`를 직접 선언하고, suspend/Flow 결과·cancellation·resource 검증에 bluetape4k assertion extension을 사용한다.
- [ ] 위 production source의 JDBC annotation/mapping/query/sort import를 common canonical package로 바꾼다. R2DBC executor, `suspendTransaction`, coroutine dispatcher와 cancellation boundary는 변경하지 않는다.
- [ ] `ExposedR2dbcSpringDataAutoConfiguration`에서 JDBC auto-config alias와 `after` ordering을 제거하고 common `ExposedMappingContext`를 조건부 bean으로 등록한다. 단독 R2DBC context와 JDBC+R2DBC combined context에서 ordering/duplicate bean을 검증한다.
- [ ] R2DBC source가 `io.bluetape4k.spring.data.exposed.jdbc`를 import하지 않는 source guard를 실행하고, R2DBC old JDBC-import caller는 common import로 이동해야 함을 README/manual migration table에 명시한다.
- [ ] suspend repository의 normal result, backend capability failure, coroutine cancellation, resource cleanup을 테스트한다. Testcontainers 경로는 PostgreSQL/MySQL을 순차 실행하고 실패 시 skipped로 간주하지 않는다.
- [ ] raw `@Query`와 sort/property 입력은 parameter binding과 identifier allow-list를 거치는지 negative test로 확인하고, 문자열 interpolation·새 reflection/classpath scan·민감 정보 logging이 추가되지 않았음을 source review로 증명한다.

## Task 6: 통합 auto-configuration·회귀·호환성 테스트

**Files:** `spring-boot/common/src/test/**`, `spring-boot/jdbc/src/test/**`, `spring-boot/r2dbc/src/test/**`, fixture/config resources.

- [ ] common context 단독 테스트에서 common bean과 annotation scanning만 활성화되는지 검증한다.
- [ ] JDBC 단독 context, R2DBC 단독 context, JDBC+R2DBC combined context를 각각 검증해 `@ConditionalOnMissingBean`, auto-config ordering, adapter-specific executor 등록, duplicate bean 및 classpath absence를 확인한다.
- [ ] JDBC/R2DBC query, sort, repository lifecycle regression suite를 유지하고, public facade와 common canonical API를 섞은 caller fixture를 추가한다.
- [ ] 실패 경로를 명시한다: 잘못된 property/annotation, unsupported query part, unknown sort, duplicate mapping bean, missing backend capability, transaction rollback, coroutine cancellation. 각 기대 예외와 로그는 안정적인 assertion으로 확인한다.
- [ ] mapping cache와 repository factory를 concurrent caller가 사용할 때의 lifecycle/close/retry 경로를 테스트하고, transaction rollback 및 coroutine cancellation 뒤 connection/resource가 회수되는지 확인한다.
- [ ] 테스트는 `bluetape4k-assertions`를 우선 사용하며, 단순 출력·`println`·`System.out`·`System.err`를 사용하지 않는다.

## Task 7: Kotlin ABI와 production API baseline 갱신

**Files:** `api/bluetape4k-exposed-spring-boot-common.api`, `api/bluetape4k-exposed-spring-boot-jdbc.api`, R2DBC API baseline, `build.gradle.kts`, ABI fixture/resources.

- [ ] common public API를 생성하고 annotation/mapping/query/sort의 의도한 descriptor만 포함하는지 확인한다.
- [ ] JDBC API baseline에는 legacy facade와 기존 constructor/descriptor가 남고, R2DBC API에는 common mapping return type과 실제 public symbol이 반영되도록 갱신한다.
- [ ] `checkKotlinAbi`를 common/JDBC/R2DBC에 각각 실행한 뒤 root `checkProductionAbi`를 실행한다. root의 publishable project 수 검증은 실제 generated inventory가 35개임을 확인한 경우에만 34에서 35로 바꾼다.
- [ ] ABI 변경이 의도하지 않은 JDBC-only symbol 제거 또는 R2DBC의 JDBC symbol 노출을 만들지 않는지 diff를 읽고, binary compatibility fixture를 재실행한다.

## Task 8: BOM·manual inventory·README·examples를 동기화

**Files:** `exposed/bom/`, `docs/manual/manifest.yaml`, `docs/manual/en/modules/bluetape4k-exposed-spring-boot-common.md`, `docs/manual/ko/modules/bluetape4k-exposed-spring-boot-common.md`, JDBC/R2DBC manual 및 `README*.md`, `examples/jdbc-demo/`, `examples/r2dbc-demo/`, `.github/workflows/ci.yml`, `.github/workflows/nightly-tests.yml`, `CHANGELOG.md`, `WIP.md`가 존재하는 경우의 issue train 기록, workflow/path-filter/catalog files.

- [ ] 현재 `exposed/bom/build.gradle.kts`의 publishable-subproject 자동 constraint가 common을 포함하는지 확인하고, generated BOM/POM/module metadata에 common artifact constraint가 실제로 나타나는지 검증한다. 자동 등록으로 충분하면 BOM source를 불필요하게 중복 수정하지 않는다.
- [ ] common manual의 English/Korean 파일을 같은 구조로 만들고, 소유 범위·BOM 좌표·annotation/query/sort 사용법·unsupported backend·legacy migration before/after를 기록한다.
- [ ] JDBC/R2DBC README와 manual을 common canonical import 및 R2DBC migration에 맞춰 갱신하고, 두 locale의 heading/예제/링크 parity를 검증한다.
- [ ] `examples/jdbc-demo`는 JDBC adapter를, `examples/r2dbc-demo`는 common+R2DBC adapter를 직접 사용하도록 import/dependency를 확인한다. examples에 개별 Bluetape version을 추가하지 않는다.
- [ ] module path filter, Nightly/Testcontainers routing, generated catalog/check script에서 `spring-boot/common`과 publish name을 함께 인식하도록 확인·갱신한다.
- [ ] `.github/workflows/ci.yml`와 `.github/workflows/nightly-tests.yml`의 Spring Boot job에 common `test` 및 `koverXmlReport`를 추가하고, `spring-boot/**` path filter와 job artifact 이름이 common 결과를 누락하지 않는지 확인한다.
- [ ] `exportManualModuleInventory`, manual manifest validator/release validator를 실행하고 생성 inventory와 manifest의 exact path/ref를 검증한다.
- [ ] `CHANGELOG.md`의 `[Unreleased]`에 `추가됨` 또는 `변경됨`으로 Issue #729와 common 분리·R2DBC migration·JDBC facade 호환성 경계를 기록한다. active issue train이 `WIP.md`를 사용하는 경우에만 해당 train 상태도 함께 갱신한다.

## Task 9: 정적 분석과 단계별 검증

**Files:** 변경 파일 전체와 검증 산출물(`.bluetape` receipt, 필요 시 `docs/review/`).

- [ ] 순차적으로 `:bluetape4k-exposed-spring-boot-common:test`, `:bluetape4k-exposed-spring-boot-jdbc:test`, `:bluetape4k-exposed-spring-boot-r2dbc:test`를 실행한다.
- [ ] common/JDBC/R2DBC compile 및 `checkKotlinAbi`, root `detekt`, 영향 범위 compile/test를 실행한다. 실패하면 원인을 분류하고 수정 후 해당 명령부터 재실행한다.
- [ ] common/JDBC/R2DBC의 `koverXmlReport`를 생성하고 `.github/scripts/aggregate-kover-coverage.py`로 모듈별 coverage aggregation이 동작하는지 확인한다. global coverage publish 정책은 Nightly full scope를 따르며, PR path-filtered 결과를 global coverage로 과장하지 않는다.
- [ ] dependency insight로 common의 forbidden JDBC/R2DBC dependency와 R2DBC의 forbidden JDBC adapter/`spring-jdbc`를 확인하고, source import guard를 재실행한다.
- [ ] `git diff --check`, production `println|System.out|System.err` grep, generated API/manual diff, BOM resolution, examples compile을 실행한다.
- [ ] JDBC auto-config를 사용하는 `:bluetape4k-exposed-spring-modulith:compileKotlin`과 관련 test를 실행해 downstream consumer가 legacy facade/새 common artifact 분리로 깨지지 않았음을 확인한다.
- [ ] PostgreSQL, MySQL, Redis 등 Exposed Testcontainers 검증은 repository 규칙대로 순차 수행하고 Docker/Colima 상태와 실제 test 결과를 기록한다.
- [ ] 운영 rollback evidence를 남긴다: legacy JDBC facade가 남아 있어 기존 caller가 즉시 rollback할 수 있고, common/R2DBC 좌표·auto-config 등록·workflow path filter를 되돌릴 때 깨지는 public API와 문서 링크를 명시한다.
- [ ] public API/좌표 변경을 `CHANGELOG.md`와 release-note 입력에 한국어로 기록하고, manual/README의 migration 안내와 동일한 before/after import를 사용한다.
- [ ] 성능·안정성·보안·운영 관점의 잔여 P2/P3를 `docs/review/2026-08-25-spring-data-common-plan-review.md`와 implementation review에 연결하고, 해결하지 않은 항목은 후속 issue로 분리한다.

## Task 10: 7-Tier review, lesson, Lore commit, PR handoff

**Files:** `docs/review/2026-08-25-spring-data-common-7-tier-review.md`, `docs/lessons/2026-08-25-spring-data-common.md`, 변경 파일 전체.

- [ ] 구현 diff와 fresh test/ABI/manual/dependency evidence를 대상으로 `$bluetape-kotlin-patterns` 및 7-Tier 관점을 source-read-only로 재검토한다. Tier 1–7 각 verdict와 근거 파일/명령을 표로 기록한다.
- [ ] review에서 발견한 P0/P1을 모두 해결하고, P2/P3는 수정·정당화·후속 issue 중 하나로 명시한다. `println` 사용과 bluetape4k assertion 누락을 별도 grep/review 항목으로 확인한다.
- [ ] Korean lesson에 선택한 common boundary, rejected alternative, compatibility/migration risk, verification evidence를 기록한다.
- [ ] full-feature workflow receipt의 `implementation`, `validation`, `review`, `pr` checks를 순서대로 완료하고 `completion-check` 전에는 PR을 만들지 않는다.
- [ ] Lore commit protocol에 따라 의도·제약·거부 대안·신뢰도·범위 위험·향후 지시·검증/미검증을 포함한 한국어 커밋을 만든다. exact head를 push한 뒤 PR body 마지막에 `## DoD Status`를 포함한다.
- [ ] PR 생성 후 hosted CI·review·threads·exact head를 다시 읽는다. merge는 별도 fresh approval 없이는 수행하지 않는다.

## 검증 명령 목록

아래 명령은 task 순서와 의존성을 지키며 실행한다. Gradle 명령은 context-mode 경로를 사용한다.

```text
:bluetape4k-exposed-spring-boot-common:compileKotlin
:bluetape4k-exposed-spring-boot-common:checkKotlinAbi
:bluetape4k-exposed-spring-boot-common:test
:bluetape4k-exposed-spring-boot-jdbc:test
:bluetape4k-exposed-spring-boot-r2dbc:test
:bluetape4k-exposed-spring-boot-common:detekt
:bluetape4k-exposed-spring-boot-jdbc:detekt
:bluetape4k-exposed-spring-boot-r2dbc:detekt
:bluetape4k-exposed-spring-boot-common:koverXmlReport
:bluetape4k-exposed-spring-boot-jdbc:koverXmlReport
:bluetape4k-exposed-spring-boot-r2dbc:koverXmlReport
detekt
checkProductionAbi
exportManualModuleInventory
git diff --check
rg -n "println|System\\.out|System\\.err" spring-boot/common spring-boot/jdbc spring-boot/r2dbc examples docs/manual
```

## 완료 조건

- [ ] 승인된 명세의 모든 수용 기준과 DoD 항목이 구현·검증 증거와 연결된다.
- [ ] common은 backend-neutral public SPI만 제공하고 R2DBC runtime/compile graph에 JDBC adapter 또는 `spring-jdbc`가 없다.
- [ ] JDBC legacy public API의 binary descriptor와 migration path가 보존된다.
- [ ] common/JDBC/R2DBC 테스트, ABI, Detekt, manual inventory, BOM, examples, dependency/source guards가 fresh exact-head에서 통과한다.
- [ ] 7-Tier review에서 P0/P1이 0이고, PR은 생성되었으나 merge는 별도 승인 대기 상태로 남는다.
