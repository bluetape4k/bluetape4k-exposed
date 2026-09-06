# #815 Full Nightly 차단 수정 점검표

## 범위와 원본

- 유형: **Type-C Bug Fix**
- 저장소: `bluetape4k/bluetape4k-exposed`
- 기준: `develop@18645ebbe8e0b8a3e0df9847dc016455fd3a80f9`
- 승인: 사용자가 Full Nightly 실패 후 보고한 두 차단 사유의 수정과 PR 생성을 승인했다.
- 기존 이슈: [#815](https://github.com/bluetape4k/bluetape4k-exposed/issues/815)은
  published test-support 소비자 계약의 상위 범위다. 이번 수정은 issue를 닫지 않고
  provider의 MySQL 행렬과 Nightly coverage 계약만 보강한다.
- 제외: Maven Central 배포 artifact 재발행, consumer 저장소 변경, merge, tag, release, 별도 issue 생성.

## 확인된 재현과 원인

- [Full Nightly 34038320082](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/34038320082)은
  47개 job 성공·4개 실패다.
- JDBC [job 101500852074](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/34038320082/job/101500852074)와
  R2DBC [job 101500852144](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/34038320082/job/101500852144)은
  `JdbcFixtureCleanupTest`/`R2dbcFixtureCleanupTest` 초기화 시
  `check(it in setOf(TestDB.H2, TestDB.POSTGRESQL))`가 `MYSQL_V8`을 거부했다.
- Coverage [job 101503257021](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/34038320082/job/101503257021)은
  실제 코드가 없는 `utils/batch` compatibility aggregator의
  `INSTRUCTION covered=0/missed=0` 보고서를 집계해 실패했다.
- 기존 CI workflow는 core/jdbc/r2dbc만 coverage task와 artifact에 포함하지만,
  Nightly workflow는 aggregator task와 전체 경로 glob을 아직 포함한다.

## 실행 순서

1. [x] **C-01 / CG-01~05 — 원인·영향·재사용 확인**
   - Action: 실패 XML, fixture 구현, CI/Nightly workflow, 기존 matrix validator와 테스트를 읽고
     MySQL cleanup 의미와 coverage 입력 경계를 확정한다.
   - Expected DoD: 두 원인이 재현되고 변경 파일이 fixture 테스트·Nightly workflow·계약 테스트로 한정된다.
   - Evidence: `JdbcFixtureCleanupTest`/`R2dbcFixtureCleanupTest`의 MySQL 허용 목록과
     `utils/batch` compatibility aggregator의 `covered=0/missed=0`을 source·hosted
     evidence와 대조했다. 기존 `docs/lessons/2026-09-04-milestone-210-ci-validation.md`의
     fail-closed coverage 경계를 재사용했으며 새 lesson은 필요하지 않다.
2. [x] **C-02 / CG-07 — RED 회귀 고정**
   - Action: 현재 기준으로 MySQL cleanup test와 Nightly coverage contract가 실패함을 확인한다.
   - Expected DoD: 실패가 compilation/setup이 아닌 허용 목록 또는 aggregator 입력 불일치로 관찰된다.
   - Evidence: 기준 head에서 JDBC/R2DBC MySQL targeted test가 `IllegalStateException: Check failed.`로
     종료했고, 새 Nightly contract test는 aggregator Kover task/path를 발견해 1개 실패했다.
3. [x] **C-03 / CG-08 — 최소 수정**
   - Action: MySQL을 실제 cleanup 테스트 행렬에 포함하고, Nightly coverage를 instrumentation 모듈만
     생성·검증·업로드하도록 CI와 일치시킨다.
   - Expected DoD: 지원하지 않는 DB를 조용히 제외하지 않으며, 빈 보고서 fail-closed 원칙을 유지한다.
   - Evidence: JDBC/R2DBC fixture 허용 목록에 `MYSQL_V8`을 추가하고 schema lifecycle만
     MySQL 권한 제약을 설명하는 명시적 JUnit assumption으로 제한했다. Nightly는
     `batch-core`/`batch-jdbc`/`batch-r2dbc`만 Kover 생성·검증·업로드한다.
4. [x] **C-04 / KT-TEST-01~05 — GREEN 및 영향 범위 검증**
   - Action: JDBC/R2DBC targeted test, Python contract/coverage tests, YAML/actionlint 가능 여부,
     affected Gradle tests와 Detekt를 순차 실행한다.
   - Expected DoD: 새 회귀와 영향 범위 검증이 모두 통과하고 Testcontainers 결과를 별도로 기록한다.
   - Evidence: JDBC/R2DBC MySQL targeted는 각각 `7 passing / 1 pending`, H2·PostgreSQL targeted는
     양 provider 모두 `8 passing`; MySQL 전체는 JDBC `390 passing / 21 pending`, R2DBC
     `303 passing / 11 pending`으로 `BUILD SUCCESSFUL`이다. `utils-batch` H2·PostgreSQL·MySQL
     전체도 모두 성공했고 sidecar validator는 `14 tests / OK`다. child Kover XML은
     `core missed=0 covered=10`, `jdbc missed=0 covered=12`, `r2dbc missed=0 covered=12`이며
     aggregator report는 생성되지 않았다. `actionlint`, Python 계약 `5 tests` 및 coverage
     `4 tests`, `py_compile`, 전체 `detekt`, terminology audit, `git diff --check`가 통과했다.
     중간 R2DBC 재실행의 task명 오타는 실행되지 않은 결과로 오인하지 않고 올바른 task로
     즉시 재실행했다.
5. [ ] **C-05 / CG-09~10 — lesson·리뷰·커밋**
   - Action: 재발 방지 lesson 필요 여부를 결정하고 final diff를 독립 리뷰한다.
   - Expected DoD: P0=0/P1=0, `git diff --check` 통과, Lore commit과 정확한 head를 기록한다.
   - Lesson decision: 기존 `docs/lessons/2026-09-04-milestone-210-ci-validation.md`가
     동일한 fail-closed Kover 규칙을 직접 예방하므로 재사용한다. 새 failure/recovery/design/
     operational guidance나 invalidated assumption은 없으며, 중간 task명 오타는 단발성
     실행 오류로 즉시 교정되어 별도 lesson을 요구하는 project rule이 아니다.
6. [ ] **C-06 / CG-11~15 — PR 생성 및 merge-ready 보고**
   - Action: 정확한 head를 push하고 `develop` 대상 PR을 생성한 뒤 live metadata, CI, review를 확인한다.
   - Expected DoD: PR의 마지막 `## DoD Status`와 검증 수치를 읽어 back하고, merge는 fresh approval 전까지 보류한다.

## Writer DoD

- [x] **SPW-01** — 대상은 Type-C 실행 점검표이며, Korean 기술 문체와 현재 run/job/source를 고정했다.
- [x] **SPW-02** — 원인, 경계, 실행 순서, RED/GREEN, 검증, PR/merge gate를 포함했다.
- [x] **SPW-03** — 사실·식별자·명령·URL·정확한 오류를 보존하고 용어를 일관되게 검토했다.
- [x] **SPW-04** — hosted 로그, source, CI/Nightly workflow, 기존 lesson을 대조했다.
- [x] **SPW-05** — 최종 변경 후 Markdown read-back과 terminology audit을 수행했다(`findings=0`).

## 현재 상태

**READY FOR REVIEW** — C-01~C-04와 로컬 검증을 완료했으며, 독립 리뷰·Lore commit·PR 생성이 남아 있다.
