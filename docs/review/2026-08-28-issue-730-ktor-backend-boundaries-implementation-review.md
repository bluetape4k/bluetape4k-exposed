# Issue #730 Ktor backend 선택 경계 구현 검토

## 문서 상태

- Issue: [#730](https://github.com/bluetape4k/bluetape4k-exposed/issues/730)
- 저장소: `bluetape4k/bluetape4k-exposed`
- 브랜치: `refactor/issue-730-ktor-boundaries`
- 작업 worktree: `.worktrees/refactor/issue-730-ktor-boundaries`
- 기준 ref: `origin/develop@c5e9d499d9c1baeb6f92a531345d184c16febc27`
- 검토 유형: Type A Step 6-R 구현·source·test·ABI·runtime·docs·CI 검토
- 승인: 아키텍처·공개 계약·구현 계획에 대한 사용자 승인 확인
- 검토 경계: local dirty worktree의 source gate만 다룬다. PR, hosted exact-head
  CI, merge, release는 이번 실행 범위에 포함하지 않았다.

## 검토 범위와 기준

`ktor/core`, `ktor/jdbc`, `ktor/r2dbc`, `ktor/cache`를 선택 artifact로 분리하고
`ktor/exposed`를 호환 aggregator로 유지하는 변경을 검토했다. core는 backend-neutral
route·probe·error·metric 계약을 소유한다. JDBC와 R2DBC adapter는 각각 필요한
Exposed backend만 사용하고, cache adapter는 `bluetape4k-exposed-cache`만 사용한다.
aggregator는 기존 package·constructor·response key·JDBC→R2DBC→cache phase를
유지하며 새 selective consumer는 child artifact를 직접 조합한다.

근거는 현재 source, test, `.api`, Gradle publication metadata, manual manifest,
README EN/KO, example, CI workflow와 다음 설계·계획 문서다.

- `docs/superpowers/specs/2026-08-27-issue-730-ktor-backend-boundaries-design.md`
- `docs/superpowers/plans/2026-08-27-issue-730-ktor-backend-boundaries-plan.md`
- `ktor/core/src/main/kotlin/`
- `ktor/jdbc/src/main/kotlin/`
- `ktor/r2dbc/src/main/kotlin/`
- `ktor/cache/src/main/kotlin/`
- `ktor/exposed/src/main/kotlin/`
- `exposed/bom/README.md`, `exposed/bom/README.ko.md`

## 7-Tier 결과

| Tier | 상태 | 근거 | 잔여 경계 |
|---|---|---|---|
| 1 Source/ownership | PASS | core와 4개 child source set을 분리하고 aggregator legacy surface를 유지했다. | legacy implementation은 phase·source characterization 보존을 위해 직접 실행한다. |
| 2 Dependency/build | PASS | `checkKtorDependencyBoundary`가 source/resolved graph와 child POM/metadata의 금지 sibling을 검사했다. | hosted dependency graph는 PR exact head에서 재확인해야 한다. |
| 3 API/validation | PASS | 4개 child `.api`, `checkKotlinAbi`, production ABI 42/42, marker·component·path·timeout 검증이 통과했다. | 신규 manual은 현재 `develop-only`다. |
| 4 Concurrency/runtime | PASS | core의 순차 shared monotonic deadline, unexecuted `TIMEOUT`, JDBC `runInterruptible`, caller-owned resource 계약을 테스트했다. | JDBC driver가 statement timeout/interruption을 무시하면 hard wall-clock은 caller 구성의 한계다. |
| 5 Test/ABI | PASS | core 7, JDBC 5, R2DBC 5, cache 3, example 32, legacy 63 테스트와 ABI가 통과했다. | remote CI와 별도 PR review는 아직 없다. |
| 6 Docs/migration | PASS | EN/KO child manual·README, aggregator migration, manifest, BOM 기반 example을 갱신하고 inventory validation을 통과했다. | release tree에 child manual을 고정하는 작업은 release 단계다. |
| 7 CI/release | PASS (local) | daily/nightly path filter, Ktor child test, boundary·ABI·inventory·Kover artifact 경로를 갱신했다. | PR 생성·hosted check·mergeability·release는 범위 밖이다. |

## 6-lens 판정

| 관점 | 판정 | 확인 내용 |
|---|---|---|
| Correctness | P0=0, P1=0 | shared deadline, timeout 우선순위, generic exception redaction, fixed error catalog를 고정했다. |
| Compatibility/ABI | P0=0, P1=0 | legacy constructor와 `$default` bridge를 유지하고 child API에 raw-cause constructor를 노출하지 않았다. |
| Concurrency/lifecycle | P0=0, P1=0 | probe를 한 번에 하나씩 실행하고 dispatcher·scope·worker·resource를 만들거나 닫지 않는다. |
| Security/redaction | P0=0, P1=0 | component allowlist와 고정 error payload를 사용하며 SQL·URL·credential·cause detail을 response/metric에 복사하지 않는다. |
| Operability | P0=0, P1=0, WATCH | `runInterruptible`과 statement timeout은 driver 지원을 전제로 하며, 미지원 driver는 caller 구성 갭으로 남긴다. |
| Docs/CI/release | P0=0, P1=0, WATCH | local manifest/metadata/YAML은 통과했지만 hosted exact-head와 release ref 검증은 수행하지 않았다. |

## 검증 증거

- `EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-ktor:test ...` → `SUCCESS: Executed 63 tests`, `BUILD SUCCESSFUL`.
- `EXPOSED_TEST_DB=POSTGRESQL ./gradlew :bluetape4k-exposed-ktor:test ...` → `SUCCESS: Executed 63 tests`, `BUILD SUCCESSFUL`.
- `EXPOSED_TEST_DB=MYSQL_V8 ./gradlew :bluetape4k-exposed-ktor:test ...` → `SUCCESS: Executed 63 tests`, `BUILD SUCCESSFUL`.
- `./gradlew :bluetape4k-exposed-ktor-core:test :bluetape4k-exposed-ktor-jdbc:test :bluetape4k-exposed-ktor-r2dbc:test :bluetape4k-exposed-ktor-cache:test :bluetape4k-exposed-ktor:test :examples-ktor-exposed-demo:test ...` → `7/5/5/3/63/32` tests, `BUILD SUCCESSFUL`.
- `./gradlew :bluetape4k-exposed-ktor-core:checkKotlinAbi :bluetape4k-exposed-ktor-jdbc:checkKotlinAbi :bluetape4k-exposed-ktor-r2dbc:checkKotlinAbi :bluetape4k-exposed-ktor-cache:checkKotlinAbi checkProductionAbi ...` → `BUILD SUCCESSFUL`; production task는 `modules=42/42`, `baselines=42/42`, `actualDumps=42/42`, `emptyBaselines=0`을 확인했다.
- `./gradlew checkKtorDependencyBoundary ...` → `Ktor dependency boundary passed: selectiveArtifacts=4`.
- `ruby scripts/verification/validate_ktor_consumer.rb 2.0.0` → `ktor-consumer: PASS consumers=4`;
  boundary task receipt에도 `consumerFixture=PASS`가 기록됐다.
- 4개 child와 aggregator의 publication POM/Gradle metadata 생성 → `BUILD SUCCESSFUL`; child별 forbidden backend가 없다.
- 대상 6개 module/example의 `detekt` → `BUILD SUCCESSFUL`.
- `./gradlew exportManualModuleInventory`와 `ruby scripts/manual/validate_manuals.rb build/manual/module-inventory.json docs/manual/manifest.yaml` → `Manuals are aligned.`
- BOM EN/KO 관리 ArtifactId 표에 core/JDBC/R2DBC/cache child와 compatibility
  aggregator를 함께 반영했다.
- `ruby -e 'require "yaml"; ...' .github/workflows/ci.yml .github/workflows/nightly-tests.yml` → 두 workflow `YAML PASS`.
- `colima status`, `docker context show`, `docker info` → Colima running, `default`, Docker server `29.2.1`.
- `git diff --check` → PASS.
- 한국어 term audit는 일반 문장의 `snapshot` loanword를 `스냅샷`/`상태`로
  정리한 뒤, README 예제 추출기가 요구하는 `example:snapshot:start/end`
  HTML marker 2건만 의도적으로 남겼다.

## 잔여 갭과 결론

이번 구현 gate에서 merge를 막는 P0/P1은 확인되지 않았다. 다만 다음은 아직
검증하지 않은 delivery 증거다.

- PR exact head의 hosted CI, review thread, mergeability, linked issue metadata
- stable release ref에서 child manual link가 존재하는지에 대한 release 검증
- tag·publication·merge와 그에 따른 원격 branch 동기화

따라서 결론은 `PASS (local source gate, P0=0, P1=0)`이며, `PR/merge/release`
상태는 `N/A (이번 승인 범위 밖)`이다.

## SPW 체크리스트

- [x] **SPW-01** 대상·독자·검토 범위와 source ledger를 문서 앞부분에 고정했다.
- [x] **SPW-02** 7-Tier, 6-lens, 근거·갭·판정을 분리했다.
- [x] **SPW-03** 한국어 기술 문체를 적용하고 API·명령·경로·수치를 그대로 보존했다.
- [x] **SPW-04** 설계·계획·현재 source와 검증 결과를 대조해 local PASS와 hosted 갭을 구분했다.
- [x] **SPW-05** 최종 Markdown을 read-back했고 표·코드 토큰·링크 흐름을 확인했다.
  한국어 자연스러움 audit의 남은 2건은 machine-required example marker다.
