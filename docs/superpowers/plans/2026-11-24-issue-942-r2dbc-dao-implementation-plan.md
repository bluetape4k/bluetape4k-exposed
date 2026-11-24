# #942 Exposed 1.6.1 R2DBC DAO 연동 구현 계획

> **For agentic workers:** 구현 전 승인된 계획을 기준으로 `$executing-plans`를 사용한다. 단계는 아래 순서대로 처리하고, 각 검증 증거를 기록한다. 이 계획의 승인은 구현에만 적용되며 PR, 병합, 배포, tag 발급은 승인하지 않는다.

**목표:** 중앙 catalog가 고정한 Exposed 1.6.1 실험적 R2DBC DAO를 JDBC DAO와 격리된 `bluetape4k-exposed-r2dbc-dao` 아티팩트로 소비하고, 게시 좌표·런타임 계약·문서를 재현 가능하게 검증한다.

**구조:** `exposed/r2dbc-dao`는 Exposed의 실험 API를 다시 포장하지 않는 의존성 재노출 전용 모듈이다. 중앙 catalog alias가 병합된 immutable commit에서만 의존성을 해석한다. ABI 검증은 정확히 허용한 무소스 모듈의 빈 baseline/dump만 받아들이고, H2 계약 테스트와 게시 소비자 fixture가 Exposed DAO 동작·실험 API opt-in·JDBC DAO 부재를 검증한다.

**기술 스택:** Kotlin/JVM, Gradle version catalogs, JetBrains Exposed 1.6.1, R2DBC/H2, Kotlin Coroutines/Flow, JUnit 5, GitHub Actions, Ruby publication validator, Markdown 중앙 매뉴얼.

---

## 승인·선행 조건과 중단 경계

- 설계 문서: [`docs/superpowers/specs/2026-11-24-issue-942-r2dbc-dao-design.md`](../specs/2026-11-24-issue-942-r2dbc-dao-design.md)
- 대상 이슈: [bluetape4k-exposed#942](https://github.com/bluetape4k/bluetape4k-exposed/issues/942), milestone `2.2.0`
- 필수 선행 이슈: [bluetape4k-dependencies#293](https://github.com/bluetape4k/bluetape4k-dependencies/issues/293). 별도 모듈 구현 전에 이슈가 병합되어야 하며 `exposed-dao-r2dbc` alias와 `exposed = 1.6.1` ref가 포함된 원격 commit SHA를 확인한다. #293을 이 계획에서 구현하거나 catalog tag를 만들지 않는다.
- 비차단 후속 이슈: [bluetape4k-exposed#954](https://github.com/bluetape4k/bluetape4k-exposed/issues/954). repository helper와 DAO를 함께 쓸 모듈 경계는 이 계획에 추가하지 않는다.
- 현재 코드 worktree: `/Users/debop/.codex/worktrees/ac2f/bluetape4k-exposed`, branch `feat/issue-942-r2dbc-dao`, 기준 commit `cd4eb89dd1f5240560327a9f0902668fc51c9286`. 이 위치의 미추적 spec을 보존한다.
- dependencies checkout `/Users/debop/work/bluetape4k/bluetape4k-dependencies`에는 이 작업과 무관한 수정 파일이 있다. 그 파일을 정리하거나 덮어쓰지 않는다. 모듈 alias가 필요한 경우 최신 `origin/develop`에서 별도 worktree를 만든다.
- central docs checkout `/Users/debop/work/bluetape4k/bluetape4k.github.io`가 관찰 시점에 `origin/develop`보다 3 commit 뒤였다. 문서 작업 때 최신 `origin/develop`에서 `docs/issue-942-r2dbc-dao` worktree를 새로 만든다. 기존 checkout은 변경하지 않는다.
- 구현 전에는 이 계획에 대한 사용자의 별도 승인이 필요하다. #293이 병합되지 않았거나 alias·checksum이 확인되지 않으면 계획을 승인받았더라도 Task 1 전에 멈춘다.
- PR 생성·병합, GitHub workflow dispatch, 카탈로그 tag 발급, 시험판·정식 버전 배포는 범위 밖이며 별도 권한이 필요하다.

## 파일·작업 경계

| 저장소 | 예상 파일 | 책임 |
|---|---|---|
| `bluetape4k-exposed` | `settings.gradle.kts`; `exposed/r2dbc-dao/build.gradle.kts`; `api/bluetape4k-exposed-r2dbc-dao.api`; `build.gradle.kts`; `buildSrc/src/main/kotlin/ProductionAbiSupport.kt`; `buildSrc/src/main/kotlin/CheckProductionAbiTask.kt`; `buildSrc/src/test/kotlin/ProductionAbiSupportTest.kt` | 고정 catalog ref, 새 모듈, 무소스 ABI 허용 목록, publication/ABI 검증 |
| `bluetape4k-exposed` | `exposed/r2dbc-dao/src/test/kotlin/io/bluetape4k/exposed/r2dbc/dao/ExposedR2dbcDaoContractTest.kt`; 필요할 때 동일 패키지의 lifecycle test와 fixture 설정 | H2 계약, Flow 수집, `attach()`, 동시성, 취소·롤백·커넥션 반환 |
| `bluetape4k-exposed` | `scripts/publication/validate_downstream_consumer.rb`; `scripts/publication/validate_downstream_consumer_test.rb`; `scripts/publication/fixtures/r2dbc-dao-kotlin/{settings.gradle.kts,build.gradle.kts,src/main/kotlin/...}` | 기존 임시 Maven repository에 Kotlin 외부 소비자 양성·음성 컴파일과 의존성 그래프 검사를 연결 |
| `bluetape4k-exposed` | `.github/workflows/ci.yml`; `.github/workflows/nightly-tests.yml` | H2 PR 필수 작업, PostgreSQL 야간 작업, 경로 감지와 테스트 결과 artifact |
| `bluetape4k-exposed` | `exposed/r2dbc-dao/README.md`; `exposed/r2dbc-dao/README.ko.md`; `README.md`; `README.ko.md`; `CHANGELOG.md`; `WIP.md` | 모듈 사용법, 조합 경고, Unreleased 변경 및 milestone 상태 |
| `bluetape4k.github.io` | `docs/manual/bluetape4k-exposed/manifest.yaml`; `en/modules/bluetape4k-exposed-r2dbc-dao.md`; `ko/modules/bluetape4k-exposed-r2dbc-dao.md`; `en/index.md`; `ko/index.md` | central manual 등록, 언어별 사용 설명과 landing 링크 |
| `bluetape4k-dependencies` | `gradle/libs.versions.toml`; `gradle/libs.versions.toml.sha256`; 필요 시 생성된 Dependabot ignore 설정 | 새 Exposed artifact catalog alias와 managed catalog checksum/sync 검사. 작업은 최신 별도 worktree에서 하며 기존 dirty checkout을 보존한다. |

`settings.gradle.kts`의 `includeModules("exposed", ...)`가 새 하위 모듈을 자동 등록하므로 수동 `includeProject` 항목은 추가하지 않는다. `exposed/bom/build.gradle.kts`도 하위 publishable 모듈을 자동 포함하므로, 생성 POM에 새 아티팩트가 들어가는지 검증하고 불필요한 수동 제약은 만들지 않는다. `exposed/r2dbc`는 `:bluetape4k-exposed-dao`를 API로 노출하므로 새 모듈의 compile/runtime 의존성으로 추가하지 않는다.

## 실행 순서

### Task 0 — 승인, 선행 catalog, 작업 공간 확인

- [ ] **T0.1 — 구현 계획 승인과 issue/worktree readback**
  - **Action:** 별도 계획 승인을 받은 뒤 `gh issue view 942 --repo bluetape4k/bluetape4k-exposed`에서 issue state와 `2.2.0` milestone을 확인하고, Exposed feature branch의 HEAD·변경 파일·승인 spec을 다시 읽는다.
  - **Evidence:** 승인 메시지, #942 issue/milestone URL과 state, Exposed branch·HEAD·변경 파일 목록.
  - **Failure:** 계획 승인이 없거나 #942가 닫혔거나 milestone/branch scope가 달라지면 구현을 시작하지 않고 최신 목표를 확인한다.
- [ ] **T0.2 — 승인된 spec과 plan을 구현 전에 commit**
  - **Action:** 별도 계획 승인을 받은 뒤 현재 feature branch에 승인된 spec과 plan만 포함하는 한국어 Lore 형식의 commit을 만든다. 실제 코드를 수정하기 전에 이 commit이 현재 branch에 있고 working tree가 예상대로 정리됐는지 확인한다.
  - **Evidence:** commit SHA, commit에 포함된 spec/plan 경로, `git show --stat --oneline HEAD`, 이후 구현 대상과 분리된 working-tree 상태.
  - **Failure:** 승인되지 않은 artifact나 사용자 파일을 섞지 않는다. commit 범위가 spec/plan 외 파일을 포함하면 중단하고 인덱스를 바로잡는다.
- [ ] **T0.3 — #293의 immutable alias와 checksum 확인**
  - **Action:** `gh issue view 293 --repo bluetape4k/bluetape4k-dependencies`로 #293의 병합 상태를 확인한다. 병합됐다면 alias `exposed-dao-r2dbc`와 `exposed = 1.6.1` ref가 포함된 원격 immutable commit SHA 및 같은 ref의 `gradle/libs.versions.toml.sha256` checksum을 확인한다.
  - **Evidence:** #293의 병합 PR/commit URL과 SHA, alias/version-ref, checksum 검증 결과.
  - **Failure:** #293이 미병합이거나 alias/checksum이 불일치하면 여기서 PENDING으로 멈추고 catalog 이슈가 완료된 뒤 이 단계부터 다시 확인한다. #293을 이 계획에서 구현하거나 catalog tag를 만들지 않는다.
- [ ] **T0.4 — 문서 worktree를 최신 develop에서 분리**
  - **Action:** 중앙 사이트의 현재 checkout을 보존하고 `origin/develop`을 fetch한 뒤 최신 ref에서 `docs/issue-942-r2dbc-dao` worktree를 만든다. 해당 worktree의 `AGENTS.md`, workspace guide, manifest를 다시 읽는다.
  - **Evidence:** 새 worktree의 branch와 base SHA가 확인되고, 기존 stale checkout과 dependencies repo의 미관련 dirty 파일이 그대로 남아 있다.
  - **Failure:** 최신 `origin/develop`을 확인할 수 없거나 worktree 상태가 모호하면 문서 수정을 시작하지 않고 원인을 기록한다.

### Task 1 — 별도 모듈과 버전 고정 경계

- [ ] **T1.1 — Gradle 모듈의 실패 경계부터 추가**
  - **Action:** `exposed/r2dbc-dao/build.gradle.kts`를 만들고 Exposed DAO 전용 catalog alias `bt4k.exposed.dao.r2dbc`와 `:bluetape4k-exposed-core`만 직접 공개 의존성으로 선언한다. catalog alias가 포함된 #293의 원격 commit SHA를 `settings.gradle.kts` 기본 ref에 고정한다. Upstream `exposed-dao-r2dbc`가 노출하는 `org.jetbrains.exposed:exposed-core`와 `org.jetbrains.exposed:exposed-r2dbc`는 허용 전이 의존성으로 구분한다. JDBC DAO alias, `:bluetape4k-exposed-dao`, `:bluetape4k-exposed-r2dbc`는 어떤 공개 configuration에도 넣지 않는다.
  - **Evidence:** `./gradlew projects`에 `:bluetape4k-exposed-r2dbc-dao`가 한 번 나타나고 `./gradlew :bluetape4k-exposed-r2dbc-dao:dependencies --configuration compileClasspath`가 Exposed 1.6.1을 선택한다. `dependencyInsight`에서 `org.jetbrains.exposed:exposed-dao`와 Bluetape JDBC DAO는 없고, upstream R2DBC engine만 허용된 POM closure로 나타난다. `./gradlew :bluetape4k-exposed-r2dbc-dao:jar`의 JAR에 자체 `.class`가 없다.
  - **Failure:** 버전이 1.6.1이 아니거나 JDBC DAO가 그래프에 나타나면 의존성 경계를 고친 뒤 다시 확인한다. 원격 alias commit을 사용할 수 없으면 Task 0으로 돌아간다.

### Task 2 — 무소스 모듈의 publication ABI 허용 목록

- [ ] **T2.1 — 빈 ABI 허용 규칙을 회귀 테스트로 고정**
  - **Action:** `buildSrc/src/test/kotlin/ProductionAbiSupportTest.kt`에 정확한 `dependencyOnlyModules` 집합, 허용 모듈의 빈 baseline과 빈 actual dump, 허용 모듈의 비어 있지 않은 ABI, 누락·고아 파일, 허용 목록 밖의 빈 baseline/actual 사례를 추가한다. 각 유효·무효 사례에서 validator 결과를 확인한다.
  - **Evidence:** `./gradlew -p buildSrc test --tests '*ProductionAbiSupportTest'`가 모든 신규 경계 사례를 통과하며 잘못된 빈 ABI를 거부한다. `buildSrc`는 독립 Gradle build이므로 root project path task로 호출하지 않는다.
  - **Failure:** 파일 존재 여부와 바이트 내용 중 하나라도 확인하지 못하면 helper 계약을 먼저 고치고 모듈 수를 늘리지 않는다.
- [ ] **T2.2 — 허용 목록과 baseline/dump 생성을 연결**
  - **Action:** `ProductionAbiSupport.kt`, `CheckProductionAbiTask.kt`, 루트 `build.gradle.kts`의 ABI 집계를 수정해 `dependencyOnlyModules`에 등록된 이 모듈만 빈 baseline/dump를 허용한다. `api/bluetape4k-exposed-r2dbc-dao.api`를 빈 파일로 추가하고 publishable JVM 수를 45에서 46으로 갱신한다. 허용된 모듈에 공개 ABI가 생기거나 파일이 없으면 aggregate가 실패하도록 유지한다.
  - **Evidence:** `./gradlew checkProductionAbi`가 46개 inventory에서 통과한다. baseline 또는 actual dump를 임시로 제거하거나 비우기 조건을 어기면 대응 검증이 실패한다. `./gradlew :bluetape4k-exposed-r2dbc-dao:jar`에서 자체 class가 계속 없다.
  - **Failure:** allowlist를 전역 빈-baseline 허용으로 넓히거나 실제 ABI 차이를 무시하지 않는다. 위 음성 사례 중 하나라도 통과하면 Task 2.1로 돌아간다.
- [ ] **T2.3 — Kover 집계의 적용 가능성을 결정**
  - **Action:** 루트 `build.gradle.kts`의 Kover 집계와 CI report 경로를 확인한다. 무소스 모듈이 coverage denominator에 들어가면 소스 존재 조건 또는 Kover 전용 제외 조건을 사용해 제외하고, ABI 허용 목록을 coverage 정책에 재사용하지 않는다. 테스트 실행 및 JUnit XML 보존은 유지하고 새 모듈에 0% coverage report를 만들어 N/A를 숨기지 않는다.
  - **Evidence:** root Kover aggregate가 production source가 없는 모듈을 coverage denominator에 넣지 않고, 계획/검토 기록에 Kover N/A와 근거가 남는다.
  - **Failure:** 집계 제외 근거가 확인되지 않으면 N/A를 선언하지 말고 적용 방식과 수용 기준을 재검토한다.

### Task 3 — 기본 Exposed DAO 계약 테스트

- [ ] **T3.1 — `newSuspend`와 Flow 수집 테스트를 먼저 작성**
  - **Action:** `exposed/r2dbc-dao/src/test/kotlin/io/bluetape4k/exposed/r2dbc/dao/ExposedR2dbcDaoContractTest.kt`에 `@OptIn(ExperimentalR2dbcDaoApi::class)`를 적용한 최소 엔티티/테이블을 만들고, H2 `suspendTransaction()` 안에서 `newSuspend` 직후 생성 ID를 확인하는 실패 테스트를 추가한다. 이어 같은 트랜잭션에서 관계 Flow의 `toList()`와 `count()`를 수집하는 테스트를 추가한다.
  - **Evidence:** 각 새 검증을 구현 전 실행해 의도한 assertion/컴파일 실패를 확인한 뒤, 수정 후 `EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-r2dbc-dao:test --tests '*ExposedR2dbcDaoContractTest'`가 통과한다. Flow 소비는 transaction 밖에서 하지 않는다.
  - **Failure:** Flow를 반환해 transaction 밖에서 수집하거나 `new()`의 즉시 ID를 가정하면 테스트와 예제를 고친다.
- [ ] **T3.2 — transaction 간 `attach()` 예외와 force 계약 추가**
  - **Action:** 같은 테스트 fixture에서 transaction 간 `attach()` 성공, 삭제 행의 `EntityNotFoundException`, dirty cache 충돌의 `IllegalStateException`, `force = true`가 미저장 변경을 버리고 DB 값을 다시 읽는 결과를 별도 테스트로 추가한다. 교체 전 변경 값은 후속 transaction에서 다시 commit해도 DB에 나타나지 않는지 조회한다.
  - **Evidence:** H2 targeted task가 네 경로를 각각 검증하고, assertion은 예외 타입·DB 값·후속 commit 결과를 확인한다.
  - **Failure:** 예외 확인만 하고 persisted state를 조회하지 않으면 테스트를 보완한다.

### Task 4 — 트랜잭션 경합과 취소 정리

- [ ] **T4.1 — 겹치는 transaction의 상태 격리 테스트 추가**
  - **Action:** barrier를 사용하는 두 개의 독립 `suspendTransaction()`이 H2에서 겹치도록 테스트한다. 각 transaction의 DAO 변경, commit 결과, 종료 후 조회를 비교해 context와 entity/cache 상태가 섞이지 않는지 확인한다.
  - **Evidence:** 두 transaction이 실제로 겹친 event/barrier 기록과 기대한 독립 commit 상태가 남고 반복 실행에서 제한 시간 안에 끝난다.
  - **Failure:** 순차 실행으로 경합을 흉내내거나 global registry를 추가해 문제를 가리면 테스트를 다시 설계한다.
- [ ] **T4.2 — 구독·checkout 이후 취소 회귀를 고정**
  - **Action:** 기존 `exposed/r2dbc/src/test/kotlin/io/bluetape4k/exposed/r2dbc/R2dbcTransactionCancellationLifecycleTest.kt`의 connection proxy/event trace 방식을 참고한다. 관계 Flow를 실제 구독하고 크기 1의 호출자 소유 pool에서 connection checkout 및 statement 구독이 관측된 뒤 첫 행 수집의 동기화 지점에서 취소한다. `CancellationException` 전파, 미커밋 행 rollback, acquire/release event, 후속 transaction 성공, fixture와 pool의 `finally` 정리를 검증한다. barrier 대기는 제한 시간으로 감싸고, 취소 후 suspend cleanup은 취소를 삼키지 않는 resource-specific `NonCancellable` 경계에서 안전하게 끝낸다.
  - **Evidence:** H2 targeted test가 event 순서상 checkout/statement 구독 전 취소를 통과시키지 않고, 취소 뒤 같은 pool로 후속 query가 성공한다.
  - **Failure:** 취소가 삼켜지거나 커넥션이 반환되지 않거나 행이 남으면 예외를 억제하지 말고 lifecycle 원인을 고친다. Testcontainers DB는 로컬 macOS에서 Colima 상태와 Docker context를 확인한 뒤 기존 환경 규칙대로 순차 실행한다.
- [ ] **T4.3 — PostgreSQL 야간 경로에 동일 계약 연결**
  - **Action:** `.github/workflows/nightly-tests.yml`의 `test-r2dbc-postgresql` task 목록에 새 모듈 테스트를 추가한다. H2와 PostgreSQL은 workflow 내에서 순차 실행 정책을 유지하고, artifact는 JUnit XML을 보존한다.
  - **Evidence:** YAML의 scheduled/full 조건, Gradle task, `EXPOSED_TEST_DB=POSTGRESQL`, Ryuk/Docker 환경과 artifact 경로가 함께 맞는다. 로컬 실행이 가능한 경우 PostgreSQL targeted task를 H2 다음에 실행하고 결과를 기록한다.
  - **Failure:** workflow dispatch를 임의로 실행하지 않는다. 로컬 Docker 또는 외부 workflow가 없으면 정확한 검증 공백으로 표시하고 PR/merge-ready를 주장하지 않는다.

### Task 5 — 게시 좌표 기반 Kotlin 소비자 검사

- [ ] **T5.1 — staged Maven consumer fixture 작성**
  - **Action:** `scripts/publication/fixtures/r2dbc-dao-kotlin/`에 별도 Gradle consumer를 만들고 staged `bluetape4k-exposed-bom`과 게시된 `bluetape4k-exposed-r2dbc-dao` 좌표만 선언한다. opt-in이 있는 Kotlin source는 `newSuspend`, relation Flow 수집, `attach()`를 `suspendTransaction()` 안에서 컴파일하고 실행한다. 저장소 설정은 staged Bluetape4k Exposed 좌표를 임시 Maven repository에서만 받고, `io.github.bluetape4k.exposed`를 다른 원격 저장소에서 받지 않도록 제한한다. 이 staging BOM은 아직 배포되지 않은 2.2.0 후보의 version 관리 증거다. 사용자 문서는 중앙 `bluetape4k-dependencies` BOM 사용 규칙을 유지한다.
  - **Evidence:** fixture의 성공 source가 게시된 좌표를 사용하며 production module source set을 참조하지 않는다. 사용 예는 테스트 DB 자원을 소유하고 종료한다.
  - **Failure:** 프로젝트 source를 직접 연결하거나 별도 JDBC DAO 의존성을 추가하면 fixture 의존성 경계를 수정한다.
- [ ] **T5.2 — 기존 publication validator에 opt-in/graph 검증 연결**
  - **Action:** 기존 `scripts/publication/validate_downstream_consumer.rb`의 임시 Maven repository와 매 실행 새로 만드는 `gradle_user_home`을 재사용하도록 Kotlin fixture를 연결하고 `scripts/publication/validate_downstream_consumer_test.rb`에 저장소 필터·격리·진단 검사 회귀를 추가한다. opt-in source는 컴파일·실행하고 opt-in이 없는 별도 source는 컴파일 실패와 `ExperimentalR2dbcDaoApi` 진단을 확인한다. `compileClasspath`와 `runtimeClasspath`의 전체 resolved coordinate set을 게시 POM에서 기대되는 전이 그래프와 비교한다. `org.jetbrains.exposed:exposed-core`, `org.jetbrains.exposed:exposed-r2dbc` 및 해당 R2DBC SPI/coroutines closure는 upstream published POM에서 확인한 범위로 허용하고, `org.jetbrains.exposed:exposed-dao`, Bluetape JDBC DAO, 예기치 않은 component/repository는 거부한다. `exposed-dao-r2dbc:1.6.1` 선택도 확인한다. staged module의 POM/JAR SHA-256을 결과에 남기고, 저장소에 Gradle dependency verification metadata가 적용되는지 확인한다. 현재 verification metadata가 없으면 N/A 근거를 기록하고 새 전역 검증 정책을 이 기능에 임의로 도입하지 않는다. JAR에 자체 `.class`가 없고 generated POM의 API dependency가 맞는지 확인한다.
  - **Evidence:** `ruby scripts/publication/validate_downstream_consumer.rb` 및 `ruby scripts/publication/validate_downstream_consumer_test.rb`가 통과하고, 격리된 consumer의 양성·음성 compiler 결과, 전체 dependency graph, repository 선택, POM/JAR checksum이 validator output에 남는다.
  - **Failure:** ambient Gradle cache나 외부 repository에서 `io.github.bluetape4k.exposed` artifact를 resolve해 통과하거나 일반 compile 실패를 opt-in 음성 증거로 처리하지 않는다. JDBC DAO, 예상하지 못한 component, checksum/POM 불일치가 있으면 consumer 결과를 승인하지 않는다.

### Task 6 — PR CI 경로와 artifact 연결

- [ ] **T6.1 — H2 필수 작업과 변경 감지 경로 갱신**
  - **Action:** `.github/workflows/ci.yml`의 `changes` `r2dbc` path filter에 `exposed/r2dbc-dao/**`를 추가하고 `test-r2dbc-h2`가 `:bluetape4k-exposed-r2dbc-dao:test`를 포함하도록 한다. `ci-status`가 기존처럼 `test-r2dbc-h2`를 요구하는지 확인한다. 별도 `test-results-r2dbc-dao` artifact에 새 모듈의 JUnit XML을 보존한다. 무소스 Kover는 Task 2.3의 N/A 규칙에 따른다.
  - **Evidence:** workflow parser/linter와 `git diff --check`가 통과하고, changed path, Gradle task, 필수 status dependency, XML glob가 모두 새 모듈을 가리킨다.
  - **Failure:** 경로 감지만 하고 task가 빠지거나 job이 `ci-status`에서 optional이면 필수 연결이 완료되지 않은 것으로 처리한다.
- [ ] **T6.2 — module publication inventory와 consumer 산출물 확인**
  - **Action:** `./gradlew projects`, `./gradlew exportPublicationInventory`, `./gradlew checkProductionAbi`, `ruby scripts/publication/validate_downstream_consumer.rb`를 실행한다. central catalog 변경은 별도 Task 8에서 처리해 기존 dependencies checkout과 catalog authority를 섞지 않는다.
  - **Evidence:** 새 Gradle module 1개, publishable ABI module 총 46개, Exposed BOM publication 좌표 1개, staged Kotlin consumer 결과가 일치한다.
  - **Failure:** inventory에 module이나 BOM publication이 중복·누락되면 Task 1/2로 돌아가 경계를 고친다.

### Task 7 — 사용자 문서, release handoff, 기록

- [ ] **T7.1 — 모듈 README와 변경 로그 작성**
  - **Action:** `exposed/r2dbc-dao/README.md`와 `README.ko.md`에 BOM 의존 선언, Exposed opt-in, transaction 안의 `newSuspend`/Flow 예제, transaction 간 `attach()`, 지원/미지원 DAO 조합, 실험 API 업그레이드 위험, 호출자 소유 pool/종료 및 취소·rollback 관측 책임을 각각 자연스러운 해당 언어로 쓴다. `README.md`와 `README.ko.md`의 모듈 표에 새 artifact 링크를 추가하고 `CHANGELOG.md`의 `[Unreleased] / 추가` 및 `WIP.md`에 #942 진행 범위를 기록한다. 자체 공개 wrapper가 없으므로 KDoc 추가는 N/A로 증거를 적는다.
  - **Evidence:** 예제의 API가 H2 fixture에서 컴파일되고, URL/비밀번호/SQL/tenant ID 원문을 기록하지 않으며 README 두 언어의 사실·경고·의존성 선언이 일치한다.
  - **Failure:** Exposed experimental API를 안정 API처럼 표현하거나 개별 bluetape4k artifact 버전을 BOM 예제에 적으면 문서를 수정한다.
- [ ] **T7.2 — central manual EN/KR와 inventory 등록**
  - **Action:** 최신 docs worktree에서 `manifest.yaml`에 새 module row를 추가하고 `en/modules/bluetape4k-exposed-r2dbc-dao.md`, `ko/modules/bluetape4k-exposed-r2dbc-dao.md`, 양쪽 `index.md`에 같은 artifact/제약 정보를 추가한다. `releaseRef: 2.0.0`과 그 `releaseCommit`은 바꾸지 않는다. Exposed repo에서 `./gradlew exportManualModuleInventory`를 실행한 후 `ruby "$TOOL_ROOT/validate_manuals.rb" --code-root "$EXPOSED_REPO_ROOT" --manual-root "$MANUAL_ROOT" --inventory "$EXPOSED_REPO_ROOT/build/manual/module-inventory-2.2.0.json" --manifest "$MANUAL_ROOT/manifest.yaml"`를 실행한다. `TOOL_ROOT`와 `MANUAL_ROOT`는 새 docs worktree 안의 `scripts/manual/repositories/bluetape4k-exposed` 및 `docs/manual/bluetape4k-exposed` 경로다. release 후 정확한 tag/commit으로 안정 문서를 갱신할 handoff만 기록하며 `validate_release_manuals.rb`는 출시 승인 뒤에만 실행한다.
  - **Evidence:** EN/KR route와 manifest locale entry가 모두 존재하고 생성 inventory가 Gradle module/source/test 경로와 일치한다. `validate_manuals.rb`가 통과하고 manifest의 stable ref/commit은 기존 2.0.0 pin을 유지한다.
  - **Failure:** 미출시 feature branch SHA를 `releaseRef`/`releaseCommit`에 사용하거나 stale site checkout을 수정하지 않는다. unreleased module을 현재 manifest에 표현할 수 없는 validator 제약이 확인되면 stable pin을 바꾸지 말고 사용자 검토용으로 남긴다.
- [ ] **T7.3 — central manual 변경을 검증·기록**
  - **Action:** EN/KR manifest·manual·landing 변경을 해당 repository의 Korean Lore commit으로 기록한다. commit 전 `git diff --check`, manifest/inventory traceability, stable ref 보존과 문서 validator 결과를 다시 읽는다.
  - **Evidence:** central-site commit SHA와 포함 파일, validator 출력, `releaseRef: 2.0.0` 및 기존 `releaseCommit`이 보존됨.
  - **Failure:** stale site checkout이나 출시 전 feature ref를 사용하지 않는다. manifest validator가 미출시 모듈 등록을 막으면 stable pin을 바꾸지 말고 PENDING 사유로 기록한다.

### Task 8 — 로컬 검증, feature commit과 catalog 동기화

- [ ] **T8.1 — 로컬 구현 검증을 순서대로 실행**
  - **Action:** `./gradlew -p buildSrc test --tests '*ProductionAbiSupportTest'`, `EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-r2dbc-dao:test`, `./gradlew checkProductionAbi`, consumer validator와 Ruby test, `./gradlew :bluetape4k-exposed-r2dbc-dao:jar`, `./gradlew exportPublicationInventory`, `./gradlew detekt`를 순서대로 실행한다. Testcontainers backend는 H2 다음 `EXPOSED_TEST_DB=POSTGRESQL ./gradlew :bluetape4k-exposed-r2dbc-dao:test`만 지원 경로에 따라 직렬 검증하고, CI의 secret scan은 `gitleaks detect --source . --redact --no-git --config .gitleaks.toml` 결과를 확인한다. `git diff --check`와 한국어 용어 audit도 실행한다.
  - **Evidence:** 모든 명령의 종료 코드와 테스트/XML/ABI/POM/소비자 그래프/gitleaks 결과가 현재 Exposed commit에 귀속된다. 로컬 PostgreSQL을 실행할 수 없으면 정확한 환경 공백과 해당 nightly workflow 확인 기준을 기록한다. retry-green, CI 미실행과 skipped job은 별도 분류한다.
  - **Failure:** 실패나 미실행을 green 증거로 바꾸지 않는다. 원인을 고친 뒤 영향을 받은 검증을 다시 실행한다. MySQL은 R2DBC DAO의 현재 PR/nightly 지원 matrix에 없으므로 임의로 요구하지 않는다.
- [ ] **T8.2 — 검증된 Exposed 구현을 Lore commit**
  - **Action:** 테스트와 정적 검증을 통과한 Exposed repository 변경만 현재 feature branch에 한국어 Lore commit으로 기록한다. commit에는 spec/plan 이후의 구현 파일을 포함하고 central-site/dependencies 파일이나 user-owned 변경을 섞지 않는다.
  - **Evidence:** commit SHA, `git show --stat --oneline HEAD`, clean task worktree와 issue #942/acceptance traceability.
  - **Failure:** 실패한 검증을 포함하거나 task 범위 밖 파일이 섞이면 commit하지 않고 범위를 바로잡는다. push/PR은 하지 않는다.
- [ ] **T8.3 — managed catalog를 독립 dependencies worktree에서 동기화**
  - **Action:** 기존 dirty dependencies checkout을 보존하고 최신 `origin/develop`에서 별도 `feat/issue-942-r2dbc-dao-catalog` worktree를 만든 뒤 그 worktree의 `AGENTS.md`와 적용되는 workspace guide를 읽는다. Exposed feature commit을 포함하는 strict repository-map v1을 canonical `/private/tmp/issue-942-catalog-repository-map.json`에 준비하고 `CATALOG_REPOSITORY_MAP`으로 지정한다. map은 central 항목과 정확한 canonical 9개 managed repository entry, 절대 canonical root/catalog path, pinned HEAD/base, `clean: true`를 만족해야 하며 모든 worktree를 담는 workspace root는 `/Users/debop`으로 지정한다. 기존 canonical repo가 dirty면 원본을 수정하지 않고 해당 entry에만 clean pinned worktree를 사용한다. `python3 scripts/sync-managed-catalog.py --workspace-root /Users/debop --repository-map "$CATALOG_REPOSITORY_MAP" --write --check --summary`를 실행하고 portable sidecar를 저장소 형식에 맞게 갱신한다. `python3 -m unittest tests/test_sync_managed_catalog.py tests/test_catalog_checksum.py tests/test_central_catalog_version_deltas.py tests/test_ci_catalog_governance.py`와 `./gradlew build`를 실행한다. 실제 generated diff가 있을 때만 dependencies worktree에 한국어 Lore commit을 만들고, diff가 없다면 기존 alias가 있다는 증거를 기록한다.
  - **Evidence:** strict map SHA와 각 entry의 origin/branch/base/HEAD, generated alias와 BOM constraint diff, sync/check/test/build 결과, `gradle/libs.versions.toml.sha256`, dependencies commit SHA.
  - **Failure:** helper 인자는 `--workspace-root`를 사용한다. `--workspace`로 managed-catalog helper를 실행하지 않는다. map validation이 실패하거나 기존 checkout의 user-owned 변경을 요구하면 쓰기를 중단하고 원인을 기록한다. alias/sidecar/checksum이 맞지 않으면 다음 단계로 진행하지 않는다.
- [ ] **T8.4 — managed catalog와 downstream alias 결과 확인**
  - **Action:** T8.3 dependencies commit에서 generated `bluetape4k-exposed-r2dbc-dao` catalog alias와 Exposed BOM import가 존재하는지 확인한다. Exposed build의 `settings.gradle.kts`는 외부 `exposed-dao-r2dbc` alias를 제공한 #293 merge SHA에 계속 고정하고, downstream Bluetape4k module alias를 얻으려고 ref를 바꾸지 않는다.
  - **Evidence:** dependencies `libs.versions.toml` generated block, `build.gradle.kts` Exposed BOM platform dependency, helper tests/build 결과와 Exposed `settings.gradle.kts`의 #293 immutable ref가 서로 다른 책임을 유지한다.
  - **Failure:** generated module alias가 없으면 catalog sync를 재검토한다. 외부 Exposed alias의 authority를 새 downstream artifact alias로 혼동하지 않는다.
- [ ] **T8.5 — Dependabot drift를 필요한 경우에만 반영**
  - **Action:** 새 strict repository map에서 T8.3 dependencies commit과 T8.2 Exposed commit을 고정하고 `python3 scripts/sync-dependabot-ignores.py --workspace /Users/debop --repository-map "$CATALOG_REPOSITORY_MAP" --repo bluetape4k-exposed --check --summary`를 실행한다. helper가 실제 Exposed ignore drift를 보고할 때만 fresh map을 다시 만들고 `--write --check --summary`를 수행한 뒤 generated Exposed 파일만 별도 Lore commit으로 기록한다.
  - **Evidence:** map의 exact heads와 check 결과 또는 drift가 없는 구체적 N/A 근거; drift가 있으면 generated diff, helper 결과, commit SHA.
  - **Failure:** 다른 저장소의 unrelated Dependabot 파일을 함께 고치지 않는다. `sync-dependabot-ignores.py`의 `--workspace` 옵션만 사용하고 managed-catalog 옵션과 혼동하지 않는다.

### Task 9 — exact-head 검증·7-Tier 리뷰와 delivery 경계

- [ ] **T9.1 — 최종 catalog head에서 검증 반복**
  - **Action:** T8.5 이후의 최종 Exposed HEAD에서 H2 DAO tests, buildSrc ABI validator, `checkProductionAbi`, staged consumer positive/negative compile, full dependency graph/POM/JAR checksum, publication inventory, `detekt`, `gitleaks`와 `git diff --check`를 다시 실행한다. PostgreSQL은 H2 뒤 순차 실행 또는 정확한 nightly CI artifact로 확인한다.
  - **Evidence:** 결과별 exact Exposed commit SHA와 #293 catalog SHA, 테스트/XML/ABI/consumer/secret-scan 결과. PostgreSQL 미실행은 명시적으로 PENDING 처리한다.
  - **Failure:** T8.5 이후의 버전 선택을 이전 결과로 추정하지 않는다. 실패하거나 skipped된 필수 job이 있으면 수정 후 영향 범위를 재검증한다.
- [ ] **T9.2 — 독립 7-Tier 구현 diff 리뷰와 main 통합**
  - **Action:** 최종 Exposed diff의 정확한 HEAD에서 성능, 안정성, 보안, 운영, 개발자/API, 사용자/호출자 관점을 독립 검토하고 main session이 문서·release·증거, 중복, 심각도를 통합한다. 독립 리뷰 호스트가 unavailable이면 이를 PASS로 기록하지 않고 workflow의 inline fallback으로 해당 관점을 검토한다. Testcontainers/실제 DB 검증은 순차 실행한다. P0/P1을 모두 수정하고 영향받은 관점과 통합 리뷰를 재실행한다. 새 follow-up은 같은 repository·2.2.0 milestone에서 기존 중복 이슈, labels, `debop` assignee를 확인한 뒤 기존 이슈에 연결하고, novel finding일 때만 한국어 이슈를 등록한다.
  - **Evidence:** 최신 통합표에 P0=0/P1=0, P2/P3 disposition, 정확한 local HEAD와 실행된 CI/테스트 범위, writer gate 결과가 남는다. single-developer lane human-review N/A가 적용돼도 별도 코드 리뷰와 정확한 HEAD 검증은 유지한다. PR이 없는 계획 범위에서는 live PR thread/remote CI를 완료 증거로 주장하지 않는다.
  - **Failure:** 어떤 P0/P1, stale head, 필요한 artifact 누락이 남으면 완료를 주장하지 않는다. 독립 검토 호스트 오류는 실제 실패 증거와 fallback 표기를 남기며 independent PASS로 바꾸지 않는다.
- [ ] **T9.3 — 구현 완료 보고 뒤 delivery gate에서 정지**
  - **Action:** 구현 branch와 변경 파일, 테스트/정적 분석/7-Tier 결과, live issue 상태, 미해결 공백을 보고한다. 다음 단계의 PR 생성·push·merge/publication은 별도 권한을 기다린다.
  - **Evidence:** 전체 DoD와 미완료 checklist item을 수치로 대조해 `DONE` 또는 `PENDING`을 구분한다.
  - **Failure:** 미확인 또는 권한 없는 외부 작업을 추정해 실행하지 않는다.

## 수용 기준 추적표

| 설계 기준 | 구현 단계 | 필요한 최종 증거 |
|---|---|---|
| AC-1 catalog alias, 1.6.1, immutable SHA/checksum | T0.3, T1.1, T8.3–T8.4 | 병합된 #293 SHA, generated module alias, alias/version 선택, catalog checksum 및 dependency insight |
| AC-2 module 공개 의존성 및 JDBC 격리 | T1.1, T5.2, T9.1 | compile/runtime graph와 POM에서 DAO-R2DBC closure만 있고 JDBC DAO가 없음 |
| AC-3 `newSuspend`, transaction 내 relation Flow | T3.1 | ID 즉시성, `toList()`/`count()` H2 assertion |
| AC-4 `attach()` 예외·force 상태 | T3.2 | 성공/삭제/dirty-cache/force와 후속 DB commit 결과 |
| AC-5 transaction 동시성, 취소·rollback, connection reuse | T4.1–T4.3 | H2 event trace 및 PostgreSQL 야간 task 결과 |
| AC-6 게시 소비자 opt-in 양성·음성·graph | T5.1–T5.2 | staged Maven Kotlin compile/run, 예상 진단, resolved graph |
| AC-7 46개 ABI inventory, 빈 ABI fail-closed | T2.1–T2.2, T6.2, T9.1 | validator 단위/음성 결과, 빈 baseline/dump, JAR class 부재 |
| AC-8 CI path/job/status/XML/Kover | T2.3, T4.3, T6.1 | PR H2 필수 연결, PostgreSQL nightly, 별도 XML artifact, Kover N/A/집계 제외 근거 |
| AC-9 README와 호출자 경계 | T7.1 | EN/KR 파일, 검증된 예제, API KDoc N/A 근거 |
| AC-10 central manual EN/KR/manifest/inventory/release ref | T0.4, T7.2–T7.3 | 최신 docs worktree, `validate_manuals.rb` 결과, 2.0.0 stable ref 보존 |
| AC-11 지원/미지원 조합 및 #954 후속 | T7.1–T7.2, T9.2 | 양언어 경고, live #954 링크 및 신규 리뷰 이슈 중복 확인 |

## 위험, 신호, 복구 지점

| 위험 | 조기 신호 | 대응 및 재실행 |
|---|---|---|
| #293 alias가 Exposed build가 고정한 catalog SHA에 없음 | alias lookup 실패 또는 checksum/ref 불일치 | T0.1로 돌아가 이슈의 병합 commit과 portable sidecar를 재확인하고, 의존성 관련 검증 전부 재실행 |
| managed catalog repository-map의 저장소가 dirty/ambiguous | strict map 검증에서 예상 HEAD·clean 상태·origin 불일치 | 원본 checkout을 수정하지 않고 해당 항목만 clean pinned worktree로 교체한다. 아홉 저장소 지도를 통과하지 못하면 catalog write를 시작하지 않는다 |
| 새 모듈에 JDBC DAO가 전이됨 | dependencyInsight/POM에서 `exposed-dao` 또는 Bluetape DAO 발견 | T1.1 dependency edge 수정 후 consumer graph, POM, JAR 검증 재실행 |
| staged consumer가 ambient cache나 다른 저장소에서 Bluetape artifact를 resolve함 | isolated `gradle_user_home`, repository filter, component graph 또는 checksum이 기대와 다름 | T5.2 repository filter를 좁히고 fresh Gradle home에서 positive/negative consumer를 다시 실행 |
| 빈 ABI가 새 API 추가를 숨김 | 빈 baseline인데 actual API가 존재하거나 허용 목록이 여러 모듈을 덮음 | T2.1–T2.2 helper/fixture 음성 테스트를 먼저 고치고 ABI aggregate 전체 재실행 |
| Flow 취소 테스트가 실제 DB 작업 전 종료됨 | trace에 connection checkout/statement subscription 없음 | T4.2 barrier 위치를 옮기고 H2 event ordering과 pool 재사용 테스트부터 재실행 |
| PostgreSQL/Testcontainers가 환경 오류로 미실행 | Docker/Colima 상태 또는 nightly artifact 부재 | 환경을 복구할 수 있는지 확인하고 sequential PostgreSQL 검증을 재실행. 불가하면 PENDING으로 보고 |
| opt-in 음성 검사가 다른 Gradle 오류로 실패 | 실패 출력에 experimental diagnostic이 없음 | T5.2에서 failure classifier를 강화하고 긍정·부정 consumer를 각각 다시 실행 |
| 매뉴얼 validator가 미출시 모듈과 stable release pin을 함께 허용하지 않음 | `validate_manuals.rb`가 inventory/ref 불일치를 보고 | T7.2에서 unreleased manifest 표현 조건을 검토하되 `releaseRef`/`releaseCommit`은 유지하고 해소되지 않으면 PENDING으로 보고 |
| CI에서 새 경로가 감지되지 않거나 필수 task가 생략됨 | 변경 파일은 있는데 `test-r2dbc-h2`가 skipped | T6.1 workflow 계약을 수정한 후 path filter와 `ci-status` 의존성을 재검증 |

## 실행 기술·문서 게이트

- 구현 전에 `$bluetape-kotlin-patterns`, `$ecc-kotlin-exposed`, `$ecc-kotlin-testing`, `$kotlin-coroutines-skill`, `$test-driven-development`를 해당 코드/test 단계에서 다시 읽는다.
- 문서 단계는 `$bluetape-maintenance`와 `$bluetape-writer`를 사용하고, 새 README/central manual에 SPW-01..05를 각각 적용한다. 한국어 용어 audit과 자연스러움 checklist를 완료한다.
- Superpowers spec 및 본 계획의 SPW-01..05는 각각 별도 증거로 통과시킨다. 통합 리뷰 문장도 별도 writer gate를 완료한다.
- public wrapper나 production Kotlin class를 만들지 않으므로 새 API KDoc은 N/A다. 새 production class가 필요해지면 승인된 범위 변경과 KDoc/ABI 기준을 먼저 추가한다.
- 수용 기준 및 이 계획이 승인되기 전에는 코드·테스트·CI·README·central site를 수정하지 않는다.

## 구현 계획 Writer gate

- SPW-01 — PASS: #942, 별도 모듈 선택, #293 차단 조건, #954 비차단 범위, 저장소별 작업 공간, delivery 승인 경계를 적었다.
- SPW-02 — PASS: T0–T9의 선후 관계, AC-1..11 추적표, 실패 시 중단점, dependencies/docs 별도 worktree와 catalog handoff를 대조했다. #293이 열린 동안 구현을 막는다.
- SPW-03 — PASS: 한국어 자연스러움 checklist와 contextual terminology audit을 실행하고 결과를 반영했다. 기술 식별자·경로·명령·공식 이름은 유지했다.
- SPW-04 — PASS: 계획의 Gradle, ABI, CI, publication validator, central manual과 catalog 항목을 현재 저장소 기준 및 Exposed 1.6.1 고정 소스와 맞췄다. #942/#293 live 상태는 이 문서 검토 중 다시 읽었다.
- SPW-05 — PASS: 최종 Markdown 전체, 상대 링크, 표, 체크 항목, 명령과 AC 참조를 다시 읽고 whitespace 검증을 마쳤다.

## 구현 계획 검토 기록

| 우선순위 | 관점 | 근거와 판정 | 후속 조치 |
|---|---|---|---|
| 독립 lane | 관점 | 독립 결과와 현재 세션의 계획 검토 | 통합 판정 및 남은 조치 |
|---|---|---|---|
| BLOCKED — host executable 누락 | 성능 | `/opt/homebrew/Caskroom/codex/0.171.0/bin/codex-code-mode-host`가 없어 독립 리뷰가 시작되지 않았다. 현재 계획은 의존성 재노출만 하며 새 런타임 경로나 처리량 SLA를 추가하지 않는다. | inline 검토: 별도 성능 benchmark는 범위 밖. 실제 의존성 해석과 게시 크기는 consumer/POM 검증에 포함한다. 독립 PASS로 간주하지 않는다. |
| BLOCKED — host executable 누락 | 안정성 | 독립 reviewer를 실행하지 못했다. | inline 검토: 두 transaction barrier, checkout·statement 구독 뒤 bounded 취소, 취소 전파·rollback·acquire/release trace·동일 pool 재사용, resource-specific cleanup, H2 뒤 PostgreSQL 직렬 실행이 계획에 있다. 구현 때 실제 DB 작업 전에 취소되는 거짓 양성을 막는 trace를 확인한다. |
| BLOCKED — host executable 누락 | 보안 | 독립 reviewer를 실행하지 못했다. | inline 검토: 임시 Maven repository, 새 Gradle user home, 저장소 필터, 전체 dependency graph, staged POM/JAR SHA-256, opt-in 음성 경로와 gitleaks를 포함한다. verification metadata 적용 여부는 구현 전 확인하며 없으면 근거를 남긴다. |
| BLOCKED — host executable 누락 | 운영 | 독립 reviewer를 실행하지 못했다. | inline 검토: 열린 #293을 명시적 blocker로 두고 dirty canonical checkout을 보존하며 clean pinned worktree와 strict map만 쓴다. PostgreSQL nightly 및 미출시 central manual의 stable release ref handoff를 분리했다. |
| BLOCKED — host executable 누락 | 개발자/API | 독립 reviewer를 실행하지 못했다. | inline 검토: 허용 전이 closure와 금지된 Exposed/Bluetape JDBC DAO를 구분하고, 무소스 ABI의 정확한 allowlist·빈 파일·음성 테스트·consumer opt-in 검사를 추적한다. |
| BLOCKED — host executable 누락 | 사용자/호출자 | 독립 writer lane을 실행하지 못했다. | inline 검토: EN/KR README 및 중앙 매뉴얼에 BOM, experimental opt-in, transaction 범위, `attach()`, 조합 제약, pool 종료 책임을 요구한다. |
| 해당 없음 — 통합은 main 수행 | 통합 | 독립 lane 결과를 독립 증거로 포장하지 않고 위 여섯 관점의 계획 검토를 이 세션에서 통합했다. | 계획 수준 P0=0/P1=0. 미해결 P2인 기존 helper와 새 DAO 조합은 #954로 추적하며 #942를 차단하지 않는다. 구현 승인 후에는 실제 diff의 별도 코드 리뷰가 필요하다. |

**계획 상태:** 리뷰 완료, 사용자 승인 대기. 독립 7-Tier 실행은 host executable 누락으로 수행되지 않았고, 위 표는 독립 PASS가 아닌 현재 세션의 계획 수준 inline 검토를 기록한다. 계획 수준에서 미해결 P0/P1은 없다. 사용자가 이 구체적 계획을 승인하고 #293 alias/checksum 선행 조건이 충족되기 전에는 Task 1 이후 구현에 들어가지 않는다.
