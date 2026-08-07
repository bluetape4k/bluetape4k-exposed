---
manualId: "bluetape4k-exposed-mysql8"
id: "bluetape4k-exposed-mysql8"
title: "Exposed MySQL 8 GIS 확장"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-mysql8"
sourceDir: "exposed/mysql8"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-mysql8
---

# Exposed MySQL 8 GIS 확장

`bluetape4k-exposed-mysql8`은 JTS 기반 MySQL 8 geometry 컬럼, WKB 변환, 생성 함수, 공간 조건·측정·변환식을 Exposed JDBC에 추가합니다.

## 해결하려는 문제 {#problem}

MySQL 내부 geometry byte와 SRID-aware SQL은 일반 Exposed 컬럼에 바로 맞지 않습니다. 이 모듈은 변환과 공간 식을 타입 중심으로 묶습니다.

## 언제 사용하는가 {#when-to-use}

MySQL 8 스키마가 geometry를 저장하거나 검색할 때 사용합니다. 평범한 MySQL CRUD는 JDBC 모듈과 Connector/J만 사용하세요.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-mysql8")
    runtimeOnly("com.mysql:mysql-connector-j")
}
```

## 핵심 개념 {#concepts}

Geometry 컬럼은 SRID와 JTS 타입을 함께 가집니다. `MySqlWkbUtils`가 MySQL 내부 geometry를 바꾸고, 조건식은 포함·교차·거리·면적·길이·buffer·union·difference·intersection 등을 다룹니다.

## 빠른 시작 {#quick-start}

```kotlin
object Places : Table() {
    val location = geoPoint("location", srid = 4326)
    val reference = geoPoint("reference", srid = 4326)
}
transaction(db) {
    Places.selectAll().where { Places.location.stDWithin(Places.reference, 1_000.0) }
}
```

## 작업별 API {#api-by-task}

| 작업 | API |
| --- | --- |
| 컬럼 | `geoPoint`, `geoPolygon`, `geoLineString`, multi/geometry helper |
| 관계 | `stContains`, `stWithin`, `stIntersects`, `stTouches` |
| 측정 | `stDistance`, `stDistanceSphere`, `stLength`, `stArea` |
| 변환 | `stBuffer`, `stUnion`, `stDifference`, `stIntersection` |

## 권장 패턴 {#patterns}

SRID를 하나로 정해 문서화하고 입력 geometry를 검증하세요. 공간 index는 migration으로 만듭니다. 일반 결과는 안정적인 정렬 키로 page를 나누고 대량 쓰기는 JDBC 모듈의 batch API를 사용합니다.

## 연동 모듈 {#integrations}

JTS는 API 의존성이고 Connector/J는 compile-only라 runtime에 직접 추가해야 합니다. 1.11 테스트는 MySQL Testcontainers에서 WKB, geometry 타입, 공간 관계·측정, write path를 확인합니다.

## 설정 {#configuration}

서버 문자셋·시간대와 geometry SRID를 각각 맞추세요. Connector/J 속성, pool 크기, 격리 수준, migration은 애플리케이션이 소유합니다.

## 실패 방식 {#failures}

SRID 불일치, 잘못된 WKB, 지원하지 않는 geometry subtype, 공간 index 누락은 변환 오류나 full scan을 일으킵니다. 일반 식으로 반환되는 결과는 명시적인 변환이 필요할 수 있습니다.

## 운영 {#operations}

공간 index 사용, rows examined, 임시 테이블, lock wait, batch 지연, pool 포화를 관찰하세요. 거리 기준 옆에는 SRID와 단위를 함께 기록합니다.

## 테스트 {#testing}

MySQL 8 컨테이너에서 geometry family별 round trip, null, WKB byte order, 공간 의미, rollback, paging 순서, batch 실패를 검증합니다.

## 학습 경로 {#workshops}

[어댑터 표](../guides/database-adapter-matrix.md)를 읽고 point 검색 하나부터 구현한 뒤 복잡한 geometry와 index로 넓히세요. 트랜잭션은 [트랜잭션 경계 가이드](../guides/transaction-boundaries.md)를 따릅니다.

## 제약 사항 {#limitations}

Connector/J runtime, 스키마·index 관리는 제공하지 않으며 공간 함수가 PostGIS와 호환된다고 보장하지 않습니다. 일반 MySQL 어댑터가 아니라 MySQL 8 GIS 확장입니다.

## 소스 {#sources}

- [Geometry 컬럼](../../../../exposed/mysql8/src/main/kotlin/io/bluetape4k/exposed/mysql8/gis/GeoColumnTypes.kt)
- [공간 함수](../../../../exposed/mysql8/src/main/kotlin/io/bluetape4k/exposed/mysql8/gis/SpatialFunctions.kt)
- [`MySqlWkbUtils`](../../../../exposed/mysql8/src/main/kotlin/io/bluetape4k/exposed/mysql8/gis/MySqlWkbUtils.kt)
- [Geometry 테스트](../../../../exposed/mysql8/src/test/kotlin/io/bluetape4k/exposed/mysql8/gis/GeometryColumnTypeTest.kt)

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### MySQL8 GIS column DSL coverage

[![MySQL8 GIS column DSL coverage](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-mysql8-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-mysql8-diagram-01.svg)

_배포본 README: [`exposed/mysql8/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/mysql8/README.ko.md)_

### MySQL8 GIS serialization 흐름

[![MySQL8 GIS serialization 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-mysql8-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-mysql8-diagram-02.svg)

_배포본 README: [`exposed/mysql8/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/mysql8/README.ko.md)_

<!-- release-readme-diagrams:end -->
