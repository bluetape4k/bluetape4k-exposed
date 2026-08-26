---
manualId: "bluetape4k-exposed-spring-boot-common"
id: "bluetape4k-exposed-spring-boot-common"
title: "Exposed Spring Boot 공통 Spring Data SPI"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-spring-boot-common"
sourceDir: "spring-boot/common"
releaseRef: "1.12.1"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-common
---

# Exposed Spring Boot 공통 Spring Data SPI

> Exposed JDBC와 R2DBC 어댑터가 공유하는 백엔드 중립 Spring Data 메타데이터와 쿼리 계획 모듈입니다.

## 문제 {#problem}

기존에는 Spring Data 메타데이터, 쿼리 계획, 정렬 변환이 백엔드 어댑터마다 중복되었습니다. 이 모듈은
공통 annotation, 매핑 메타데이터, 파생 쿼리 조건, 파라미터 접근, `Sort` 변환을 하나의 계약으로
제공하며 JDBC와 R2DBC가 서로의 어댑터에 의존하지 않도록 합니다.

## 사용 시점 {#when-to-use}

데이터베이스 연결 없이 Exposed 전용 Spring Data annotation, 매핑 메타데이터, 파생 쿼리 계획,
정렬 변환이 필요할 때 사용합니다. 저장소 factory, 실제 실행, 트랜잭션 처리가 필요하면 JDBC 또는
R2DBC 어댑터를 사용합니다.

## 좌표 {#coordinates}

ecosystem BOM을 import하고 개별 Bluetape4k 모듈 버전은 생략합니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-common")
}
```

공통 모듈은 JDBC 또는 R2DBC Spring Data 어댑터에 의존하지 않습니다. Exposed BOM과 테스트용
Bluetape4k logging/assertions를 사용하며, 런타임 백엔드는 별도 어댑터가 소유합니다.

## 핵심 개념 {#concepts}

`@ExposedEntity`는 Exposed DAO entity를 Spring Data 메타데이터 대상으로 표시합니다.
`ExposedMappingContext`는 `ExposedPersistentEntity`와 테이블 기반 property를 캐시합니다.
`ExposedQueryCreator`는 지원하는 Spring Data `PartTree` 연산자를 Exposed expression으로 변환하고,
`ParameterMetadataProvider`는 메서드 파라미터를 제공하며, `Sort.toExposedOrderBy`는 안전한
property 이름을 테이블 column으로 변환합니다.

## 빠른 시작 {#quick-start}

새 코드에서는 공통 annotation과 정렬 변환을 import합니다.

```kotlin
import io.bluetape4k.spring.data.exposed.common.annotation.ExposedEntity
import io.bluetape4k.spring.data.exposed.common.annotation.Query
import io.bluetape4k.spring.data.exposed.common.repository.support.toExposedOrderBy

@ExposedEntity
class Member(/* Exposed DAO constructor */)

@Query("SELECT * FROM members WHERE email = ?1")
fun findByEmail(email: String): List<Member>
```

저장소를 실행하는 어댑터가 데이터베이스와 트랜잭션 경계를 계속 소유합니다.

## 작업별 API {#api-by-task}

- 백엔드 중립 entity 메타데이터에는 `@ExposedEntity`와 `ExposedMappingContext`를 사용합니다.
- JDBC와 R2DBC 저장소 메서드가 공유하는 선언 쿼리 정보에는 `@Query`를 사용합니다.
- Spring Data 쿼리 어댑터 구현에는 `ExposedQueryCreator`와 `ParameterMetadataProvider`를 사용합니다.
- 검증된 테이블 column 정렬에는 `Sort.toExposedOrderBy`를 사용합니다.
- 저장소 등록과 실행에는 JDBC 또는 R2DBC artifact를 사용합니다.

## 권장 패턴 {#patterns}

이 모듈에는 백엔드별 트랜잭션이나 연결 코드를 넣지 않습니다. `ExposedMappingContext`는 공유
메타데이터로 취급하고 쿼리 실행 중 테이블 메타데이터를 변경하지 않습니다. 신규 코드는 공통
import를 우선 사용하고, 기존 consumer를 단계적으로 이전하는 동안에만 legacy JDBC import를
유지합니다. 정렬 변환은 임의 SQL 식별자를 삽입하지 않고 Bluetape4k logging 경로를 통해 알 수
없는 property를 건너뜁니다.

## 연동 {#integrations}

JDBC 어댑터는 공통 query, mapping, annotation, sort 계약을 사용하면서 트랜잭션 매니저와 저장소
실행을 유지합니다. R2DBC 어댑터도 같은 계약을 사용하면서 suspend 실행과 coroutine lifecycle을
유지합니다. 공통 모듈 자체는 Spring Boot auto-configuration을 등록하지 않습니다.

## 구성 {#configuration}

이 모듈에는 데이터베이스나 트랜잭션 설정이 없습니다. 애플리케이션은 선택한 JDBC 또는 R2DBC
어댑터, 데이터베이스, pool, 트랜잭션 경계, 저장소 scanning을 구성합니다. 버전은
`bluetape4k-dependencies`로 정렬합니다.

## 실패 모드 {#failures}

- entity가 매핑되지 않음: `@ExposedEntity`와 Exposed DAO/table 계약을 확인합니다.
- property를 찾을 수 없음: 지원하는 Exposed column 매핑을 노출하는지 확인합니다.
- 파생 쿼리가 거부됨: `ExposedQueryCreator`가 지원하는 연산자만 사용하거나 선언 쿼리/어댑터별
  구현을 제공합니다.
- sort field가 무시됨: 매핑된 테이블 column 이름 또는 지원되는 `camelCase`/`snake_case` 표기를
  사용합니다. 알 수 없는 field는 로그를 남기고 건너뜁니다.
- 런타임 트랜잭션이 없음: JDBC 또는 R2DBC 어댑터를 구성합니다. 이 모듈은 트랜잭션을 만들지 않습니다.

## 운영 {#operations}

설정된 Bluetape4k logging 경로에서 쿼리 계획 실패와 건너뛴 sort property를 관찰합니다. 해당
메시지를 선택한 어댑터의 트랜잭션 및 데이터베이스 지표와 함께 추적합니다. 메타데이터 생성 성공을
데이터베이스 readiness로 간주하지 않습니다.

## 테스트 {#testing}

annotation 메타데이터, 매핑 캐시 identity와 동시성, 지원 파생 쿼리 연산자, 파라미터 바인딩,
정렬 변환, 지원하지 않는 property 동작을 테스트합니다. 테스트에는 `bluetape4k-assertions`를
사용하고 JDBC와 R2DBC 어댑터 테스트를 별도로 실행해 각 백엔드의 데이터베이스/트랜잭션 의미를
분리합니다.

## Workshop과 학습 경로 {#workshops}

[Spring Boot JDBC 예제](exposed-spring-boot-jdbc-demo.md)와
[Spring Boot R2DBC 예제](exposed-spring-boot-r2dbc-demo.md)를 실행해 각 어댑터 뒤의 공통 SPI를
확인합니다. 이후 각 어댑터 매뉴얼에서 트랜잭션 소유권과 coroutine lifecycle을 읽습니다.

## 제한 사항 {#limitations}

이 모듈은 저장소 factory, 트랜잭션 매니저, connection pool, entity reload, 백엔드별 오류 처리를
제공하지 않습니다. 파생 쿼리는 `ExposedQueryCreator`가 구현한 연산자로 제한되며 지원하지 않는
연산자는 명시적으로 실패합니다.

## 소스 {#sources}

- [`ExposedEntity.kt`](../../../../spring-boot/common/src/main/kotlin/io/bluetape4k/spring/data/exposed/common/annotation/ExposedEntity.kt)
- [`Query.kt`](../../../../spring-boot/common/src/main/kotlin/io/bluetape4k/spring/data/exposed/common/annotation/Query.kt)
- [`ExposedMappingContext.kt`](../../../../spring-boot/common/src/main/kotlin/io/bluetape4k/spring/data/exposed/common/mapping/ExposedMappingContext.kt)
- [`ExposedQueryCreator.kt`](../../../../spring-boot/common/src/main/kotlin/io/bluetape4k/spring/data/exposed/common/repository/query/ExposedQueryCreator.kt)
- [`ExposedSortSupport.kt`](../../../../spring-boot/common/src/main/kotlin/io/bluetape4k/spring/data/exposed/common/repository/support/ExposedSortSupport.kt)
