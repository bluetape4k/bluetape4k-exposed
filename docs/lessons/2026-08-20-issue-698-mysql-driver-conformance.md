# Issue #698 MySQL 8 JDBC driver·pool·isolation conformance lesson

## Context

Issue #698은 `parallelJdbcKeyEnumeration`의 MySQL 8 Connector/J·HikariCP·InnoDB
실행 경계를 고정하는 Epic #659의 stacked train slot이다. 안정 배포선은 `1.12.1`이므로
production source/API/ABI, catalog/BOM, workflow, `docs/manual/**`는 변경하지 않았다.
이번 작업은 `MySQLJdbcParallelKeyEnumerationTest`와 JDBC README EN/KO의 테스트 증거만
추가한다.

## 결정과 발견

- `Containers.MySQL8`과 `TestDB.MYSQL_V8.connection()`의 기존 Connector/J 옵션을
  Hikari에 전달하고, test-only `TrackingDataSource`로 실제 lease request/active/peak와
  성공한 `Connection.close()` 반환을 계측했다. setup은 fault를 끈 상태에서 schema/seed를
  만든 뒤 `active == 0`을 확인하고 peak/request만 초기화한다.
- 8개 seed row에서 ID 2와 6을 삭제해 sparse ordering을 만들고, `[null, 5)`와
  `[5, null)` 결과를 순차 `ORDER BY id`와 비교했다. 결과를 `distinct()`로 정규화하지
  않아 중복과 순서 오류가 직접 실패한다.
- pool `1/2/4`와 `maxConcurrency=2`는 acquisition barrier로 실제 SQL lease가
  `min(pool, 2)`에 도달하는지 확인한다. pool 1은 under-provisioned pressure case이며
  운영 pool 권고가 아니다. caller-owned executor는 helper가 닫지 않는지 probe로
  검증한다.
- 내부 `rangeReader`의 두-SELECT seam에서 writer가 row 9를 commit하도록 한 뒤
  `READ_COMMITTED`는 mutation을 관찰하고 명시적 `REPEATABLE_READ`는 첫 read 경계를
  유지한다. 이 결과를 public overload의 shared 기준 데이터 계약으로 확장하지 않는다.
- lease fault는 `defaultMaxAttempts=2`, retry delay 0, 단일 range에서 transient
  connection exception을 주입해 request count 2와 cause chain을 확인한다. statement
  fault는 `readOnly=false`, `defaultMaxAttempts=1`에서 marker INSERT 후 unique
  duplicate를 발생시켜 SQLState `23000` 계열, marker rollback, seed count 불변을
  별도 transaction으로 확인한다.
- schema drop 실패와 datasource close 실패의 primary/suppressed 보존, delegate
  connection close 실패 시 active lease 미반환과 원인 보존을 별도 test-only seam으로
  고정했다. Exposed transaction cleanup이 close 예외를 로깅만 하는 경계도 우회하지
  않고 직접 proxy close를 호출해 증명했다.

## Outcome

- RED: 초기 skeleton은 컴파일 후 1개 MySQL selector에서 의도한
  `RED: sparse ordering contract` assertion으로 실패했다. 이후 `fail<Unit>` 타입 추론,
  실제 SQL acquisition, fixture cleanup 순서를 수정했다.
- GREEN: MySQL 8 Testcontainers targeted `10/0/0/0` (tests/failures/errors/skipped),
  `BUILD SUCCESSFUL`.
- H2 targeted baseline `JdbcParallelKeyEnumerationTest`: `8/0/0/0`,
  `BUILD SUCCESSFUL`.
- affected `:bluetape4k-exposed-jdbc:test` H2 full: `207` executed, `21` skipped,
  `BUILD SUCCESSFUL`. shared `:bluetape4k-exposed-jdbc-tests:test`: `72` executed,
  `5` skipped, `BUILD SUCCESSFUL`.
- `:bluetape4k-exposed-jdbc:detekt`: `BUILD SUCCESSFUL`, findings 없음.
- EN/KO README는 동일한 MySQL matrix, 실행 명령, pool/isolation/N/A 경계와
  `.github/workflows/nightly-tests.yml` 경로를 설명하며 credential/full JDBC URL을
  기록하지 않는다. `docs/manual/**`, production/API/ABI/catalog/BOM/workflow는
  변경하지 않았다.

## Verification

| 검증 | 결과 |
| --- | --- |
| MySQL 8 Testcontainers targeted | `10/0/0/0`, `BUILD SUCCESSFUL` |
| 실행 환경·driver provenance | Docker Server `29.2.1`, image `mysql:8.4.11`, Connector/J `9.7.0` |
| MySQL XML evidence | `exposed/jdbc/build/test-results/test/TEST-io.bluetape4k.exposed.jdbc.MySQLJdbcParallelKeyEnumerationTest.xml`, fresh mtime `2026-08-20 21:24:39 +0900`, `tests=10 skipped=0 failures=0 errors=0` |
| H2 parallel enumeration targeted | `8/0/0/0`, `BUILD SUCCESSFUL` |
| exposed-jdbc H2 full regression | `207/0/0/21`, `BUILD SUCCESSFUL` |
| shared jdbc-tests H2 regression | `72/0/0/5`, `BUILD SUCCESSFUL` |
| detekt | affected module `BUILD SUCCESSFUL`, finding 0 |
| 문서/범위 | EN/KO parity 확인, `git diff --check` PASS, 허용 목록 밖 경로 없음 |
| Type A review | independent design/plan review P0=0/P1=0 |

MySQL nightly full dispatch와 PR exact-head CI는 PR 생성 후 별도 외부 실행 gate다.
dispatch 권한과 fresh CI 증거 전에는 merge-ready로 보고하지 않는다. `SERIALIZABLE`,
network fault, cancellation/child transaction lifecycle은 이 slot의 증거가 아니며
후속 #697과 benchmark/chart 범위 #690에서 다룬다.

## Future Guidance

1. backend conformance fixture는 공통 helper를 성급히 추출하지 말고 driver별 URL,
   isolation, SQLState, cleanup semantics를 독립된 test class에서 먼저 고정한다.
2. connection pool 동시성은 callback 진입만 세지 말고 실제 SQL을 실행한 뒤 acquisition
   barrier를 열어야 한다. barrier timeout·cancel·finally를 모두 bounded하게 만들고
   모든 reader 종료 후 exact peak와 active 0을 판정한다.
3. Exposed transaction이 cleanup 예외를 로깅하고 계속할 수 있으므로, connection
   close failure의 primary/cause 계약은 transaction wrapper가 아니라 직접 주입한 proxy
   seam으로 검증한다.
4. MySQL 한 backend의 PASS를 전체 JDBC 호환성이나 운영 pool 설정 권고로 일반화하지
   않는다. stable manual은 실제 1.13.0 공개 artifact가 생길 때까지 1.12.1을 유지한다.
5. Type A 작업은 PR merge-ready 선언 전에 이 lesson, fresh XML, independent 7-Tier
   review, exact-head CI와 후속 issue 링크를 함께 남긴다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — Issue #698, Epic #659, 선행 #696, 구현 경계와 #697/#690 후속을 고정했다.
- [x] SPW-02 — sparse/range/pool/isolation/rollback/retry/cleanup acceptance와
  RED/GREEN/N/A 경계를 실제 테스트 결과에 맞춰 기록했다.
- [x] SPW-03 — 한국어 technical register를 적용하고 `READ_COMMITTED`,
  `REPEATABLE_READ`, `SQLState`, `N/A`, `PENDING` token을 보존했다.
- [x] SPW-04 — production helper, MySQL fixture, H2/full XML, detekt, README EN/KO,
  scope union과 independent review 결과를 대조했다.
- [x] SPW-05 — Markdown read-back으로 test count, 후속 범위, stable manual 경계와
  merge gate 문장을 확인했다.
