---
manualId: "exposed-spring-boot-jdbc-demo"
id: "exposed-spring-boot-jdbc-demo"
title: "Spring Boot JDBC 데모"
locale: "ko"
kind: "example"
gradlePath: ":exposed-spring-boot-jdbc-demo"
sourceDir: "examples/jdbc-demo"
releaseRef: "1.12.1"
artifact: null
---

# Spring Boot JDBC 데모

> Exposed DAO 저장소를 Spring MVC API 뒤에 두고 트랜잭션 소유권을 눈으로 확인합니다.

## 학습 내용 {#problem}

`@ExposedEntity`, `ExposedJdbcRepository`, Spring MVC, H2, Spring Boot 4를 작은 애플리케이션 하나로 연결한 예제입니다. 특히 Exposed DAO 엔티티를 어디서 DTO로 바꿔야 하는지 잘 보여 줍니다. 컨트롤러 작업은 모두 Exposed `transaction {}`에 들어가며, 블록을 빠져나오기 전에 `toRecord()`로 변환합니다.

설정을 하나도 하지 않아도 돌아가는 예제는 아닙니다. `ExposedConfig`가 `SpringTransactionManager`를 명시적으로 만들고, 연동 모듈은 저장소 탐색과 구현을 맡습니다.

## 사전 조건 {#prerequisites}

- JDK 21 이상
- 저장소의 Gradle Wrapper
- 기본 실행에는 Docker가 필요하지 않습니다. 테스트와 `bootRun` 모두 메모리 H2를 사용합니다.

## 실행 {#run}

먼저 테스트를 실행합니다.

```bash
./gradlew :exposed-spring-boot-jdbc-demo:test
```

그다음 HTTP 애플리케이션을 시작합니다.

```bash
./gradlew :exposed-spring-boot-jdbc-demo:bootRun
```

기본 포트는 `8080`입니다.

## 확인할 결과 {#expected-result}

애플리케이션이 시작되면 `DataInitializer`가 `Products` 테이블에 필요한 SQL을 `MigrationUtils`에서 받아 실행하고, 테이블이 비어 있으면 상품 3개를 넣습니다. 아래 요청은 JSON 또는 명시한 상태 코드를 반환해야 합니다.

```bash
curl http://localhost:8080/products
curl 'http://localhost:8080/products/search?name=Kotlin Programming Book'
curl -i http://localhost:8080/products/999999
```

첫 목록에는 상품 3개가 있고, 이름 검색은 정확히 일치하는 상품을 반환하며, 없는 ID는 `404`가 됩니다. 컨트롤러 테스트는 생성·수정·삭제도 검증합니다. 저장소 테스트에서는 CRUD, 페이징, 정렬, Exposed DSL 조건과 `findByName`, `findByPriceLessThan` 파생 질의를 확인합니다.

## 실패 진단 {#failures}

- 컨텍스트가 `springTransactionManager`를 만들지 못함: `DataSource`가 하나인지, 애플리케이션에서 재정의한 빈이 `ExposedConfig`와 충돌하지 않는지 확인합니다.
- `No transaction in context`: DAO 접근과 `toRecord()`를 `transaction {}` 안에 둡니다. 블록이 끝난 뒤 살아 있는 DAO 엔티티를 직렬화하면 안 됩니다.
- 검색 결과가 없음: `findByName`은 부분 검색이 아니라 정확히 일치하는 파생 질의입니다.
- 재시작하니 데이터가 사라짐: 기본 URL은 `jdbc:h2:mem:mvcdb`입니다. 프로세스를 넘는 영속성은 이 예제의 범위가 아닙니다.
- 시작할 때 스키마 오류가 남: `Products` 정의, `MigrationUtils` 출력, 저장소에 포함된 마이그레이션을 비교한 뒤 운영 데이터베이스로 옮깁니다.

## 다음 학습 경로 {#next}

[Spring Boot JDBC 연동](bluetape4k-exposed-spring-boot-jdbc.md)에서 저장소 팩터리와 트랜잭션 관리자 소유권을 확인하고, [JDBC 저장소 패턴](bluetape4k-exposed-jdbc/repository-patterns.md)에서 페이징·감사·논리 삭제 경계를 익히세요. [Exposed 워크숍](https://github.com/bluetape4k/exposed-workshop)은 같은 JDBC 경로를 스키마, 트랜잭션, 저장소 실습으로 확장합니다.

## 사용하기 좋은 경우 {#when-to-use}

Spring MVC 기반 블로킹 애플리케이션에서 Exposed DAO 엔티티를 저장소 인터페이스 뒤에 두려 할 때 먼저 실행해 보기 좋습니다. 저장소 메서드 이름 해석을 검증하는 작은 회귀 테스트로도 쓸 수 있습니다. R2DBC, 운영 마이그레이션, 여러 저장소 호출을 하나로 묶는 서비스 트랜잭션이 필요하다면 이 예제를 그대로 복사하지 마세요.

## 의존성 좌표 {#coordinates}

이 데모는 라이브러리를 배포하지 않습니다. 사용자 애플리케이션에서는 중앙 BOM과 JDBC 연동 모듈을 버전 없이 선언합니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc")
}
```

## 핵심 개념 {#concepts}

`ProductEntity`는 트랜잭션에 묶인 객체이고, `ProductRecord`는 HTTP 응답으로 내보내도 안전합니다. `ProductJdbcRepository`는 `ExposedJdbcRepository<ProductEntity, Long>`를 확장하고 지원 범위가 분명한 PartTree 메서드를 추가합니다. 현재 컨트롤러가 Exposed 트랜잭션을 하나씩 소유하며, Spring은 `DataSource`와 명시적으로 선언한 `SpringTransactionManager` 빈을 관리합니다.

학습용으로 책임을 눈에 보이게 나눈 구조입니다. 실제 애플리케이션에서 여러 작업이 한 트랜잭션이어야 한다면 컨트롤러 곳곳에 블록을 두거나 중첩하지 말고 서비스로 옮기세요.

## 빠르게 시작하기 {#quick-start}

1. 테스트를 실행하고 `ProductControllerTest`, `ProductJdbcRepositoryTest`를 읽습니다.
2. `bootRun`으로 시작한 뒤 `GET /products`를 호출합니다.
3. `ProductController.findById`에 중단점을 걸고 `toRecord()`가 `transaction {}` 안에서 실행되는지 확인합니다.
4. `ProductJdbcRepository`에 파생 질의 하나를 추가하고 저장소 테스트를 먼저 만든 뒤 컨트롤러에서 사용합니다.

## 작업별 API {#api-by-task}

| 확인할 작업 | 배포본에서 볼 소스 |
| --- | --- |
| 저장소 계약 | `ProductJdbcRepository` |
| DAO/테이블 매핑 | `ProductEntity`, `Products` |
| DTO 변환과 HTTP 상태 | `ProductController` |
| DataSource 트랜잭션 관리자 | `ExposedConfig` |
| 스키마 초기화와 시드 데이터 | `DataInitializer` |
| MVC 동작 | `ProductControllerTest` |
| 저장소 동작 | `ProductJdbcRepositoryTest` |

## 권장 패턴 {#patterns}

- HTTP 경계에서는 Exposed DAO 엔티티가 아니라 DTO를 반환합니다.
- 여러 작업이 한 단위라면 비즈니스 트랜잭션을 Spring 관리 서비스에 둡니다.
- Spring Data의 모든 키워드가 된다고 가정하지 말고, 지원하고 테스트한 파생 질의만 사용합니다.
- 운영에서는 시작 시점의 자동 스키마 변경 대신 검토한 마이그레이션을 적용합니다.
- 블로킹 JDBC 작업을 reactive/event-loop 스레드에서 실행하지 않습니다.

## 연동 {#integrations}

Spring Boot Web/JDBC Starter, bluetape4k Exposed Spring Boot JDBC 모듈, Exposed DAO/JDBC/migration, Jackson 3, H2를 사용합니다. R2DBC나 외부 컨테이너는 사용하지 않습니다.

## 설정 {#configuration}

`application.yml`은 `jdbc:h2:mem:mvcdb`, 사용자 `sa`, bluetape4k·Exposed 디버그 로그를 설정합니다. 데모 설정에 `spring.exposed.generate-ddl: true`가 있고, 시작 시 실제 스키마 작업은 `DataInitializer`가 `MigrationUtils`로 명시적으로 수행합니다. 운영에서는 마이그레이션 책임을 한 곳으로 정해 애매함을 없애세요.

## 운영 {#operations}

컨텍스트 시작, 스키마 초기화, 트랜잭션, 파생 질의 해석, HTTP 직렬화 실패를 구분해서 기록합니다. H2에서 성공했다고 PostgreSQL이나 MySQL까지 검증된 것은 아닙니다. 데이터베이스를 바꾸면 선택한 엔진에서 저장소 동작과 마이그레이션을 다시 확인해야 합니다.

## 테스트 {#testing}

`ProductControllerTest`는 임의 포트에서 실제 Spring 애플리케이션을 시작하고 `RestClient`로 호출합니다. `ProductJdbcRepositoryTest`는 H2에서 저장소를 검증하며 테스트 사이에 테이블을 정리합니다. 여러 작업을 서비스 트랜잭션으로 옮기기 전에는 업무 예외가 전체 작업을 롤백하는 테스트를 추가하세요.

## 학습 경로와 예제 {#workshops}

`ExposedConfig` → `ProductEntity` → `ProductJdbcRepository` → `ProductController` → 두 테스트 클래스 순서로 읽으면 책임이 자연스럽게 이어집니다. 모듈 매뉴얼은 연동 기능이 무엇을 만드는지 설명하고, 워크숍은 더 넓은 실습을 제공합니다. 이 데모는 두 자료를 실제 실행 경로로 연결합니다.

## 제약 사항 {#limitations}

메모리 데이터베이스 하나, 시작 시 스키마 조정, 컨트롤러 소유 Exposed 트랜잭션을 사용하며 인증은 없습니다. 운영 마이그레이션 전략, 커넥션 풀 크기 산정, 모든 Spring Data 파생 질의 키워드의 지원을 보장하는 예제가 아닙니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Spring Boot JDBC 예제 구조도

[![Spring Boot JDBC 예제 구조도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-jdbc-demo-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-jdbc-demo-diagram-01.svg)

_배포본 README: [`examples/jdbc-demo/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/examples/jdbc-demo/README.ko.md)_

### Spring Boot JDBC 예제 request transaction 처리 흐름

[![Spring Boot JDBC 예제 request transaction 처리 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-jdbc-demo-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-jdbc-demo-diagram-02.svg)

_배포본 README: [`examples/jdbc-demo/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/examples/jdbc-demo/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [데모 개요](../../../../examples/jdbc-demo/README.ko.md)
- [트랜잭션 설정](../../../../examples/jdbc-demo/src/main/kotlin/io/bluetape4k/examples/exposed/mvc/config/ExposedConfig.kt)
- [컨트롤러 트랜잭션과 DTO 경계](../../../../examples/jdbc-demo/src/main/kotlin/io/bluetape4k/examples/exposed/mvc/controller/ProductController.kt)
- [저장소 계약](../../../../examples/jdbc-demo/src/main/kotlin/io/bluetape4k/examples/exposed/mvc/repository/ProductJdbcRepository.kt)
- [컨트롤러 테스트](../../../../examples/jdbc-demo/src/test/kotlin/io/bluetape4k/examples/exposed/mvc/ProductControllerTest.kt)
