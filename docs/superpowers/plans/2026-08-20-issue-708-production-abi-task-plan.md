# Issue #708 production ABI task 실행 계획

> Type E build/CI 호환성 유지보수 계획이다. public API와 안정 manual은 건드리지
> 않고, 현재 develop 기준선과 KGP 내장 ABI 검증을 공통 fail-closed gate로 만든다.

## 기준과 선행 조건

- base: `develop` `9fda4b0984d30d9e0f4514281e663d4bd4221e04`
- branch/worktree: `ci/issue-708-production-abi` /
  `.worktrees/ci-issue-708-production-abi`
- live issue: [#708](https://github.com/bluetape4k/bluetape4k-exposed/issues/708),
  Epic #659, milestone `2.0.0`, assignee `debop`
- 선행 runtime PR: #706 merge SHA `9fda4b0984d30d9e0f4514281e663d4bd4221e04`
- 허용 영역: root/buildSrc Gradle logic, `api/<project-name>.api`, `.gitattributes`,
  `.github/workflows/ci.yml`, ABI helpers/fixtures, 이 문서군
- 금지 영역: production source/API 변경, dependency/catalog/BOM 변경,
  `docs/manual/**` `1.12.1`, release/publish/tag, unrelated worktree

## Task 0 — preflight·publication 파생·fixture inventory

1. exact HEAD, worktree topology, `git status --short`, `git diff --check`를 기록한다.
2. `build.gradle.kts`의 `publishableProjects`, `isNonPublishedModule()` 및
   `exportPublicationInventory`를 source of truth로 읽어 35개 publication에서
   `java-platform` BOM 1개를 제외한 34개 published JVM module을 파생한다.
3. `settings.gradle.kts`에만 존재하는 BOM, examples, benchmark, demo는 published JVM
   ABI 집합에 포함하지 않는다. 별도 YAML에 module 목록을 복제하지 않는다.
4. `spring-boot/jdbc`, `spring-boot/r2dbc`, `ktor/exposed`의 기존 ABI fixture와
   checked-in resource를 owner/parity 대상으로 유지한다.
5. 예외 파일은 이 slot에 만들지 않는다. 예외가 필요하면 suppressing metadata를
   추가하기 전에 별도 API decision issue와 owner·사유·만료 승인을 만든다.

## Task 1 — KGP 내장 ABI와 exact-base baseline

1. immutable catalog가 고정한 Kotlin Gradle Plugin `2.4.10`의 단일 구성 지점에
   `abiValidation`을 opt-in하고 `binariesSource = MAVEN_PUBLICATIONS`를 설정한다.
2. KGP가 제공하는 `checkKotlinAbi`와 `updateKotlinAbi`를 사용한다. 신규
   `kotlinx.binary-compatibility-validator` plugin/dependency는 추가하지 않는다.
3. 각 publication project에
   `referenceDumpDir.set(rootProject.layout.projectDirectory.dir("api"))`를 명시하고,
   exact base `develop@9fda4b0984d30d9e0f4514281e663d4bd4221e04`에서 34개 module의
   checked-in `api/<project-name>.api`를 최초 bootstrap한다. `1.12.1` release
   artifact와 `2.0.0` development line의 release-to-release 비교는 별도 issue로
   분리한다.
4. `updateKotlinAbi`는 CI에서 실행하지 않는다. 이후 baseline update는 API owner
   `debop`이 linked API decision과 승인된 candidate head를 확인한 뒤에만 수행한다.
   current output을 무조건 덮어쓰거나 additive descriptor를 REPORT/WATCH로 통과시키지
   않는다.
5. dump 비교 입력에는 `generatedAt` 같은 비결정적 metadata를 넣지 않는다. KGP DSL이
   experimental인 점과 catalog upgrade 뒤 `help`/ABI compile gate 재검증을 문서화한다.

최초 bootstrap은 root의 manual-only `updateProductionAbiBaseline` wrapper가 34개
module의 `updateKotlinAbi`를 순차 호출하도록 구현하고, exact base에서 다음 read-back을
남긴다.

```bash
./gradlew updateProductionAbiBaseline --no-build-cache \
  --no-configuration-cache --no-daemon --console=plain
test "$(find api -maxdepth 1 -name '*.api' -type f | wc -l | tr -d ' ')" -eq 34
git diff -- api/
```

`updateProductionAbiBaseline`은 CI task graph에 연결하지 않으며, 34 files와 orphan=0
확인은 이후 `checkProductionAbi` report에서 다시 증명한다.

**RED:** public descriptor의 removal/change/addition을 임시 fixture로 만들면 모두
`checkKotlinAbi`가 실패하고, 승인된 baseline update 뒤에만 GREEN이 되는지 확인한다.

## Task 2 — `checkProductionAbi` aggregate guard

1. 루트 `checkProductionAbi`를 등록해 34개 expected module마다 `checkKotlinAbi`
   task 존재, non-empty actual dump, checked-in baseline 존재를 검증한다.
2. aggregate 결과에서 orphan/unknown baseline, actual dump 0개, empty baseline,
   missing class/jar, malformed descriptor를 fail-closed로 판정한다. KGP 내부 task가
   `actualDumps.forEach`만 순회해 empty actual을 자체 실패시키지 않을 수 있으므로 이
   guard를 별도로 둔다.
3. removal/change/addition, missing baseline, empty actual, orphan baseline 각각의
   negative fixture를 고정한다. 구현 helper는
   `buildSrc/src/main/kotlin/ProductionAbiSupport.kt`, 단위 테스트는
   `buildSrc/src/test/kotlin/ProductionAbiSupportTest.kt`, aggregate wiring은 root
   `build.gradle.kts`에 둔다. custom `javap`가 필요하면 KGP parity/negative probe에
   한정하고 주 비교기를 재구현하지 않는다.
4. `japicmp`/`Revapi`는 release-JAR 비교가 필요한 별도 후속 gate에서만 의사결정한다.

**RED:** Task 0에서 파생한 publication 하나를 제거하거나 empty baseline/fixture로
만든 검증 입력, removal/change/addition, missing baseline, empty actual, orphan
baseline을 각각 실행한다. 모두 FAIL이어야 하며 `N/A`, 빈 목록, 누락 task를 PASS로
처리하지 않는다. 승인된 baseline update 뒤에만 GREEN이다.

**RED/GREEN 명령:**

```bash
set -euo pipefail
./gradlew checkProductionAbi --no-build-cache \
  --no-configuration-cache --no-daemon --console=plain
```

negative fixture를 바꾼 직후의 local RED 재현에만 `--rerun-tasks`를 붙인다. PR CI는
앞선 compile/jar 단계의 산출물을 재사용하고 aggregate만 무재시도로 실행한다.

## Task 3 — 기존 fixture parity

1. 다음 세 fixture와 resource를 삭제하지 않고 순차 실행한다. 이들은 KGP `.api`를
   raw descriptor로 변환하지 않는 독립 consumer smoke로 유지한다.
   - `ExposedJdbcRepositoryAbiCompatibilityTest`
   - `ExposedR2dbcRepositoryAbiCompatibilityTest`
   - `ExposedKtorAbiCompatibilityTest`
2. parity는 raw descriptor 번역이 아니라, 세 fixture가 보호하는 module이 derived
   inventory에 포함되고 공통 aggregate가 같은 module의 KGP dump를 검사하는지로
   고정한다. `ProductionAbiSupportTest`는 buildSrc 순수 helper의 inventory/report
   negative만 검증하며, 실제 JDBC/R2DBC/Ktor smoke와 aggregate positive는 각 Gradle
   test task를 순차 실행해 별도 증명한다. fixture가 없는 module을 공통 gate가 놓치지
   않는지는 Task 0/2 증거와 연결한다.
3. 기존 fixture의 public API나 resource 형식은 이 slot에서 변경하지 않는다.

```bash
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
  --tests '*ExposedJdbcRepositoryAbiCompatibilityTest'
./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test \
  --tests '*ExposedR2dbcRepositoryAbiCompatibilityTest'
./gradlew :bluetape4k-exposed-ktor:test \
  --tests '*ExposedKtorAbiCompatibilityTest'
```

## Task 4 — CI required gate

1. 기존 non-doc build job에서 compile/build retry가 ABI 결과를 재시도해 덮지 않도록
   retry 명령에는 `-x checkKotlinAbi`를 적용한다.
2. build 직후 별도의 **무재시도** 단계에서 다음 명령을 실행한다.

```bash
./gradlew checkProductionAbi --no-build-cache \
  --no-configuration-cache --no-daemon --console=plain
```

3. ABI 단계 시작 전에 report directory를 만들고 inventory를 먼저 export한 뒤,
   publication inventory를 먼저 export한다. Gradle 실패도 log에 남도록 `pipefail`과
   `tee`를 사용한다.

```bash
set -euo pipefail
mkdir -p build/abi/reports
./gradlew exportPublicationInventory --no-daemon --console=plain \
  2>&1 | tee build/abi/reports/publication-inventory.txt
./gradlew checkProductionAbi --no-build-cache \
  --no-configuration-cache --no-daemon --console=plain 2>&1 \
  | tee build/abi/reports/check-production-abi.log
test -s build/abi/reports/production-abi.txt
grep -F 'modules=34/34' build/abi/reports/production-abi.txt
```

4. `production-abi.txt`는 aggregate가 실제로 생성한 non-empty report만 인정하며,
   placeholder를 선행 생성하지 않는다. report/log/inventory artifact는 `if: always()`와
   `if-no-files-found: error`로 업로드한다. inventory는 Gradle stdout log뿐 아니라
   실제 `build/publication/publication-inventory.json`도 별도 artifact로 업로드한다.
   proven docs-only 변경만 N/A이며, non-doc/ABI-impact selector miss나
   task skip은 FAIL이다. 이 계획은 기존 non-doc build job에 ABI를 포함하므로 별도
   ABI-specific path filter를 추가하지 않는다. 별도 job을 만들 경우에만 RUN/N/A
   classifier와 `ci-status.needs`를 함께 갱신한다.
5. affected paths와 실제 required check 이름을 live workflow read-back으로 고정한다.

## Task 5 — 검증·7-Tier·문서 evidence

1. 세 fixture, affected compile/test, `detekt`, ABI aggregate를 순차 실행한다.
2. `actionlint .github/workflows/ci.yml`, `git diff --check`, Korean terminology audit를
   실행한다.
3. `git diff --name-only`로 production source/API, catalog/BOM, `docs/manual/**`가
   변경되지 않았음을 확인한다.
4. 7-Tier review와 lesson에 exact base, 34-module 파생 근거, full/targeted 결과,
   N/A·PENDING 경계를 기록한다. KGP catalog upgrade 및 release-JAR 비교는 후속
   issue로 명시한다.

### Implementation evidence (2026-08-21)

- `ProductionAbiSupportTest`는 helper 구현 전 unresolved symbol로 RED를 확인했고,
  구현 후 targeted 및 full `:buildSrc:test`가 GREEN이다.
- Linux hosted `checkProductionAbi` 기준은 `modules=34/34`, `baselines=34/34`,
  `actualDumps=34/34`, `orphanBaselines=0`, `orphanActuals=0`,
  `emptyBaselines=0`이어야 한다. macOS local dump는 case-insensitive
  classpath에서 `UUID`/Kotlin `Uuid`를 충돌시켜 이 쌍을 생략할 수 있으므로
  hosted Linux 결과를 최종 증거로 사용한다.
- 기존 ABI fixture: JDBC `3/3`, R2DBC `2/2`, Ktor `3/3` pass; failure/error `0/0`.
- compile retry-equivalent build, `detekt`, `actionlint`, terminology audit,
  `git diff --check`가 통과했다.
- `bluetape4k-exposed-core` baseline에 public descriptor addition/removal/change를
  순차적으로 임시 적용한 controlled negative probe가 모두 `checkKotlinAbi`
  exit `1`/`ABI has changed`로 실패했고 기준선은 원복했다. macOS의
  case-insensitive classpath 한계 때문에 보정된 `Uuid` 쌍을 포함한 aggregate
  GREEN은 corrected-head hosted Linux rerun에서 재확인한다.
- hosted PR run `32435651147`은 이전 head에서 Linux 전용 `Uuid` descriptor
  baseline 누락으로 실패했다. JDBC/R2DBC baseline과 canonical EOF를 보정했고,
  corrected head run `32438771629`의 compile·POM·no-retry ABI·두 artifact upload가
  성공했다. nightly backend run은 아직 실행하지 않았고, fresh PR review/merge는
  별도 gate다.

## Task 6 — PR readiness와 stop gate

1. 설계·계획 독립 review가 P0=0/P1=0이 되기 전에는 implementation을 시작하지 않는다.
2. 구현 완료 뒤 Lore commit과 exact head를 기록한다. PR 생성/merge는 별도 권한 및
   exact-head approval gate를 통과한 뒤에만 수행한다.
3. PR body는 한국어, `Fixes #708`, 마지막 H2 `## DoD Status`와
   `Required checks: X/Y; N/A: N; Blocked: N`를 사용한다.

## Rollback

- public production signature가 변하면 즉시 중단하고 별도 feature/fix issue로 분리한다.
- initial exact-base 또는 승인된 candidate-head baseline을 재현할 수 없으면 파일을
  임의 갱신하지 않고 `PENDING`으로 둔다.
- CI path filter가 ABI를 skip하면 workflow를 녹색으로 해석하지 않고 trigger/required
  classifier를 수정한다.

## Plan DoD

- [x] Task 0 publication 파생·fixture owner 결정
- [x] Task 1 KGP ABI 구성·exact-base baseline provenance
- [x] Task 2 fail-closed aggregate와 descriptor/helper/inventory RED/GREEN negative fixture
- [x] Task 3 JDBC/R2DBC/Ktor fixture parity
- [x] Task 4 무재시도 required CI gate
- [x] Task 5 static/7-Tier/lesson evidence
- [ ] Task 6 exact-head PR readiness

## Plan gate

- [x] 이 계획 자체의 SPW-01 — Issue/Epic/base와 허용·금지 경계를 read-back했다.
- [x] 이 계획 자체의 SPW-02 — KGP 선택, baseline lifecycle, fail-closed/rollback을
  기록했다.
- [x] 이 계획 자체의 SPW-03 — 한국어 prose와 `KGP`, `MAVEN_PUBLICATIONS`, `N/A`,
  `PENDING`, exact SHA를 보존했다.
- [x] 이 계획 자체의 SPW-04 — publication SSOT, 34 module, 세 fixture, no-retry CI와
  artifact evidence를 대조했다.
- [x] 이 계획 자체의 SPW-05 — headings, code fence, checklist, 명령과 issue acceptance를
  read-back했다.
- 구현 전 독립 review가 P0=0/P1=0인지 확인한다.
- baseline 범위는 exact current develop이며, `1.12.1` release 비교·KGP catalog
  upgrade·force-abort는 이 계획의 선행/후속 issue로 섞지 않는다.

## Implementation DoD Status

Required checks: 7/8; N/A: 0; Blocked: 0

Final status: **PENDING — corrected-head hosted exact-head CI는 통과했으며 fresh
PR review/merge gate와 nightly backend evidence가 남아 있음**
