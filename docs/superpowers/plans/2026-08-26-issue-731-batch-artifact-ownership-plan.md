# Issue #731 batch artifact ownership 분리 실행 계획

## 문서 상태

- Issue: [#731](https://github.com/bluetape4k/bluetape4k-exposed/issues/731)
- 저장소: `bluetape4k/bluetape4k-exposed`
- 기준 ref: `origin/develop@b993fdd89d5fdc8d09fbe7ae9d5a3aeb30376331`
- workflow: Type A `bluetape-full-feature`
- 선행 설계: `docs/superpowers/specs/2026-08-26-issue-731-batch-artifact-ownership-design.md`
- 선행 검토: `docs/review/2026-08-26-issue-731-batch-artifact-ownership-spec-review.md`
- 실행 상태: Step 6-R source 구현·로컬 검증·워크플로 source 증거 완료, Lore commit·PR handoff 대기

이 계획은 승인된 설계와 Step 2-R 통합 검토를 실행 가능한 작은 단계로
분해한다. 각 단계는 먼저 RED 회귀 또는 경계 검사를 추가하고, 해당 검사가
실패하는 것을 확인한 뒤 최소 구현을 적용한다. 설계에 없는 public API,
dependency, schema migration은 구현하지 않고 별도 이슈로 분리한다.

## 완료 조건과 중단 조건

완료는 다음 증거를 모두 확보한 경우에만 선언한다.

1. core/JDBC/R2DBC/aggregator module graph와 published API·POM·ABI가 설계와
   일치한다.
2. `bluetape4k-assertions` 기반 단위·conformance·consumer fixture 검사가
   통과하고, H2 → PostgreSQL → MySQL_V8 순차 Testcontainers 검사가 통과한다.
3. owner-aware CAS, interruptible lock, cancellation cleanup, 이름 검증,
   schema parity, R2DBC JDBC reverse-edge 금지 검사가 모두 통과한다.
4. README/manual EN·KO, BOM, module inventory, CI/Nightly, Kover, benchmark
   sidecar와 rollback 절차가 실제 경로·좌표·artifact에 맞는다.
5. 7-Tier code review에서 P0/P1이 0이고, 필요한 P2/P3가 처리되며,
   `git diff --check`, 한국어 용어 audit, 전체 검증 로그가 보존된다.

다음 경우 즉시 해당 단계에서 중단하고 원인을 기록한다.

- public ABI 삭제, dependency leakage, schema mismatch 또는 raw secret
  노출이 발견된 경우
- owner/CAS 검사가 fail-open이거나 동시성 검사가 재현 불가능한 경우
- PostgreSQL/MySQL 컨테이너가 실패했는데 환경 원인과 제품 원인을 분리하지
  못한 경우
- manual/inventory/generated manifest가 실제 파일을 가리키지 않는 경우
- Step 3-R 또는 Step 6-R에서 P0/P1이 남은 경우

## 단계별 실행

### T0. 기준선·작업 경계 고정

- feature worktree와 `refactor/batch-artifact-ownership` branch가
  `origin/develop@b993fdd89d5fdc8d09fbe7ae9d5a3aeb30376331`에서 시작했는지
  확인한다.
- root worktree의 기존 사용자 변경은 수정하지 않는다.
- workflow `mutation-check`를 source, plan, manual, workflow 변경 대상마다
  실행하고 receipt sequence와 expected head를 기록한다.
- 다음 기준선 명령을 실행하고 결과를 계획 검토 artifact에 붙인다.

```bash
./gradlew :bluetape4k-exposed-batch:test \
  --no-configuration-cache --no-daemon --console=plain
git diff --check
```

- 기준선은 379 tests, 7 skipped, H2/PostgreSQL/MySQL 통과 결과를 보존한다.

### T1. RED 회귀·소비자 경계 먼저 추가

소스 이동 전에 다음 실패 검사를 추가한다.

- `batch-core`의 `apiElements`, `runtimeElements`, published POM 및 Gradle
  metadata에 Exposed/JDBC/R2DBC가 나타나지 않는지 검사한다.
- `batch-core` custom `CheckpointJson` consumer는 Jackson 없이 compile·runtime,
  `CheckpointJson.jackson3()` consumer는 `bluetape4k-jackson3` runtime에서
  동작하는지 fixture로 고정한다.
- `aggregator-runtime`, `core-custom-json`, `jdbc-runtime`,
  `r2dbc-jackson3-runtime`, `legacy-binary-runtime`는 각각 정확한 dependency
  조합을 선언한다.
  aggregator fixture만 aggregator를, core fixture만 custom JSON을, JDBC fixture는
  core+JDBC를, R2DBC fixture는 core+R2DBC+Jackson 3를 선택한다. JDBC와 R2DBC를
  동시에 끌어오는 fixture는 두지 않는다.
- 추가 `maven-jdbc-runtime` fixture는 같은 JDBC 최소 조합을 Maven
  coordinate로 compile·runtime 검증한다. 다섯 Gradle fixture와 Maven fixture는
  `io.github.bluetape4k:bluetape4k-dependencies` BOM과 unversioned alias를
  사용한다.
- 기존 aggregator class inventory와 이동 후 ABI descriptor가 의도한
  deprecated bridge 조건을 제외하고 동일한지 고정한다.

검사 실패가 의도한 경계를 증명하는 RED 상태를 확인한 뒤 T2로 진행한다.

### T2. Gradle module graph와 source ownership 이동

- `settings.gradle.kts`에 다음 project를 등록한다.
  `:bluetape4k-exposed-batch-core`, `:bluetape4k-exposed-batch-jdbc`,
  `:bluetape4k-exposed-batch-r2dbc`, 기존 aggregator.
- `utils/batch/core`는 API, runner/DSL, in-memory repository,
  `io.bluetape4k.batch.CheckpointJson`을 소유한다.
- `utils/batch/jdbc`는 JDBC table, mapper, repository, reader, writer를
  소유한다.
- `utils/batch/r2dbc`는 자체 R2DBC table, mapper, repository, reader, writer를
  소유하며 production·test source에서 `io.bluetape4k.batch.jdbc`를 import하지
  않는다.
- aggregator는 세 child를 `api(project(...))`로 노출하고 benchmark source set과
  기존 task 이름을 보존한다. backend implementation dependency를 aggregator에
  중복 선언하지 않는다.
- package와 public class 이름은 가능한 한 보존하고, ABI baseline에 옛
  `io.bluetape4k.batch.internal.CheckpointJson` descriptor가 있을 때만
  deprecated bridge와 constructor overload를 한 minor line 유지한다.
- aggregator의 기존 JAR에 있던 effective public class surface가 child JAR로
  이동해도 사라지지 않는지 old aggregator consumer compile/runtime fixture,
  child JAR 포함 class inventory, `checkProductionAbi` 및 non-empty API baseline
  read-back으로 검증한다. 단순 transitive POM 노출이나 baseline suppression을
  ABI 보존 근거로 인정하지 않는다.
- `CheckpointJson` ABI는 interface, `Companion.jackson3()` 반환형,
  `toJobExecution`/`toStepExecution` mapper, JDBC/R2DBC constructor를 symbol별
  JVM descriptor ledger로 비교한다. 옛 descriptor가 있으면 deprecated bridge,
  companion factory bridge, mapper bridge, constructor overload와 old-binary
  runtime fixture를 함께 유지한다.
- checkpoint CAS는 version 증가를 포함하므로 runner가 stale execution을
  반복 전달하지 않도록 additive `saveCheckpointAndReturn(execution, checkpoint)`
  owner-aware API를 도입한다. 기존 Unit 반환 `saveCheckpoint(execution,
  checkpoint)` source/ABI surface는 유지하고 새 method를 호출한 뒤 반환된
  `StepExecution`으로 runner local state를 갱신한다. 각 adapter는 owner+version
  조건과 affected-row=1을 한 transaction에서 적용한다. 버전을 증가시키지 않는
  silent 예외 semantics는 허용하지 않는다.
- aggregator benchmark source set은 `benchmarkImplementation` 상속,
  child project classpath, existing test fixture association을 명시적으로
  wiring하고, R2DBC child가 JDBC test infrastructure를 끌어오지 않는지
  configuration report로 검사한다.

T2 완료 증거는 Gradle project graph, source ownership map, R2DBC negative scan,
ABI RED/green 전환이다.

### T3. core 동시성·소유권·직렬화 계약 구현

TDD 순서로 다음 테스트를 먼저 작성한다.

- `runInterruptible { ReentrantLock.lockInterruptibly() }` helper가 취소와
  interrupt에 반응하고 `finally`에서 unlock 하는지 검사한다.
- lock 임계구역에 suspend/I/O/callback/user code가 포함되지 않는지 source
  scan과 characterization test로 고정한다.
- InMemory/JDBC/R2DBC `findOrCreate`, claim, update, checkpoint가 owner와
  version을 함께 CAS하고 affected row가 1이 아니면 명시적으로 실패하는지
  검사한다.
- `execution.ownerId`가 null/blank이면 DB 접근 전에 `IllegalStateException`을
  내고 SQL `IS NULL` 우회가 없는지 검사한다.
- unclaimed, wrong-owner, stale-version, zero-row 결과를 각각 분리한다.
- ID-only checkpoint overload는 trusted/admin legacy escape hatch로만 남기고
  정상 runner 경로가 owner-aware overload를 선택하는지 검사한다.
- STOPPED primary `CancellationException`에 cleanup cancellation을 suppressed
  로 연결하고 primary를 다시 던지는지 검사한다.
- `requireValidBatchName`이 public `findOrCreate*`, `BatchJob`/`BatchStep`
  constructor, runner DSL의 모든 진입점에서 raw name 없이 실패하는지 검사한다.
- `CheckpointJson` registry가 등록된 type만 해석하고 임의 `Class.forName`을
  호출하지 않는지, malformed/unknown checkpoint characterization이 baseline
  의미를 보존하는지 검사한다.
- 새 logging·exception wrapping이 raw payload, `className`, caller params를
  추가 출력하지 않는지 redaction characterization test로 고정한다. 기존
  오류 메시지의 전체 redaction은 이 issue의 범위로 가장하지 않고 별도
  hardening issue로 추적한다.
- claim/update 경쟁은 고정 worker 수, 반복 횟수, bounded timeout과 deterministic
  barrier를 사용해 duplicate claim·lost update·deadlock을 재현 가능하게
  검증한다. 무제한 stress loop나 flaky timing assertion은 acceptance 근거로
  사용하지 않는다.
- `BatchJobBuilder`와 `BatchStepBuilder` public constructor/init 경계에서도
  `requireValidBatchName`을 즉시 적용하고 control-character와 원문 미노출을
  검사한다. `build()` 시점 검증만 남겨 두지 않는다.
- 동시성 oracle은 기존 `MultithreadingTester`, `StructuredTaskScopeTester`,
  `SuspendedJobTester`를 backend별로 매핑한다. `io.bluetape4k.assertions`의
  `assertFailsWith`, nullability·collection·exception matcher를 테스트 표에
  명시하고, 새 generic assertion helper를 만들지 않는다.
- reader/writer close와 STOPPED 저장의 suspend lifecycle에서 `runCatching`이
  primary `CancellationException`을 삼키지 않는지 검사한다. cleanup 예외는
  primary에 suppressed로 연결하고 primary를 다시 던지는 cancellation
  regression을 둔다.

테스트는 `bluetape4k-assertions`의 nullability, exception, collection,
경계값 assertion을 우선 사용하고, 필요한 경우 기존 Kluent/MockK 스타일과
맞춘다. 새 assertion helper나 dependency는 추가하지 않는다.

### T4. JDBC/R2DBC adapter와 schema parity

- JDBC adapter는 기존 table명·column명·nullable·default·status/checkpoint/
  params encoding·unique/index semantics를 그대로 보존한다.
- R2DBC adapter는 JDBC mapper를 재사용하지 않고 자체 table/mapping을 구현한다.
- test-only schema descriptor/parity fixture가 두 adapter의 column명, SQL type
  의도, nullability, unique/index, enum/status 및 encoding을 비교한다.
- H2, PostgreSQL, MySQL_V8 legacy-schema oracle에서 `findOrCreate`, claim,
  complete, checkpoint round-trip을 순차 검증한다.
- schema mismatch가 있으면 migration SQL을 추가하지 않고 구현을 중단하며,
  실제 migration 필요성은 별도 이슈로 기록한다.

### T5. aggregator·benchmark·관측성 계약

- 기존 benchmark task 이름과 의미를 보존하고 benchmark compilation에 필요한
  child artifact를 명시한다.
- benchmark sidecar에 `runId`, `sourceRef`, `sourceHead`, `environment`,
  `warmups`, `iterations`, `metric`을 기록한다.
- 알려진 여섯 profile만 허용하고 report root/결과가 비어 있으면 실패시키며,
  raw JSON/score는 finite 값만 허용하고 type은 nonblank로 검증한다.
- `sourceHead`가 현재 HEAD 및 run directory와 일치하는지, `pending` report와
  stale report가 문서 생성 단계에서 거부되는지 검사한다.
- benchmark 결과는 성능 개선을 주장하는 근거가 아니라 회귀 감지 증거로만
  보고한다.

### T6. BOM·문서·manifest·CI/Nightly 동기화

- BOM에 aggregator와 세 child artifact를 모두 등록하고 소비자 예제에는
  개별 버전을 쓰지 않는다.
- root/module README와 `docs/manual/{en,ko}`에 aggregator 유지와 selective
  artifact migration 예제를 같은 좌표로 기록한다.
- `docs/manual/manifest.yaml`, generated manifest, `gradlePath`, `sourceDir`,
  `sourcePaths`, `testPaths`, `artifact`, EN/KO child manual, releaseRef 및
  releaseCommit을 실제 파일과 맞춘다.
- 현재 stable release tree(`1.12.1`/
  `4cc2cce07087241ec24a597d8464615434ea2b81`)에는 nested child source path가
  없으므로, 이 PR의 child manual은 manifest에 `releaseStatus: develop-only`를
  명시한다. stable release 검증 대상인 repository-relative 링크는 기존
  `utils/batch` 경계만 가리키며, child source 링크를 낡은 release tree에
  거짓으로 고정하지 않는다. 다음 release promotion에서 새 tag/commit을
  manifest와 EN/KO source 링크에 함께 pin하고 stable로 승격한다.
- repository `AGENTS.md`와 module-local guidance의 layout, commands, module
  names, test matrix, publication coordinate를 실제 nested module graph와
  일치시킨다.
- release diagram은 실제 SVG/PNG pair와 target/source를 등록하거나,
  diagram 없음 결정을 명시한다.
- `ci.yml`, `nightly-tests.yml`, path filter, `strategy.max-parallel: 1`,
  child/aggregator Kover XML task와 changed-path simulation을 새 graph에
  맞춘다.
- Docker-backed test 전에는 `colima status`, `docker context show`,
  `docker info --format '{{.ServerVersion}}'` 및 socket을 조건부 확인하고,
  healthy Colima를 재시작하지 않는다.

### T7. 검증 실행 순서

다음 명령은 의존 순서를 지키며 실행한다.

```bash
set -euo pipefail
./gradlew :bluetape4k-exposed-batch-core:test \
  :bluetape4k-exposed-batch-jdbc:test \
  :bluetape4k-exposed-batch-r2dbc:test \
  :bluetape4k-exposed-batch:test \
  --no-configuration-cache --no-daemon --console=plain

for db in H2 POSTGRESQL MYSQL_V8; do
  EXPOSED_TEST_DB="$db" ./gradlew :bluetape4k-exposed-batch-jdbc:test \
    :bluetape4k-exposed-batch-r2dbc:test \
    --no-configuration-cache --no-daemon --console=plain
done

./gradlew detekt \
  --no-configuration-cache --no-daemon --console=plain
```

H2 → PostgreSQL → MySQL_V8 중 앞 단계가 실패하면 뒤 단계를 실행하지 않고,
실패가 코드인지 환경인지 증거를 분리한다. Testcontainers 실패를 통과로
간주하지 않는다.

### T8. 정적·ABI·문서·소비자·workflow 검증

- `jar tf` class inventory와 public ABI descriptor를 기준선과 비교한다.
- child `apiElements`/`runtimeElements`, POM, Gradle metadata에서 core
  dependency leakage와 JDBC/R2DBC reverse edge를 negative scan한다.
- `publishPublicationValidation`으로 먼저 isolated temporary Maven repository에
  모든 child/aggregator publication을 만들고, 같은 repository를 다섯 consumer
  fixture에 주입한다. 일반 사용자 Maven local에 남은 stale artifact를 사용하지
  않는다. 각 Gradle fixture는 `mavenLocal()`만 허용하는 repository profile,
  `--offline`, 기대하는 published version/sourceHead provenance assertion을
  사용해 remote fallback과 다른 checkout의 artifact resolution을 거부한다.
- Maven fixture는 `mvn -o -Dmaven.repo.local=<task-local-local-repo> clean
  package -DskipTests dependency:build-classpath` 후 Java public-type probe를
  실행해 같은 provenance와 runtime smoke를 검증한다. JUnit provider의
  플랫폼 버전 혼합을 피하고, compile·runtime 경계를 직접 확인한다.
- `exportManualModuleInventory`, `validate_manuals`,
  `validate_release_manuals`를 release ref/commit과 함께 실행한다.
- generated manifest는 export 후 read-back/diff로 확인하고, exporter unit
  test도 함께 실행한다.
- release diagram contract는 다음 명령으로 실제 target/source와 SVG/PNG
  pair를 확인한다. 새 diagram이 없으면 동일 명령의 `--check` 결과와
  manifest review에 그 결정을 기록한다.

```bash
ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml \
  docs/manual/generated/manifest.json
ruby scripts/manual/export_manifest.rb --check docs/manual/manifest.yaml \
  docs/manual/generated/manifest.json
ruby scripts/manual/export_manifest_test.rb
ruby scripts/manual/sync_release_diagrams.rb --check
ruby scripts/manual/release_diagram_contract_test.rb
```
- `actionlint`와 changed-path simulation으로 CI YAML과 path filter를 검사한다.
- child/aggregator Kover XML이 모두 존재하고 비어 있지 않은지 `test -s`로
  확인한다.
- Nightly terminal job은 다음처럼 결론을 직접 읽는다.

```bash
gh run view "$NIGHTLY_RUN_ID" --json jobs --jq '.jobs[] | {name, conclusion}'
```

위 출력은 예상 matrix job 이름(H2, PostgreSQL, MySQL_V8, child/aggregator
coverage)을 set으로 비교하고 각 conclusion이 `success`인지 fail-closed로
판정한다. 검증 로그와 command receipt는
`.omx/evidence/issue-731/<run-id>/`에 저장하고, benchmark sidecar의
`runId`와 동일한 식별자를 사용한다.

소비자 fixture의 provenance와 오프라인 경계는 다음 명령으로 재현한다.

```bash
ISSUE731_MAVEN_LOCAL="$PWD/.gradle/issue-731-maven-local"
./gradlew publishPublicationValidation \
  -Dmaven.repo.local="$ISSUE731_MAVEN_LOCAL" \
  --no-configuration-cache --no-daemon --console=plain
for fixture in \
  aggregator-runtime core-custom-json jdbc-runtime r2dbc-jackson3-runtime legacy-binary-runtime; do
  ./gradlew -p "utils/batch/consumer-fixtures/$fixture" clean compileKotlin test \
    -Dmaven.repo.local="$ISSUE731_MAVEN_LOCAL" --offline \
    --no-configuration-cache --no-daemon --console=plain
done
MAVEN_LOCAL_REPO="$ISSUE731_MAVEN_LOCAL" \
  mvn -o -Dmaven.repo.local="$MAVEN_LOCAL_REPO" -f \
  utils/batch/consumer-fixtures/maven-jdbc-runtime/pom.xml \
  clean package dependency:build-classpath -DskipTests \
  -Dmdep.outputFile=target/classpath.txt
java -cp \
  "utils/batch/consumer-fixtures/maven-jdbc-runtime/target/classes:$(< \
    utils/batch/consumer-fixtures/maven-jdbc-runtime/target/classpath.txt)" \
  issue731.consumer.Consumer
```

### T9. Step 6-R 7-Tier 구현 검토

구현 후 performance, security, stability, operations, developer experience,
caller compatibility 여섯 관점의 독립 검토를 수행한다. 각 검토는 다음
7-Tier 범위를 source, test, dependency, ABI, docs, CI, runtime 증거로
확인한다.

1. public API·source ownership
2. dependency·build graph
3. nullability·exception·validation
4. concurrency·transaction·persistence
5. test·fixture·coverage·static scan
6. docs·manual·migration·consumer ergonomics
7. CI/Nightly·release·rollback·observability

모든 관점의 P0/P1을 0으로 만들고, 남은 P2/P3에는 issue 또는 후속 범위를
명시한다. caller 검토는 aggregator compatibility와 exact coordinate를
실제 fixture로 확인한다.

### T10. lesson·Lore commit·PR handoff

- 한국어 lesson과 PR body에 변경 파일, 테스트, ABI/POM, CI/Nightly,
  manual/fixture 증거를 `## DoD Status`로 기록한다.
- 모든 reader-facing 문서는 SPW-01~05를 충족하고 다음 검사를 통과한다.

```bash
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/superpowers/specs/2026-08-26-issue-731-batch-artifact-ownership-design.md \
  docs/superpowers/plans/2026-08-26-issue-731-batch-artifact-ownership-plan.md \
  docs/review/2026-08-26-issue-731-batch-artifact-ownership-spec-review.md
```

- commit message는 Lore protocol을 따른다.

```text
artifact 경계를 분리해 batch 소비자의 선택적 의존성을 회복한다

Constraint: 기존 aggregator 호환성과 public ABI를 유지해야 한다
Rejected: 별도 schema artifact 추가 | migration 범위를 불필요하게 확장함
Confidence: high
Scope-risk: broad
Directive: 다음 변경자는 owner-aware CAS와 R2DBC 소유 경계를 우회하지 말 것
Tested: <실제 검증 명령과 결과>
Not-tested: <남은 환경 또는 hosted CI 공백>
```

- branch를 push하고 Korean PR을 생성하되, PR 생성 전 exact head, base,
  checks, review threads, linked issue #731, milestone/labels, `## DoD Status`
  를 다시 확인한다.
- PR merge는 이 계획의 완료가 아니다. fresh exact-head CI/review/mergeability
  증거를 수집한 뒤 사용자에게 별도 merge 승인을 요청한다.

## 롤백과 복구

- pre-merge 실패는 path-scoped 변경을 되돌리고 기준선 테스트를 재실행한다.
- schema mismatch나 ABI 삭제가 발견되면 해당 child 이동만 되돌리고,
  aggregator source와 기존 dependency를 보존한다.
- publication 이후 문제가 발견되면 corrective patch를 우선 만들고, 승인된
  경우에만 이전 BOM/aggregator 좌표로 downgrade한 다섯 consumer fixture와
  manual validator(`validate_manuals`, `validate_release_manuals`, release
  diagram `--check`)를 동일 command receipt로 재실행한다. downgrade 대상
  version, sourceHead, local repository 경로, expected result를 rollback
  checklist에 먼저 기록한다. tag 삭제나 강제 history rewrite는 수행하지
  않는다.
- 모든 실패는 원인, 재현 명령, 영향 범위, 재개 조건을 lesson/PR에 기록한다.

## Step 3-R 계획 검토 수락 기준

- performance/security/stability/operations/developer/caller 여섯 관점 결과가
  모두 존재하고 각 결과에 모델/관점/검토 범위/증거 경계가 기록된다.
- P0/P1=0이며, 발견된 항목은 이 계획에 반영하거나 명시적으로 후속 issue로
  분리된다.
- 계획·설계·review 문서의 한국어 용어 audit와 `git diff --check`가 통과한다.
- integrated Step 3-R artifact가 `PASS`인 경우에만 TDD source mutation을
  시작한다.
