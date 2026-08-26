---
manualId: "bluetape4k-exposed-spring-boot-jdbc"
id: "bluetape4k-exposed-spring-boot-jdbc"
title: "Exposed Spring Boot JDBC 연동"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-spring-boot-jdbc"
sourceDir: "spring-boot/jdbc"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc
---

# Exposed Spring Boot JDBC 연동

> 애플리케이션 `DataSource`와 명시적으로 선택한 트랜잭션 관리자를 사용하는 Exposed DAO 엔티티용 Spring Data 저장소입니다.

## 제공하는 기능 {#problem}

이 모듈은 `@ExposedEntity`가 붙은 클래스를 Spring Data 저장소 메타데이터로 매핑하고 Exposed DAO 엔티티용 저장소 프록시를 만듭니다. 자동 설정은 Exposed `EntityClass`가 클래스 경로에 있을 때 활성화됩니다. 호출자가 `DataSource`를 제공하면 `Database.connect(dataSource)`를 호출하고, `springTransactionManager`라는 이름의 빈이 없을 때만 같은 이름의 트랜잭션 관리자를 만듭니다.

![Spring Boot JDBC 자동 설정](../../assets/spring/jdbc-auto-configuration.png)

## 사용하기 좋은 경우 {#when-to-use}

Spring Boot JDBC 애플리케이션이 Exposed DAO 엔티티로 영속 모델을 구성하고 Spring Data 저장소 검색, CRUD, 페이징·정렬, Query by Example, 지원 범위 안의 파생 질의를 사용하려 할 때 적합합니다. 엔티티가 DAO `Entity`가 아니거나 저장소 프록시 규칙이 맞지 않으면 하위 Exposed JDBC 저장소를 사용하세요.

## 의존성 좌표 {#coordinates}

생태계 BOM을 가져오면 모듈 버전을 따로 적지 않아도 됩니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc")
}
```

공통 Spring Data annotation, mapping metadata, query planning, sort 변환은
`bluetape4k-exposed-spring-boot-common`이 소유합니다. 새 source는
`io.bluetape4k.spring.data.exposed.common.*`을 import하세요. 기존
`io.bluetape4k.spring.data.exposed.jdbc.annotation`, `mapping`, `repository.query`,
`repository.support` symbol은 descriptor를 보존하는 deprecated binary facade로 이 artifact에 남아
있으므로 기존 caller를 순차적으로 migration할 수 있습니다.

```kotlin
import io.bluetape4k.spring.data.exposed.common.annotation.ExposedEntity
import io.bluetape4k.spring.data.exposed.common.annotation.Query
```

## 핵심 개념 {#concepts}

`ExposedJdbcRepository<E, ID>`는 Exposed DAO `Entity`와 해당 `IdTable`을 사용합니다. DAO 식별성, 엔티티 캐시, 변경 추적은 현재 Exposed 트랜잭션에 속합니다. 따라서 트랜잭션 밖에서 DAO 엔티티를 만들거나 변경하면 안 됩니다. Spring 서비스 트랜잭션이 비즈니스 작업 단위를 소유하고 저장소는 그 경계 안에서 엔티티 작업을 수행합니다.

## 빠르게 시작하기 {#quick-start}

DAO 엔티티에 `@ExposedEntity`를 붙이고 `table`과 `extractId`를 제공하는 저장소를 정의한 뒤 검색을 활성화합니다.

```kotlin
@EnableExposedJdbcRepositories(basePackageClasses = [MemberRepository::class])
class PersistenceConfiguration

interface MemberRepository : ExposedJdbcRepository<Member, Long> {
    override val table get() = Members
    override fun extractId(entity: Member): Long? = entity.id.value.takeIf { it != 0L }
}
```

애플리케이션 `DataSource`를 제공합니다. 애플리케이션에 `springTransactionManager`가 없다면 자동 설정이 해당 데이터소스를 Exposed에 연결하고 이름이 정해진 관리자를 제공합니다. 비즈니스 작업은 Spring이 관리하는 서비스 메서드에 둡니다.

## 작업별 API {#api-by-task}

- `@EnableExposedJdbcRepositories`에서 저장소 패키지, 질의 전략, `transactionManagerRef`를 지정합니다.
- `ExposedJdbcRepository`로 목록 CRUD, 페이징·정렬, Query by Example과 Exposed `Op<Boolean>` 기반 `findAll`·`count`·`exists`를 사용합니다.
- 지원되는 메서드 이름 질의는 `PartTreeExposedQuery`를 거쳐 실행합니다.
- 새 DAO 엔티티 생성과 변경 추적 속성 수정은 서비스 트랜잭션 안에서 수행합니다.
- 파생 질의 지원 범위를 벗어나면 명시적인 저장소 구현이나 Exposed DSL을 사용합니다.

## 권장 패턴 {#patterns}

여러 저장소 호출과 DAO 변경이 하나의 엔티티 캐시와 커밋 결정을 공유하도록 서비스 계층에 비즈니스 트랜잭션을 둡니다. 비동기 또는 원격 경계 밖으로 DAO 엔티티를 들고 나가지 말고 트랜잭션 안에서 DTO로 바꾸세요. 저장소 검색 범위를 좁게 유지하고 데이터소스가 여러 개라면 트랜잭션 관리자 이름을 명시합니다.

## 연동 {#integrations}

`ExposedSpringDataAutoConfiguration`은 매핑 컨텍스트와 조건부 이름 지정 트랜잭션 관리자를 제공합니다. `@EnableExposedJdbcRepositories`는 registrar를 가져오고 `ExposedJdbcRepositoryFactoryBean`을 사용합니다. 저장소 프록시는 설정한 `transactionManagerRef`를 사용하며 DAO 엔티티를 보고 실행 중에 올바른 데이터소스를 찾지는 않습니다.

## 설정 {#configuration}

`DataSource`, 드라이버, 풀, 인증 정보, 연결 검증, 종료는 호출자가 설정하고 소유합니다. 기본 저장소 관리자 이름은 `springTransactionManager`입니다. 관리자가 여러 개라면 `transactionManagerRef`와 서비스 트랜잭션 qualifier를 같은 대상으로 맞추세요. 기본 이름의 빈이 있으면 자동 설정 관리자는 만들어지지 않으므로 애플리케이션이나 데모가 의도적으로 교체할 수 있습니다.

## 실패 유형과 해결 방법 {#failures}

- 저장소 엔티티를 인식하지 못함: DAO 클래스의 `@ExposedEntity`와 필요한 `EntityClass`·테이블 매핑을 확인합니다.
- `springTransactionManager`가 없음: `DataSource` 빈이 있는지 확인하거나 이름이 같은 관리자를 직접 제공합니다.
- 다른 데이터베이스가 갱신됨: `transactionManagerRef`와 서비스 트랜잭션 qualifier를 같은 관리자로 지정합니다.
- 반환 뒤 DAO 엔티티 접근이 실패함: 트랜잭션을 나가기 전에 DTO로 변환합니다.
- 파생 메서드를 해석하지 못함: 제한된 `PartTreeExposedQuery` 지원 범위를 지키거나 명시적 질의·구현을 제공합니다.

## 운영 {#operations}

데이터소스 연결 획득 시간, 활성·유휴 연결, 트랜잭션 시간, 롤백 건수, 질의 지연, 풀 종료를 관찰하세요. 데이터소스가 여러 개인 서비스는 시작할 때 선택한 트랜잭션 관리자와 데이터소스 식별자를 기록하는 편이 좋습니다. 저장소 프록시가 만들어졌다는 사실은 데이터베이스 readiness 검사가 아닙니다.

## 테스트 {#testing}

Testcontainers로 운영과 같은 데이터베이스 계열을 사용합니다. 저장소 검색, 트랜잭션 안의 DAO 생성과 변경 속성 flush, 여러 저장소 호출의 서비스 수준 롤백, 페이징·정렬, 실제로 사용하는 각 파생 질의, 여러 관리자 환경에서 선택한 관리자를 검증하세요. 사용자 정의 `springTransactionManager`가 자동 설정을 대체하는 사례도 포함합니다.

## 학습 경로와 예제 {#workshops}

[Spring Boot JDBC 예제](exposed-spring-boot-jdbc-demo.md)를 실행한 뒤 [트랜잭션 경계](../guides/transaction-boundaries.md)와 [JDBC 저장소 패턴](bluetape4k-exposed-jdbc/repository-patterns.md)을 읽으세요. [Exposed 워크숍](https://github.com/bluetape4k/exposed-workshop)에서는 서비스와 저장소 설계를 더 큰 예제로 발전시킵니다.

## 제약 사항 {#limitations}

이 모듈은 `DataSource`를 만들거나 여러 트랜잭션 관리자 중 하나를 선택하지 않으며 모든 Spring Data 파생 질의 연산자를 지원하지도 않습니다. DAO 엔티티의 식별성과 변경 추적에는 활성 Exposed 트랜잭션이 필요합니다. 데모처럼 트랜잭션 관리자를 명시적으로 교체할 수 있으므로 기본 관리자가 언제나 자동 생성된다고 가정하면 안 됩니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Spring Boot Exposed JDBC repository wiring 다이어그램

[![Spring Boot Exposed JDBC repository wiring 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-jdbc-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-jdbc-diagram-01.svg)

_배포본 README: [`spring-boot/jdbc/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/spring-boot/jdbc/README.ko.md)_

### Spring Boot Exposed JDBC query resolution 처리 흐름

[![Spring Boot Exposed JDBC query resolution 처리 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-jdbc-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-jdbc-diagram-02.svg)

_배포본 README: [`spring-boot/jdbc/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/spring-boot/jdbc/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [`ExposedSpringDataAutoConfiguration.kt`](../../../../spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/config/ExposedSpringDataAutoConfiguration.kt)
- [`ExposedEntity.kt`](../../../../spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/annotation/ExposedEntity.kt)
- [`EnableExposedJdbcRepositories.kt`](../../../../spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/config/EnableExposedJdbcRepositories.kt)
- [`ExposedJdbcRepository.kt`](../../../../spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/ExposedJdbcRepository.kt)
- [`SimpleExposedJdbcRepository.kt`](../../../../spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/support/SimpleExposedJdbcRepository.kt)
- [`PartTreeExposedQuery.kt`](../../../../spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/query/PartTreeExposedQuery.kt)
- [`ExposedConfig.kt` 데모 재정의](../../../../examples/jdbc-demo/src/main/kotlin/io/bluetape4k/examples/exposed/mvc/config/ExposedConfig.kt)
