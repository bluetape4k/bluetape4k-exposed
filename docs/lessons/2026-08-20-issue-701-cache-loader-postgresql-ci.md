# Issue #701 cache-loader PostgreSQL CI lesson

## 결과

Issue #692에서 남은 PostgreSQL driver 증거를 일반 PR 비용과 기존 full
regression 경로를 늘리지 않고 주간 nightly에 연결했다.

- `.github/workflows/nightly-tests.yml`에 `test-cache-loader-postgresql` 단일 job을
  추가했다.
- `EXPOSED_TEST_DB=POSTGRESQL` 환경에서 JDBC Lettuce, JDBC Redisson, R2DBC Redisson
  selector를 한 job 안에서 순차 실행한다.
- R2DBC selector에만 `EXPOSED_ISSUE_692_TIMEOUT_TEST=true`를 추가해 기존
  PostgreSQL timeout contract를 활성화한다.
- `if: always()` artifact upload와 `if-no-files-found: error`를 사용하고,
  `nightly-status.needs`에 job을 포함했다. 실패·timeout·artifact 누락을 성공으로
  집계하지 않도록 기존 status gate와 연결했다.
- 기존 세 모듈 full job과 `coverage-report`, production Kotlin/API/ABI,
  `docs/manual/**`, 안정 릴리스 1.12.1 문서는 변경하지 않았다.

## 검증 결과

### Local PostgreSQL selector evidence

Docker 29.2.1에서 Testcontainers를 병렬화하지 않고 계획된 순서로 실행했다.

| Selector | 결과 | XML |
| --- | --- | --- |
| JDBC Lettuce `Issue692CustomIdLoaderTest` | 6 passing (H2 3, PostgreSQL 3) | tests=6, failures=0, errors=0, skipped=0 |
| JDBC Redisson `Issue692CustomIdLoaderTest` | 4 passing (H2 2, PostgreSQL 2) | tests=4, failures=0, errors=0, skipped=0 |
| R2DBC Redisson `Issue692CustomIdLoaderTest` + timeout env | PostgreSQL 6 passing, H2 5 passing + 1 expected assumption skip | tests=12, failures=0, errors=0, skipped=1 |

R2DBC의 H2 skip은 테스트가 PostgreSQL nightly 전용 60초 timeout 계약이라는
`TestAbortedException` assumption 메시지에 따른 것이다. PostgreSQL case는 모두
실행되어 통과했으며, H2 PASS/skip을 PostgreSQL PASS로 승격하지 않았다.

### Workflow/static evidence

- `actionlint .github/workflows/nightly-tests.yml`: PASS.
- bounded workflow assertion: job 조건, 세 selector 순서, PostgreSQL env,
  R2DBC timeout env, artifact `if-no-files-found: error`, `nightly-status` 연결,
  `coverage-report` 비변경을 확인: PASS.
- `./gradlew detekt --no-configuration-cache --no-daemon`: BUILD SUCCESSFUL
  (37 actionable tasks, 34 executed, 3 up-to-date).
- `git diff --check`: PASS.
- Korean terminology audit (design/plan/lesson): PASS (3 files, findings=0).

## 문제와 해결

기존 nightly는 영향을 받은 세 모듈의 전체 test를 실행했지만 #692 selector의
PostgreSQL 실행 여부와 결과 artifact를 별도 식별할 수 없었다. 기존 full job에
selector를 섞으면 artifact와 재실행 경계가 모호해지고, 세 job으로 분리하면
Testcontainers 자원 경쟁이 생긴다.

따라서 bounded 단일 job을 만들고 selector step을 순차화했다. step은 재시도하지
않아 최초 실패를 보존한다. 결과 업로드는 항상 시도하되 XML이 하나도 없으면
실패하도록 해 test process 조기 종료를 놓치지 않는다. `nightly-status`는 새 job의
실패/timeout을 기존 nightly 최종 상태에 포함한다.

## 남은 경계와 후속 작업

- 로컬 PostgreSQL selector는 PASS했지만 GitHub-hosted 주간/manual full run과
  artifact URL은 아직 생성되지 않았다. PR 이후 첫 hosted run에서 실제 check와
  artifact를 채워야 하며, 그 전까지 #701의 hosted acceptance는 PENDING이다.
- GitHub runner의 image pull, queue, Docker socket 상태는 로컬 결과로 대체하지
  않는다. hosted에서 unavailable이면 `N/A/PENDING`으로 남기고 H2 결과를
  승격하지 않는다.
- 저수준 connection-close provenance/instrumentation은 기존 Issue #697이
  소유한다. `../../infra/lettuce` README ownership은 Issue #702에서 다룬다.

## 재발 방지

1. 비-H2 driver 증거는 기존 H2/full job과 분리된 artifact 이름과 status dependency를
   사용한다.
2. Testcontainers를 사용하는 selector는 한 job 안에서 순차 실행해 shared runner의
   database/Redis 자원 경쟁을 줄인다.
3. timeout 또는 fault selector는 환경 변수를 step 범위로 제한하고, 일반 selector에
   전파하지 않는다.
4. artifact `if-no-files-found: error`와 final status `needs`를 함께 검사해
   결과 파일 누락을 false pass로 만들지 않는다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — Issue #701, Issue #692 잔여 P2, 세 selector와 nightly 경계를
  고정했다.
- [x] SPW-02 — job 조건, 순차 실행, 환경 변수, artifact/status 계약과 실제 XML
  결과를 대조했다.
- [x] SPW-03 — 한국어 technical register와 `selector`, `Testcontainers`,
  `timeout`, `artifact`, `PENDING`, `N/A` token을 일관되게 유지했다.
- [x] SPW-04 — nightly workflow, 기존 selector source, #692 review/plan과
  local PostgreSQL evidence를 교차 검증했다.
- [x] SPW-05 — Markdown read-back, terminology audit, link/token/scope 검사를
  완료했다.
