# #815 Full Nightly 차단 수정 inline exact-diff 리뷰

## 리뷰 범위와 provenance

- 저장소: `bluetape4k/bluetape4k-exposed`
- 기준: `develop@18645ebbe8e0b8a3e0df9847dc016455fd3a80f9`
- 대상: 현재 `fix/issue-815-nightly-blockers` worktree의 fixture 테스트,
  Nightly workflow, CI 계약 테스트, lesson과 실행 점검표 변경
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

- MySQL table/schema capability 차이를 `docs/lessons/`에 기록해 다음 matrix
  확장 시 권한을 capability별로 확인하도록 했다.
- 실행 점검표에는 hosted 원인, RED/GREEN, 로컬 matrix/Kover/Detekt 결과와
  merge 보류 경계를 기록했다.

## 검증 근거

- JDBC/R2DBC MySQL targeted fixture: 각각 `7 passing / 1 pending`.
- JDBC/R2DBC H2·PostgreSQL targeted fixture: 각 `8 passing`.
- MySQL 전체 JDBC `390 passing / 21 pending`, R2DBC `303 passing / 11 pending`.
- `utils-batch` H2·PostgreSQL·MySQL 전체 성공, sidecar `14 tests / OK`.
- child Kover: `core missed=0 covered=10`, `jdbc missed=0 covered=12`,
  `r2dbc missed=0 covered=12`; aggregator report 미생성.
- `actionlint`, CI contract `5 tests`, Kover validator `4 tests`, `py_compile`,
  `detekt`, terminology audit, `git diff --check` 통과.

## 남은 검토 범위

PR exact head의 hosted CI와 live review/thread read-back은 PR 생성 후 CG-14에서
확인한다. 이 문서는 merge 승인이나 merge-ready 판정을 대신하지 않는다.
