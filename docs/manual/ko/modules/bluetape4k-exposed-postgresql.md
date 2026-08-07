---
manualId: "bluetape4k-exposed-postgresql"
id: "bluetape4k-exposed-postgresql"
title: "Exposed PostgreSQL 확장"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-postgresql"
sourceDir: "exposed/postgresql"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-postgresql
---

# Exposed PostgreSQL 확장

`bluetape4k-exposed-postgresql`는 일반 Exposed JDBC PostgreSQL 경로에 pgvector, PostGIS, `tstzrange` 타입과 연산자를 추가합니다. 연결과 트랜잭션은 `bluetape4k-exposed-jdbc`와 애플리케이션이 계속 소유합니다.

## 해결하려는 문제 {#problem}

PostgreSQL 전용 값은 그대로 두면 raw SQL이나 드라이버 객체가 저장소 코드에 스며듭니다. 이 모듈은 vector, geometry, timestamp range를 타입이 있는 Exposed 컬럼과 식으로 매핑합니다.

## 언제 사용하는가 {#when-to-use}

스키마가 pgvector, PostGIS, `tstzrange`를 사용할 때만 추가하세요. 평범한 PostgreSQL CRUD에는 JDBC 모듈과 드라이버면 충분합니다.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    // 사용하는 기능에만 pgvector-java 또는 postgis-jdbc 추가
}
```

## 핵심 개념 {#concepts}

`vector(dimension)`은 cosine·L2·inner-product 거리를 지원합니다. PostGIS 컬럼은 JTS point/polygon/geometry와 공간 조건식을 매핑합니다. `tstzRange`는 시각 범위와 겹침·포함·인접 연산을 제공합니다.

## 빠른 시작 {#quick-start}

```kotlin
object Embeddings : Table() { val value = vector("value", 768) }
transaction(db) {
    connection.registerVectorType()
    Embeddings.select(Embeddings.value.cosineDistance(queryVector.literal())).limit(20)
}
```

## 작업별 API {#api-by-task}

| 작업 | API |
| --- | --- |
| vector 검색 | `vector`, `cosineDistance`, `l2Distance`, `innerProduct` |
| 공간 컬럼 | `geoPoint`, `geoPolygon`, `geoGeometry` |
| 공간 조건 | `stDWithin`, `stContains`, `stIntersects`, `stArea` |
| 시간 범위 | `tstzRange`, `overlaps`, `contains`, `isAdjacentTo` |

## 권장 패턴 {#patterns}

서버 extension은 migration으로 설치하고 모든 물리 연결에 pgvector 타입을 등록하세요. workload에 맞는 index와 안정적인 paging 정렬 키를 마련합니다. batch는 JDBC 모듈 API를 사용하며 이 확장 모듈이 batch 의미를 바꾸지는 않습니다.

## 연동 모듈 {#integrations}

PostgreSQL driver, pgvector, PostGIS JDBC, Exposed JDBC/time API는 compile-only입니다. 애플리케이션이 필요한 runtime만 고릅니다. 테스트는 Testcontainers PostgreSQL에서 range와 타입 변환을 검증합니다.

## 설정 {#configuration}

SRID, vector 차원, range 경계, extension 버전을 스키마 계약에 남기세요. pool과 JDBC 격리 수준은 애플리케이션에서 설정합니다.

## 실패 방식 {#failures}

extension 누락, 차원·SRID 불일치, vector 미등록, index 누락은 시작·변환·성능 장애로 이어집니다. 식을 생성했다고 서버 extension이나 index가 만들어지는 것은 아닙니다.

## 운영 {#operations}

실행 계획, index 사용, 거리 검색 범위, 공간 선택도, lock 시간, batch 지연을 관찰하세요. 대표 파라미터로 계획을 확인하고 특정 연산자가 늘 index를 탄다고 가정하지 않습니다.

## 테스트 {#testing}

운영과 같은 extension·migration을 적용한 PostgreSQL 컨테이너를 사용하세요. 값 round trip, null, 범위 경계, 연산 SQL, paging 순서, rollback, batch 실패를 확인합니다.

## 학습 경로 {#workshops}

[어댑터 표](../guides/database-adapter-matrix.md)를 본 뒤 JDBC 저장소에 extension을 하나씩 추가하세요. 트랜잭션은 [트랜잭션 경계 가이드](../guides/transaction-boundaries.md)를 따릅니다.

## 제약 사항 {#limitations}

연결 관리, extension 설치, index 선택은 제공하지 않으며 optional driver도 runtime에 묶지 않습니다. 1.11에 있는 타입과 연산자만 지원 범위입니다.

## 소스 {#sources}

- [pgvector 확장](../../../../exposed/postgresql/src/main/kotlin/io/bluetape4k/exposed/postgresql/pgvector/VectorExtensions.kt)
- [PostGIS 확장](../../../../exposed/postgresql/src/main/kotlin/io/bluetape4k/exposed/postgresql/postgis/GeoExtensions.kt)
- [`tstzrange` 확장](../../../../exposed/postgresql/src/main/kotlin/io/bluetape4k/exposed/postgresql/tsrange/TstzRangeExtensions.kt)
- [Vector 테스트](../../../../exposed/postgresql/src/test/kotlin/io/bluetape4k/exposed/postgresql/pgvector/VectorColumnTypeTest.kt)

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### PostgreSQL extension feature coverage

[![PostgreSQL extension feature coverage](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-postgresql-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-postgresql-diagram-01.svg)

_배포본 README: [`exposed/postgresql/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/postgresql/README.ko.md)_

### PostgreSQL column conversion 흐름

[![PostgreSQL column conversion 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-postgresql-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-postgresql-diagram-02.svg)

_배포본 README: [`exposed/postgresql/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/postgresql/README.ko.md)_

<!-- release-readme-diagrams:end -->
