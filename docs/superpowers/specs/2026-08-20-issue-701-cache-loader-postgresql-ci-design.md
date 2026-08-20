# Issue #701 cache-loader PostgreSQL CI 설계

## 문제와 범위

Issue #692는 `exposed-jdbc-lettuce`, `exposed-jdbc-redisson`,
`exposed-r2dbc-redisson`의 custom ID·page mutation·취소 경계를 구현하고,
로컬 H2와 제한된 PostgreSQL selector를 통과시켰습니다. 그러나 당시 review의
P2로 PostgreSQL hosted/nightly selector가 별도 job·artifact·최종 status에
연결되어 있지 않았습니다.

현재 `nightly-tests.yml`에는 세 모듈의 full test job이 이미 있지만, #692
selector만 PostgreSQL 환경에서 실행했다는 증거와 그 결과 artifact를 별도로
구분하지 않습니다. 이 설계는 그 증거를 추가하되 기존 full module job과
coverage 경로를 보존합니다.

### 기준 데이터 원본

| 근거 | 확인 내용 |
| --- | --- |
| `.github/workflows/nightly-tests.yml`의 `test-jdbc-lettuce`, `test-jdbc-redisson`, `test-r2dbc-redisson` | 주간 full/nightly에서 영향을 받은 모듈의 전체 test job을 이미 실행함 |
| 같은 파일의 `nightly-status` | `needs.*.result`를 `success|skipped`만 허용하고 실패·timeout을 실패로 집계함 |
| `exposed/jdbc-lettuce/src/test/.../Issue692CustomIdLoaderTest.kt` | custom ID page와 mutation/cancellation selector가 존재함 |
| `exposed/jdbc-redisson/src/test/.../Issue692CustomIdLoaderTest.kt` | synchronous loader selector가 존재하며 `EXPOSED_TEST_DB=POSTGRESQL`을 사용 가능함 |
| `exposed/r2dbc-redisson/src/test/.../Issue692CustomIdLoaderTest.kt` | R2DBC fault/retry/cancellation/timeout selector가 존재함 |
| `exposed/{jdbc-tests,r2dbc-tests}/.../TestDB.kt` | `EXPOSED_TEST_DB=POSTGRESQL`이 H2+실제 PostgreSQL을 선택함 |
| `docs/review/2026-08-20-issue-692-cache-loader-driver-parity-review.md` | PostgreSQL hosted/nightly 증거와 artifact/status 연결이 잔여 P2임 |

## 결정

`.github/workflows/nightly-tests.yml`에 `test-cache-loader-postgresql` 단일
job을 추가합니다.

- 실행 조건은 주간 full schedule(`3 19 * * 0`) 또는
  `workflow_dispatch`의 `scope=full`입니다. 평일 smoke와 일반 PR CI에는
  추가하지 않습니다.
- `needs: build`, `ubuntu-latest`, `timeout-minutes: 30`을 사용합니다.
- 하나의 job 안에서 세 Gradle selector를 별도 step으로 순차 실행합니다.
  별도 job 병렬 실행으로 Testcontainers 자원 경쟁을 만들지 않습니다.
- 각 selector는 `--tests '*Issue692CustomIdLoaderTest'`,
  `--rerun-tasks`, `--no-build-cache`, `--no-configuration-cache`,
  `--no-daemon`을 사용합니다.
- 공통 환경은 `EXPOSED_TEST_DB=POSTGRESQL`,
  `TESTCONTAINERS_RYUK_DISABLED=true`, `DOCKER_HOST=unix:///var/run/docker.sock`
  입니다. R2DBC step만 `EXPOSED_ISSUE_692_TIMEOUT_TEST=true`를 추가해
  기존 60초 PostgreSQL timeout selector를 활성화합니다.
- 각 step은 실패를 그대로 반환합니다. 재시도로 실패를 감추지 않고,
  `if: always()`와 `if-no-files-found: error`인 test-result artifact upload로
  실패·timeout·artifact 누락을 구분합니다.
- `nightly-status.needs`에 새 job을 추가합니다. 기존 세 모듈 full test와
  `coverage-report`는 변경하지 않습니다.

## 대안과 트레이드오프

### 기존 세 full job에 환경 변수와 selector step을 직접 추가

기존 job의 책임과 artifact 이름이 섞이고, 세 모듈의 selector가 서로 다른
재시작·자원 조건을 공유하게 됩니다. 별도 bounded job이 증거를 읽기 쉽고
실패를 독립적으로 집계하므로 선택하지 않습니다.

### 매일 PR/smoke에 PostgreSQL selector 추가

Testcontainers 비용과 Redis/PostgreSQL 동시 자원 사용을 일반 개발 경로에
강제합니다. #701은 hosted/nightly evidence가 목적이므로 주간 full로
한정합니다.

### 세 모듈을 별도 job으로 병렬 실행

현재 workflow가 모듈별 full job을 이미 병렬 실행합니다. 새 selector까지
병렬화하면 같은 runner fleet에서 Redis/PostgreSQL container 경쟁이 늘어납니다.
단일 job의 순차 step으로 bounded 실행을 보장합니다.

## 계약과 비목표

- production Kotlin/API/ABI와 test fixture의 의미는 변경하지 않습니다.
- `docs/manual/**`와 안정 릴리스 1.12.1 문서는 변경하지 않습니다.
- MySQL 8은 #698, query timeout production 계약은 #699에서 소유합니다.
- 이 job의 PASS는 PostgreSQL full/nightly selector와 artifact/status 증거를
  의미하며, 모든 지원 driver의 무결성을 주장하지 않습니다.
- 기존 모듈 full test job은 영향을 받은 모듈 regression의 넓은 범위를
  계속 담당하고, 새 job은 #692 selector의 PostgreSQL 증거를 담당합니다.

## 수용 기준과 DoD 매핑

| #701 기준 | 구현/검증 증거 |
| --- | --- |
| 세 selector의 PostgreSQL 실행 | 새 job의 세 순차 Gradle step과 `EXPOSED_TEST_DB=POSTGRESQL` |
| 실패·timeout·artifact 누락의 false pass 방지 | step 실패 전파, `if: always()`, `if-no-files-found: error`, `nightly-status.needs` |
| 실행 순서·환경·artifact 문서화 | 이 설계와 plan, workflow step/env/artifact 이름 |
| H2와 PostgreSQL 증거 분리 | 기존 H2/full job 보존, 새 artifact 이름에 `postgresql` 명시 |
| production/manual 경계 보존 | YAML·설계·plan·lesson만 변경, diff scope 검사 |

## 남은 불확실성

GitHub-hosted runner의 Docker/Redis/PostgreSQL image pull 및 workflow queue
상태는 로컬에서 재현할 수 없습니다. 로컬에서는 actionlint, YAML 구조, 세
selector의 H2/가능한 PostgreSQL 실행을 검증하고, hosted/nightly 결과는 PR
DoD에서 정확한 run URL과 artifact로 보완합니다.
