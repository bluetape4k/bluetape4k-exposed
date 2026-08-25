---
manualId: "bluetape4k-exposed-spring-boot-r2dbc"
id: "bluetape4k-exposed-spring-boot-r2dbc"
title: "Exposed Spring Boot R2DBC 연동"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-spring-boot-r2dbc"
sourceDir: "spring-boot/r2dbc"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-r2dbc
---

# Exposed Spring Boot R2DBC 연동

> 애플리케이션이 소유한 Exposed R2DBC 실행 환경에 Spring Data 저장소 기능만 얹는 얇은 어댑터입니다.

## 제공하는 기능 {#problem}

이 모듈은 Exposed R2DBC용 Spring Data 저장소 매핑과 팩토리를 등록합니다. 자동 설정의 범위는 의도적으로 좁습니다. R2DBC `ConnectionPool`, Exposed `R2dbcDatabase`, Spring reactive 트랜잭션 관리자를 만들지 않습니다. 풀과 `R2dbcDatabase`의 생성·설정·종료는 애플리케이션이 책임집니다.

![Spring Boot R2DBC 소유권과 자동 설정](../../assets/spring/r2dbc-auto-configuration.png)

## 사용하기 좋은 경우 {#when-to-use}

애플리케이션에 이미 정상 동작하는 Exposed R2DBC 실행 환경이 있고 Spring Data 저장소 인터페이스, 파생 질의, 저장소 검색을 사용하려 할 때 적합합니다. 저장소 프록시가 필요 없거나 모든 트랜잭션 조합을 코드에 명시하려면 하위 `bluetape4k-exposed-r2dbc` API를 직접 사용하세요.

## 의존성 좌표 {#coordinates}

생태계 BOM을 가져오면 모듈 버전을 따로 적지 않아도 됩니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-r2dbc")
}
```

R2DBC adapter는 annotation, mapping metadata, 파생 질의 계획, sort 변환을 위해 backend-neutral
`bluetape4k-exposed-spring-boot-common`에 의존합니다. JDBC Spring Data adapter나 `spring-jdbc`에는
의존하지 않으므로 R2DBC-only runtime에 JDBC class가 들어오지 않습니다. Repository interface에서는
common annotation import를 사용하세요.

```kotlin
import io.bluetape4k.spring.data.exposed.common.annotation.Query
```

## 핵심 개념 {#concepts}

`@EnableExposedR2dbcRepositories`가 저장소 인터페이스를 찾아 `ExposedR2dbcRepositoryFactory`에 연결합니다. 저장소를 호출하기 전에 애플리케이션이 `R2dbcDatabase`를 연결해야 하며 저장소 팩토리는 데이터베이스를 주입하거나 소유하지 않습니다. `SimpleExposedR2dbcRepository`의 개별 메서드는 내부에서 Exposed `suspendTransaction`을 엽니다. 따라서 저장소 호출 하나는 트랜잭션으로 실행되지만 여러 호출이 자동으로 한 트랜잭션이 되지는 않습니다.

## 빠르게 시작하기 {#quick-start}

드라이버 `ConnectionFactory`를 만들고 애플리케이션의 `ConnectionPool`로 감싼 뒤 Exposed가 사용할 트랜잭션 대상이 준비되도록 `R2dbcDatabase`를 연결합니다. 저장소 검색을 활성화하고 suspend 함수에서 저장소를 호출한 다음 애플리케이션이 종료될 때 풀을 닫습니다.

```kotlin
@EnableExposedR2dbcRepositories(basePackageClasses = [MemberRepository::class])
class PersistenceConfiguration
```

풀 옵션, 인증 정보, 생명주기 훅, `R2dbcDatabase` 생성 코드는 자동 설정이 아니라 애플리케이션에 둡니다.

## 작업별 API {#api-by-task}

- 저장소 인터페이스는 `ExposedR2dbcRepository<T, ID>`를 확장합니다.
- `@EnableExposedR2dbcRepositories`로 검색 범위를 지정합니다.
- 지원되는 파생 질의는 `PartTreeExposedR2dbcQuery`를 통해 실행합니다.
- 행을 실제 스트림으로 소비해야 한다면 `streamAll()`을 사용합니다.
- 관련 호출을 함께 커밋하거나 롤백해야 한다면 하나의 명시적인 외부 Exposed `suspendTransaction`으로 묶습니다.

## 권장 패턴 {#patterns}

풀과 `R2dbcDatabase`는 애플리케이션 기반 구성 요소 한 곳에서 만들고 정확히 한 번 닫습니다. 저장소를 사용하기 전에 Exposed가 사용할 데이터베이스를 준비하세요. 여러 저장소를 사용하는 업무 작업은 명시적인 외부 `suspendTransaction` 하나로 감쌉니다. 이 모듈이 제공하지 않는 Spring reactive 트랜잭션 관리자를 암묵적으로 기대하면 안 됩니다.

## 연동 {#integrations}

Spring Data의 저장소 검색을 Exposed R2DBC 질의·저장소 구현에 연결합니다. 팩토리는 suspend 메서드에서 Spring Data 트랜잭션 인터셉터를 우회하고 Exposed 구현에 직접 위임합니다. Exposed 트랜잭션을 Spring `ReactiveTransactionManager`에 연결하지 않으므로 저장소가 등록되었다는 이유만으로 Spring `@Transactional` 의미가 적용된다고 가정하면 안 됩니다.

## 설정 {#configuration}

R2DBC 드라이버, 풀 크기, 연결 획득 제한 시간, 검증, 종료 방식은 애플리케이션에서 설정합니다. 데이터베이스가 여러 개라면 명시적인 외부 `suspendTransaction` 또는 `streamAll()`이 지원하는 `database` 인자로 대상을 선택하세요. 저장소 패키지 검색 범위는 데이터베이스를 선택하지 않으므로 별도로 좁게 관리합니다.

## 실패 유형과 해결 방법 {#failures}

- `R2dbcDatabase`가 없어 저장소 시작에 실패함: 저장소 등록 전에 애플리케이션 소유 데이터베이스를 만들고 제공합니다.
- 저장소 호출 두 개 중 일부만 커밋됨: 각각 별도 내부 `suspendTransaction`에서 실행된 것입니다. 하나의 명시적인 외부 `suspendTransaction`으로 묶으세요.
- 종료 뒤에도 풀 연결이 남음: 애플리케이션이 소유한 `ConnectionPool`을 닫습니다. 자동 설정은 풀을 소유하지 않습니다.
- 취소가 애플리케이션 오류로 바뀜: 정리를 마친 뒤 `CancellationException`을 다시 던져 Exposed 트랜잭션과 호출자의 취소 의미를 보존합니다.
- `Flow`가 예상보다 많은 메모리를 사용함: `streamAll()`과 `findAll()`은 `channelFlow`로 행을 실제 스트리밍하지만 `findAllById()`와 `saveAll()` 같은 연산은 방출 전에 결과를 모을 수 있습니다.

## 운영 {#operations}

풀 연결 획득 시간, 활성·유휴 연결, 트랜잭션 시간, 소비한 행 수, 취소, 풀 종료를 관찰하세요. 연결 대기 시간과 질의 실행 시간을 구분해야 합니다. 저장소 프록시가 정상이라는 사실만으로 애플리케이션 소유 풀에서 연결을 얻을 수 있다고 단정할 수 없습니다.

## 테스트 {#testing}

운영과 같은 드라이버·풀 종류로 테스트합니다. 저장소 검색, 단일 호출 커밋·롤백, 여러 호출의 명시적 원자성, 취소 전파, `streamAll()`의 점진적 소비, 메모리에 모으는 Flow의 특성, 풀 종료를 검증하세요. 외부 트랜잭션으로 감싸지 않은 저장소 호출 두 개가 한 원자적 작업이 아니라는 음성 테스트도 포함합니다.

## 학습 경로와 예제 {#workshops}

[Spring Boot R2DBC 예제](exposed-spring-boot-r2dbc-demo.md)를 실행한 뒤 [JDBC와 R2DBC 선택](../guides/jdbc-vs-r2dbc.md), [트랜잭션 경계](../guides/transaction-boundaries.md)를 읽으세요. [Exposed R2DBC 워크숍](https://github.com/bluetape4k/exposed-r2dbc-workshop)에서는 같은 소유권 모델을 더 큰 예제로 발전시킵니다.

## 제약 사항 {#limitations}

이 모듈은 연결을 준비하거나 `R2dbcDatabase` 생명주기를 관리하거나 스키마를 만들지 않으며 Spring reactive 트랜잭션 관리도 제공하지 않습니다. 파생 질의 지원 범위는 배포본 구현에 한정됩니다. Flow를 반환한다고 모두 데이터베이스 커서를 스트리밍하는 것은 아니며 명시적인 스트리밍 경로는 `streamAll()`입니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Spring Boot Exposed R2DBC coroutine repository wiring 다이어그램

[![Spring Boot Exposed R2DBC coroutine repository wiring 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-r2dbc-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-r2dbc-diagram-01.svg)

_배포본 README: [`spring-boot/r2dbc/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/spring-boot/r2dbc/README.ko.md)_

### Spring Boot Exposed R2DBC suspend query 처리 흐름

[![Spring Boot Exposed R2DBC suspend query 처리 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-r2dbc-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-r2dbc-diagram-02.svg)

_배포본 README: [`spring-boot/r2dbc/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/spring-boot/r2dbc/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [`ExposedR2dbcSpringDataAutoConfiguration.kt`](../../../../spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/config/ExposedR2dbcSpringDataAutoConfiguration.kt)
- [`EnableExposedR2dbcRepositories.kt`](../../../../spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/config/EnableExposedR2dbcRepositories.kt)
- [`SimpleExposedR2dbcRepository.kt`](../../../../spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/support/SimpleExposedR2dbcRepository.kt)
- [`PartTreeExposedR2dbcQuery.kt`](../../../../spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/query/PartTreeExposedR2dbcQuery.kt)
