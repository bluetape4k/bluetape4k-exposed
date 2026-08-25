# Spring Data 공통 모듈 분리 설계

## 문제와 목표

`spring-boot/r2dbc`가 현재 `spring-boot/jdbc`를 `api` 의존성으로 끌어오고
있다. 이 때문에 R2DBC 소비자의 runtime classpath에 JDBC adapter와
`spring-jdbc`가 노출되며, R2DBC 코드가 JDBC 소유 패키지의 annotation,
mapping context, PartTree query creator, sort 변환기를 직접 참조한다.

이 변경의 목표는 다음과 같다.

1. backend-neutral Spring Data SPI를 독립된
   `bluetape4k-exposed-spring-boot-common` 모듈로 소유시킨다.
2. JDBC와 R2DBC adapter가 common 모듈에 의존하고 서로 의존하지 않게 한다.
3. JDBC/R2DBC transaction executor와 adapter별 auto-configuration은 각
   adapter에 남긴다.
4. 기존 `io.bluetape4k.spring.data.exposed.jdbc.*` 공개 심볼을
   `@Deprecated` JVM forwarding facade로 보존하고 ABI baseline으로 잠근다.
5. R2DBC 단독 소비자의 compile/runtime classpath에서 JDBC adapter와
   `spring-jdbc`를 제거한다.

## 현재 근거

- `spring-boot/r2dbc/build.gradle.kts`가
  `project(":bluetape4k-exposed-spring-boot-jdbc")`를 `api`로 선언한다.
- 현재 R2DBC runtime dependency graph에
  `bluetape4k-exposed-spring-boot-jdbc`와 `org.springframework:spring-jdbc`가
  함께 나타난다.
- R2DBC production source는 다음 JDBC-owned symbol을 직접 import한다.
  `ExposedEntity`, `Query`, `ExposedMappingContext`,
  `ExposedQueryCreator`, `ParameterMetadataProvider`,
  `toExposedOrderBy`.
- JDBC 쪽 `ExposedSpringDataAutoConfiguration`과 R2DBC 쪽
  `ExposedR2dbcSpringDataAutoConfiguration`이 동일한 mapping context bean을
  JDBC auto-configuration ordering으로 공유한다.
- `settings.gradle.kts`에는 Spring Boot JDBC/R2DBC만 등록되어 있고,
  `build.gradle.kts`의 production ABI publication inventory는 34개 모듈을
  fail-closed로 요구한다.
- `docs/manual/manifest.yaml`은 JDBC와 R2DBC manual entry를 별도로
  관리하며, en/ko 모듈 문서 쌍이 필요하다.

## 선택한 구조

### 모듈 경계

새 모듈은 `spring-boot/common` 디렉터리와
`:bluetape4k-exposed-spring-boot-common` Gradle publication으로 등록한다.

common 모듈은 다음만 소유한다.

- `common.annotation`: `ExposedEntity`, `Query`
- `common.mapping`: `ExposedMappingContext`,
  `DefaultExposedPersistentEntity`, `DefaultExposedPersistentProperty`,
  `ExposedPersistentEntity`, `ExposedPersistentProperty`
- `common.repository.query`: `ParameterMetadataProvider`,
  `ExposedQueryCreator`
- `common.repository.support`: `Sort.toExposedOrderBy`와 camel/snake case
  변환 보조 함수

common 모듈은 Exposed core/DAO, Spring Data Commons, Kotlin reflection 및
기존 bluetape4k logging API만 의존한다. JDBC, R2DBC, `spring-jdbc`,
transaction executor, cache adapter, DataSource, R2DBC connection pool은
의존하지 않는다.

JDBC adapter는 common 모듈을 `api`로 사용하고 다음을 계속 소유한다.

- JDBC `transaction {}` 실행과 `SpringTransactionManager`
- JDBC repository/factory/query execution 및 QBE/projection 구현
- JDBC cache health와 aggregate event publisher auto-configuration
- JDBC 전용 `ExposedEntityInformation`

R2DBC adapter는 common 모듈을 `api`로 사용하고 다음을 계속 소유한다.

- `suspendTransaction {}` 실행과 R2DBC repository/factory
- suspend/Flow query execution 및 R2DBC QBE/projection 구현
- R2DBC cache health auto-configuration
- R2DBC 전용 transaction lease와 retry 정책

R2DBC auto-configuration은 JDBC auto-configuration의 `after` ordering을
제거하고 common mapping context를 자체 `@ConditionalOnMissingBean`으로
등록한다. JDBC와 R2DBC가 동시에 classpath에 있을 때도 동일한 bean contract를
중복 생성하지 않도록 양쪽 context test를 유지한다.

### 패키지와 호환성

새 code의 canonical import는 `io.bluetape4k.spring.data.exposed.common.*`로
통일한다. 기존 JDBC package는 다음 규칙으로 보존한다.

- JDBC module의 기존 annotation/class/function JVM symbol은 삭제하지 않고
  `@Deprecated(message = "Use ...common... instead", ReplaceWith = ...)`를
  붙인다.
- class/interface facade는 common 구현을 상속하거나 bridge하여 기존
  constructor와 erased JVM descriptor를 유지한다.
- annotation facade는 기존 runtime retention과 target을 유지하고 adapter가
  common annotation과 legacy annotation을 모두 인식하게 한다.
- `Sort.toExposedOrderBy`는 기존 Kotlin extension entry point를 남기고
  common implementation으로 forwarding한다.
- 기존 JDBC package facade의 binary descriptor와 public ABI dump를
  `api/bluetape4k-exposed-spring-boot-jdbc.api` 및 Kotlin consumer fixture로
  검증한다. 새 common public API에는 별도 baseline을 만든다.
- R2DBC는 JDBC module을 compile/runtime에 추가하지 않는다. 따라서 기존
  R2DBC application이 `jdbc.annotation.*`을 직접 import했다면
  `common.annotation.*`으로 import를 migration해야 한다. 이 migration은
  README와 manual의 before/after 예제로 명시한다. JDBC adapter 사용자는
  기존 annotation facade를 계속 사용할 수 있지만 새 code에는 common
  annotation을 권장한다.

호환 facade의 소유권과 migration 경계는 다음 표로 고정한다.

| 현재 JDBC symbol | canonical symbol | compatibility 위치/전략 | R2DBC 정책 |
|---|---|---|---|
| `jdbc.annotation.ExposedEntity`, `jdbc.annotation.Query` | `common.annotation.*` | JDBC module의 runtime annotation mirror를 `@Deprecated`로 유지하고 JDBC scanner가 두 경로를 인식한다. | common annotation만 사용한다. |
| `jdbc.mapping.*` | `common.mapping.*` | JDBC module의 public class/interface를 삭제하지 않고 deprecated bridge 또는 공통 helper를 호출하는 facade로 유지한다. | common mapping만 사용한다. |
| `jdbc.repository.query.ExposedQueryCreator`, `ParameterMetadataProvider` | `common.repository.query.*` | 기존 constructor/erased descriptor를 보존하는 deprecated subclass/bridge를 유지한다. | common query SPI만 사용한다. |
| `jdbc.repository.support.toExposedOrderBy` | `common.repository.support.toExposedOrderBy` | 기존 Kotlin extension entry point가 common 구현을 호출한다. | common extension만 사용한다. |

기존 JDBC facade는 JDBC artifact에 남기므로 기존 JDBC-only binary consumer의
class loader와 ABI를 보존한다. common artifact에는 legacy package를 중복
발행하지 않는다. facade가 상속으로 동일한 erased descriptor를 만들 수 없는
mapping interface/class는 기존 public symbol을 JDBC module에 유지하고
공통 변환/검색 helper만 common implementation으로 위임한다. 이 경우에도
`@Deprecated`와 migration KDoc을 붙이고 ABI fixture로 실제 descriptor를
검증한다. R2DBC source가 legacy package를 다시 import하는 방식은 허용하지
않는다.

호환 facade가 common implementation과 동일한 JVM descriptor를 만들 수 없는
경우에는 해당 symbol을 임의로 typealias로 대체하지 않는다. typealias는
별도 JVM class를 만들지 않아 binary compatibility를 보장하지 않기 때문이다.
그 경우 실제 facade class와 ABI fixture를 추가하거나, 사용자-visible
breaking change로 승격하여 별도 이슈로 분리한다.

### 자동 설정과 데이터 흐름

```text
JDBC adapter ─┐
              ├─> common mapping/query/sort SPI
R2DBC adapter ┘

JDBC transaction executor      R2DBC suspendTransaction executor
JDBC auto-config               R2DBC auto-config
```

repository factory가 common `ExposedMappingContext`를 받아 domain type과
Exposed `Table`/`Column` metadata를 해석한다. PartTree query creator는
Spring Data `PartTree`와 `ParameterAccessor`를 Exposed `Op<Boolean>`로
변환하고, sort converter는 공통 `SortOrder` 배열을 만든다. 실제 query
execution과 transaction 경계는 adapter가 결정한다.

## 실패 모드와 대응

| 실패 모드 | 방지/검증 |
|---|---|
| common이 JDBC adapter를 다시 의존함 | common/R2DBC dependency graph와 source import guard를 별도로 검사한다. |
| legacy facade가 binary descriptor를 잃음 | 기존 ABI baseline, Java/Kotlin consumer fixture, `checkProductionAbi`를 함께 실행한다. |
| JDBC와 R2DBC auto-config가 context bean을 중복 생성함 | 단독/동시 classpath `ApplicationContextRunner` 테스트와 missing-bean 조건을 검증한다. |
| legacy/common annotation 인식이 달라짐 | 두 annotation 경로의 repository scan 및 `@Query` method metadata 테스트를 추가한다. |
| 새 모듈이 BOM/manual/inventory에서 누락됨 | settings, publication inventory, BOM, ABI baseline, en/ko manual manifest validator를 순서대로 검사한다. |
| sort/query 공통 코드가 adapter 동작을 바꿈 | 기존 JDBC query/sort 테스트와 R2DBC PartTree/페이지/Flow 테스트를 변경 전후로 실행한다. |

## 범위와 제외

포함 범위:

- common 모듈 등록과 publishable/BOM/ABI/manual inventory 연결
- JDBC/R2DBC shared SPI import 및 dependency graph 정리
- legacy JDBC package facade와 migration KDoc/README
- common/JDBC/R2DBC 단위·context·consumer compatibility 테스트

제외 범위:

- JDBC 또는 R2DBC transaction semantics 재설계
- repository CRUD/QBE/projection 기능 추가
- Spring Boot 버전 업그레이드와 Exposed 버전 업그레이드
- unrelated cache, DDD, Ktor, documentation migration

## 수용 기준

1. `settings.gradle.kts`와 publication registry에 common 모듈이 등록되고,
   `:bluetape4k-exposed-spring-boot-common`이 publishable project가 된다.
2. common 모듈의 compile/runtime dependency graph에 JDBC/R2DBC adapter,
   `spring-jdbc`, DataSource 또는 R2DBC pool이 없다.
3. R2DBC production source와 generated dependency graph에 JDBC adapter 및
   JDBC package import가 없다.
4. JDBC와 R2DBC는 common SPI를 통해 동일한 mapping/query/sort semantics를
   유지한다.
5. 기존 JDBC public symbols가 deprecated facade로 남고,
   `checkKotlinAbi`, existing ABI fixture, new common ABI baseline이 PASS한다.
6. JDBC와 R2DBC adapter tests, common tests, 단독 context tests, 필요한
   combined context tests가 PASS한다.
7. root Detekt, module compile, targeted tests, production ABI, manual module
   inventory/validator, `git diff --check`가 PASS한다.
8. common/JDBC/R2DBC `README.md`와 `README.ko.md`의 변경 내용이 parity를
   유지하고, manual manifest의 en/ko 파일이 존재한다.
9. production source에 `println`, `System.out`, `System.err`를 추가하지
   않으며, operational diagnostics는 기존 bluetape4k logging 패턴을 따른다.

## DoD

- 승인된 설계와 구현 계획이 한국어 문서로 기록되고 각 문서의 SPW-01부터
  SPW-05 writer gate가 PASS한다.
- 7-Tier 관점 리뷰의 latest integrated table이 P0=0/P1=0이다.
- Lore trailer를 포함한 한국어 커밋과 issue-linked PR이 생성된다.
- PR은 정확한 head SHA, issue #729, milestone/labels/assignee, 한국어 body와
  마지막 `## DoD Status`를 가진다.
- CI와 human review가 끝나기 전에는 merge하지 않는다. 최종 merge는 별도의
  fresh approval 게이트로 남긴다.
