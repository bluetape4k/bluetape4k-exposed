# Issue #730 Ktor backend-selective artifact 실행 계획

## 문서 상태

- 이슈: [#730](https://github.com/bluetape4k/bluetape4k-exposed/issues/730)
- 저장소: `bluetape4k/bluetape4k-exposed`
- 기준 ref: `origin/develop@c5e9d499d9c1baeb6f92a531345d184c16febc27`
- workflow: Type A `bluetape-full-feature`
- 선행 설계: `docs/superpowers/specs/2026-08-27-issue-730-ktor-backend-boundaries-design.md`
- 선행 통합 리뷰: `docs/superpowers/reviews/2026-08-27-issue-730-spec-review.md`
- 작업 worktree: `.worktrees/refactor/issue-730-ktor-boundaries`
- 실행 경계: 구현·검증까지만 수행하고 PR·merge·release는 수행하지 않는다.

승인된 설계를 작은 RED→GREEN 단계로 실행한다. 기존 aggregator의 binary
surface와 동작을 먼저 고정한 뒤 source ownership을 분리하고, 각 선택 모듈의
dependency/ABI 경계를 독립적으로 검증한다. 설계에 없는 public API, dependency,
DB schema 변경은 추가하지 않는다.

## 완료·중단 조건

완료는 다음 증거를 모두 확보한 경우에만 선언한다.

1. settings/BOM/inventory에 core·JDBC·R2DBC·cache child와 legacy aggregator가
   모두 등록되고, child POM/metadata/classpath에 금지된 backend가 없다.
2. core/child/aggregator 단위·consumer·ABI·metric·redaction 검사가 통과한다.
3. 기존 aggregator 63개 baseline과 legacy response/phase budget/constructor/
   `$default` descriptor가 유지된다.
4. H2를 먼저 실행하고 PostgreSQL, MySQL_V8를 순차 실행해 PASS/환경 PENDING을
   분리한다. skipped/PENDING은 green으로 세지 않는다.
5. EN/KO README/manual, manifest, example, CI/Kover가 실제 경로와 좌표를
   가리키고 `git diff --check`와 detekt가 통과한다.

다음은 즉시 해당 단계에서 중단할 조건이다.

- legacy ABI 삭제/descriptor drift, child reverse dependency, raw SQL/cause/secret
  노출, non-cooperative probe를 blocking으로 허용하는 구현
- dependency boundary·module inventory·manual 링크가 실제 산출물과 불일치
- PostgreSQL/MySQL assertion 실패를 환경 PENDING으로 위장하거나 skip을 PASS로
  집계하는 경우
- 명세 재리뷰에서 P0/P1이 다시 발견되는 경우

## 단계별 실행

### T0. 기준선과 workflow receipt 고정

- 두 worktree와 canonical checkout의 branch/HEAD를 확인하고 canonical의
  기존 `.issue721-workflow/`, `.issue721-worktree/` untracked 파일은 건드리지
  않는다.
- source, Gradle, workflow, BOM, manual 경로별 `mutation-check`를 실행하고
  run `20260827T071052Z-812cf75f`의 expected head를 유지한다.
- 다음 baseline을 저장한다.

```bash
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-ktor:test \
  --no-configuration-cache --no-daemon --console=plain
./gradlew :bluetape4k-exposed-ktor:checkProductionAbi \
  --no-configuration-cache --no-daemon --console=plain
git diff --check
```

### T1. RED 계약·소비자 fixture 선행

source 이동 전에 `ktor/core`, `ktor/jdbc`, `ktor/r2dbc`, `ktor/cache`의
test/fixture에 다음 실패 검사를 추가한다.

- marker 없는 readiness probe, 빈 목록·17개 초과·중복/동적 component,
  잘못된 path와 finite timeout을 거부한다.
- 순차 probe, 전체 monotonic deadline, unexecuted `TIMEOUT`, wrapper timeout과
  직접 `TimeoutCancellationException`, active/inactive cancellation, `Error`와
  generic exception redaction을 결정적 clock/barrier로 고정한다.
- JDBC는 `runInterruptible`/caller-provided dispatcher와 statement timeout을,
  R2DBC는 cancellable suspend를 검증하고 보상 worker가 생성되지 않는지 확인한다.
- core error catalog/status/message, fixed backend metric tags, exactly-once
  readiness sample과 legacy/core meter family 분리를 고정한다.
- child API의 정확한 FQCN, companion `$Companion`, suspend `Continuation`/
  `Object`/`$default` bridge, legacy constructor/cause compatibility를
  `javap -p -s`, `.api`, clean Java/Kotlin consumer fixture로 고정한다.
- 각 child의 compile/runtime dependency, POM와 Gradle metadata를 allowlist와
  deny-by-default namespace 규칙으로 검사해 의도한 RED를 확인한다.

### T2. 모듈 graph와 Gradle 경계 구현

- `settings.gradle.kts`에 다음을 추가한다: `:bluetape4k-exposed-ktor-core`,
  `:bluetape4k-exposed-ktor-jdbc`, `:bluetape4k-exposed-ktor-r2dbc`,
  `:bluetape4k-exposed-ktor-cache`; 기존 `:bluetape4k-exposed-ktor`는 유지한다.
- `ktor/core`, `ktor/jdbc`, `ktor/r2dbc`, `ktor/cache` 각각의 build script는
  표에 있는 direct dependency만 선언하고 core에는
  `org.jetbrains.kotlin.plugin.serialization`을 실제 적용한다.
- core의 direct/transitive allowlist를
  `checkKtorDependencyBoundary`의 단일 source로 두고 JVM artifact/metadata
  variant를 정규화해 forbidden `io.github.bluetape4k.exposed` namespace를
  fail-closed한다.
- `exposed/bom`의 자동 module constraint와 publication inventory를 38개에서
  42개로 갱신하고 child artifact의 `.api` baseline을 생성한다.
- core의 Ktor `api`, child의 core+backend `api`, aggregator의 선택 모듈
  forwarding `api`만 허용한다. aggregator 외 child가 sibling backend를
  끌어오지 않는 clean consumer fixture를 만든다.

### T3. core route/probe/error/metric 구현

T1 RED가 실패하는 것을 확인한 뒤 다음 순서로 최소 구현한다.

- `ktor/core/src/main/kotlin/io/bluetape4k/exposed/ktor/core/`에 backend enum,
  cooperative probe, immutable registration state, `ReadinessClock`,
  sequential route와 path/timeout/component validation을 구현한다.
- route는 probe를 최대 동시성 1로 실행하고, remaining deadline을 전달하며,
  wrapper-owned timeout·caller cancellation·generic Exception·`Error` 규칙과
  unexecuted timeout을 정확히 적용한다.
- `ExposedKtorCoreErrorResponse`, `ExposedKtorCoreErrorCode`, status-pages와
  fixed message/status catalog를 추가한다. path/SQL/cause/secret은 response와
  log/metric에 복사하지 않는다.
- core transaction/readiness meter를 설치 시 한 번만 등록하고 cached reference를
  사용한다. legacy/cache metric 이름과 tag vocabulary를 변경하지 않는다.
- fake clock/barrier, collision registry, error/redaction, metric exactly-once
  테스트를 GREEN으로 만든다.

### T4. 선택 backend adapter 구현

- `ktor/jdbc`는 `exposedJdbcTransaction`과 JDBC readiness factory를 구현한다.
  caller dispatcher의 `runInterruptible`에서만 blocking I/O를 실행하고,
  statement 직전 remaining을 재계산해 whole-second query timeout을 적용한다.
- `ktor/r2dbc`는 `exposedR2dbcTransaction`과 R2DBC readiness factory를
  `suspendTransaction`/cancellable suspend로 구현하며 JDBC/cache 의존성을
  만들지 않는다.
- `ktor/cache`는 기존 cache report/contributor 계약을 backend-neutral probe로
  변환한다. supplier는 설치 후 O(1)·non-blocking·side-effect-free로 유지하고
  metric readiness를 직접 기록하지 않는다.
- 각 child는 core 예외를 사용하고 backend exception은
  `DATABASE_UNAVAILABLE`로 sanitized mapping한다. child API/companion/`@file:
  JvmName`/default bridge를 `.api`와 consumer fixture로 고정한다.

### T5. legacy aggregator forwarding과 ABI 보존

- 기존 `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/` facade의
  Config, installer, health route, transactions, status pages, cache contributor를
  유지한다. transaction/status/error 표면은 child/core forwarding으로 전환하되,
  legacy health route의 JDBC/R2DBC probe와 독립 phase budget은 기존 구현을
  유지해 호환 timeout semantics를 보존한다.
- aggregator는 core whole-deadline route를 호출하지 않는다. 기존 JDBC→R2DBC→
  cache phase 순서, 독립 budget, response key와 status mapping을 characterization
  fixture로 보존한다.
- child/core 예외는 legacy `ExposedKtorTransactionException`/
  `ExposedKtorReadinessTimeoutException`으로 재래핑한다. `(Throwable)`/`(String)`
  생성자, cause chain, actual JVM owner와 `$default` bridge를 유지하고 raw detail은
  경계를 넘기지 않는다.
- `@Deprecated(level = WARNING)`와 migration KDoc만 추가하고 `ERROR/HIDDEN`은
  사용하지 않는다. 기존 63개 테스트와 ABI whole-file baseline을 GREEN으로 만든다.

### T6. BOM·manual·README·example·CI 동기화

- `docs/manual/manifest.yaml`의 기존 `bluetape4k-exposed-ktor` entry를
  aggregator source/test/workshop 경계로 유지하고 child 4개 EN/KO manual entry를
  실제 `ktor/*` 경로와 artifact에 추가한다. stable release tree에 없는 child
  링크는 `releaseStatus: develop-only`로 표시한다.
- `ktor/*/README.md`, `README.ko.md`, aggregator README와
  `examples/ktor-exposed-demo`를 selective child 조합과 legacy migration 예제로
  갱신한다. 예제는 `bluetape4k-dependencies` BOM만 사용하고 개별 버전을 쓰지
  않는다.
- `.github/workflows/ci.yml`, `nightly-tests.yml`의 path filter와 Ktor jobs를
  core/child/aggregator로 나누고 Kover XML, driver-timeout, changed-path
  simulation을 갱신한다. child dependency boundary/ABI/inventory task receipt를
  artifact로 보존한다.

### T7. 검증 실행 순서

의존 순서를 지키고 앞 단계 실패 시 뒤 단계는 실행하지 않는다.

```bash
set -euo pipefail
EXPOSED_TEST_DB=H2 ./gradlew \
  :bluetape4k-exposed-ktor-core:test \
  :bluetape4k-exposed-ktor-jdbc:test \
  :bluetape4k-exposed-ktor-r2dbc:test \
  :bluetape4k-exposed-ktor-cache:test \
  :bluetape4k-exposed-ktor:test \
  --no-configuration-cache --no-daemon --console=plain

EXPOSED_TEST_DB=POSTGRESQL ./gradlew \
  :bluetape4k-exposed-ktor-jdbc:test :bluetape4k-exposed-ktor:test \
  --no-configuration-cache --no-daemon --console=plain

EXPOSED_TEST_DB=MYSQL_V8 ./gradlew \
  :bluetape4k-exposed-ktor-jdbc:test :bluetape4k-exposed-ktor:test \
  --no-configuration-cache --no-daemon --console=plain

./gradlew checkKtorDependencyBoundary checkProductionAbi detekt \
  --no-configuration-cache --no-daemon --console=plain
./gradlew exportManualModuleInventory
ruby scripts/manual/validate_manuals.rb \
  build/manual/module-inventory.json docs/manual/manifest.yaml
git diff --check
```

Docker 검증 전 `colima status`, `docker context show`, `docker info`를 읽어
healthy context를 확인한다. 컨테이너 불가 환경은 `PENDING`, assertion 실패는
`FAIL`, 조건부 fixture 부재는 명시적 `N/A`로 기록한다.

### T8. 최종 검토·receipt·handoff

- changed paths, `.api`/POM/metadata/class inventory, clean consumer 결과와
  manual/CI/inventory 생성물을 다시 읽는다.
- Type A 최종 6관점 code review에서 P0/P1 0을 확인하고, 필요하면 P2를 수정한
  뒤 같은 경계에서 재검토한다.
- `check-result`, `component-evidence`, `completion-check`, `verify`를 실행해
  run receipt를 갱신한다. implementation 계획과 검증 산출물이 모두 있는 경우에만
  workflow completion을 기록한다.
- 로컬 branch/worktree/commit은 보존한다. PR·merge·push·release와 canonical
  untracked cleanup은 이 작업에서 하지 않는다.

## 결과 기록

- 변경 파일: T2~T6의 Gradle/source/test/docs/workflow/BOM 산출물
- 테스트: T7 명령 및 각 결과/상태 manifest
- 남은 위험: interrupt를 무시하는 외부 JDBC driver의 hard wall-clock은 caller
  책임이며, PostgreSQL/MySQL 미가용은 PASS가 아닌 PENDING이다.
