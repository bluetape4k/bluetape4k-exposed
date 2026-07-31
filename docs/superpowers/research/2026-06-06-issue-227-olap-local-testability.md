# 이슈 #227 OLAP 로컬 테스트 가능성 조사

날짜: 2026-06-06
범위: 추가 OLAP/데이터 웨어하우스 대상에 대한 `bluetape4k-exposed` 백로그 조사.

## 결정

`bluetape4k-exposed`는 범위가 좁고 로컬에서 테스트 가능한 계약을 갖춘 후보에 대해서만 후속 구현 작업을 시작해야 한다.

후속 이슈 승인:

- #255 — StarRocks 로컬 우선 Exposed 모듈.
- #256 — Apache Druid 쿼리 전용 JDBC 실험.

보류 후보:

- Apache Pinot: 당분간 조사만 유지한다. JDBC 클라이언트는 쿼리 전용이며 `INSERT`, `DELETE`, `UPDATE`를 명시적으로 지원하지 않는다. 또한 문서에서는 드라이버가 ANSI SQL 92를 완전히 준수하지 않는다고 경고한다. 이후 스파이크에서 메타데이터 동작이 입증되지 않는 한, 첫 구현 이슈로는 제약이 너무 크다.
- Amazon Redshift: SaaS/자격 증명 필요. AWS는 향후 Redshift 전용 드라이버 사용을 권장하며, fetch-size 지침과 `maxRows` 미지원 등 Redshift 전용 JDBC 동작을 문서화하고 있다.
- Snowflake: JDBC 지원은 강력하지만, 로컬 에뮬레이터/Testcontainers 경로는 확인되지 않았다. 자격 증명과 외부 테스트 정책이 승인될 때까지 구현 범위에서 제외한다.
- Databricks: JDBC에는 Databricks workspace와 cluster 또는 SQL warehouse가 필요하다. Databricks Connect도 remote cluster/serverless compute에 대해 검증하므로 로컬 Exposed 모듈의 증명이 될 수 없다.

## 후보 매트릭스

| 후보 | JDBC/드라이버 | SQL 방언 적합성 | DDL/DML 적합성 | 메타데이터 | 로컬 테스트 전략 | CI 실행 가능성 | 자격 증명 | 예상 Exposed 범위 | 결정 |
|---|---|---|---|---|---|---|---|---|---|
| StarRocks | Native `com.starrocks:starrocks-connector-j` 드라이버; URL `jdbc:starrocks://...` | MySQL 유사 클라이언트 접근이 가능하지만 StarRocks 전용 SQL을 검증해야 함 | 가능하지만 좁은 범위로 시작 | 공식 문서에서 `DatabaseMetaData` 지원을 명시 | FE 쿼리 포트 `9030`을 사용하는 `starrocks/allin1-ubuntu` Docker quickstart | launcher 검증 후 serial Testcontainers/nightly job으로 실행 가능 | 로컬 root/admin 설정 외 기본 자격 증명 없음 | Connection, 방언 등록, 메타데이터 스모크, 쿼리 실행; 검증 후에만 DDL | #255 열기 |
| Apache Druid | Apache Calcite Avatica JDBC | Druid SQL 쿼리 범위만 지원 | 광범위한 DDL/DML/repository parity에 부적합 | JDBC 메타데이터와 `INFORMATION_SCHEMA`가 문서화됨 | fixture datasource를 사용하는 로컬 quickstart 또는 container; Router endpoint `:8888/druid/v2/sql/avatica/` | container recipe가 안정적이면 serial query-only smoke로 실행 가능 | 보안이 설정되지 않은 로컬 quickstart에는 없음 | 쿼리 실행 및 메타데이터 탐색만 | #256 열기 |
| Apache Pinot | `org.apache.pinot:pinot-jdbc-client` | 준수성 관련 주의사항이 있는 쿼리 전용 OLAP SQL | 부적합: JDBC를 통한 `INSERT`, `DELETE`, `UPDATE` 미지원 | 기능이 다르므로 `ConnectionMetadata`를 검사해야 함 | 로컬 cluster는 가능하지만 메타데이터/방언 검증 위험이 여전히 높음 | 먼저 research spike 수행; 구현 보류 | 로컬 cluster에는 없음; 인증 가능 | 쿼리 전용 후보, 아직 구현 이슈 없음 | 보류 |
| Amazon Redshift | Redshift 전용 JDBC 권장 | PostgreSQL에서 파생되었지만 Redshift 전용 동작이 중요함 | SaaS 엔진; 로컬 호환 Redshift 검증 없음 | 드라이버에 따라 다름 | 로컬 검증을 찾지 못함 | 자격 증명 필요 | AWS 자격 증명 및 cluster | 외부 테스트 경로가 승인될 때까지 조사만 수행 | 보류 |
| Snowflake | 핵심 JDBC API를 지원하는 JDBC type 4 드라이버 | 강력한 warehouse SQL이지만 Snowflake 전용 동작이 있음 | SaaS 엔진; 로컬 에뮬레이터 검증 없음 | `getMetaData()` 및 Snowflake extension APIs가 문서화됨 | 로컬 검증을 찾지 못함 | 자격 증명 필요 | Snowflake account/warehouse | 외부 테스트 경로가 승인될 때까지 조사만 수행 | 보류 |
| Databricks | 현재 JDBC 드라이버 경로는 Databricks workspace compute를 거침 | Spark SQL/lakehouse이며 로컬 DB 엔진이 아님 | Remote compute 계약 | 드라이버/compute에 따라 다름 | 로컬 JDBC 검증 없음; Databricks Connect도 remote cluster/serverless compute를 대상으로 함 | 자격 증명 필요 | Databricks workspace, token, cluster 또는 SQL warehouse | 외부 테스트 경로가 승인될 때까지 조사만 수행 | 보류 |

## 범위가 좁은 계약

### StarRocks

MySQL parity 주장 대신 실제 StarRocks 스모크 경로부터 시작한다.

- StarRocks JDBC 드라이버를 통해 연결한다.
- container recipe가 안정적인 경우에만 최소 fixture table을 생성하거나 로드한다.
- `DatabaseMetaData`의 catalog/schema/table/column 탐색을 입증한다.
- 기본 `SELECT`와 방언에 민감한 쿼리 하나를 실행한다.
- Docker memory 및 port 요구사항을 문서화한다.
- Testcontainers 검증은 serial로 유지한다.

### Druid

쿼리 전용으로 시작한다.

- `transparent_reconnection=true`를 사용해 Avatica JDBC로 연결한다.
- 로컬 fixture datasource를 사용한다.
- `DatabaseMetaData` 또는 `INFORMATION_SCHEMA`를 통해 메타데이터를 조회한다.
- `SELECT` 쿼리만 실행한다.
- DDL, DML, DAO/repository 및 migration 동작은 명시적으로 제외한다.

### Pinot

아직 구현을 시작하지 않는다. 향후 스파이크에서 먼저 다음을 입증해야 한다.

- 안정적인 로컬 cluster startup;
- 예상 Exposed 범위에 대한 JDBC 메타데이터 동작;
- pagination, aggregation 및 prepared statements에 대한 생성 SQL 호환성;
- JDBC가 쿼리 전용이라는 명확한 공개 설명.

## 근거 자료

- Apache Druid SQL JDBC driver API: https://druid.apache.org/docs/latest/api-reference/sql-jdbc/
- Apache Pinot JDBC docs: https://docs.pinot.apache.org/build-with-pinot/connectors-clients-apis/client-libraries/jdbc
- StarRocks JDBC driver docs: https://docs.starrocks.io/docs/integrations/JDBC_driver/
- StarRocks Docker quickstart: https://docs.starrocks.io/docs/quick_start/shared-nothing/
- Amazon Redshift PostgreSQL JDBC/ODBC guidance: https://docs.aws.amazon.com/redshift/latest/dg/c_redshift-postgres-jdbc.html
- Snowflake JDBC API support: https://docs.snowflake.com/en/developer-guide/jdbc/jdbc-api
- Databricks JDBC driver docs: https://docs.databricks.com/aws/en/integrations/jdbc/
- Databricks Connect compute configuration: https://docs.databricks.com/aws/en/dev-tools/databricks-connect/cluster-config
- 기존 위키 노트: `bluetape4k-wiki/research/2026-05-27-exposed-cockroach-olap-bigquery.md`

## 이슈 #227 종료 체크리스트

- [x] 후보 매트릭스가 JDBC 드라이버, SQL 방언 적합성, DDL 지원, 메타데이터 지원, 로컬 테스트 전략, CI 실행 가능성, 자격 증명 및 예상 Exposed 범위를 다룬다.
- [x] Snowflake 구현 이슈를 열지 않는다.
- [x] Databricks 구현 이슈를 열지 않는다.
- [x] 로컬 우선 구현 후보를 제안한다: #255 및 #256.
- [x] 아직 사용자 대상 모듈로 승인된 후보가 없으므로 README 변경은 필요하지 않다.
