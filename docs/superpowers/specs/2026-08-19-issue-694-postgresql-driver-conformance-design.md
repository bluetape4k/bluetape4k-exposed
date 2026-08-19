# Issue #694 첫 stacked slot: PostgreSQL JDBC driver conformance 설계

## 문서 상태

- 대상 이슈: [#694](https://github.com/bluetape4k/bluetape4k-exposed/issues/694)
- Epic: [#659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659)
- 선행 slot: #690 / merged PR #695
- 대상 milestone: `1.13.0` 개발선
- 기준 base: `develop` `ff85c999ff976544358d87f519d565188e6380f2`
- 구현 branch: `test/issue-694-postgresql`
- worktree: `.worktrees/issue-694-postgresql`
- 범위: PostgreSQL Testcontainers/JDBC test-only conformance
- 설계 상태: 승인·구현 완료, 첫 slot fresh verification evidence 반영

## 문제 정의

PR #695는 호출자가 나눈 PK range를 bounded Virtual Thread JDBC transaction으로
조회하는 opt-in helper를 추가했다. 현재 helper의 correctness와 pool 상한은 H2
fixture로만 검증되어 있으며, H2의 in-memory 동작만으로 PostgreSQL driver의
connection lease, transaction isolation, range 사이 mutation 의미를 보장할 수 없다.

Issue #694 전체는 PostgreSQL과 MySQL 8, pool/isolation, 실제 cancellation/lifecycle,
세 번의 benchmark와 차트까지 포함한다. 이 slot은 그중 PostgreSQL의 결정론적
correctness·pool·isolation 증거만 고정한다. MySQL fixture와 cross-driver 성능 비교를
같은 변경에 섞지 않아 driver별 실패 원인과 stacked PR 경계를 분리한다.

## 현재 근거와 불변 경계

- `exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/JdbcParallelKeyEnumeration.kt`
  이 slot의 production API 기준이다. 이번 slot에서는 해당 파일을 수정하지 않는다.
- `exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/JdbcParallelKeyEnumerationTest.kt`
  는 H2에서 range ordering, sparse ID, bounded concurrency와 실패 lifecycle을 이미
  검증한다. PostgreSQL fixture는 이 계약의 driver-specific 증거를 추가한다.
- `exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/Containers.kt`의
  `Containers.Postgres`와 `TestDB.POSTGRESQL`을 재사용한다. container lifecycle을
  새로 만들거나 별도 이미지 태그를 하드코딩하지 않는다.
- `.github/workflows/nightly-tests.yml`의 `EXPOSED_TEST_DB: POSTGRESQL` job이
  `exposed/jdbc`와 `exposed/jdbc-tests`를 Testcontainers로 실행한다. 첫 slot에서
  workflow를 늘리지 않고 기존 nightly fan-out에 test를 태운다.
- `docs/manual/**`는 안정 release `1.12.1`의 source of truth이므로 이 slot에서
  변경하지 않는다.

## 채택한 구조

### PostgreSQL 전용 test fixture

새 테스트는 `exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/` 아래에 두고,
기본 parity fixture는 기존 `withTables(TestDB.POSTGRESQL, ...)` 경계를 사용한다.
pool/isolation fixture는 같은 `Containers.Postgres` 인스턴스에서 Hikari
`DataSource`를 직접 만들고 `Database.connect(dataSource)`로 연결한다. 이렇게 해야
실제 pool 크기를 test subject로 고정할 수 있으며, `TestDB.db`의 DriverManager
연결과 혼동하지 않는다. fixture가 확인할 대상은 다음과 같다.

1. deterministic seed를 가진 sparse `Long` PK table을 준비한다.
2. 같은 범위를 sequential keyset reader와 `parallelJdbcKeyEnumeration`에 각각
   적용한다.
3. 두 결과가 range 선언 순서, sparse row, no-duplicate 조건에서 일치하는지
   검증한다.
4. PostgreSQL JDBC connection lease를 test-only `DataSource` decorator로 관찰해
   active lease가 `maxConcurrency`를 넘지 않는지 검증한다.
5. pool size가 `maxConcurrency`보다 작거나 같거나 큰 세 조합을 실행한다. 실제
   Hikari 대기 timeout은 scheduler timing에 따라 결정론적이지 않을 수 있으므로,
   성공 조합은 lease peak로 검증하고 failure propagation은 Hikari를 감싼 test-only
   `SQLTransientConnectionException` fault injector로 고정한다. Exposed의 기본
   transaction retry가 모든 주입 fault를 소진한 뒤에도 원인과 cleanup을 보존해야 한다.
6. `READ_COMMITTED`와 `SERIALIZABLE`을 명시적으로 선택한 transaction에서 range
   사이 insert/delete를 수행하고, 전체 결과가 하나의 읽기 기준을 따른다고 주장하지
   않는 weak-consistency 계약을 고정한다.

`DataSource` decorator는 production source set이나 public API에 노출하지 않는다.
현재 helper는 connection lease observer를 제공하지 않고, Hikari 자체의 pool metric을
테스트에서 직접 고정하면 구현 세부사항에 결합되므로 test-only proxy를 사용한다. 이
proxy는 `getConnection()` 획득·반환을 세고, close 누락을 실패로 보고한다. 측정은
connection pool 동작만 대상으로 하며 SQL 결과를 변형하지 않는다.

### test fixture의 책임

- database와 Hikari pool의 생성·종료는 test fixture가 소유한다.
- helper에 넘긴 custom executor는 테스트가 소유하고 종료한다. helper가 executor를
  닫지 않는다는 기존 API 계약도 assertion으로 고정한다.
- test container는 `Containers.Postgres`를 통해 기존 공용 lifecycle에 등록한다.
- test가 실패해도 shared container나 canonical worktree를 정리하는 destructive
  명령을 실행하지 않는다.

## 검증 계약

### Correctness

| 시나리오 | 기대 결과 |
| --- | --- |
| 연속하지 않은 PK와 열린 외부 경계 | sequential/parallel 결과가 같은 정렬된 ID 집합을 반환 |
| 인접한 disjoint range | 각 ID가 정확히 한 번만 나타남 |
| 역순 또는 overlap range | query/connection side effect 전에 명확한 validation failure |
| 빈 table 또는 빈 range 목록 | 빈 결과, 불필요한 child transaction 없음 |
| caller가 정한 `maxConcurrency` | active connection lease 상한이 해당 값 이하 |

결과 비교는 `distinct()`로 오류를 숨기지 않는다. 중복이 관찰되면 partition
계약 위반으로 실패시킨다. PostgreSQL의 실제 PK ordering과 helper의 comparator가
일치하는지 fixture의 seed와 assertion으로 확인한다.

### Pool과 실패 원인

세 pool/concurrency 조합을 별도 test case로 고정한다.

- `poolSize < maxConcurrency`: bounded helper가 pool 대기 후 회수되는지와 active
  lease가 pool 크기를 넘지 않는지 검증한다. Hikari 자체 timeout의 재현성은 별도
  환경 evidence로 남긴다.
- `poolSize == maxConcurrency`: 모든 range가 동시에 lease를 얻을 수 있고 active
  lease가 상한을 넘지 않아야 한다.
- `poolSize > maxConcurrency`: 여분 connection이 있어도 helper의 상한은
  `maxConcurrency`에 머물러야 한다.

환경 또는 driver가 특정 timeout을 결정론적으로 보장하지 않으면 해당 조합은
`N/A`로 기록하고 exact command, driver version, failure cause를 evidence ledger에
남긴다. test-only fault injection은 실제 Hikari timeout의 대체 성능 수치가 아니며,
helper의 retry·원인 전파·lease cleanup만 증명한다. 성공하지 않은 조합을 성능 수치나
보편적 pool 권고로 일반화하지 않는다.

### Isolation과 mutation

mutation fixture는 다음 경계를 분리한다.

- reader transaction이 시작된 뒤 writer transaction이 range 안에 row를 insert하거나
  기존 row를 delete한다.
- `READ_COMMITTED`에서는 range별 statement 시점에 따라 mutation이 관찰될 수 있음을
  기록한다.
- `SERIALIZABLE`에서는 PostgreSQL이 serialization failure를 선택할 수 있으므로,
  성공만 요구하지 않고 예외 종류와 rollback/child cleanup을 검증한다.

이 slot은 모든 range가 동일한 읽기 기준을 공유하거나 repeatable read를 제공한다고
약속하지 않는다. 실제 읽기 기준과 serialization 결과가 driver 설정과 fixture timing에
종속되면 관찰된 결과와 재현 조건을 함께 기록하고, 안정된 public contract로 승격하지
않는다.

## 제외와 명시적 N/A

다음 항목은 이 slot의 DoD가 아니다.

- MySQL 8 driver/Testcontainers conformance: 다음 backend slot
- PostgreSQL 대 MySQL throughput/query round-trip benchmark: cross-driver slot
- 세 번의 raw JSON, median table, SVG/PNG/semantic ledger: cross-driver benchmark slot
- 실제 driver query cancellation과 sibling transaction interrupt/lifecycle: 별도
  lifecycle 검증 slot. 이 slot의 failure cleanup assertion은 해당 범위를 대체하지
  않는다.
- production Kotlin/API/ABI, loader signature, default sequential path, catalog/BOM,
  workflow definition, `docs/manual/**`: 변경하지 않음

N/A는 빈 칸이 아니라 이유·실행 명령·관찰된 제한을 기록한 상태다. 후속 slot에서
동일한 환경을 재사용할 수 있도록 test fixture 이름과 seed를 안정적으로 유지한다.

## 실패 모드와 방어

| 실패 모드 | 방어 |
| --- | --- |
| PostgreSQL container/driver가 기동하지 않음 | 기존 `Containers.Postgres`/nightly job을 사용하고 환경 failure로 분류 |
| pool lease가 `maxConcurrency`를 초과 | test-only `DataSource` decorator의 peak counter로 즉시 실패 |
| pool 부족이 hang으로 변함 | bounded timeout과 thread dump/원인 기록, 무한 재시도 금지 |
| Exposed transaction retry가 첫 connection fault를 삼킴 | 모든 retry attempt에 test-only SQL transient fault를 주입하고 최종 원인·cleanup을 검증 |
| range 결과가 중복·순서 불일치 | sequential parity와 no-duplicate assertion, `distinct()` 금지 |
| mutation timing이 flaky함 | barrier/latch로 writer 시점을 고정하고 관찰 결과를 weak consistency로 한정 |
| `SERIALIZABLE` serialization failure | 원인 예외·rollback·child cleanup을 검증하고 성공으로 변환하지 않음 |
| test가 custom executor/container를 닫지 않음 | `finally` cleanup와 post-test lease/termination assertion |

## 검토한 대안

### A. PostgreSQL test-only fixture를 첫 slot으로 채택

기존 production API를 건드리지 않고 실제 driver의 correctness·pool·isolation
증거를 먼저 고정한다. 다음 MySQL/benchmark slot이 같은 fixture 계약을 재사용할 수
있어 실패 원인이 backend와 측정 범위로 분리된다. **채택한다.**

### B. 두 driver와 benchmark를 한 PR에서 함께 처리

cross-driver 결과를 빨리 볼 수 있지만 container, pool, isolation, chart, CI 실패가
한 변경에 섞여 review와 rollback 경계가 커진다. broad multi-backend 변경을 backend별
stack으로 분리한다는 repository hazard와 맞지 않아 거부한다.

### C. H2 fixture를 확장하고 실제 driver는 nightly 후속으로 미룬다

빠르지만 H2의 connection/pool/isolation 의미가 PostgreSQL을 대체하지 못한다. Issue
#694의 핵심 미검증 범위를 해소하지 못하므로 거부한다.

## 수용 기준과 DoD

- [x] PostgreSQL Testcontainers/JDBC driver와 실행 조건이 기존 fixture 기준으로
  고정된다.
- [x] sparse/ordered/no-duplicate sequential-parallel parity가 통과한다.
- [x] `poolSize <`, `=`, `>` `maxConcurrency` 조합과 active lease 상한·failure cause가
  명시적으로 기록된다.
- [x] `READ_COMMITTED`/`SERIALIZABLE` mutation 결과와 weak-consistency 경계가
  재현 가능한 assertion으로 남는다.
- [x] 정상·validation failure·pool failure·serialization failure에서 connection,
  child transaction, custom executor cleanup을 확인한다.
- [x] production source/API/ABI, catalog/BOM, workflow, `docs/manual/**`가 변경되지
  않는다.
- [x] `git diff --check`, targeted PostgreSQL test, existing H2 regression, detekt 또는
  변경 모듈의 정적 검사가 fresh evidence로 남는다.
- [x] 후속 MySQL/benchmark/cancellation 범위와 이 slot의 N/A가 Issue #694와 Epic
  #659에 연결된다.

## SPW 검토 기록

- SPW-01 사실 고정: 현재 `develop`/PR #695, 기존 H2 fixture, nightly PostgreSQL job,
  stable manual `1.12.1`을 기준으로 작성했다.
- SPW-02 구조: 문제·현재 근거·계약·대안·실패 모드·수용 기준 순서를 유지했다.
- SPW-03 내용: production 변경과 test-only instrumentation, PostgreSQL evidence와
  후속 N/A를 분리했다.
- SPW-04 독자 검토: 구현자가 바로 test fixture 책임과 명령 범위를 식별할 수 있도록
  표와 경계를 사용했다.
- SPW-05 Korean naturalness: 사실·식별자·명령·불확실성을 보존하고, 과장된 성능
  표현을 제거했다. 최종 문서에서 terminology audit를 다시 실행한다.
