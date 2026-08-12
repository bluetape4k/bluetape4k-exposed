# R2DBC `transactionManagerRef` 계약 정렬 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** R2DBC 저장소의 오해 가능한 `transactionManagerRef` 설정을 ABI 호환 방식으로 정렬하고, 잘못된 custom 값은 등록 단계에서 거부한다.

**Architecture:** R2DBC repository factory의 직접 `suspendTransaction` 경로와 애플리케이션 소유 `R2dbcDatabase` 경계를 유지한다. 공개 annotation 속성은 deprecated 상태로 보존하고 configuration extension의 `postProcess`에서 비기본값을 조기 거부한다. 다중 DB 선택은 기존 명시적 `suspendTransaction(database)`와 `streamAll(database)` API로만 수행한다.

**Tech Stack:** Kotlin 2.4, Spring Data Repository configuration, Spring Boot 4, Exposed R2DBC, JUnit 5, Kluent, Gradle.

---

## 실행 순서와 파일 책임

### Task 1: 설계·범위 고정

**Files:**

- Reference: `docs/superpowers/specs/2026-08-12-issue-637-r2dbc-transaction-manager-contract-design.md`
- Issue: GitHub `#637`

- [x] **Step 1: 현재 실행 경계를 확인한다**

  `ExposedR2dbcRepositoryFactoryBean`, `ExposedR2dbcRepositoryFactory`,
  `SimpleExposedR2dbcRepository`, `streamAll(database)` 및 두 README의
  transaction scope를 확인한다.

- [x] **Step 2: 선택하지 않은 대안을 기록한다**

  Spring transaction manager/R2dbcDatabase 브리지와 즉시 속성 제거는 각각
  리소스 소유권 확장과 breaking ABI를 유발하므로 이번 작업에서 제외한다.

### Task 2: RED 회귀 테스트 작성

**Files:**

- Create: `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/config/ExposedSuspendRepositoryConfigurationExtensionTest.kt`

- [x] **Step 1: custom 값의 등록 거부를 표현한다**

  `AnnotationRepositoryConfigurationSource`를 구성해 extension의 `postProcess`
  가 custom `transactionManagerRef`에 `IllegalArgumentException`을 던지는지
  검증한다.

- [x] **Step 2: 실제 registrar 경로를 검증한다**

  `ExposedR2dbcRepositoriesRegistrar.registerBeanDefinitions`를 호출해 factory
  등록 전에 custom 값이 거부되는지 검증한다.

- [x] **Step 3: ABI 기본값을 고정한다**

  Java reflection으로 annotation method의 default가
  `springTransactionManager`인지 검증하고, default source가 정상 통과하는지
  확인한다.

- [x] **Step 4: RED를 관찰한다**

  실행:

  ```bash
  ./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test \
    --tests 'io.bluetape4k.spring.data.exposed.r2dbc.config.ExposedSuspendRepositoryConfigurationExtensionTest' \
    --rerun-tasks --no-configuration-cache --console=plain
  ```

  구현 전 결과는 custom 값에 대한 `Expected IllegalArgumentException but no
  exception was thrown`이어야 한다.

### Task 3: 최소 production 구현

**Files:**

- Modify: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/config/EnableExposedR2dbcRepositories.kt`
- Modify: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/config/ExposedSuspendRepositoryConfigurationExtension.kt`

- [x] **Step 1: annotation ABI를 유지한다**

  `transactionManagerRef`의 이름과 `springTransactionManager` 기본값을 유지하고
  Korean KDoc 및 `@Deprecated`를 추가한다.

- [x] **Step 2: 등록 경계를 고정한다**

  `postProcess`에서 기본값만 허용하고 custom 값은 explicit database API를
  안내하는 `IllegalArgumentException`으로 거부한다.

- [x] **Step 3: GREEN을 관찰한다**

  Task 2의 동일 명령으로 4개 테스트가 모두 통과해야 한다.

### Task 4: README locale parity 반영

**Files:**

- Modify: `spring-boot/r2dbc/README.md`
- Modify: `spring-boot/r2dbc/README.ko.md`

- [x] **Step 1: EN transaction scope를 정렬한다**

  deprecated 속성은 선택 기능이 아니며 custom 값이 등록 단계에서 거부되고,
  다중 DB는 `suspendTransaction(database)` 또는 `streamAll(database)`로
  선택한다는 내용을 추가한다.

- [x] **Step 2: KO transaction scope를 동일 의미로 정렬한다**

  API·식별자·코드 예시는 보존하고 EN과 동작·경계·마이그레이션 설명을 맞춘다.

- [x] **Step 3: release manual 범위를 보존한다**

  `docs/manual/**`의 1.12.1 고정 문서는 변경하지 않는다.

### Task 5: 회귀·정적 분석·API 검증

**Files:**

- Test: `spring-boot/r2dbc/src/test/**`
- Check: repository Gradle tasks and API/ABI validation configuration

- [ ] **Step 1: 대상 테스트를 재실행한다**

  Task 2의 targeted test를 fresh run으로 실행하고 4/4 PASS를 확인한다.

- [ ] **Step 2: 대상 모듈 전체 테스트를 실행한다**

  ```bash
  ./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test \
    --rerun-tasks --no-configuration-cache --console=plain
  ```

  기존 repository, Flow, cancellation 테스트를 포함해 exit code 0이어야 한다.

- [ ] **Step 3: Detekt와 API/ABI 검사를 실행한다**

  repository에 정의된 `detekt`와 API/ABI validation task를 확인해 실행한다.
  새 public symbol이나 annotation default 변경이 없어야 한다.

- [ ] **Step 4: 문서·diff 검사를 실행한다**

  `git diff --check`와 EN/KO README parity 검색을 실행한다. manual 1.12.1
  파일은 diff에 없어야 한다.

### Task 6: 최종 workflow DoD 및 lesson

**Files:**

- Create: `docs/superpowers/lessons/2026-08-12-issue-637-r2dbc-transaction-manager-contract.md`
- Workflow evidence: `.bluetape/runs/20260812T094815Z-7f92ca56/`

- [ ] **Step 1: six-perspective pre-PR review를 수렴한다**

  performance, stability, security, operator, developer/API, user/caller 관점과
  main integration을 현재 diff에 적용하고 P0/P1이 0인지 확인한다.

- [ ] **Step 2: lesson을 기록한다**

  `transactionManagerRef`처럼 ABI에 남은 설정이 실행 경계와 분리될 때 등록
  단계 fail-fast와 explicit API 문서화를 함께 요구한다는 재사용 가능한 guard를
  기록한다.

- [ ] **Step 3: workflow receipt를 닫는다**

  `check-result`, `component-evidence`, `lane-complete`, `completion-check`,
  `complete` 순으로 fresh 결과와 변경 경계를 기록한다.

### Task 7: PR 이후 별도 게이트

- [ ] **Step 1: PR 권한을 확인한다**

  구현 DoD와 PR 생성 권한을 분리해 확인하고, 승인된 repo/base/head가 없으면
  merge-ready report에서 대기한다.

- [ ] **Step 2: CI·merge·sync·cleanup을 별도 실행한다**

  PR exact head와 job-level CI를 검증한 뒤 fresh merge approval이 있을 때만
  merge하고, develop sync와 feature worktree/branch cleanup을 수행한다.

## 롤백 및 재실행

동작 변경은 configuration extension의 단일 `require`와 문서/KDoc으로 제한된다.
문제 발생 시 해당 두 Kotlin 파일과 README 두 파일 및 테스트를 revert하고,
targeted test → module test 순서로 다시 실행한다. `docs/manual/**`와 다른
모듈은 롤백 대상이 아니다.

## Traceability

| 수용 기준 | 계획 단계 | 검증 |
| --- | --- | --- |
| ABI/default 유지 | Task 2, 3 | reflection test, API/ABI task |
| custom 값 조기 거부 | Task 2, 3 | direct extension + registrar test |
| explicit multi-DB API 문서화 | Task 4 | EN/KO parity search |
| 기존 실행 경계 보존 | Task 3, 5 | module regression tests |
| 1.12.1 manual 보존 | Task 4, 5 | manual path diff check |

## Writer gate

- `SPW-01`: PASS — 계획 대상, source/test/docs 경로, 승인 범위와 외부 side effect를
  고정했다.
- `SPW-02`: PASS — dependency order, exact files, commands, expected evidence,
  rollback, PR/merge gates를 포함했다.
- `SPW-03`: PASS — 한국어 technical register와 코드 토큰 보존을 확인했다.
- `SPW-04`: PASS — spec의 acceptance 기준마다 Task/검증을 매핑했다.
- `SPW-05`: PASS — Markdown read-back에서 checklist, code fence, 표, 경로를
  확인했다.
