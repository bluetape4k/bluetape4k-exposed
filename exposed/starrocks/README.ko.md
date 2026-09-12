# Module exposed-starrocks

[English](./README.md) | 한국어

JetBrains Exposed ORM을 위한 StarRocks JDBC 통합 모듈입니다. 이 모듈은 native StarRocks Connector/J 연결, Exposed dialect 등록, metadata 조회, fixture table 설정, 단순 query 실행까지의 좁은 local-first OLAP 경로를 검증합니다.

## 로컬 OLAP 통합 경계

![StarRocks local OLAP integration boundary diagram](../../docs/images/readme-diagrams/exposed-starrocks-diagram-01.png)

### 로컬 Smoke Lifecycle

![StarRocks local smoke lifecycle diagram](../../docs/images/readme-diagrams/exposed-starrocks-flow-02.png)

## 범위

`exposed-starrocks`는 다음을 제공합니다:

- **StarRocksDatabase**:
  `jdbc:starrocks://<fe_host>:<fe_query_port>/<catalog>.<database>` 연결 팩토리
- **StarRocksDialect**: `starrocks` 이름으로 등록되는 최소 Exposed dialect
- **StarRocksDialectMetadata**: 표준 JDBC `DatabaseMetaData` 조회를 유지하는 metadata adapter
- **StarRocksConnectionWrapper**: Exposed 호환을 위한 autocommit 중심 JDBC wrapper
- **StarRocksConnectionOptions**: 추가 JDBC property holder
- **StarRocksTable**: generic primary-key 구문을 제거하고 보수적인 StarRocks OLAP table option을 붙이는 fixture-oriented table base

이 모듈은 MySQL, PostgreSQL, Trino, ClickHouse parity를 주장하지 않습니다. 넓은 StarRocks DDL, partitioning, aggregate key variants, stream load, external catalog, StarRocks Cloud 검증은 범위 밖입니다.

## Table 옵션 지원 정책

Exposed `1.5.0`의 generic `Table.options`·`storageParameters`는 dialect별 안전성을 검사하지 않습니다. 다음 정책은 CREATE TABLE 생성에만 적용됩니다.

| 테이블 / DB             | options                                     | storageParameters                                                            |
|-------------------------|---------------------------------------------|------------------------------------------------------------------------------|
| 기본 Table / H2         | upstream 동작 유지, 빈 옵션 기준 DDL 검증   | 빈 목록 기준 검증                                                            |
| 기본 Table / PostgreSQL | `USING heap` 생성·실행 검증                 | `FillFactorParameter(70)`·`AutovacuumEnabledParameter(false)` 보존·실행 검증 |
| StarRocksTable          | 비어 있지 않으면 `IllegalArgumentException` | 비어 있지 않으면 `IllegalArgumentException`                                  |
| ClickHouseTable         | 비어 있지 않으면 `IllegalArgumentException` | 비어 있지 않으면 `IllegalArgumentException`                                  |

custom table에서는 typed·raw·사용자 정의 옵션을 모두 SQL 생성 전에 거부하며, 옵션의 `toSQL()`이나 문자열 정제로 안전성을 추정하지 않습니다. 빈 문자열 옵션도 지원으로 취급하지 않습니다. 이는 원래 raw 옵션을 지정하던 호출자에게 동작 변경입니다.

StarRocks는 기존의 고정 `ENGINE=OLAP PROPERTIES ("replication_num" = "1")`을 유지합니다. ClickHouse는 기존 `engine` DSL의 `orderBy`·`partitionBy`·`setting`을 사용합니다. MySQL engine/charset과 PostgreSQL WITH 파라미터를 이 설정으로 자동 변환하지 않습니다. 검증된 generic 옵션 allowlist는 현재 없으며, 임의 raw SQL 우회 API도 추가하지 않습니다.

### 수동 migration

[Exposed Table 옵션 계약](https://github.com/JetBrains/Exposed/blob/1.5.0/exposed-core/src/main/kotlin/org/jetbrains/exposed/v1/core/Table.kt)에 따르면 옵션 변경은 migration diff로 추적되지 않습니다. 기존 테이블 변경은 현재 schema와 원하는 설정을 비교한 뒤 DB별 ALTER/재생성 SQL을 별도 migration으로 작성하고, 테스트 DB에서 데이터 보존과 복구 절차를 검증한 후 적용해야 합니다. `SchemaUtils.create`
재호출이나 자동 diff만으로 옵션이 갱신된다고 가정하지 마세요.

## 의존성

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-starrocks:${version}")
}
```

이 모듈은 StarRocks Connector/J를 사용합니다:

```kotlin
implementation("com.starrocks:starrocks-connector-j:1.1.1")
```

## 로컬 StarRocks

검증된 로컬 컨테이너 경로는 공식 StarRocks all-in-one image를 따릅니다:

```bash
docker run -p 9030:9030 -p 8030:8030 -p 8040:8040 -itd \
  --name quickstart starrocks/allin1-ubuntu
```

Docker에는 최소 4 GB RAM과 10 GB 여유 디스크를 할당하는 것을 권장합니다. FE query port는 `9030`입니다.

## 기본 사용법

```kotlin
import io.bluetape4k.exposed.starrocks.StarRocksDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

val db = StarRocksDatabase.connect(
    host = "localhost",
    port = 9030,
    catalog = "default_catalog",
    database = "analytics",
    user = "root",
)

transaction(db) {
    exec("SELECT 1") { rs ->
        rs.next()
        rs.getInt(1)
    }
}
```

`default_catalog.<database>`로 연결하기 전에 대상 database를 먼저 생성해야 합니다. 로컬 테스트는 전용 database를 bootstrap한 뒤 table metadata와 `SELECT` query를 Exposed를 통해 검증합니다.

## 검증

```bash
./gradlew :bluetape4k-exposed-starrocks:test --no-configuration-cache --no-daemon
```
