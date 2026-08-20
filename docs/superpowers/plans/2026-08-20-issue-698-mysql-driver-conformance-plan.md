# Issue #698 MySQL 8 JDBC driver·pool·isolation 구현 계획

> **For agentic workers:** 이 계획을 별도 실행 세션에서 수행할 때는 `$executing-plans`를
> 사용하고, 각 task를 완료한 뒤 명령 출력과 변경 파일을 다시 확인한다.

**Goal:** MySQL 8 Testcontainers와 Connector/J를 사용해
`parallelJdbcKeyEnumeration`의 sparse ordering, range validation, Hikari lease 경계,
명시적 isolation, retry/cause, transaction rollback을 test-only 증거로 고정한다.

**Architecture:** production helper와 public API는 건드리지 않는다. MySQL 전용 JUnit
fixture가 `Containers.MySQL8`의 endpoint/credential로 Hikari를 만들고,
`TrackingDataSource`가 lease lifecycle을 계측한다. `DatabaseConfig`는 fault case의
retry 횟수만 명시한다. EN/KO README에는 실행 증거와 경계를 같은 내용으로 기록한다.

**Tech Stack:** Kotlin/JUnit 5, JetBrains Exposed 1.4.0 JDBC, HikariCP, MySQL 8
Testcontainers, Java Virtual Threads, Gradle, Detekt.

## 선행 조건과 변경 경계

- worktree: `.worktrees/issue-698-mysql`, branch `test/issue-698-mysql`
- base: `develop` `ea19b9e0c6d5135d2447c9a95435c85c1127e3b3`
- 선행 slot: #696 PostgreSQL merge 완료
- live metadata: #698 milestone `2.0.0`, assignee `debop`, labels `test`, `performance`,
  `stacked-pr`
- 허용 파일: 새 `exposed/jdbc/src/test/kotlin/.../MySQLJdbcParallelKeyEnumerationTest.kt`,
  `exposed/jdbc/README.md`, `exposed/jdbc/README.ko.md`, 설계 spec·이 계획·review·lesson 문서
- 금지 범위: `exposed/jdbc/src/main/**`, public/API/ABI, BOM/catalog, dependency,
  workflow, Kover, `docs/manual/**`, container launcher와 image tag
- Testcontainers startup/configuration: `useTestcontainers=false` 또는 MySQL selector
  미활성만 assumption skip; 실제 container/driver/schema 오류는 FAIL/PENDING으로
  기록하고 GREEN으로 대체하지 않는다.

## Task 0 — 현재 head와 RED 기준선 고정

1. `git status --short`, `git rev-parse HEAD`, `git diff --check`로 worktree와
   설계·review commit을 기록한다. committed/staged/unstaged/untracked 경로의 합집합을
   허용 목록과 대조한다.
2. live #698/#694/#659와 PR train 선행 #696을 `gh issue view`로 다시 읽고, 계획의
   부모·milestone·labels가 현재 값과 다르면 구현 전에 문서만 정정한다.
3. 다음 H2 기준선을 `--rerun-tasks --no-build-cache --no-configuration-cache`
   로 실행해 8/8 PASS를 fresh XML로 남긴다.

```bash
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-jdbc:test \
  --tests 'io.bluetape4k.exposed.jdbc.JdbcParallelKeyEnumerationTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

## Task 1 — Step 3-R plan review gate (구현 금지)

1. 설계와 이 계획을 여섯 독립 관점(performance, stability, security, operator/Ops,
   developer/API, user/caller)과 main integration 관점으로 대조한다. 어느 관점에서든
   P0/P1을 하나라도 발견하면 Task 2로 진행하지 않고 문서와 review artifact를 수정한다.
   결과를 `docs/review/2026-08-20-issue-698-mysql-plan-review.md`에 먼저 기록한다.
2. plan review artifact를 포함한 정확한 입력으로 audit한다.

```bash
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/superpowers/specs/2026-08-20-issue-698-mysql-driver-conformance-design.md \
  docs/review/2026-08-20-issue-698-mysql-design-review.md \
  docs/superpowers/plans/2026-08-20-issue-698-mysql-driver-conformance-plan.md \
  docs/review/2026-08-20-issue-698-mysql-plan-review.md
git diff --check
```

3. plan review artifact의 P0=0/P1=0, terminology audit PASS, diff check PASS가 남기
   전에는 RED skeleton도 추가하지 않는다.
4. gate 통과 후 설계 spec·설계 review·계획·plan review만 Lore trailer가 있는 문서
   commit으로 고정한다. commit 전 staged diff check, commit 후 clean worktree와
   `git diff --check develop...HEAD` read-back을 남긴 뒤에만 Task 2 RED로 진입한다.
5. plan과 integrated plan review 각각에 독립적인 SPW-01~05 PASS checklist가 존재하는지
   확인한다. 하나라도 unchecked이면 Task 2 RED로 진입하지 않는다.

## Task 2 — RED: MySQL test contract skeleton

**Files:** `exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/MySQLJdbcParallelKeyEnumerationTest.kt`

1. `AbstractExposedTest`를 상속하는 MySQL 전용 test class와 고유 table factory를
   만든다. `EnumerationTable : LongIdTable`에는 `payload = varchar("payload", 64)`와
   `uniqueIndex()`를 둔다.
2. `assumeMySQL8()`는 `TestDBConfig.useTestcontainers`와
   `TestDB.MYSQL_V8 in TestDB.enabledDialects()`만 assumption으로 확인한다.
   `Containers.MySQL8`에 접근해 실제 startup/driver 예외가 test failure가 되도록
   `runCatching`으로 감싸거나 일반 exception을 삼키지 않는다.
3. 설계의 테스트 이름과 assertion을 먼저 작성한다. fixture 구현 전에는 모든 테스트를
   하나의 공통 예외로 실패시키지 않고, 선택한 계약 하나씩 고유한 `fail("RED: <contract>")`
   assertion을 둔 뒤 해당 test selector만 실행한다. 각 RED 원인은 sparse/range/pool/
   isolation/retry/rollback 이름과 일치해야 하며, 다음 계약을 GREEN으로 만들 때 즉시
   그 RED assertion을 제거한다.

필수 test 목록:

- sparse IDs의 순차 `ORDER BY id`와 parallel range 결과가 동일하며 중복이 없음
- overlap/reverse는 connection 획득 전에 `IllegalArgumentException`,
  `ranges = emptyList()`는 빈 결과와 lease 0
- pool `1/2/4`, `maxConcurrency=2`, 4 ranges의 exact acquisition barrier와 peak
- 명시적 `READ_COMMITTED`에서 두 번째 SELECT가 committed row 9를 관찰
- 명시적 `REPEATABLE_READ`에서 두 번째 SELECT가 row 9를 관찰하지 않음
- 단일 range lease fault의 `defaultMaxAttempts=2`, retry request count 2, cause chain
- `readOnly=false` marker INSERT 후 unique 충돌의 SQLState/cause와 marker rollback
- schema drop 실패에도 datasource close와 primary/suppressed 보존
- delegate connection close 실패 시 active 미반환과 원인 보존
- caller executor가 helper 호출 전후 살아 있고 helper가 닫지 않음

## Task 3 — GREEN: MySQL fixture와 deterministic oracle 구현

**File:** 동일한 `MySQLJdbcParallelKeyEnumerationTest.kt`

1. `MySQLFixture.create(...)` factory가 첫 줄에서 selector/configuration을 확인하고
   `Containers.MySQL8`에 접근한다. 그 endpoint로 조합된 `TestDB.MYSQL_V8.connection()`을
   사용해 기존 UTC·cursor-fetch·batch Connector/J 옵션을 재사용하고, URL prefix가
   container endpoint인지 assert한다. username/password는 container에서 읽고 MySQL
   driver를 지정한다. `maximumPoolSize`, `minimumIdle=0`, 5초 bounded
   `connectionTimeout`, 고유 pool name을 지정한다.
2. 순서를 `HikariDataSource → TrackingDataSource(fault off) → Database.connect(...,
   databaseConfig = DatabaseConfig { ... }) → schema setup/seed → active==0 assert 및
   peak/request reset → fault on`으로 고정한다. setup 전에 fault를 켜지 않는다.
3. `Database.connect(trackingDataSource, databaseConfig = DatabaseConfig { ... })`를
   사용한다. 일반 fixture는 Exposed 기본값을 유지하고, lease-fault fixture는
   `defaultMaxAttempts=2`, `defaultMinRetryDelay=0`, `defaultMaxRetryDelay=0`을,
   statement-fault fixture는 `defaultMaxAttempts=1`을 명시한다.
4. setup transaction에서 schema 생성, `row-0`부터 8행 삽입, ID 2/6 삭제를 수행한다.
   setup 예외에서는 부분 Hikari를 즉시 close하고 primary 예외를 다시 던진다.
5. `TrackingDataSource`는 `getConnection()` request count, active, peak를 기록한다.
   반환 proxy는 delegate `close()`가 성공했을 때만 active를 감소시키고, close 실패를
   숨기지 않는다. setup 직후 `active == 0`을 먼저 확인한 뒤 `peak`와 request count만
   reset하며, active를 0으로 덮어 setup 누수를 숨기지 않는다. idempotent close를 제공한다.
6. fixture `close()`는 `failEveryLease=false`를 복원한 뒤 schema drop과
   tracker/datasource close를 각각 nested `finally`에서 실행한다. cleanup 실패는
   primary failure에 suppressed로 연결한다. caller-owned executor는 fixture가 닫지
   않으며 각 test의 `finally`에서 bounded close와 `isShutdown`을 확인한다.
   `Containers.MySQL8`은 fixture가 stop하지 않는다.
7. pool test는 명시적으로 생성한 test-owned `ExecutorService`에서 helper 실행을 별도
   future로 감싸고, 두 reader가 connection을 획득하면
   latch를 count down한다. `expected = min(poolSize, 2)`에 도달할 때까지 bounded
   대기하며 timeout·예외·취소 어느 경로에서도 `finally`가 acquisition/release latch를
   열고 helper future를 cancel/await한다. 모든 reader가 종료된 뒤에만 exact peak와
   active 0을 판정한다.
   pool 2/4는 exact
   peak 2, pool 1은 exact peak 1을 확인한다. pool 1은 under-provisioned pressure
   case이며 운영 권고가 아니다.
8. isolation test는 내부 `rangeReader` 주입으로 첫 SELECT 뒤 writer transaction을
   commit시키고 두 번째 SELECT를 수행한다. 이 두-SELECT 경로를 public overload의
   공유 기준 데이터 계약으로 문서화하지 않는다.
9. statement fault는 `readOnly=false`로 marker payload를 먼저 INSERT한 다음
   `row-0` duplicate INSERT를 실행한다. 별도 transaction에서 marker가 없고 seed
   count가 보존되는지, SQLState `23000` 계열과 integrity cause가 유지되는지 확인한다.
10. lease fault는 한 range만 실행해 acquisition request count가 정확히 2인지 확인한다.
   모든 fault가 acquisition 전에 발생하므로 acquired sibling cleanup 증거라고
   주장하지 않고 #697 후속 범위로 남긴다.
11. drop 함수와 connection close를 주입할 수 있는 test-only seam으로 schema drop 실패와
    delegate close 실패를 각각 재현한다. 각 경우 datasource가 최종 close되고 primary
    failure에 cleanup failure가 suppressed로 남는지 확인한다.

## Task 4 — README EN/KO evidence parity

**Files:** `exposed/jdbc/README.md`, `exposed/jdbc/README.ko.md`

1. 동일한 heading과 표로 MySQL 8 test class, H2 baseline, sparse/range/pool/isolation/
   rollback/retry evidence를 추가한다.
2. exact MySQL command와 `TESTCONTAINERS_RYUK_DISABLED=true`를 기록하되 credential과
   full JDBC URL은 기록하지 않는다. 기존 nightly `.github/workflows/nightly-tests.yml`
   MySQL job이 이 class를 포함한다는 근거만 링크/경로로 남기고 workflow는 수정하지 않는다.
3. pool 1 pressure case, 명시적 `REPEATABLE_READ`, 내부 두-SELECT callback 한계,
   `SERIALIZABLE`/network/cancellation N/A, #697/#690 후속 경계를 EN/KO에서 동일하게
   설명한다. MySQL 전체 호환성·운영 pool 권고·cross-driver 우열을 주장하지 않는다.
4. 현재 manual이 `1.12.1` source of truth라는 경계를 유지하고 `docs/manual/**`는
   수정하지 않는다.

## Task 5 — RED→GREEN 검증과 회귀

1. Task 2 skeleton에서 MySQL targeted RED를 기록한다. Task 3 구현 후 같은 selector로
   GREEN을 재실행한다.

```bash
EXPOSED_TEST_DB=MYSQL_V8 TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew :bluetape4k-exposed-jdbc:test \
  --tests 'io.bluetape4k.exposed.jdbc.MySQLJdbcParallelKeyEnumerationTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

2. H2 targeted regression을 명시적으로 다시 실행한다.

```bash
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-jdbc:test \
  --tests 'io.bluetape4k.exposed.jdbc.JdbcParallelKeyEnumerationTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

3. MySQL selector로 JDBC implementation과 공용 test fixture를 순차 검증한다.

```bash
EXPOSED_TEST_DB=MYSQL_V8 TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew :bluetape4k-exposed-jdbc:test :bluetape4k-exposed-jdbc-tests:test \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

4. 각 결과에서 test count, failure/error, skipped, Docker/MySQL/Connector-J version,
   XML mtime/path를 기록한다. container unavailable이면 MySQL acceptance를 PASS로
   바꾸지 않고 `N/A/PENDING`으로 남긴다.

## Task 6 — 정적 검증·문서 품질·scope guard

1. `./gradlew :bluetape4k-exposed-jdbc:detekt`와 필요한 module detekt를 실행한다.
2. README가 추가된 뒤 명시적 파일 목록으로 설계·계획·review·README EN/KO를 audit한다.
   lesson은 Task 8에서 생성한 뒤 같은 명령에 추가해 다시 audit한다.

```bash
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/superpowers/specs/2026-08-20-issue-698-mysql-driver-conformance-design.md \
  docs/review/2026-08-20-issue-698-mysql-design-review.md \
  docs/superpowers/plans/2026-08-20-issue-698-mysql-driver-conformance-plan.md \
  exposed/jdbc/README.md exposed/jdbc/README.ko.md
```

3. `git diff --check`와 staged/unstaged/untracked/base diff의 합집합을 검사한다.

```bash
{ git diff --name-only develop...HEAD; git diff --name-only; \
  git diff --cached --name-only; git ls-files --others --exclude-standard; } \
  | sort -u
```

출력은 위 허용 목록과 대조하고 production/API/ABI/catalog/workflow/manual 경로가
있으면 구현을 중단한다. 단일 base diff 명령만으로 untracked 파일을 통과시키지 않는다.

## Task 7 — performance/stability risk pass

1. `pool 1/2/4`의 exact peak, barrier timeout, active 반환과 executor 수명을 XML 및
   review evidence로 교차 확인한다.
2. setup 실패, schema drop 실패, connection close 실패, duplicate rollback, retry
   exhaustion의 targeted test와 review evidence에서 primary/suppressed exception이
   보존되는지 확인한다.
3. MySQL targeted가 첫 시도 실패 후 nightly retry에서 통과하면 clean PASS가 아니라
   `WATCH`로 기록하고 최초 실패 로그와 원인을 PR DoD에 남긴다.

## Task 8 — verifier·lesson·PR gate

1. Type A verifier가 source/plan/test/XML/diff/README parity를 다시 읽고 P0=0/P1=0을
   확인한 뒤 `docs/lessons/2026-08-20-issue-698-mysql-driver-conformance.md`를
   한국어로 작성한다. lesson에는 RED/GREEN count, skipped/N/A, detekt, scope guard,
   후속 #697/#690을 기록한다.
2. lesson 생성 후 설계·계획·review·README EN/KO·lesson 전체를 다시 audit하고
   `git diff --check`를 재실행한다. 새 untracked 파일을 포함하도록 먼저 `git add`하고,
   staged 전체에 `git diff --cached --check`를 실행한다.
3. 최종 scope union을 재검사하고 Lore trailer가 포함된 구현 commit을 만든다. commit
   뒤 `git status --short`, `git rev-parse HEAD`를 확인하고
   `git diff --check develop...HEAD`와 `git push --set-upstream origin test/issue-698-mysql`
   로 원격 head를 read-back한다.
4. push된 head에서 Korean PR 본문(`## DoD Status`를 마지막 H2)으로 PR을 생성하고,
   생성 직후 head/base/linked issue/milestone/labels와 check 목록을 다시 읽는다.
5. PR exact head가 확인된 뒤에만 nightly full dispatch를 검토한다. workflow dispatch는
   별도의 외부 실행 권한이므로 해당 권한이 없으면 dispatch하지 않고 `PENDING — nightly
   dispatch authority`로 남긴다. 권한이 있으면 다음으로 feature branch full run을
   생성하고 run ID를 즉시 기록한다.

```bash
PR_NUMBER=$(gh pr list --head test/issue-698-mysql --json number --jq '.[0].number')
HEAD_SHA=$(git rev-parse HEAD)
BEFORE_RUN_IDS=$(mktemp -t issue698-nightly-before)
trap 'rm -f "$BEFORE_RUN_IDS"' EXIT
gh run list --workflow nightly-tests.yml --branch test/issue-698-mysql \
  --limit 20 --json databaseId --jq '.[].databaseId' > "$BEFORE_RUN_IDS"
gh pr view "$PR_NUMBER" --json headRefOid,baseRefName,mergeable,statusCheckRollup,milestone,labels,closingIssuesReferences
DISPATCHED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
gh workflow run nightly-tests.yml --ref test/issue-698-mysql -f scope=full
NIGHTLY_RUN_ID=''
for poll in $(seq 1 30); do
  CANDIDATES=$(gh run list --workflow nightly-tests.yml --branch test/issue-698-mysql \
    --limit 20 --json databaseId,headSha,createdAt \
    --jq ".[] | select(.headSha == \"$HEAD_SHA\" and .createdAt >= \"$DISPATCHED_AT\") | .databaseId")
  while IFS= read -r candidate; do
    [ -z "$candidate" ] && continue
    if ! grep -qx "$candidate" "$BEFORE_RUN_IDS"; then
      NIGHTLY_RUN_ID="$candidate"
      break
    fi
  done <<< "$CANDIDATES"
  [ -n "$NIGHTLY_RUN_ID" ] && break
  sleep 10
done
test -n "$NIGHTLY_RUN_ID"
set +e
gh run watch "$NIGHTLY_RUN_ID" --exit-status
NIGHTLY_WATCH_STATUS=$?
set -e
gh run view "$NIGHTLY_RUN_ID" --json headSha,status,conclusion,jobs
mkdir -p build/evidence
gh run view "$NIGHTLY_RUN_ID" --log > build/evidence/issue-698-nightly.log
if rg -n 'Attempt [1-4] failed' build/evidence/issue-698-nightly.log; then
  echo 'WATCH: nightly passed only after an internal Gradle retry';
else
  echo 'No internal retry failure marker observed';
fi
```

`NIGHTLY_WATCH_STATUS`가 0이 아니거나, `headSha`가 PR exact head와 다르거나, 최종 conclusion이 실패/취소이거나, retry 이전
최초 attempt가 실패하면 clean conformance PASS가 아니라 `WATCH/PENDING`으로 기록하고
최초 로그를 보존한다. production diff가 없으면 ABI 검증은 `N/A — production/API/ABI
불변 guard PASS`로 기록하며, 이를 ABI 실행 PASS로 과장하지 않는다.

6. exact-head CI와 nightly MySQL evidence 전에는 merge-ready로 보고하지 않는다. merge는
   별도 fresh head/check/review/metadata 확인과 사용자 승인이 필요한 후속 gate다.

## Rollback·완료 조건

- fixture/assertion 자체의 결함이면 해당 test/docs evidence를 되돌릴 수 있지만,
  실제 MySQL/Connector-J driver defect는 증거와 재현 로그를 유지한 `PENDING/BLOCK`으로
  보고하고 연결 issue를 만든다. conformance 실패를 test 삭제로 숨기지 않는다.
- Testcontainers startup/driver/schema 오류는 assumption으로 완화하지 않고
  `PENDING`으로 보고한다.
- 완료는 Task 0–8의 fresh evidence, P0=0/P1=0, PR exact-head CI, live metadata parity와
  후속 issue 링크가 모두 남은 경우에만 `DONE`이다. 그 전에는 `PENDING`이다.
