# Module exposed-druid

[English](./README.md) | 한국어

`bluetape4k-exposed`용 Apache Druid JDBC query-only 실험 모듈입니다. Apache
Calcite Avatica JDBC로 Druid Router 또는 Broker에 연결하고, SQL 조회 실행과
datasource metadata 조회를 위한 작은 helper만 제공합니다.

## 포지셔닝

`exposed-druid`는 의도적으로 **Exposed dialect parity 모듈이 아닙니다**.

지원 범위:

- Druid Router/Broker endpoint용 Avatica JDBC URL/property 구성
- `SELECT`, `WITH`, `EXPLAIN`, `DESCRIBE`, `SHOW` 계열 조회 실행
- `INFORMATION_SCHEMA.COLUMNS` 기반 datasource column metadata 조회
- `Dispatchers.IO` 기반 suspend query 실행

범위 제외:

- DDL/DML helper
- DAO/repository abstraction
- Exposed `Database`/Dialect 등록
- migration 또는 schema generation
- batch write 또는 ingestion API

## 의존성

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-druid:${version}")
}
```

## 연결

```kotlin
import io.bluetape4k.exposed.druid.DruidConnectionOptions
import io.bluetape4k.exposed.druid.DruidJdbc

val options = DruidConnectionOptions(
    avaticaEndpoint = "http://localhost:8888/druid/v2/sql/avatica/",
    contextProperties = mapOf("sqlTimeZone" to "Etc/UTC"),
)

val rows = DruidJdbc.query(
    sql = "SELECT COUNT(*) AS cnt FROM \"wikipedia\"",
    options = options,
) { rs -> rs.getLong("cnt") }
```

기본 endpoint는 Router Avatica path입니다:

```text
http://localhost:8888/druid/v2/sql/avatica/
```

`DruidConnectionOptions.jdbcUrl()`은 다음 형태의 Avatica URL을 만듭니다:

```text
jdbc:avatica:remote:url=http://localhost:8888/druid/v2/sql/avatica/;transparent_reconnection=true
```

배포 환경이 지원한다면 protobuf endpoint도 사용할 수 있습니다:

```kotlin
val options = DruidConnectionOptions(
    avaticaEndpoint = "http://localhost:8888/druid/v2/sql/avatica-protobuf/",
    serialization = DruidAvaticaSerialization.PROTOBUF,
)
```

## Metadata 조회

```kotlin
val columns = DruidJdbc.listColumns(
    datasource = "wikipedia",
    options = options,
)

columns.forEach { column ->
    println("${column.columnName}: ${column.dataType}")
}
```

helper는 기본적으로 `TABLE_SCHEMA='druid'` 조건을 사용하고,
`INFORMATION_SCHEMA.COLUMNS`를 parameterized query로 조회합니다.

## Suspend query

```kotlin
val comments = DruidJdbc.querySuspend(
    sql = "SELECT comment FROM \"wikipedia\" LIMIT 10",
    options = options,
) { rs -> rs.getString("comment") }
```

블로킹 JDBC 작업은 기본적으로 `Dispatchers.IO`에서 실행합니다. 코루틴 취소는
삼키지 않고 다시 던집니다.

## Router/Broker stickiness

Druid JDBC 연결은 Broker 쪽 상태를 가집니다. Router Avatica endpoint를 우선
사용하세요. Router는 JDBC 요청을 sticky하게 라우팅할 수 있습니다. Broker 또는
load balancer에 직접 연결한다면 JDBC 요청이 같은 Broker로 유지되도록 구성해야
합니다. Broker pool 변경이나 재시작에 대비해 `transparent_reconnection`은 켜둡니다.

## Local/container smoke test

일반 CI는 모듈 unit test만 실행합니다. 준비된 local/container Druid 인스턴스와
로드된 fixture datasource를 검증하려면 먼저 Druid를 시작하고 `wikipedia` 같은
fixture를 로드한 뒤 다음을 실행합니다:

```bash
EXPOSED_DRUID_SMOKE=true \
EXPOSED_DRUID_AVATICA_ENDPOINT='http://localhost:8888/druid/v2/sql/avatica/' \
EXPOSED_DRUID_DATASOURCE=wikipedia \
./gradlew --no-parallel :bluetape4k-exposed-druid:test --tests '*DruidJdbcSmokeTest'
```

smoke test는 Avatica 연결, metadata discovery, fixture datasource 대상 `SELECT`를
검증합니다. Testcontainers 또는 Docker 기반 Druid 검증은 반드시 serial로 실행하세요.
공식 Druid quickstart는 multi-container이고 메모리 요구량이 큽니다.

## 참고

- Apache Druid SQL JDBC driver API: https://druid.apache.org/docs/latest/api-reference/sql-jdbc/
- Apache Druid SQL metadata tables: https://druid.apache.org/docs/latest/querying/sql-metadata-tables/
- Apache Druid Docker quickstart: https://druid.apache.org/docs/latest/tutorials/docker/
