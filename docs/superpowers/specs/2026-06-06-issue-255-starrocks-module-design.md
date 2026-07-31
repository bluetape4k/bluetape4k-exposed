# Issue #255 StarRocks Exposed module spec

Date: 2026-06-06
Repository: `bluetape4k-exposed`
Issue: `bluetape4k/bluetape4k-exposed#255`
Workflow: `bluetape4k-full-feature`

## Step 1 근거

- live issue #255는 open이며 `debop`에게 할당되고 milestone `backlog`,
  label `enhancement`, `feature`가 지정되어 있다.
- Parent research #227은 StarRocks를 가장 강한 local-first OLAP 구현 후보로
  채택하고 container/dialect 증명을 #255에 위임했다.
- 2026-06-06에 확인한 official StarRocks JDBC docs:
  - Maven coordinate `com.starrocks:starrocks-connector-j:1.1.1`
  - JDBC URL `jdbc:starrocks://<fe_host>:<fe_query_port>/<catalog>.<database>`
  - FE query port 기본값 `9030`
  - catalog/schema/table/column introspection을 위한 표준 JDBC `DatabaseMetaData` 지원
- 2026-06-06에 확인한 official StarRocks Docker quickstart:
  - image `starrocks/allin1-ubuntu`
  - port `9030`, `8030`, `8040`
  - local Docker requirement: 4 GB RAM, 10 GB free disk
- official StarRocks DataGrip docs는 native driver class를
  `com.starrocks.cj.jdbc.Driver`로 명시하고 metadata discovery에 native driver를 권장한다.
- official StarRocks `CREATE TABLE` docs는 고유 OLAP table syntax와
  distribution/key clause를 보여 준다. 첫 구현은 generated DDL을 의도적으로 제한해야 한다.
- Maven Central StarRocks Connector/J metadata는 artifact를 MySQL protocol과
  호환되는 JDBC Type 4 driver로 설명한다. published POM의 license는
  `GNU General Public License, v2 with Universal FOSS Exception, v1.0`이다.
  PR에 dependency-license 근거를 기록하고 driver를 shade/repackage하지 않는다.

## 목표

Exposed가 native StarRocks JDBC driver로 연결하고 StarRocks dialect를
등록하며 local/container StarRocks에서 기본 query와 metadata smoke test를
수행할 수 있도록 dedicated `:bluetape4k-exposed-starrocks` module을 추가한다.

지원 범위를 문서화하되 광범위한 MySQL, PostgreSQL, Trino, ClickHouse parity를 주장하지 않는다.

## 범위 밖

- 완전한 Exposed DAO/repository parity를 주장하지 않는다.
- StarRocks가 MySQL-compatible protocol을 사용한다는 이유만으로 MySQL dialect parity를 주장하지 않는다.
- 광범위한 StarRocks DDL generation, partitioning, rollup, aggregate key variant, external catalog, stream load, data lake feature를 구현하지 않는다.
- Spring Boot starter 또는 R2DBC module을 추가하지 않는다.
- SaaS credential 또는 외부 StarRocks Cloud access를 요구하지 않는다.
- 현재 저장소에 기존 launcher가 없다면 이 issue에서 재사용 가능한
  `bluetape4k-testcontainers` StarRocks launcher를 추가하지 않는다.
  local container fixture는 test-scoped로 유지하고 반복 사용 가능성이 생기면 follow-up을 기록한다.

## Public module 계약

### Module

- Directory: `exposed/exposed-starrocks`
- Gradle project: `:bluetape4k-exposed-starrocks`
- Artifact: `io.github.bluetape4k.exposed:bluetape4k-exposed-starrocks`

`settings.gradle.kts`는 `exposed/*/build.gradle.kts`를 auto-discovery하므로
directory 추가로 module이 등록된다. root README와 repo-local `AGENTS.md`의
public module 목록은 명시적으로 갱신해야 한다.

### Dependency

- sibling OLAP JDBC driver인 `clickhouse-jdbc`, `duckdb-jdbc`, `trino-jdbc`가
  중앙 `bluetape4k-dependencies`가 아닌 이 저장소의 module-local alias이므로
  StarRocks driver도 local version-catalog alias로 추가한다.
- `api(libs.starrocks.connector.j)` 또는 구현 중 확정한 최종 alias를 사용한다.
- module의 connection factory가 runtime에 StarRocks JDBC driver가 있음을
  전제로 하므로 dependency scope는 public으로 유지한다.

### Package와 public type

기존 OLAP module naming style을 따른다.

- `io.bluetape4k.exposed.starrocks.StarRocksDatabase`
- `io.bluetape4k.exposed.starrocks.StarRocksConnectionOptions`
- `io.bluetape4k.exposed.starrocks.StarRocksConnectionWrapper`
- `io.bluetape4k.exposed.starrocks.StarRocksTable`
- `io.bluetape4k.exposed.starrocks.dialect.StarRocksDialect`
- `io.bluetape4k.exposed.starrocks.dialect.StarRocksDialectMetadata`

Public API KDoc은 English로 작성하고 StarRocks가 기존 OLAP wrapper처럼
동작한다면 non-atomic transaction 계약을 명시한다.

### Connection factory

`StarRocksDatabase` 요구사항:

- prefix `jdbc:starrocks` 등록
- dialect name `starrocks` 등록
- dialect metadata 등록
- 다음 기본값을 가진 host/port/catalog/database overload 제공:
  - `host = "localhost"`
  - `port = 9030`
  - `catalog = "default_catalog"`
  - `user = "root"`
  - `password = ""`
- `database`는 명시적으로 요구한다. official quickstart가 container 시작 뒤
  database를 생성하므로 `default_catalog.default`가 있다고 가정하지 않는다.
- direct JDBC URL overload 제공
- Trino/ClickHouse wrapper leak-prevention pattern을 따를 수 있으면 `DataSource` overload 제공
- `DriverManager` 호출 전에 빈 host, catalog, database, user와 invalid port 검증
- URL은 `jdbc:starrocks://{host}:{port}/{catalog}.{database}`로 구성
- driver가 official DataGrip URL template을 허용하면 `CREATE DATABASE` 또는
  readiness 전용 no-database URL `jdbc:starrocks://{host}:{port}`를
  internal/test bootstrap에만 제공한다. 사용자 example은 database 생성 뒤 catalog/database URL을 사용한다.

`StarRocksConnectionOptions`는 작게 유지한다.

- 표준 `user`, `password` property는 항상 지원한다.
- 추가 JDBC property map은 빈 key와 value를 거부한다.
- official docs 또는 driver source가 확인하기 전에는 driver-specific option을 추가하지 않는다.

### Dialect 범위

실제 StarRocks smoke test를 통과할 수 있는 가장 작은 Exposed dialect에서 시작한다.

- source inspection으로 generated SQL이 StarRocks에서 허용됨을 확인한 뒤에만 기존 Exposed vendor dialect를 재사용한다.
- unsupported 또는 unproven DDL feature는 광범위하게 번역하지 말고 disable한다.
- `CREATE TABLE`은 StarRocks-specific으로 취급한다. Exposed 기본 DDL이
  거부되면 test에 필요한 최소 fixture DDL만 생성하는 `StarRocksTable` 또는 좁은 table override를 제공한다.
- 실제 failure가 관찰된 뒤에만 metadata adapter에서 unsupported `DatabaseMetaData` 호출을 피한다. metadata 동작을 선제적으로 가리지 않는다.

## 테스트와 local 검증 계약

module에 다음을 포함한다.

- URL 구성 validation과 dialect registration unit test
- serial로 실행되는 container smoke test:
  - `com.starrocks.cj.jdbc.Driver` connection 성공
  - `SELECT 1` 실행
  - `default_catalog.<test_database>` 연결 전 test database 명시적 생성
  - 알려진 정상 SQL 경로로 최소 fixture table 생성
  - local fixture 경로로 row insert 또는 load
  - `SELECT` query로 예상 데이터 반환
  - `DatabaseMetaData`가 catalog/schema/table/column 정보 발견
- Test resource:
  - `src/test/resources/junit-platform.properties`
  - `src/test/resources/logback-test.xml`
- assertion은 `bluetape4k-assertions` 사용
- Testcontainers 기반 Gradle 검증은 serial 실행

official all-in-one image가 PR CI에 너무 느리거나 불안정하면 다음 acceptance path를 사용한다.

1. local container smoke test는 module에 유지한다.
2. module을 Nightly 또는 explicit serial workflow lane에 둔다.
3. Docker memory/disk requirement와 정확한 local command를 문서화한다.
4. 남은 CI resource risk를 review와 PR body에 기록한다.

## 문서 계약

English와 Korean README를 모두 갱신한다.

- Root `README.md`, `README.ko.md` module table
- Module `exposed/exposed-starrocks/README.md`, `exposed/exposed-starrocks/README.ko.md`

README 내용:

- 지원: connection, narrow dialect registration, metadata smoke, simple query execution
- 비지원: broad DDL/DML parity, MySQL parity claim, StarRocks Cloud, stream load, external catalog feature
- dependency snippet
- local Docker/Testcontainers requirement: image, port, 4 GB RAM, 10 GB disk
- 최소 사용 예

## CI와 등록 계약

새 module이므로 다음을 갱신하거나 검증한다.

- `settings.gradle.kts` auto-discovery 등록
- root README locale set
- repo-local `AGENTS.md` module 목록
- `.github/workflows/ci.yml` path filter, job, coverage artifact, coverage summary `needs`
- StarRocks container가 normal CI에 너무 무거우면 `.github/workflows/nightly-tests.yml` path filter/job, 아니면 CI가 smoke lane을 소유한다는 explicit confirmation
- `gradle/libs.versions.toml` dependency alias
- 저장소가 새 module을 명시적으로 나열한다면 BOM/catalog publication constraint
- 존재한다면 generated catalog/check script
- `./gradlew projects`
- workflow 변경 뒤 `actionlint`

## 인수 기준 mapping

| Issue #255 AC | Spec requirement |
|---|---|
| Local/container smoke가 connection 증명 | `AbstractStarRocksTest` 또는 대응 serial container smoke |
| Fixture/table setup | 알려진 정상 StarRocks SQL fixture 경로 |
| Metadata introspection | `DatabaseMetaData` catalog/schema/table/column smoke |
| SELECT query execution | `SELECT 1`과 fixture query test |
| PostgreSQL/MySQL parity 주장 없음 | README non-goal과 좁은 dialect 범위 |
| CI/Nightly 명시 | Workflow 갱신/검증 계약 |
| 사용자 기능이면 README 갱신 | Root 및 module README locale set |

## 위험

| 위험 | 완화 |
|---|---|
| StarRocks all-in-one image가 무거움 | test serial 실행, resource requirement 문서화, CI 불안정 시 Nightly 사용 |
| Driver는 MySQL protocol compatible이지만 SQL은 StarRocks-specific | generated SQL source 확인과 좁은 DDL 범위 |
| `DatabaseMetaData` 지원이 catalog/database URL에 따라 다름 | test database 명시적 생성 후 `default_catalog.<test_database>` 검증 및 지원 형태 문서화 |
| 기존 bluetape4k StarRocks Testcontainers launcher 없음 | test-scoped singleton fixture 사용, 재사용 필요가 생길 때만 follow-up 생성 |
| 새 dependency가 `bluetape4k-dependencies`에 중앙화되지 않음 | sibling OLAP local alias pattern 재사용과 governance 결정 기록 |

## Source link

- StarRocks JDBC Driver: https://docs.starrocks.io/docs/integrations/JDBC_driver/
- StarRocks Docker quickstart: https://docs.starrocks.io/docs/quick_start/shared-nothing/
- StarRocks DataGrip integration: https://docs.starrocks.io/docs/integrations/IDE_integrations/DataGrip/
- StarRocks CREATE TABLE: https://docs.starrocks.io/docs/sql-reference/sql-statements/table_bucket_part_index/CREATE_TABLE/
- Maven Central StarRocks Connector/J: https://central.sonatype.com/artifact/com.starrocks/starrocks-connector-j/1.1.1
