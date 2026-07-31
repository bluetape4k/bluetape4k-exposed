# Issue 415 Spring Boot 활성화 시각 해설서 구현 계획

> **에이전트 작업자용:** 필수 하위 기술: superpowers:subagent-driven-development 사용(권장) 또는 superpowers:executing-plans 사용. 단계는 추적을 위해 체크박스(`- [ ]`) 구문을 사용한다.

**목표:** Spring Data JPA의 자동 구성 기대와 Exposed의 명시적 활성화 계약을 비교하고, JDBC와 R2DBC의 조건·소유권·실패 진단을 실제 소스와 테스트에 연결한 영어·한국어 독립 HTML을 제공한다.

**아키텍처:** `docs/visual-companions/data/spring-boot-exposed-activation.json`을 조건, 시나리오, 책임, 레시피, 진단, 근거를 담은 단일 구조화 원본으로 확장한다. 공용 generator는 이 모델에서 한·영 HTML과 DOM 기반 sequence diagram을 만들고, 기존 JDBC/R2DBC Architecture Diagram은 해시로 고정해 내장한다. 사용자 시각 승인 전에는 manifest를 `pending-review/private`로 유지하고, 승인 후에만 정적 fallback과 `approved/public` 상태를 기록한다.

**기술 스택:** Node.js ESM, JSON, 독립 실행형 HTML/CSS/JavaScript, Chrome DevTools Protocol, 기존 visual companion validator/capture 도구

---

## 파일별 책임 구조

| 파일 | 책임 |
|---|---|
| `docs/visual-companions/data/spring-boot-exposed-activation.json` | 한·영 문구, 7개 구성 preset, sequence message, 빈 소유권, recipe, failure, source ledger |
| `scripts/visual-companions/lib/model.mjs` | activation 모델의 ID, locale, source/test, scenario 구조 검증 |
| `scripts/visual-companions/lib/render.mjs` | activation 전용 비교, preset, sequence, matrix, recipe, failure, evidence HTML 생성 |
| `scripts/visual-companions/capture.mjs` | activation interaction, keyboard, responsive, theme, deterministic capture 검증 |
| `scripts/visual-companions/validate.mjs` | 한·영 구조 동등성, source ledger, manifest 공개 상태, reader-facing surface 검증 |
| `tests/visual-companions/build.test.mjs` | 모델에서 한·영 HTML이 결정적으로 생성되는지 검증 |
| `tests/visual-companions/validator.test.mjs` | 누락된 activation 구조와 잘못된 공개 상태를 거부하는 회귀 테스트 |
| `docs/visual-companions/en/spring-boot-exposed-activation.html` | 생성된 영어 독립 HTML |
| `docs/visual-companions/ko/spring-boot-exposed-activation.html` | 생성된 한국어 독립 HTML |
| `docs/visual-companions/assets/spring-boot-exposed-activation.*.png` | 승인 후 생성하는 한·영 × 밝은/어두운 테마 fallback |
| `docs/visual-companions/manifest.json` | 사용자 승인 전 private, 승인 후 approved/public 및 fallback 경로 등록 |

## 고정된 사실과 표현 경계

- JDBC auto-configuration은 `EntityClass`가 classpath에 있을 때 `ExposedMappingContext`를 제공한다.
- `DataSource`가 있고 이름이 `springTransactionManager`인 빈이 없을 때만 `SpringTransactionManager`를 만든다.
- `@EnableExposedJdbcRepositories`는 registrar와 JDBC auto-configuration을 import하며, `transactionManagerRef`를 JDBC repository factory에 전달한다.
- R2DBC auto-configuration은 기존 `ExposedMappingContext`가 없을 때만 같은 mapping context를 제공한다.
- `@EnableExposedR2dbcRepositories`는 registrar를 import한다. `transactionManagerRef` 속성이 선언되어 있지만 R2DBC factory는 Spring transaction interceptor를 사용하지 않으므로 이를 Spring reactive transaction manager 연결로 설명하지 않는다.
- R2DBC 모듈은 `ConnectionPool`, `R2dbcDatabase`, dispatcher, 종료 수명주기, Spring `ReactiveTransactionManager`를 생성하지 않는다.
- Architecture Diagram은 `docs/manual/assets/spring/jdbc-auto-configuration.svg`와 `docs/manual/assets/spring/r2dbc-auto-configuration.svg`를 구조적 원본으로 사용한다.
- source/test 원장은 전체 경로 대신 `ExposedSpringDataAutoConfiguration.kt`, `ExposedSpringDataAutoConfigurationTest.kt`처럼 basename만 표시하되 링크는 전체 저장소 경로를 유지한다.
- 한국어 `mental model`은 `사고방식`으로 표현한다.
- sequence diagram은 call line과 role line을 분리하고, call/return/error arrowhead 색상을 각 line과 일치시키며, participant card text를 세로 중앙 정렬한다.

### Task 1: Activation 모델 contract를 실패 테스트로 고정한다

**파일:**

- 수정: `tests/visual-companions/build.test.mjs`
- 수정: `tests/visual-companions/validator.test.mjs`
- 수정: `scripts/visual-companions/lib/model.mjs`

- [ ] **Step 1: 7개 preset과 reader-facing section을 요구하는 실패 테스트를 추가한다**

테스트가 다음 ID를 정확히 요구하도록 작성한다.

```javascript
const activationScenarioIds = [
  'jdbc-ready',
  'r2dbc-ready',
  'dual-stack',
  'custom-jdbc-manager',
  'custom-mapping-context',
  'missing-infrastructure',
  'entity-class-absent',
];

const activationSectionIds = [
  'mental-model',
  'architecture',
  'scenario-explorer',
  'sequence',
  'ownership-matrix',
  'configuration-recipes',
  'failure-diagnostics',
  'tradeoffs',
  'evidence',
];
```

- [ ] **Step 2: 실패 테스트를 실행한다**

Run:

```bash
node --test tests/visual-companions/build.test.mjs tests/visual-companions/validator.test.mjs
```

Expected: placeholder 모델에 scenario가 없어 activation contract assertion이 FAIL.

- [ ] **Step 3: 모델 validator에 activation 구조 검증을 추가한다**

`kind === "activation"`이면 scenario별 `conditions`, `results`, `participants`, `messages`, locale별 `label`, `summary`, `outcome`을 요구한다. 모든 source ledger 항목은 `sourcePath`, `testPath`, `verificationCommand`, locale별 `claim`을 가져야 한다.

- [ ] **Step 4: validator 단위 테스트를 다시 실행한다**

Run:

```bash
node --test tests/visual-companions/build.test.mjs tests/visual-companions/validator.test.mjs
```

Expected: 새 검증기는 동작하지만 placeholder JSON이 아직 새 contract를 만족하지 않아 예상된 FAIL 유지.
### 작업 2: 실제 activation 데이터와 한·영 기술문서를 작성한다

**파일:**

- 수정: `docs/visual-companions/data/spring-boot-exposed-activation.json`

- [ ] **1단계: JPA 기대와 Exposed 활성화의 사고방식 비교를 작성한다**

비교 축은 `activation trigger`, `repository registration`, `transaction infrastructure`, `runtime ownership`, `back-off`로 제한한다. JPA 전체 명세를 일반화하지 않고 전형적인 Spring Data JPA + Hibernate 경험이라고 범위를 밝힌다.

- [ ] **2단계: 7개 preset의 조건과 결과를 구조화한다**

각 preset은 다음 상태를 명시한다.

```text
condition: present | missing | custom | not-applicable
result: created | reused | backed-off | application-owned | unavailable
stack: jdbc | r2dbc | dual | inactive
```

`missing-infrastructure`는 JDBC `DataSource` 누락과 R2DBC application runtime 누락을 한 실패 preset 안의 분리된 결과로 보여 준다.

- [ ] **3단계: JDBC/R2DBC activation sequence를 작성한다**

각 sequence는 `Spring Boot`, `Auto-configuration`, `Condition evaluation`, `Enable annotation / registrar`, `Repository factory`, `Application infrastructure` 참여자를 사용한다. 정상 call, reuse/back-off return, missing/error branch를 번호가 붙은 message로 표현한다.

- [ ] **4단계: 책임 행렬, recipe, failure diagnostics를 작성한다**

책임 행렬에는 `DataSource`, `springTransactionManager`, `ExposedMappingContext`, `R2dbcDatabase`, connection pool/dispatcher/lifecycle, repository proxy/factory, schema migration을 포함한다. 각 recipe와 failure에는 제공 주체, 생성 결과, 관찰 증상, 확인 지점, verification command를 기록한다.

- [ ] **5단계: source/test ledger를 실제 구현에 고정한다**

최소 근거는 다음 파일을 포함한다.

```text
ExposedSpringDataAutoConfiguration.kt
ExposedR2dbcSpringDataAutoConfiguration.kt
EnableExposedJdbcRepositories.kt
EnableExposedR2dbcRepositories.kt
ExposedJdbcRepositoryConfigurationExtension.kt
ExposedSuspendRepositoryConfigurationExtension.kt
ExposedJdbcRepositoryFactoryBean.kt
ExposedR2dbcRepositoryFactoryBean.kt
ExposedR2dbcRepositoryFactory.kt
ExposedSpringDataAutoConfigurationTest.kt
AbstractExposedR2dbcRepositoryTest.kt
MultiManagerDocumentationExample.kt
```

### 작업 3: 공용 renderer를 activation 해설 구조로 확장한다

**파일:**

- 수정: `scripts/visual-companions/lib/render.mjs`
- 생성: `docs/visual-companions/en/spring-boot-exposed-activation.html`
- 생성: `docs/visual-companions/ko/spring-boot-exposed-activation.html`
- 삭제: `docs/visual-companions/spring-boot-exposed-activation.html`
- 삭제: `docs/visual-companions/spring-boot-exposed-activation.ko.html`

- [ ] **1단계: placeholder renderer를 activation renderer로 교체한다**

`renderActivationPlaceholder`를 제거하고 `renderActivationDocument`가 다음 구역을 순서대로 생성하게 한다.

```text
01 사고방식 비교
02 JDBC/R2DBC Architecture Diagram
03 조건 preset explorer
04 activation sequence
05 빈과 수명주기 소유권
06 최소 구성 recipe
07 실패 진단
08 장점·비용·선택 기준
09 소스와 테스트 근거
```

- [ ] **2단계: scenario 선택이 관련 정보를 함께 갱신하게 한다**

scenario button을 선택하면 sequence, 결과 요약, 조건/결과 목록, 관련 source anchor가 함께 바뀐다. native button과 `aria-pressed`, 화살표 키 이동, `aria-live` 상태 전달을 유지한다.

- [ ] **3단계: sequence geometry와 semantic color를 고정한다**

participant header와 lifeline은 `--role-line`, call은 `--call-line`, return은 `--teal`, created/reused는 `--green`, missing/error는 `--red`를 사용한다. arrowhead는 `currentColor`을 사용하고 forward/reverse 위치가 실제 대상 participant 쪽을 가리키게 한다.

- [ ] **4단계: Architecture Diagram 비교와 lightbox를 구현한다**

넓은 화면에서는 JDBC/R2DBC 두 diagram을 같은 높이의 비교 grid에 놓고, 좁은 화면에서는 의미가 유지되는 세로 배치로 바꾼다. 각 diagram은 독립적인 확대 button과 dialog를 사용한다.

- [ ] **5단계: 한·영 HTML을 생성하고 검증한다**

Generator가 소유하지 않는 root-level legacy HTML 두 개를 삭제하고, canonical locale 경로만 남긴다.

실행:

```bash
node scripts/visual-companions/build.mjs
node scripts/visual-companions/build.mjs --check
node scripts/visual-companions/validate.mjs
node --test tests/visual-companions/build.test.mjs tests/visual-companions/validator.test.mjs
```

예상 결과: companion HTML 4개가 생성되고/check PASS, 문서 2개 / locale 파일 4개의 validation PASS, Node tests PASS.
### 작업 4: 브라우저와 시각 판정을 수렴하고 사용자에게 로컬 URL을 제공한다

**파일:**

- 수정: `scripts/visual-companions/capture.mjs`
- 생성: `.omx/state/issue-415/ralph-progress.json`
- 검증: `docs/visual-companions/en/spring-boot-exposed-activation.html`
- 검증: `docs/visual-companions/ko/spring-boot-exposed-activation.html`

- [ ] **단계 1: 활성화 전용 브라우저 assertion을 추가한다**

다음을 CDP 기반 검사에 포함한다.

```text
scenarioCount = 7
sequenceCount = 7
architectureCount = 2
sourceAnchorCount >= 10
consoleErrors = 0
failedRequests = 0
horizontalOverflow = 0
keyboard scenario change = PASS
lightbox open/close = PASS
auto/light/dark theme = PASS
prefers-reduced-motion = PASS
```

- [ ] **단계 2: 데스크톱과 좁은 화면을 반복 검사한다**

실행:

```bash
node scripts/visual-companions/capture.mjs spring-boot-exposed-activation --qa
```

예상 결과: en/ko × 1440px/360px × light/dark 조합의 assertion PASS.

- [ ] **단계 3: 시각 판정 JSON을 기록한다**

설치된 `$visual-verdict` surface가 없으므로 동일한 판정 계약을 `.omx/state/issue-415/ralph-progress.json`에 기록한다. 각 iteration에는 screenshot 경로, viewport, locale, theme, clipping, overflow, sequence arrowhead, participant alignment, Architecture Diagram 가독성, verdict를 포함한다.

- [ ] **단계 4: 한국어 HTML을 로컬 서버로 연다**

실행:

```bash
python3 -m http.server 4173 --bind 127.0.0.1
open http://127.0.0.1:4173/docs/visual-companions/ko/spring-boot-exposed-activation.html
```

예상 결과: 사용자가 scenario, theme, diagram 확대, keyboard 동작과 설명의 깊이를 직접 확인할 수 있다.

- [ ] **단계 5: 사용자의 시각 승인을 기다린다**

Manifest는 이 시점까지 `pending-review/private` 상태다. 승인된 exact local commit과 사용자 메시지가 확보되기 전에는 Task 5를 시작하지 않는다.

### 작업 5: 승인된 companion을 공개 manifest와 fallback에 등록한다

**사전 조건:** Task 4 Step 5 사용자 시각 승인 PASS.

**파일:**

- 수정: `docs/visual-companions/manifest.json`
- 생성: `docs/visual-companions/assets/spring-boot-exposed-activation.en.light.png`
- 생성: `docs/visual-companions/assets/spring-boot-exposed-activation.en.dark.png`
- 생성: `docs/visual-companions/assets/spring-boot-exposed-activation.ko.light.png`
- 생성: `docs/visual-companions/assets/spring-boot-exposed-activation.ko.dark.png`

- [ ] **단계 1: 결정론적 fallback matrix를 두 번 생성한다**

실행:

```bash
node scripts/visual-companions/capture.mjs spring-boot-exposed-activation
node scripts/visual-companions/capture.mjs spring-boot-exposed-activation --check
```

예상 결과: 4개 PNG의 dimensions가 일치하고, 동일한 입력의 paired SHA-256이 모두 동일하다.

- [ ] **단계 2: manifest를 `approved/public`으로 승격한다**

`spring-boot-exposed-activation`에 다음을 기록한다.

```json
{
  "status": "approved",
  "public": true,
  "presentation": {
    "mode": "condition-explorer",
    "defaultView": "jdbc-ready",
    "views": [
      "jdbc-ready",
      "r2dbc-ready",
      "dual-stack",
      "custom-jdbc-manager",
      "custom-mapping-context",
      "missing-infrastructure",
      "entity-class-absent"
    ]
  }
}
```

- [ ] **단계 3: manifest와 fallback을 다시 검증한다**

실행:

```bash
node scripts/visual-companions/build.mjs --check
node scripts/visual-companions/validate.mjs
node scripts/visual-companions/capture.mjs spring-boot-exposed-activation --check
```

예상 결과: approved/public 문서가 한국어·영어 HTML과 네 개의 fallback을 모두 참조하며 PASS.
### 작업 6: PR 전 증거를 수렴하고 PR에서 멈춘다

**파일:**

- 검증: `origin/develop...HEAD` 전체 변경

- [ ] **단계 1: 최종 정적·브라우저·문서 검증을 실행한다**

실행:

```bash
node scripts/visual-companions/build.mjs --check
node --test tests/visual-companions/*.test.mjs
node scripts/visual-companions/validate.mjs
node scripts/visual-companions/capture.mjs spring-boot-exposed-activation --check
git diff --check origin/develop...HEAD
```

예상 결과: 모든 command exit 0.

- [ ] **단계 2: 범위와 P0/P1을 검토한다**

실행:

```bash
git diff --stat origin/develop...HEAD
git diff --name-only origin/develop...HEAD
rg -n '정신 모형|사고 구조|TBD|TODO|pending deep-dive|waits at the explicit user-review gate' \
  docs/visual-companions scripts/visual-companions tests/visual-companions
```

예상 결과: production Kotlin 변경 0, 금지 문구 0, P0=0, P1=0.

- [ ] **단계 3: Lore protocol에 따라 수렴된 commit을 만든다**

커밋 의도:

```text
Make Spring Boot activation ownership observable before repository use
```

Commit trailers에는 constraint, confidence, scope-risk, tested, not-tested를 기록한다.

- [ ] **단계 4: exact head를 push하고 PR을 생성한다**

대상:

```text
repository: bluetape4k/bluetape4k-exposed
base: develop
head: docs/issue-415-spring-boot-activation
```

PR은 `debop`에게 assign하고 issue #415의 `documentation`, `enhancement`, milestone `1.12.0`을 반영한다. 본문 마지막 `##` section은 `## DoD Status`여야 한다.

- [ ] **단계 5: live PR과 CI를 확인하고 merge gate에서 멈춘다**

PR URL, exact head SHA, checks, reviews, threads, mergeability, DoD body를 live로 다시 읽는다. merge-ready 증거를 사용자에게 보고한 뒤 fresh merge approval을 기다리며, 자동 merge는 설정하지 않는다.
