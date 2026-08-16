# 변경 로그

이 프로젝트의 모든 주요 변경 사항은 이 파일에 문서화됩니다.

형식은 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)를 기반으로 하며,
이 프로젝트는 [Semantic Versioning](https://semver.org/spec/v2.0.0.html)을 따릅니다.

## [Unreleased]

### 추가됨

- Spring Data R2DBC repository에 Reactor 타입 없이 coroutine-native Query by Example과
  immutable `FluentQuery` terminal, projection, paging, slice, cold `Flow` 실행을
  추가했습니다
  ([#643](https://github.com/bluetape4k/bluetape4k-exposed/issues/643)).
- Spring Data JDBC `QueryByExampleExecutor.findBy`가 closed interface, Kotlin data class,
  Java record projection과 `project`/sort/limit/page/count/exists를 SQL로 pushdown하고,
  caller-owned transaction에서 한 행씩 소비하는 cursor-backed `stream()`을 지원합니다
  ([#642](https://github.com/bluetape4k/bluetape4k-exposed/issues/642)).

### 변경됨

- JDBC QBE terminal이 하나의 matcher compiler와 cardinality 규칙을 공유합니다. 현재
  transaction에 연결되지 않은 probe, open SpEL projection, nested/unknown property,
  결과 cardinality를 바꾸는 custom `EntityClass.searchQuery` shape는 SQL 전에
  fail-fast로 거부합니다
  ([#642](https://github.com/bluetape4k/bluetape4k-exposed/issues/642)).

## [1.12.1] - 2026-08-06

### 버그 수정

- Gradle Module Metadata의 API 및 runtime variant가 실제 publication dependency-management
  계약을 함께 노출하도록 외부 BOM과 constraint를 정렬했습니다
  ([#619](https://github.com/bluetape4k/bluetape4k-exposed/issues/619)).
- 모든 publication의 POM, Gradle metadata, artifact-isolated downstream consumer를 release
  workflow에서 검증하여 versionless dependency가 관리되지 않은 채 게시되지 않도록 했습니다
  ([#619](https://github.com/bluetape4k/bluetape4k-exposed/issues/619)).

## [1.12.0] - 2026-08-06

### 추가됨

- Apache Druid SQL용 query-only JDBC 통합과 metadata discovery 경로를 추가했습니다
  ([#256](https://github.com/bluetape4k/bluetape4k-exposed/issues/256)).
- Spring, Spring Modulith, Exposed DAO 구현에 종속되지 않는 `AggregateRoot` 및
  `DomainEvent` 계약을 추가했습니다
  ([#320](https://github.com/bluetape4k/bluetape4k-exposed/issues/320)).
- 트랜잭션 결과 이후의 불변 snapshot을 저장하는 DAO near-cache를 추가했습니다
  ([#321](https://github.com/bluetape4k/bluetape4k-exposed/issues/321)).
- 트랜잭션 인식 JDBC aggregate event 전달과 Spring Modulith DDD 예제를 추가했습니다
  ([#323](https://github.com/bluetape4k/bluetape4k-exposed/issues/323),
  [#316](https://github.com/bluetape4k/bluetape4k-exposed/issues/316)).
- Ktor Exposed demo에 R2DBC cache 및 DDD event 시나리오를 추가하고, cache/near-cache의
  health 및 metrics route helper를 제공했습니다
  ([#325](https://github.com/bluetape4k/bluetape4k-exposed/issues/325),
  [#326](https://github.com/bluetape4k/bluetape4k-exposed/issues/326)).

### 버그 수정

- 여러 repository resource를 닫는 과정에서 앞선 실패가 후속 cleanup을 건너뛰지 않도록
  lifecycle 및 coroutine cancellation 처리를 보강했습니다
  ([#341](https://github.com/bluetape4k/bluetape4k-exposed/issues/341)).

### 문서화

- Druid와 Spring Modulith demo module을 매뉴얼 manifest와 module inventory에 등록했습니다
  ([#411](https://github.com/bluetape4k/bluetape4k-exposed/issues/411)).
- 단일 언어 문서와 Kotlin KDoc을 한국어 기술 문체로 정리했습니다
  ([#395](https://github.com/bluetape4k/bluetape4k-exposed/issues/395)).
- Keep a Changelog의 `Fixed` 범주를 한국어 표준 용어인 `버그 수정`으로 통일했습니다
  ([#615](https://github.com/bluetape4k/bluetape4k-exposed/issues/615)).

## [1.11.0] - 2026-06-27

### 변경됨

- `1.10.0` 안정 버전 릴리스 이후 `1.11.0` 개발 라인을 시작했습니다.
- 로컬 `bluetape4k-bom` 및 직접 참조하는 `bluetape4kVersion`을
  `1.11.0-SNAPSHOT`으로 맞췄습니다.

### 추가됨

- JDBC 및 R2DBC Caffeine cache consistency report를 위한 Spring Boot Actuator health indicator를 추가했습니다 ([#225](https://github.com/bluetape4k/bluetape4k-exposed/issues/225)).
- raw SQL 및 생성된 Exposed query를 위한 BigQuery query job option과 dry-run validation API를 추가했습니다 ([#228](https://github.com/bluetape4k/bluetape4k-exposed/issues/228)).
- 성능/session property를 위한 typed Trino JDBC connection option과 pushdown verification guidance 문서를 추가했습니다 ([#229](https://github.com/bluetape4k/bluetape4k-exposed/issues/229)).
- credential이 필요 없는 BigQuery dry-run example module 및 database example matrix를 추가했습니다 ([#230](https://github.com/bluetape4k/bluetape4k-exposed/issues/230)).
- PostgreSQL-wire JDBC connection helper와 CockroachDB Testcontainers smoke coverage를 포함한 초기 `exposed-cockroachdb` module을 추가했습니다 ([#30](https://github.com/bluetape4k/bluetape4k-exposed/issues/30)).
- primary key, unique/index DDL, generated ID, `RETURNING`, metadata 및 deferred migration diff semantics에 대한 CockroachDB Testcontainers coverage와 함께 `exposed-cockroachdb` DDL compatibility boundary를 문서화했습니다 ([#31](https://github.com/bluetape4k/bluetape4k-exposed/issues/31)).
- SQLSTATE `40001` / `restart transaction` error만 재시도하는 CockroachDB 전용 serializable transaction retry helper를 추가했으며, regression 및 Testcontainers smoke coverage를 포함했습니다 ([#32](https://github.com/bluetape4k/bluetape4k-exposed/issues/32)).

### 변경됨

- 1.10.0 라인이 `io.github.bluetape4k:bluetape4k-bom:1.10.0`을 사용하도록 준비했습니다.
- JDBC 및 R2DBC Spring Boot demo module을 위한 weekly 및 pull-request migration smoke workflow를 추가했습니다 ([#226](https://github.com/bluetape4k/bluetape4k-exposed/issues/226)).

## [1.9.2] - 2026-05-26

### 변경됨

- 1.9.2 릴리스 라인이 `io.github.bluetape4k:bluetape4k-bom:1.9.2` 및 `catalog/2026-05-26-00` 공유 의존성 카탈로그를 사용하도록 준비했습니다.
- 중앙에서 관리되는 JetBrains Exposed Gradle plugin을 도입하여 application 및 example module이 shared compatibility line에서 migration script를 생성할 수 있도록 했습니다 ([#213](https://github.com/bluetape4k/bluetape4k-exposed/issues/213)).

### 문서화

- Spring Boot JDBC README의 placeholder repository example을 복사 가능한 example로 교체했습니다 ([#208](https://github.com/bluetape4k/bluetape4k-exposed/issues/208)).
- 현재 module table 및 source layout을 기준으로 root README module relationship diagram을 갱신하고, `docs/images/readme-diagrams/` 아래에 일치하는 SVG 및 PNG asset을 추가했습니다 ([#209](https://github.com/bluetape4k/bluetape4k-exposed/issues/209)).
- README 의존성 예제를 1.9.2 안정 릴리스 좌표에 맞췄습니다.

## [1.9.0] - 2026-05-22

### 변경됨

- 1.9.0 release line이 `io.github.bluetape4k:bluetape4k-bom:1.9.0`에 의존하고 immutable `io.github.bluetape4k.exposed` artifact를 publish하도록 준비했습니다 ([#202](https://github.com/bluetape4k/bluetape4k-exposed/issues/202)).

### 버그 수정

- `AuditableEdgeCaseRecord`를 포함하여 JDBC repository test record에 stable Java serialization contract를 추가했습니다 ([#200](https://github.com/bluetape4k/bluetape4k-exposed/issues/200)).

## [1.8.1] - 2026-05-22

### 추가됨

- PostgreSQL/MySQL `WITH` 및 `WITH RECURSIVE` query를 위한 `CteTable`과 JDBC/R2DBC `withCte` / `withCtes` SELECT helper를 추가했습니다 ([#157](https://github.com/bluetape4k/bluetape4k-exposed/issues/157)).
- JDBC 및 R2DBC repository contract에 batch `saveAll(entities)` API를 추가하고, empty 및 single-entity edge case coverage를 추가했습니다 ([#121](https://github.com/bluetape4k/bluetape4k-exposed/issues/121), [#195](https://github.com/bluetape4k/bluetape4k-exposed/issues/195)).
- Caffeine repository consistency health check 및 명시적인 Redisson `upsertAll(Map<ID, E>)` cache-warming support를 추가했습니다 ([#123](https://github.com/bluetape4k/bluetape4k-exposed/issues/123), [#126](https://github.com/bluetape4k/bluetape4k-exposed/issues/126)).
- virtual-thread executor를 위한 Spring Batch auto-configuration property를 추가했습니다 ([#122](https://github.com/bluetape4k/bluetape4k-exposed/issues/122)).

### 변경됨

- 1.8.1 릴리스 라인이 이후의 `1.8.1-SNAPSHOT` BOM 대신 `io.github.bluetape4k:bluetape4k-bom:1.8.0`에 의존하도록 준비했습니다.
- 1.8.1 cycle 동안 변경된 repository 및 cache API의 public KDoc과 README 문구를 맞췄습니다 ([#129](https://github.com/bluetape4k/bluetape4k-exposed/issues/129), [#130](https://github.com/bluetape4k/bluetape4k-exposed/issues/130), [#138](https://github.com/bluetape4k/bluetape4k-exposed/issues/138), [#194](https://github.com/bluetape4k/bluetape4k-exposed/issues/194)).

### 버그 수정

- `NullPointerException` 또는 `NoSuchElementException`을 발생시킬 수 있었던 R2DBC batch job execution retry 및 concurrent insert recovery path를 수정했습니다 ([#117](https://github.com/bluetape4k/bluetape4k-exposed/issues/117), [#124](https://github.com/bluetape4k/bluetape4k-exposed/issues/124), [#165](https://github.com/bluetape4k/bluetape4k-exposed/issues/165)).
- batch reader close/checkpoint state handling을 수정하여 close 이후 cursor state가 안전하게 reset되고, 잘못된 checkpoint cast가 context와 함께 실패하도록 했습니다 ([#118](https://github.com/bluetape4k/bluetape4k-exposed/issues/118)).
- stale read-through overwrite 또는 cache failure 누락을 일으킬 수 있었던 Caffeine repository cache miss 및 cache warming error path를 수정했습니다 ([#120](https://github.com/bluetape4k/bluetape4k-exposed/issues/120), [#162](https://github.com/bluetape4k/bluetape4k-exposed/issues/162)).
- cancellation이 보존되고 scope shutdown 전에 pending batch가 flush되도록 write-behind close/finally 동작을 수정했습니다 ([#119](https://github.com/bluetape4k/bluetape4k-exposed/issues/119), [#161](https://github.com/bluetape4k/bluetape4k-exposed/issues/161), [#163](https://github.com/bluetape4k/bluetape4k-exposed/issues/163)).
- multiple CTE, `UNION` 및 invalid field-set usage에 대한 CTE edge-case coverage를 추가했습니다 ([#167](https://github.com/bluetape4k/bluetape4k-exposed/issues/167)).

## [1.8.0] - 2026-05-16

### 추가됨

- 독립 repository로서 `bluetape4k-exposed`의 초기 릴리스를 추가했습니다.
- `exposed-core`: JetBrains Exposed DSL을 위한 핵심 컬럼 타입 및 확장 함수.
- `exposed-dao`: DAO Entity 확장 및 생명주기 훅.
- `exposed-jdbc`: 타입 안전 트랜잭션 DSL을 사용하는 JDBC 기반 Repository 패턴.
- `exposed-r2dbc`: `suspendTransaction` DSL을 사용하는 R2DBC 코루틴 네이티브 Repository.
- `exposed-jdbc-tests` / `exposed-r2dbc-tests`: 공유 통합 테스트 픽스처.
- `exposed-cache`: Repository 패턴을 위한 캐시 추상화 인터페이스.
- `exposed-jdbc-caffeine`: Caffeine 로컬 캐시 기반 JDBC Repository.
- `exposed-jdbc-lettuce`: Lettuce Redis 분산 캐시 기반 JDBC Repository.
- `exposed-jdbc-redisson`: Redisson Redis 분산 캐시 기반 JDBC Repository.
- `exposed-r2dbc-caffeine`: Caffeine 로컬 캐시 기반 R2DBC Repository.
- `exposed-r2dbc-lettuce`: Lettuce Redis 분산 캐시 기반 R2DBC Repository.
- `exposed-r2dbc-redisson`: Redisson Redis 분산 캐시 기반 R2DBC Repository.
- `exposed-jackson2`: Jackson 2.x JSON 컬럼 직렬화.
- `exposed-jackson3`: Jackson 3.x JSON 컬럼 직렬화.
- `exposed-fastjson2`: Fastjson2 JSON 컬럼 직렬화.
- `exposed-tink`: Google Tink AES-GCM 암호화 컬럼 지원.
- `exposed-measured`: 쿼리 계측을 위한 Micrometer 메트릭 통합.
- `exposed-postgresql`: PostgreSQL 방언 전용 컬럼 타입 및 확장.
- `exposed-mysql8`: MySQL 8 방언 전용 컬럼 타입 및 확장.
- `exposed-bigquery`: BigQuery 커넥터 지원(외부 SaaS 계정 필요).
- `exposed-clickhouse`: ClickHouse 커넥터 지원(외부 SaaS 계정 필요).
- `exposed-trino`: Trino 커넥터 지원(외부 SaaS 계정 필요).
- `exposed-duckdb`: DuckDB 임베디드 분석 데이터베이스 지원.
- `exposed-timefold-solver-persistence`: Timefold Solver 영속성 통합.
- `spring-boot/jdbc`: Spring Boot 4 JDBC 자동 구성.
- `spring-boot/r2dbc`: Spring Boot 4 R2DBC 자동 구성.
- `spring-boot/batch-exposed`: Boot 4를 위한 Spring Batch + Exposed 통합.
- GitHub Actions CI 워크플로(PR/push에서 H2 전용 빠른 테스트).
- GitHub Actions Nightly 워크플로(전체 매트릭스: H2, PostgreSQL, MySQL, Redis).
- Maven Central 대상 NMCP 통합 게시(Snapshot + Release).
- `exposed-trino`에서 이제 production connection-pool integration(예: HikariCP)을 위한 `TrinoDatabase.connect(dataSource)`를 제공합니다. overload는 pool connection을 `TrinoConnectionWrapper`로 감싸 `autoCommit = true`를 강제하며, wrapper failure 시 raw connection을 close합니다 ([#27](https://github.com/bluetape4k/bluetape4k-exposed/issues/27)).
- `exposed-trino`에서 이제 생성 키 조회를 기본적으로 비활성화하고 커넥터에 종속된 배치 쓰기를 제한된 범위로 수행하는 `trinoBatchInsert`를 제공합니다.
- `exposed-trino`에서 이제 JDBC `ResultSet` 수명을 Exposed 트랜잭션 외부로 노출하지 않고 대규모 결과 집합을 페이지 단위로 수집하는 `pagedQueryFlow`를 제공합니다.
- Root README hero image와 purpose, feature 및 Mermaid architecture documentation을 갱신했습니다 ([PR #64](https://github.com/bluetape4k/bluetape4k-exposed/pull/64)).
- 현재 WIP 대기열에서 초기 독립 릴리스 이후 생성된 Trino Phase 2 및 CockroachDB 에픽을 추적합니다.
- Exposed consumer를 위한 `exposed-bom` BOM module을 추가했습니다 ([PR #15](https://github.com/bluetape4k/bluetape4k-exposed/pull/15)).
- Exposed BOM module을 위한 English 및 Korean README file을 추가했습니다 ([PR #16](https://github.com/bluetape4k/bluetape4k-exposed/pull/16)).
- `exposed-r2dbc-lettuce` documentation을 위한 Mermaid architecture 및 sequence diagram을 추가했습니다 ([PR #2](https://github.com/bluetape4k/bluetape4k-exposed/pull/2)).
- `AuditableR2dbcRepository` 및 R2DBC 감사 업데이트 동등성을 위한 `Int`/`Long`/`UUID` 편의 인터페이스를 추가했습니다.

### 변경됨

- JetBrains Exposed를 1.2.0에서 1.3.0으로 업그레이드했습니다 ([PR #112](https://github.com/bluetape4k/bluetape4k-exposed/pull/112)).
- `ExposedEventPublicationTable.completionAttempts` column을 nullable `integer().default(0).nullable()`에서 non-nullable `integer().default(0)`으로 변경하여 `markResubmitted` UPDATE에서 `Coalesce`가 필요하지 않도록 했습니다 ([PR #113](https://github.com/bluetape4k/bluetape4k-exposed/pull/113), [#101](https://github.com/bluetape4k/bluetape4k-exposed/issues/101)).
- 2026-05-12 기준으로 WIP queue를 갱신했습니다 ([PR #63](https://github.com/bluetape4k/bluetape4k-exposed/pull/63)).
- Build, dependency 및 governance maintenance에서 NMCP, compatibility guard 및 dependency pin을 갱신했습니다 ([PR #49](https://github.com/bluetape4k/bluetape4k-exposed/pull/49), [PR #50](https://github.com/bluetape4k/bluetape4k-exposed/pull/50), [PR #53](https://github.com/bluetape4k/bluetape4k-exposed/pull/53), [PR #54](https://github.com/bluetape4k/bluetape4k-exposed/pull/54), [PR #55](https://github.com/bluetape4k/bluetape4k-exposed/pull/55), [PR #56](https://github.com/bluetape4k/bluetape4k-exposed/pull/56), [PR #57](https://github.com/bluetape4k/bluetape4k-exposed/pull/57), [PR #58](https://github.com/bluetape4k/bluetape4k-exposed/pull/58), [PR #59](https://github.com/bluetape4k/bluetape4k-exposed/pull/59), [PR #60](https://github.com/bluetape4k/bluetape4k-exposed/pull/60), [PR #61](https://github.com/bluetape4k/bluetape4k-exposed/pull/61), [PR #62](https://github.com/bluetape4k/bluetape4k-exposed/pull/62)).
- `spring-boot3/*` module을 제거하고 `spring-boot4/*`를 versionless `spring-boot/*`로 이름을 변경했습니다.
- Spring Boot Gradle 카탈로그 별칭을 `spring.boot.dependencies`, `spring.cloud.dependencies` 및 `libs.plugins.spring.boot`으로 표준화했습니다.
- Spring Boot 4 전용 계약에 맞게 CI, BOM 문서 및 모듈 README 파일을 갱신했습니다.
- path filter 및 new-module test coverage를 포함하도록 CI/Nightly workflow를 재구성했습니다 ([PR #11](https://github.com/bluetape4k/bluetape4k-exposed/pull/11)).
- transient failure noise를 줄이기 위해 CI 및 nightly test job에 `retry=3`을 추가했습니다 ([PR #12](https://github.com/bluetape4k/bluetape4k-exposed/pull/12)).
- Test code를 Kluent에서 `bluetape4k-assertions`로 마이그레이션했습니다 ([PR #14](https://github.com/bluetape4k/bluetape4k-exposed/pull/14)).

### 버그 수정

- **#79** `AbstractJdbcCaffeineRepository` / `AbstractR2dbcCaffeineRepository`: channel overflow 시 `writeBehindQueue.trySend()`가 entity를 조용히 drop하던 문제를 수정하여 이제 `IllegalStateException`을 throw합니다 ([PR #95](https://github.com/bluetape4k/bluetape4k-exposed/pull/95)).
- **#80** `AbstractJdbcRedissonRepository.invalidateAll()` / `invalidateByPattern()`: 안전하지 않은 `*ids.toTypedArray<Any>() as Array<ID>` cast를 element별 `fastRemove()`로 교체하여 `ClassCastException`을 제거했습니다 ([PR #96](https://github.com/bluetape4k/bluetape4k-exposed/pull/96)).
- **#81** `PartTreeExposedQuery.executeDelete`: non-atomic SELECT+DELETE를 직접적인 `table.deleteWhere { op }` DSL call로 교체했으며, 반환값이 이제 실제 deleted row count를 반영합니다 ([PR #97](https://github.com/bluetape4k/bluetape4k-exposed/pull/97)).
- **#82** `DeclaredExposedQuery.coerceIdValue`: bare `rawId as ID` cast를 `idType.isInstance(rawId)` check로 보호하고, 불일치 시 설명적인 message와 함께 `IllegalArgumentException`을 throw하도록 했습니다 ([PR #98](https://github.com/bluetape4k/bluetape4k-exposed/pull/98)).
- **#83** `ClickHouseDatabase.connect()`: wrapper construction failure 이후 close exception이 원래 exception을 대체하지 않고 `e.addSuppressed(closeEx)`를 통해 첨부되도록 수정했습니다 ([PR #99](https://github.com/bluetape4k/bluetape4k-exposed/pull/99)).
- **#84** `ExposedEventPublicationRepository.markResubmitted`: `completionAttempts`에 대한 non-atomic read-modify-write를 `Coalesce(completionAttempts, 0) + 1` SQL expression을 사용하는 단일 UPDATE로 교체했습니다 ([PR #100](https://github.com/bluetape4k/bluetape4k-exposed/pull/100)).
- **#85** `DeclaredExposedR2dbcQuery.toSqlArg`: `runCatching { resolveColumnType() }.getOrElse { TextColumnType() }`가 error를 조용히 무시하던 문제를 수정하여, fallback 전에 warning을 log하는 try/catch로 교체했습니다 ([PR #102](https://github.com/bluetape4k/bluetape4k-exposed/pull/102)).
- **#86** `ExposedJdbcBatchReader.restoreFrom` / `ExposedR2dbcBatchReader.restoreFrom`: `checkpoint as K`를 try/catch `ClassCastException`으로 보호하고, message에 column name 및 actual type을 포함한 `IllegalArgumentException`을 다시 throw하도록 했습니다 ([PR #103](https://github.com/bluetape4k/bluetape4k-exposed/pull/103)).
- **#87** `DeclaredExposedR2dbcQuery`: name으로 ID column을 resolve할 때 사용하던 broad `catch (_: Exception)`을 `IllegalArgumentException`으로 좁혔으며, 다른 exception은 method context와 함께 `IllegalStateException`으로 다시 throw하도록 했습니다 ([PR #102](https://github.com/bluetape4k/bluetape4k-exposed/pull/102), [PR #114](https://github.com/bluetape4k/bluetape4k-exposed/pull/114)).
- **#88** `ExposedEventPublicationRepository.insertArchive`: TOCTOU existence-check-then-insert race를 제거했으며, duplicate-key `ExposedSQLException` (SQL state `23xxx`)는 조용히 흡수하고 그 외 모든 exception은 다시 throw하도록 했습니다 ([PR #104](https://github.com/bluetape4k/bluetape4k-exposed/pull/104)).
- **#89** `BigQueryQueryExecutor.convertValue`: `NumberFormatException` 및 기타 conversion error를 이제 raw value, column name 및 column type context를 포함한 `IllegalArgumentException`으로 감쌉니다 ([PR #105](https://github.com/bluetape4k/bluetape4k-exposed/pull/105)).
- **#90** `PartTreeExposedQuery.executePageQuery`: count를 위한 이중 `entityClass.find { op }` call을 제거하고, 이제 count가 `table.selectAll().where { op }.count()`를 직접 사용합니다 ([PR #106](https://github.com/bluetape4k/bluetape4k-exposed/pull/106)).
- `exposed-fastjson2`를 위한 `DefaultFastjsonSerializer` 파사드를 추가하고 모듈 기본값을 Jackson 직렬화기와 동등하게 맞췄습니다.
- 초기 `utils/batch` Gradle module naming mismatch를 수정했으며, 현재 module path는 `:bluetape4k-exposed-batch`입니다 ([PR #13](https://github.com/bluetape4k/bluetape4k-exposed/pull/13)).
