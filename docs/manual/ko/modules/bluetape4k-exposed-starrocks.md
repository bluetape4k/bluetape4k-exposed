---
manualId: "bluetape4k-exposed-starrocks"
id: "bluetape4k-exposed-starrocks"
title: "Exposed StarRocks 어댑터"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-starrocks"
sourceDir: "exposed/starrocks"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-starrocks
---

# Exposed StarRocks 어댑터

`bluetape4k-exposed-starrocks`는 StarRocks Connector/J를 위한 의도적으로 좁은 Exposed JDBC 어댑터입니다. 1.11은 로컬 All-in-One 연결, 단순 OLAP 테이블 DDL, 삽입, 조회만 검증하며 MySQL과 폭넓게 호환된다고 주장하지 않습니다.

## 해결하려는 문제 {#problem}

StarRocks는 MySQL 호환 wire protocol을 쓰지만 OLAP DDL과 내구성 모델은 별개입니다. 어댑터는 드라이버와 방언을 등록하고 autocommit을 유지하며 smoke test용 보수적인 테이블 기반 클래스를 제공합니다.

## 언제 사용하는가 {#when-to-use}

1.11에서 검증한 작은 StarRocks 연동 범위가 요구사항과 맞고, 기능을 늘릴 때마다 직접 검증할 수 있을 때 사용합니다. 그 밖의 수집은 native SQL이나 전용 ingestion 경로를 고려하세요.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-starrocks")
}
```

## 핵심 개념 {#concepts}

- `StarRocksDatabase`는 host/FE query port/catalog/database, JDBC URL, `DataSource`를 받습니다.
- `StarRocksConnectionWrapper`는 autocommit을 강제하며 commit/rollback은 아무 일도 하지 않습니다.
- `StarRocksTable`은 관계형 PK 문법을 지우고 `ENGINE=OLAP`, 단일 replica fixture 속성을 붙입니다.
- `StarRocksDialect`는 검증되지 않은 스키마 변경과 generated-key 기능을 끕니다.

## 빠른 시작 {#quick-start}

```kotlin
val db = StarRocksDatabase.connect(
    host = "localhost", port = 9030,
    catalog = "default_catalog", database = "analytics", user = "root",
)
transaction(db) { Events.selectAll().limit(100).toList() }
```

## 작업별 API {#api-by-task}

| 작업 | API |
| --- | --- |
| 직접 연결 | `StarRocksDatabase.connect` |
| 애플리케이션 pool | `connect(dataSource)` |
| 드라이버 추가 속성 | `StarRocksConnectionOptions` |
| 좁은 fixture DDL | `StarRocksTable` 상속 |
| 조회·쓰기 | 독립 문장으로 Exposed JDBC DSL 사용 |

## 권장 패턴 {#patterns}

이 모듈을 opt-in 호환 계층으로 다루세요. 생성 DDL을 검토하고 운영에서는 fixture용 replica 설정을 교체합니다. 쓰기는 중복 실행에도 안전하게 만들고, 단건 smoke test만 보고 batch 동작을 추정하지 않습니다.

## 연동 모듈 {#integrations}

StarRocks Connector/J를 제공합니다. 1.11 테스트는 Testcontainers의 `starrocks/allin1-ubuntu`로 메타데이터, 입력 검증, 테이블 생성, 삽입, 조회를 확인합니다. 이 이미지는 로컬 smoke 경로의 근거일 뿐 모든 운영 topology를 대변하지 않습니다.

## 설정 {#configuration}

URL은 `jdbc:starrocks://host:port/catalog.database`이며 보통 FE query port `9030`을 사용합니다. pool 수명은 호출자가 소유합니다. `extraProperties`는 좁은 탈출구이므로 공식 드라이버 문서를 확인한 속성만 넣으세요.

## 실패 방식 {#failures}

- rollback을 내구성 보장으로 쓸 수 없고 문장은 각각 실행됩니다.
- 컬럼 타입 변경, sequence, 여러 generated key, 일부 참조 동작은 꺼져 있습니다.
- 기본 `replication_num=1`은 로컬 fixture용입니다.
- MySQL wire 호환성이 MySQL DDL·트랜잭션 동등성을 뜻하지 않습니다.

## 운영 {#operations}

load 오류, 쿼리 지연, tablet·replica 상태, FE 가용성, 거부된 행을 관찰하세요. ingestion 재시도와 중복 제거 정책은 JDBC wrapper 밖에서 관리하고 운영 DDL은 실제 StarRocks 스키마 도구로 검증합니다.

## 테스트 {#testing}

All-in-One 컨테이너 테스트는 어댑터 회귀 검증에 유지하고 운영과 비슷한 topology는 환경 조건부 테스트로 보완합니다. `SchemaUtils.create` 성공 여부만 보지 말고 최종 DDL 문자열도 검사하세요.

## 학습 경로 {#workshops}

[데이터베이스 어댑터 표](../guides/database-adapter-matrix.md)와 [OLTP와 OLAP 가이드](../guides/oltp-vs-olap.md)를 읽으세요. 연결 → DDL → 단건 삽입 → 단건 조회 순서로 근거를 쌓은 뒤 기능 범위를 넓힙니다.

## 제약 사항 {#limitations}

1.11이 보장하는 범위는 로컬 AIO smoke path입니다. Connector/J 전체, MySQL 방언 전체, 분산 트랜잭션, batch ingestion, paging, 운영 DDL의 동등성을 약속하지 않습니다.

## 소스 {#sources}

- [`StarRocksDatabase`](../../../../exposed/starrocks/src/main/kotlin/io/bluetape4k/exposed/starrocks/StarRocksDatabase.kt)
- [`StarRocksTable`](../../../../exposed/starrocks/src/main/kotlin/io/bluetape4k/exposed/starrocks/StarRocksTable.kt)
- [`StarRocksDialect`](../../../../exposed/starrocks/src/main/kotlin/io/bluetape4k/exposed/starrocks/dialect/StarRocksDialect.kt)
- [DB 테스트](../../../../exposed/starrocks/src/test/kotlin/io/bluetape4k/exposed/starrocks/StarRocksDatabaseTest.kt)

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### StarRocks local OLAP integration boundary 다이어그램

[![StarRocks local OLAP integration boundary 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-starrocks-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-starrocks-diagram-01.svg)

_배포본 README: [`exposed/starrocks/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/starrocks/README.ko.md)_

### StarRocks local smoke 수명 주기 다이어그램

[![StarRocks local smoke 수명 주기 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-starrocks-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-starrocks-flow-02.svg)

_배포본 README: [`exposed/starrocks/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/starrocks/README.ko.md)_

<!-- release-readme-diagrams:end -->
