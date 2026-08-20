# Issue #701 cache-loader PostgreSQL CI 실행 계획

## 승인된 목표

Issue #692의 세 custom-ID cache-loader selector를 주간 PostgreSQL
full/nightly에서 순차 실행하고, 결과 artifact와 `nightly-status`에 연결한다.
production API/ABI, 기존 full module regression, `docs/manual/**`와 1.12.1
안정 문서는 변경하지 않는다.

## 변경 파일

| 단계 | 파일 | 변경 |
| --- | --- | --- |
| 1 | `.github/workflows/nightly-tests.yml` | `test-cache-loader-postgresql` job과 `nightly-status.needs` 항목 추가 |
| 2 | `docs/superpowers/specs/2026-08-20-issue-701-cache-loader-postgresql-ci-design.md` | 설계·근거·대안·수용 기준 고정 |
| 3 | `docs/superpowers/plans/2026-08-20-issue-701-cache-loader-postgresql-ci-plan.md` | 실행·검증·rollback 순서 고정 |
| 4 | `docs/lessons/2026-08-20-issue-701-cache-loader-postgresql-ci.md` | 재사용 가능한 CI 증거 lesson (구현 후 작성) |
| 5 | `docs/review/2026-08-20-issue-701-cache-loader-postgresql-ci-review.md` | 7-Tier 판정과 hosted PENDING handoff |

## 실행 순서

### 1. Preflight

- 현재 branch가 `ci/issue-701-cache-loader-postgres`인지 확인한다.
- `git diff --check`, workflow 주변 job, #701/#692 metadata를 다시 읽는다.
- `actionlint` 설치/경로와 Docker 가용성을 확인한다.

### 2. Workflow 구현

`nightly-tests.yml`의 `test-r2dbc-redisson` 뒤에 새 job을 추가한다.

1. `test-cache-loader-postgresql`을 주간 full/manual full 조건, `needs: build`,
   `timeout-minutes: 30`으로 선언한다.
2. checkout, Java 25, Gradle setup을 기존 nightly 패턴으로 재사용한다.
3. 다음 세 step을 이 순서로 실행한다.

   ```text
   :bluetape4k-exposed-jdbc-lettuce:test --tests '*Issue692CustomIdLoaderTest'
   :bluetape4k-exposed-jdbc-redisson:test --tests '*Issue692CustomIdLoaderTest'
   :bluetape4k-exposed-r2dbc-redisson:test --tests '*Issue692CustomIdLoaderTest'
   ```

   모든 step에 `--rerun-tasks --no-build-cache --no-configuration-cache
   --no-daemon`과 PostgreSQL/Testcontainers 환경을 적용하고, R2DBC에만
   `EXPOSED_ISSUE_692_TIMEOUT_TEST=true`를 추가한다.
4. `if: always()` test-result artifact를 `if-no-files-found: error`로
   업로드한다.
5. `nightly-status.needs`에 새 job을 추가하고 `coverage-report`에는 추가하지
   않는다. 기존 full job과 coverage artifact 중복을 피한다.

### 3. RED/GREEN 및 정적 검증

- RED는 새 job의 YAML 구조가 없거나 selector command가 누락된 상태에서
  실행하지 않는다. 이 변경은 CI 정책 변경이므로 production RED test는
  N/A이며, workflow diff와 actionlint가 계약 잠금이다.
- `actionlint .github/workflows/nightly-tests.yml`을 실행한다.
- YAML parser 또는 `ruby`/Python bounded check로 다음을 확인한다.
  - job id/name/condition/needs가 존재한다.
  - 세 selector가 정확히 한 번씩, 순서대로 존재한다.
  - PostgreSQL env와 R2DBC timeout env가 올바른 step에만 있다.
  - artifact upload가 `always` 및 `if-no-files-found: error`를 사용한다.
  - nightly-status needs에 job이 포함된다.
- `git diff --check`와 scope `git diff --name-only`를 실행한다.

### 4. 로컬 실행 증거

Docker가 가용하면 아래 명령을 **순차적으로** 실행한다.

```bash
EXPOSED_TEST_DB=POSTGRESQL ./gradlew \
  :bluetape4k-exposed-jdbc-lettuce:test \
  --tests '*Issue692CustomIdLoaderTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --no-daemon

EXPOSED_TEST_DB=POSTGRESQL ./gradlew \
  :bluetape4k-exposed-jdbc-redisson:test \
  --tests '*Issue692CustomIdLoaderTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --no-daemon

EXPOSED_TEST_DB=POSTGRESQL EXPOSED_ISSUE_692_TIMEOUT_TEST=true ./gradlew \
  :bluetape4k-exposed-r2dbc-redisson:test \
  --tests '*Issue692CustomIdLoaderTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --no-daemon
```

Docker가 없으면 H2 targeted test와 workflow/static 검증은 실행하고,
PostgreSQL hosted proof를 `PENDING/N/A`로 구분해 PR DoD에 남긴다. H2 PASS를
PostgreSQL PASS로 승격하지 않는다.

### 5. Lesson과 최종 review

- workflow diff, actionlint, selector count, local result, unavailable backend
  evidence를 한국어 lesson에 기록한다.
- `rg`로 old/new job id와 artifact 이름을 점검하고, English YAML token을
  변형하지 않는다.
- Type E 유지보수 및 Kotlin/test triggered checklist를 재검토한다. Kotlin
  source를 변경하지 않았으면 Kotlin implementation rows는 N/A 근거를
  기록하고, 기존 selector source review는 PASS로 남긴다.
- 7-Tier review에서 P0/P1=0을 확인한 뒤 PR body를 `## DoD Status`로 끝낸다.

## 실패·rollback

- actionlint 또는 YAML 구조 검증 실패: workflow만 수정하고 다시 검증한다.
- selector 실패: implementation 수정으로 우회하지 않고 test XML/log를
  보존하며 hosted 환경/driver 원인을 조사한다.
- artifact upload 실패: `if-no-files-found: error`와 job status를 유지한 채
  경로를 수정하고 재실행한다.
- PR 전 scope가 세 workflow/문서 파일을 벗어나면 변경을 되돌리지 말고
  작업을 중지해 범위 재평가한다.
- rollback은 새 job과 `nightly-status.needs` 항목을 함께 제거하는 단일
  revert commit으로 가능하다. 기존 full job은 rollback 대상이 아니다.

## 완료 조건

- [x] 새 job이 주간 full/manual full에서만 실행된다. `actionlint`와 조건
  read-back으로 schedule/manual full gate를 확인했다.
- [x] 세 selector가 PostgreSQL/Testcontainers 환경에서 순차 실행된다. 로컬에서
  JDBC Lettuce 6/6, JDBC Redisson 4/4, R2DBC Redisson PostgreSQL 6/6을 확인했다.
- [x] test-result artifact와 `nightly-status`가 실패/timeout/missing을
  성공으로 오인하지 않는다.
- [x] actionlint, 구조 검증, diff check, local targeted evidence가 fresh하다.
- [x] lesson과 7-Tier review evidence가 최신 head와 일치한다. PR `## DoD Status`와
  hosted run/artifact는 PR 생성 후 채울 PENDING 범위다.

## 현재 증거 상태

- Local PostgreSQL/Testcontainers: PASS (세 selector, failures/errors=0).
- H2: 기존 모듈 회귀와 함께 실행; R2DBC timeout case의 H2 assumption skip 1건은
  의도된 N/A 경계다.
- Hosted nightly/manual full: PENDING. GitHub run URL과 artifact가 생기기 전에는
  local evidence를 hosted acceptance로 승격하지 않는다.
