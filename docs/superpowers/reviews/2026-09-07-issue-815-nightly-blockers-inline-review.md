# #815 Full Nightly 차단 수정 inline exact-diff 리뷰

## 리뷰 범위와 provenance

- 저장소: `bluetape4k/bluetape4k-exposed`
- 기준: `develop@18645ebbe8e0b8a3e0df9847dc016455fd3a80f9`
- 대상: 현재 `fix/issue-815-nightly-blockers` worktree의 fixture 테스트,
  Nightly workflow, CI 계약 테스트, write-behind 공통 시나리오와 persisted hook
  테스트, lesson과 실행 점검표 변경
- 독립 리뷰: `code-reviewer` lane이 90초 이상 usable verdict를 반환하지 않아
  중단했다. 이 문서는 독립 모델·아키텍처 provenance가 아닌 leader의 inline
  exact-diff review 기록이다.

## 판정

**P0: 0 · P1: 0 · P2: 0 — 수정 없이 진행 가능**

### Kotlin fixture 변경

- `JdbcFixtureCleanupTest`와 `R2dbcFixtureCleanupTest`는 기존 H2·PostgreSQL
  계약을 유지하면서 `MYSQL_V8` table cleanup을 실제로 실행한다.
- schema lifecycle 테스트는 MySQL Testcontainers `test` 계정의
  `CREATE SCHEMA`/database 권한 부재를 JUnit assumption으로 명시한다. DB 전체를
  제외하거나 table cleanup 결과를 숨기지 않으며, H2·PostgreSQL schema 회귀는
  계속 실행된다.
- 변경은 테스트 범위와 assertion에만 있고 production ABI/API 또는 dependency
  변경이 없다.

### Nightly coverage 변경

- `batch-core`·`batch-jdbc`·`batch-r2dbc`만 Kover XML을 생성·검증·업로드한다.
- 실제 코드가 없는 compatibility aggregator task와 경로 glob은 제거했다.
- `test -s`와 artifact `if-no-files-found: error`를 유지해 빈 입력을 허용하지
  않는다.
- 계약 테스트는 Nightly `test-utils-batch` job 구간에서 aggregator task/path가
  다시 추가되면 실패하도록 고정하고, 세 child 경로가 모두 존재하는지도 확인한다.

### 문서·운영 변경

- MySQL table/schema capability와 write-behind 완료 신호 경계를 `docs/lessons/`에
  기록해 다음 matrix 확장과 비동기 테스트에서 실제 관찰 경계를 확인하도록 했다.
- 실행 점검표에는 hosted 원인, RED/GREEN, 로컬 matrix/Kover/Detekt 결과와
  merge 보류 경계를 기록했다.

### write-behind 완료 조건 변경

- JDBC, suspended JDBC, R2DBC 대량 insert 시나리오는 초기 DB 건수와
  `entityMap.size`를 더한 기대값을 사용한다. 기존 조건처럼 일부 batch만 저장된
  중간 상태에서 깨어나지 않으며, 중복 ID가 생겨도 실제 write 수와 일치한다.
- 기대값 assertion은 Awaitility `untilAsserted`의 polling transaction 안에서
  수행한다. polling 뒤 바깥 MySQL `REPEATABLE READ` transaction의 오래된 읽기 기준을
  다시 읽지 않으므로 기다림과 검증의 관찰 경계가 일치한다.
- retry hook 테스트는 성공한 `UpdateStatement`를 완료 신호로 쓰지 않는다.
  `afterPersisted(writes)`가 전체 목록을 기록한 뒤 latch를 해제하므로 assertion과
  같은 side effect를 동기화한다.
- 변경은 test fixture와 테스트 helper에만 있으며 production write-behind lifecycle,
  ABI/API, dependency에는 영향이 없다.

## 검증 근거

- JDBC/R2DBC MySQL targeted fixture: 각각 `7 passing / 1 pending`.
- JDBC/R2DBC H2·PostgreSQL targeted fixture: 각 `8 passing`.
- MySQL 전체 JDBC `390 passing / 21 pending`, R2DBC `303 passing / 11 pending`.
- `utils-batch` H2·PostgreSQL·MySQL 전체 성공, sidecar `14 tests / OK`.
- child Kover: `core missed=0 covered=10`, `jdbc missed=0 covered=12`,
  `r2dbc missed=0 covered=12`; aggregator report 미생성.
- `actionlint`, CI contract `5 tests`, Kover validator `4 tests`, `py_compile`,
  `detekt`, terminology audit, `git diff --check` 통과.
- hosted 실패 대상 PostgreSQL 테스트는 수정 후 총 4회 연속 통과했다.
- `jdbc-caffeine` 전체는 PostgreSQL/H2에서 각각 `181 tests / 2 skipped`,
  `r2dbc-caffeine` 전체 H2는 `121 tests / 1 skipped`로 성공했다.
- 후속 exact-head MySQL transaction 읽기 기준 실패 수정 뒤 `jdbc-caffeine` 동기·suspended
  시나리오는 각각 `2 tests / 1 skipped`, 전체 MySQL은
  `181 tests / 18 skipped`로 성공했다.
- 같은 공통 fixture의 `jdbc-lettuce` MySQL write-behind는
  `48 tests / 14 skipped`, R2DBC Caffeine H2는 `27 tests / 1 skipped`로 성공했다.
- canonical `./gradlew detekt`는 성공했다. 직접 실행한 source-set Detekt는 변경 전
  detached `HEAD`와 같은 `testFixtures=50`, `jdbc-caffeine test=22` 진단으로
  실패했으므로 별도 clean gate 통과로 주장하지 않는다.

## 남은 검토 범위

새 exact head의 hosted CI와 live review/thread read-back은 push 후 CG-14에서
확인한다. 이 문서는 merge 승인이나 merge-ready 판정을 대신하지 않는다.

## Writer DoD

- [x] SPW-01: 한국어 개발자용 inline review이며 exact base, 변경 범위, hosted
  failure, 로컬 검증과 독립 리뷰 불가 사유를 고정했다.
- [x] SPW-02: 범위, provenance, severity 판정, 파일별 근거, 검증, 남은 gate를
  포함했다.
- [x] SPW-03: KO-01–KO-07 검토에서 수치, 식별자, lifecycle 경계를 보존했다.
- [x] SPW-04: 현재 diff, hosted assertion, 테스트·Detekt 결과를 대조했다.
- [x] SPW-05: 최종 Markdown read-back과 terminology audit 결과를 기록한다.
