# Issue 410 Exposed 심층 시각 해설서 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** JPA/Hibernate 경험자가 Exposed의 선택 기준, 트랜잭션 소유권, Spring Boot 활성화 계약을 기존 Architecture Diagram과 실제 소스 근거로 탐색할 수 있는 영어·한국어 심층 HTML 해설서 두 개를 제공한다.

**Architecture:** 두 구조화 JSON이 시나리오, 로케일 문구, 시퀀스, 책임 행렬, 코드 근거의 단일 원본이 된다. 순수 Node.js 생성기가 기존 매뉴얼 SVG를 SHA-256으로 결속해 독립 HTML을 생성하고, validator와 Chrome DevTools Protocol 기반 capture 도구가 로케일·테마·접근성·결정적 PNG 계약을 검증한다.

**Tech Stack:** Node.js 26 built-in modules, `node:test`, standalone HTML/CSS/JavaScript, JSON, existing manual SVG/PNG assets, local Google Chrome headless/CDP, Markdown.

**Execution mode:** 사용자의 기존 “혼자 작업” 지시에 따라 subagent를 사용하지 않고 이 세션에서 `executing-plans`로 순차 실행한다. 트랜잭션 해설서를 실제 브라우저로 보여 주고 사용자 시각 승인을 받은 뒤에만 Spring Boot 해설서 구현을 시작한다.

---

## 파일 책임 구조

| 경로 | 책임 |
|---|---|
| `docs/visual-companions/data/jdbc-r2dbc-transaction-boundaries.json` | JPA 대비 트랜잭션 서사, 6개 시나리오, 책임 행렬, 소스·테스트 원장 |
| `docs/visual-companions/data/spring-boot-exposed-activation.json` | JPA 자동 구성 기대 대비 Exposed 서사, 7개 프리셋, 빈 소유권, 소스·테스트 원장 |
| `scripts/visual-companions/lib/model.mjs` | JSON 로딩, 스키마 검사, 소스 경로 검사, Architecture Diagram 해시와 내장 데이터 생성 |
| `scripts/visual-companions/lib/render.mjs` | 공통 HTML shell, Architecture Diagram, 시퀀스, 행렬, 코드, 근거 원장 렌더링 |
| `scripts/visual-companions/build.mjs` | 영어·한국어 HTML 결정적 생성 및 `--check` diff 검사 |
| `scripts/visual-companions/validate.mjs` | manifest, 생성물, 로케일 동등성, source ledger, 외부 의존성, SVG hash 검증 |
| `scripts/visual-companions/capture.mjs` | Chrome CDP 실행, 로케일·테마 캡처, 반복 SHA-256 동등성 검사 |
| `tests/visual-companions/build.test.mjs` | 모델·생성기·결정성 계약 |
| `tests/visual-companions/validator.test.mjs` | 저장소·manifest·HTML·로케일·source ledger 거부 계약 |
| `tests/visual-companions/capture.test.mjs` | 캡처 matrix와 Chrome 인자·ready 조건 계약 |
| `docs/visual-companions/en/*.html` | 생성된 영어 독립 HTML |
| `docs/visual-companions/ko/*.html` | 생성된 한국어 독립 HTML |
| `docs/visual-companions/assets/*.png` | 영어/한국어 × light/dark 기본 화면 정적 대체 이미지 |
| `docs/visual-companions/manifest.json` | 기존 public ID를 유지하는 게시 계약과 Architecture Diagram 해시 |

기존 public ID와 사이트 route는 호환성을 위해 유지한다.

```text
jdbc-r2dbc-transaction-boundaries
spring-boot-exposed-activation
```

기존 flat HTML 네 개는 새 `en/` 및 `ko/` 생성물로 대체한다. 매뉴얼의 public route는 ID 기반이므로 변경하지 않는다.

## 워크플로 gate 상태

- [x] **WF-01 — Type E로 분류**
  - **Action:** production Kotlin 동작을 바꾸지 않는 문서·시각화 유지보수로 분류한다.
  - **Evidence:** Issue #410 exclusions, 현재 diff, `bluetape-maintenance` route.
  - **Failure:** Kotlin 또는 public API 변경이 필요해지면 Type A/B/C로 재분류한다.
- [x] **WF-02 — 첫 구체 계획 고정**
  - **Action:** JPA 대비 서사, 기존 Diagram 재사용, 사용자 HTML 검토 순서를 설계서에 기록한다.
  - **Evidence:** `docs/superpowers/specs/2026-07-30-issue-410-exposed-visual-companions-design.md`.
  - **Failure:** 승인된 설계 범위를 벗어나는 구현을 시작하지 않는다.
- [x] **WF-03 — 첫 계획 승인**
  - **Action:** 사용자에게 재작성 설계를 제시하고 명시적 승인을 받는다.
  - **Evidence:** 이 계획 직전 사용자의 `승인`.
  - **Failure:** 승인 없이는 구현 mutation을 진행하지 않는다.
- [x] **WF-04/WF-04A — 실행 계약과 receipt 초기화**
  - **Action:** workflow, maintenance, diagram, writing-plans 계약을 읽고 현재 세션에 Type E run을 만든다.
  - **Evidence:** run `20260730T143307Z-1f6892bf`, lane `inline-execution`, topology `visual-companion-plan`.
  - **Failure:** mutation check가 실패하면 해당 경로를 수정하지 않는다.
- [x] **CG-01~CG-05/E-01~E-03/DIA-01~DIA-02 — preflight**
  - **Action:** authority, live Issue/PR, GNO history, isolated worktree, locale 정책, 기존 6개 자료와 Diagram/source를 확인한다.
  - **Evidence:** branch `docs/issue-410-visual-companions`, local head `3431ed1c`, remote PR head `ca21e5c6`, loaded `common.md`, `architecture.md`, `sequence.md`, `workflow.md`.
  - **Failure:** unrelated user work 또는 source contract 충돌이 발견되면 보존하고 중단한다.
- [ ] **CG-06~CG-10/E-04~E-06/DIA-03~DIA-08 — 구현과 pre-PR 검증**
  - **Action:** 아래 Task 1~9를 순서대로 완료하고 P0/P1을 0으로 수렴한다.
  - **Evidence:** 생성물, 테스트, PNG ledger, 사용자 시각 승인, exact local head.
  - **Failure:** 실패한 gate 이후 작업을 진행하지 않고 해당 Task로 돌아간다.
- [ ] **CG-11~CG-15/E-07 — PR 갱신과 merge-ready**
  - **Action:** Task 10에서 exact head push, PR body/metadata/CI/review를 갱신한다.
  - **Evidence:** local=remote=PR head, green checks, current reviews/threads, 최종 `## DoD Status`.
  - **Failure:** merge-ready 보고를 하지 않는다.
- [ ] **CG-16~CG-18/E-08 — merge, sync, cleanup**
  - **Action:** merge-ready 보고 뒤 exact-head에 대한 새 사용자 승인을 받아 merge·sync·cleanup한다.
  - **Evidence:** 승인 메시지, merge SHA, local/upstream SHA, 보수적 cleanup 결과.
  - **Failure:** 이전 승인으로 merge하지 않으며 CG-16에서 `PENDING`으로 멈춘다.

---

### Task 1: 생성형 contract를 실패 테스트로 잠근다

**Files:**

- Create: `tests/visual-companions/build.test.mjs`
- Create: `tests/visual-companions/capture.test.mjs`
- Modify: `tests/visual-companions/validator.test.mjs`
- Test: `tests/visual-companions/build.test.mjs`
- Test: `tests/visual-companions/capture.test.mjs`
- Test: `tests/visual-companions/validator.test.mjs`

- [x] **Step 1: 모델과 생성기 API에 대한 실패 테스트를 작성한다**

`tests/visual-companions/build.test.mjs`에서 다음 contract를 고정한다.

```js
import assert from 'node:assert/strict';
import test from 'node:test';
import { buildRepository } from '../../scripts/visual-companions/build.mjs';
import { loadCompanionModels } from '../../scripts/visual-companions/lib/model.mjs';

const root = new URL('../../', import.meta.url);

test('models expose the approved document and scenario ids', async () => {
  const models = await loadCompanionModels(root);
  assert.deepEqual(
    models.map(({ id }) => id),
    ['jdbc-r2dbc-transaction-boundaries', 'spring-boot-exposed-activation'],
  );
  assert.deepEqual(
    models[0].scenarios.map(({ id }) => id),
    [
      'jdbc-single',
      'jdbc-multi-repository',
      'r2dbc-single',
      'r2dbc-flow-inside',
      'r2dbc-flow-escape',
      'rollback-or-cancellation',
    ],
  );
});

test('build check reports stale or missing generated files', async () => {
  await assert.rejects(
    buildRepository({ root, check: true }),
    /generated visual companion differs/,
  );
});
```

- [x] **Step 2: 캡처 matrix 실패 테스트를 작성한다**

```js
import assert from 'node:assert/strict';
import test from 'node:test';
import { captureTargets, chromeArguments } from '../../scripts/visual-companions/capture.mjs';

test('capture matrix is bounded to two locales and two explicit themes', () => {
  assert.deepEqual(
    captureTargets('jdbc-r2dbc-transaction-boundaries'),
    [
      'jdbc-r2dbc-transaction-boundaries.en.light.png',
      'jdbc-r2dbc-transaction-boundaries.en.dark.png',
      'jdbc-r2dbc-transaction-boundaries.ko.light.png',
      'jdbc-r2dbc-transaction-boundaries.ko.dark.png',
    ],
  );
});

test('chrome runs without background network or animation drift', () => {
  const args = chromeArguments('/tmp/profile', 9222);
  assert.ok(args.includes('--headless=new'));
  assert.ok(args.includes('--disable-background-networking'));
  assert.ok(args.includes('--force-device-scale-factor=1'));
  assert.ok(args.includes('--remote-debugging-port=9222'));
});
```

- [x] **Step 3: validator 거부 테스트를 새 구조에 맞게 바꾼다**

다음 실패를 각각 독립 fixture로 검증한다.

```text
missing data model
duplicate scenario id
missing en/ko locale key
missing sourcePath or testPath
sourcePath escaping repository
architecture SVG digest mismatch
generated HTML outside en/ko directory
missing fallback PNG
external runtime dependency
locale structural fingerprint mismatch
missing workflow-ready signal
missing architecture lightbox control
missing sequence participant, lifeline, activation, numbered message, or alt frame
```

- [x] **Step 4: 세 테스트를 실행해 RED를 확인한다**

Run:

```bash
node --test \
  tests/visual-companions/build.test.mjs \
  tests/visual-companions/capture.test.mjs \
  tests/visual-companions/validator.test.mjs
```

Expected: `ERR_MODULE_NOT_FOUND` for `build.mjs`, `model.mjs`, or `capture.mjs`; 기존 validator 테스트는 새 manifest/data contract가 없어 실패한다.

- [x] **Step 5: RED contract를 커밋한다**

```bash
git add tests/visual-companions
git commit
```

Commit intent: `Lock the depth and determinism contract before rebuilding the companions`

---

### Task 2: 구조화 모델과 결정적 HTML 생성기를 구현한다

**Files:**

- Create: `scripts/visual-companions/lib/model.mjs`
- Create: `scripts/visual-companions/lib/render.mjs`
- Create: `scripts/visual-companions/build.mjs`
- Create: `docs/visual-companions/data/jdbc-r2dbc-transaction-boundaries.json`
- Create: `docs/visual-companions/data/spring-boot-exposed-activation.json`
- Modify: `tests/visual-companions/build.test.mjs`

- [x] **Step 1: 모델 스키마의 최소 공통 shape를 구현한다**

`model.mjs`가 다음 API를 export한다.

```js
import { createHash } from 'node:crypto';
import { readFile, readdir, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const locales = ['en', 'ko'];

function containedPath(rootUrl, relativePath) {
  const root = fileURLToPath(rootUrl);
  const target = path.resolve(root, relativePath);
  if (target !== root && !target.startsWith(`${root}${path.sep}`)) {
    throw new Error(`path escapes repository: ${relativePath}`);
  }
  return target;
}

export function validateCompanionModel(model) {
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(model.id)) {
    throw new Error(`invalid companion id: ${model.id}`);
  }
  if (Object.keys(model.locales).sort().join(',') !== locales.join(',')) {
    throw new Error(`${model.id}: locales must be exactly en and ko`);
  }
  for (const field of ['sections', 'scenarios', 'sources']) {
    const ids = model[field].map(({ id }) => id);
    if (new Set(ids).size !== ids.length) {
      throw new Error(`${model.id}: duplicate ${field} id`);
    }
  }
  return model;
}

export async function loadCompanionModels(rootUrl) {
  const directory = containedPath(rootUrl, 'docs/visual-companions/data');
  const names = (await readdir(directory))
    .filter((name) => name.endsWith('.json'))
    .sort();
  return Promise.all(
    names.map(async (name) => {
      const model = JSON.parse(await readFile(path.join(directory, name), 'utf8'));
      return validateCompanionModel(model);
    }),
  );
}

export async function loadArchitectureAsset(rootUrl, asset) {
  const source = containedPath(rootUrl, asset.source);
  const fallback = containedPath(rootUrl, asset.fallback);
  await stat(fallback);
  const svg = await readFile(source, 'utf8');
  return {
    id: asset.id,
    source: asset.source,
    fallback: asset.fallback,
    sha256: createHash('sha256').update(svg).digest('hex'),
    dataUri: `data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}`,
  };
}

export function structuralFingerprint(model) {
  return JSON.stringify({
    id: model.id,
    sections: model.sections.map(({ id }) => id),
    scenarios: model.scenarios.map(({ id, outcome }) => ({ id, outcome })),
    sources: model.sources.map(({ id, sourcePath, testPath }) => ({
      id,
      sourcePath,
      testPath,
    })),
  });
}
```

모든 모델은 다음 shape를 사용한다.

```json
{
  "id": "jdbc-r2dbc-transaction-boundaries",
  "architecture": [
    {
      "id": "transaction-ownership",
      "source": "docs/manual/assets/persistence/transaction-ownership.svg",
      "fallback": "docs/manual/assets/persistence/transaction-ownership.png"
    }
  ],
  "locales": {
    "en": { "title": "JPA to Exposed: Transaction Ownership" },
    "ko": { "title": "JPA에서 Exposed로: 트랜잭션 소유권" }
  },
  "sections": [],
  "scenarios": [],
  "sources": []
}
```

`validateCompanionModel`은 kebab-case ID, 정확한 `en`/`ko`, 중복 없는 section/scenario/source ID, 존재하는 architecture/source/test 경로를 검사한다.

- [x] **Step 2: Architecture Diagram을 해시와 data URI로 고정한다**

`loadArchitectureAsset`은 다음 값을 반환한다.

```js
{
  id: asset.id,
  source: asset.source,
  fallback: asset.fallback,
  sha256: createHash('sha256').update(svg).digest('hex'),
  dataUri: `data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}`,
}
```

HTML은 외부 파일을 runtime에 읽지 않고 `dataUri`를 `<img>`의 `src`로 사용한다. 구조화 모델의 `hotspots`는 percentage 좌표를 가진 native `<button>` overlay로 렌더링한다.

- [x] **Step 3: 공통 renderer를 구현한다**

`render.mjs`가 다음 API를 export한다.

```js
const escapeHtml = (value) => String(value)
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;');

export function renderArchitecture({ model, locale, architectureAssets }) {
  return architectureAssets.map((asset) => `
    <figure data-architecture-id="${escapeHtml(asset.id)}">
      <button type="button" data-lightbox-open="${escapeHtml(asset.id)}">
        ${escapeHtml(model.locales[locale].openArchitecture)}
      </button>
      <img src="${asset.dataUri}" alt="${escapeHtml(model.locales[locale].architectureAlt[asset.id])}">
      <dialog id="architecture-${escapeHtml(asset.id)}">
        <img src="${asset.dataUri}" alt="${escapeHtml(model.locales[locale].architectureAlt[asset.id])}">
        <button type="button" data-lightbox-close="${escapeHtml(asset.id)}">
          ${escapeHtml(model.locales[locale].close)}
        </button>
      </dialog>
    </figure>
  `).join('');
}

export function renderScenarioExplorer({ model, locale }) {
  return model.scenarios.map((scenario, index) => `
    <button type="button"
            data-scenario="${escapeHtml(scenario.id)}"
            aria-pressed="${index === 0}">
      ${escapeHtml(scenario.locales[locale].label)}
    </button>
  `).join('');
}

export function renderSequence({ scenario, locale }) {
  const participants = scenario.participants.map((participant) => `
    <div class="sequence-participant" data-participant="${escapeHtml(participant.id)}">
      <strong>${escapeHtml(participant.locales[locale].label)}</strong>
      <span class="lifeline" aria-hidden="true"></span>
    </div>
  `).join('');
  const messages = scenario.messages.map((message, index) => `
    <li data-message-kind="${escapeHtml(message.kind)}">
      <span class="message-number">${index + 1}</span>
      <span class="message-label">${escapeHtml(message.locales[locale].label)}</span>
    </li>
  `).join('');
  return `
    <div class="sequence" data-sequence="${escapeHtml(scenario.id)}">
      <div class="sequence-participants">${participants}</div>
      <div class="activation-bars" aria-hidden="true"></div>
      <ol class="sequence-messages">${messages}</ol>
      <div class="sequence-alt" data-outcome="${escapeHtml(scenario.outcome)}">
        ${escapeHtml(scenario.locales[locale].outcome)}
      </div>
    </div>
  `;
}

export function renderEvidenceLedger({ model, locale }) {
  return model.sources.map((source) => `
    <tr data-source-anchor="${escapeHtml(source.id)}">
      <th scope="row">${escapeHtml(source.locales[locale].claim)}</th>
      <td><code>${escapeHtml(source.sourcePath)}</code></td>
      <td><code>${escapeHtml(source.testPath)}</code></td>
      <td><code>${escapeHtml(source.verificationCommand)}</code></td>
    </tr>
  `).join('');
}

export function renderDocument({ model, locale, architectureAssets }) {
  const defaultScenario = model.scenarios[0];
  return renderStandaloneShell({
    model,
    locale,
    body: `
      <section id="mental-model">${renderMentalModel({ model, locale })}</section>
      <section id="architecture">${renderArchitecture({ model, locale, architectureAssets })}</section>
      <section id="scenario-explorer">${renderScenarioExplorer({ model, locale })}</section>
      <section id="sequence">${renderSequence({ scenario: defaultScenario, locale })}</section>
      <section id="ownership-matrix">${renderOwnershipMatrix({ model, locale })}</section>
      <section id="code-mapping">${renderCodeMapping({ model, locale })}</section>
      ${renderOptionalSections({ model, locale })}
      <section id="tradeoffs">${renderTradeoffs({ model, locale })}</section>
      <section id="evidence"><table><tbody>${renderEvidenceLedger({ model, locale })}</tbody></table></section>
    `,
  });
}
```

`renderStandaloneShell`, `renderMentalModel`, `renderOwnershipMatrix`, `renderCodeMapping`, `renderOptionalSections`, `renderTradeoffs`는 같은 파일의 private 함수로 구현하며, 구조화 모델만 읽고 locale별 markup을 직접 복제하지 않는다.

`renderDocument`는 다음 section ID를 항상 같은 순서로 출력한다.

```js
const sectionIds = [
  'mental-model',
  'architecture',
  'scenario-explorer',
  'sequence',
  'ownership-matrix',
  'code-mapping',
  'tradeoffs',
  'evidence',
];
```

Spring Boot 모델은 `configuration-recipes`와 `failure-diagnostics`를 `tradeoffs` 앞에 추가한다. 공통 shell은 locale 전환 링크, `auto/light/dark` theme 선택, `<main>`, skip link, visible focus, `prefers-reduced-motion`, `aria-live="polite"` 상태 요약을 포함한다.

- [x] **Step 4: builder와 `--check`를 구현한다**

```js
export async function buildRepository({ root, check = false }) {
  const models = await loadCompanionModels(root);
  const outputs = [];
  for (const model of models) {
    const architectureAssets = await Promise.all(
      model.architecture.map((asset) => loadArchitectureAsset(root, asset)),
    );
    for (const locale of ['en', 'ko']) {
      outputs.push({
        path: `docs/visual-companions/${locale}/${model.id}.html`,
        content: renderDocument({ model, locale, architectureAssets }),
      });
    }
  }
  return writeOrCheckOutputs(root, outputs, check);
}
```

`writeOrCheckOutputs`은 LF와 마지막 newline을 고정한다. `--check`에서는 어떤 파일도 쓰지 않고 첫 차이 경로와 함께 실패한다.

- [x] **Step 5: build 테스트를 GREEN으로 만든다**

Run:

```bash
node scripts/visual-companions/build.mjs
node scripts/visual-companions/build.mjs --check
node --test tests/visual-companions/build.test.mjs
```

Expected: generated file 4개, `--check` exit 0, build tests PASS.

- [x] **Step 6: 모델·builder를 커밋한다**

```bash
git add docs/visual-companions/data scripts/visual-companions/lib scripts/visual-companions/build.mjs tests/visual-companions/build.test.mjs
git commit
```

Commit intent: `Make one source of truth govern every visual state and locale`

---

### Task 3: validator와 캡처 도구를 새 contract로 강화한다

**Files:**

- Modify: `scripts/visual-companions/validate.mjs`
- Create: `scripts/visual-companions/capture.mjs`
- Modify: `tests/visual-companions/validator.test.mjs`
- Modify: `tests/visual-companions/capture.test.mjs`

- [x] **Step 1: validator를 manifest→model→generated artifact 순서로 재구성한다**

`validateRepository`는 다음 순서로 검사한다.

```js
const checks = [
  validateManifest,
  validateModels,
  validateGeneratedDocuments,
  validateLocaleParity,
  validateArchitectureDigests,
  validateFallbackImages,
  validateManualRoutes,
];
```

기존 path containment, external dependency, reciprocal locale, section/control fingerprint 검사는 유지한다. 새 검사는 model scenario ID, source ledger, architecture digest, sequence visual signal, `window.__VISUAL_COMPANION_READY__ === true`를 추가한다.

- [x] **Step 2: Node built-in WebSocket을 사용하는 CDP client를 구현한다**

`capture.mjs`가 다음 API를 export한다.

```js
const locales = ['en', 'ko'];
const themes = ['light', 'dark'];

export function captureTargets(documentId) {
  return locales.flatMap((locale) =>
    themes.map((theme) => `${documentId}.${locale}.${theme}.png`),
  );
}

export function chromeArguments(profileDir, port) {
  return [
    '--headless=new',
    '--disable-background-networking',
    '--disable-component-update',
    '--disable-default-apps',
    '--disable-extensions',
    '--disable-sync',
    '--force-device-scale-factor=1',
    '--hide-scrollbars',
    '--lang=en-US',
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profileDir}`,
  ];
}

export async function captureMatrix({ root, documentId, check = false }) {
  const results = [];
  for (const locale of locales) {
    for (const theme of themes) {
      const first = await captureOne({ root, documentId, locale, theme });
      const second = await captureOne({ root, documentId, locale, theme });
      assertDeterministic(first, second);
      results.push(await writeOrCheckCapture({
        root,
        documentId,
        locale,
        theme,
        png: first.png,
        check,
      }));
    }
  }
  return results;
}
```

`captureOne`, `assertDeterministic`, `writeOrCheckCapture`는 같은 파일의 private 함수로 구현한다. `captureOne`은 아래 CDP 순서를 그대로 수행하고, `assertDeterministic`은 width·height·SHA-256을 비교한다.

Chrome 고정 인자는 다음과 같다.

```js
return [
  '--headless=new',
  '--disable-background-networking',
  '--disable-component-update',
  '--disable-default-apps',
  '--disable-extensions',
  '--disable-sync',
  '--force-device-scale-factor=1',
  '--hide-scrollbars',
  '--lang=en-US',
  `--remote-debugging-port=${port}`,
  `--user-data-dir=${profileDir}`,
];
```

CDP 호출 순서는 다음으로 고정한다.

```text
Page.enable
Runtime.enable
Emulation.setDeviceMetricsOverride(1440 × 1000, scale 1)
Emulation.setEmulatedMedia(prefers-reduced-motion: reduce, color-scheme: selected theme)
Page.navigate(file URL with ?theme=<theme>&capture=1)
Runtime.evaluate(await document.fonts.ready and ready signal)
Page.getLayoutMetrics
Page.captureScreenshot(full content clip)
```

- [x] **Step 3: 같은 입력을 두 번 캡처해 hash equality를 검사한다**

각 target을 임시 경로에 두 번 캡처하고 다음을 비교한다.

```js
assert.equal(first.width, second.width);
assert.equal(first.height, second.height);
assert.equal(sha256(first.png), sha256(second.png));
```

일치할 때만 `docs/visual-companions/assets/<target>`에 쓴다. `--check`는 기존 PNG와 새 PNG의 SHA-256이 다르면 실패한다.

- [x] **Step 4: validator와 capture 단위 테스트를 GREEN으로 만든다**

Run:

```bash
node --test \
  tests/visual-companions/validator.test.mjs \
  tests/visual-companions/capture.test.mjs
```

Expected: fixture rejection diagnostics를 포함해 PASS.

- [x] **Step 5: validator와 capture 도구를 커밋한다**

```bash
git add scripts/visual-companions tests/visual-companions
git commit
```

Commit intent: `Fail publication when visuals drift from source or replay`

---

### Task 4: 트랜잭션 소유권 심층 해설서를 구현한다

**Files:**

- Modify: `docs/visual-companions/data/jdbc-r2dbc-transaction-boundaries.json`
- Generate: `docs/visual-companions/en/jdbc-r2dbc-transaction-boundaries.html`
- Generate: `docs/visual-companions/ko/jdbc-r2dbc-transaction-boundaries.html`
- Generate: `docs/visual-companions/assets/jdbc-r2dbc-transaction-boundaries.{en,ko}.{light,dark}.png`
- Delete: `docs/visual-companions/jdbc-r2dbc-transaction-boundaries.html`
- Delete: `docs/visual-companions/jdbc-r2dbc-transaction-boundaries.ko.html`
- Test: `tests/visual-companions/build.test.mjs`
- Test: `tests/visual-companions/validator.test.mjs`

- [x] **Step 1: 6개 시나리오와 정확한 source ledger를 채운다**

시나리오 ID와 결과를 다음으로 고정한다.

```json
[
  { "id": "jdbc-single", "outcome": "commit" },
  { "id": "jdbc-multi-repository", "outcome": "commit" },
  { "id": "r2dbc-single", "outcome": "commit" },
  { "id": "r2dbc-flow-inside", "outcome": "commit" },
  { "id": "r2dbc-flow-escape", "outcome": "boundary-error" },
  { "id": "rollback-or-cancellation", "outcome": "rollback" }
]
```

source ledger는 최소 다음 파일을 포함한다.

```text
exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/JdbcRepository.kt
exposed/r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/repository/R2dbcRepository.kt
examples/jdbc-demo/src/main/kotlin/io/bluetape4k/examples/exposed/mvc/controller/ProductController.kt
examples/r2dbc-demo/src/main/kotlin/io/bluetape4k/examples/exposed/webflux/controller/ProductController.kt
exposed/r2dbc/src/test/kotlin/io/bluetape4k/exposed/r2dbc/repository/MovieR2dbcRepositoryTest.kt
docs/manual/en/modules/bluetape4k-exposed-jdbc/transaction-ownership.md
docs/manual/en/modules/bluetape4k-exposed-r2dbc/coroutine-transactions.md
docs/manual/en/modules/bluetape4k-exposed-r2dbc/repository-patterns.md
```

- [x] **Step 2: JPA 대비 정신 모형과 실제 코드 mapping을 채운다**

다음 내용을 영어와 한국어에 의미 동등하게 제공한다.

```text
JPA/Hibernate: managed entity → dirty checking → flush
Exposed DSL: explicit select/join/map → explicit insert/update/delete
JPA advantage: object graph productivity and ecosystem
JPA cost: hidden I/O, N+1, proxy and flush lifecycle
Exposed advantage: visible SQL intent and predictable I/O
Exposed cost: explicit query, mapping, write, and boundary code
```

“Exposed는 ORM이 아니다”, “JPA 저장소가 업무 트랜잭션을 소유한다”, “Exposed는 schema 도구가 없다”는 문구는 금지한다.

- [x] **Step 3: 기존 Architecture Diagram을 기본 주인공으로 배치한다**

`transaction-ownership.svg`를 data URI로 내장하고, JDBC/R2DBC lane을 선택하는 hotspot과 확대 dialog를 제공한다. dialog는 native `<dialog>`, 열기 `<button>`, 닫기 `<button>`, `Escape`를 사용한다.

- [x] **Step 4: 시나리오에 따라 정식 sequence view를 갱신한다**

모든 시나리오는 다음 요소를 포함한다.

```text
participant header
vertical lifeline
activation bar
numbered call/return messages
alt success/failure frame
commit or rollback terminal result
```

색만으로 결과를 구분하지 않고 `CALL`, `RETURN`, `COMMIT`, `ROLLBACK`, `BOUNDARY ERROR` label을 함께 표시한다.

- [x] **Step 5: 책임 행렬, trade-off, 선택 가이드, 검증 원장을 채운다**

문서가 다음 질문에 모두 답하는지 build test로 검사한다.

```text
what is different
why the ownership rule exists
how JDBC succeeds
how R2DBC succeeds
where Flow escape fails
who owns atomicity and lifecycle
which source and test prove the claim
when to choose or avoid each approach
```

- [x] **Step 6: 생성·정적 검증을 실행한다**

Run:

```bash
node scripts/visual-companions/build.mjs
node scripts/visual-companions/build.mjs --check
node scripts/visual-companions/validate.mjs
node --test tests/visual-companions/build.test.mjs tests/visual-companions/validator.test.mjs
```

Expected: transaction pair와 source contract PASS.

- [x] **Step 7: 트랜잭션 해설서를 커밋한다**

```bash
git add docs/visual-companions scripts/visual-companions tests/visual-companions
git commit
```

Commit intent: `Teach transaction ownership through the JPA migration mental model`

---

### Task 5: 트랜잭션 HTML을 브라우저로 검증하고 사용자에게 보여 준다

**Files:**

- Verify: `docs/visual-companions/en/jdbc-r2dbc-transaction-boundaries.html`
- Verify: `docs/visual-companions/ko/jdbc-r2dbc-transaction-boundaries.html`
- Generate: `docs/visual-companions/assets/jdbc-r2dbc-transaction-boundaries.*.png`

- [x] **Step 1: 결정적 fallback matrix를 두 번 캡처한다**

Run:

```bash
node scripts/visual-companions/capture.mjs jdbc-r2dbc-transaction-boundaries
node scripts/visual-companions/capture.mjs jdbc-r2dbc-transaction-boundaries --check
```

Expected: 4개 PNG, 각 반복 capture dimensions/hash equality PASS.

- [x] **Step 2: desktop/narrow, keyboard, reduced motion, console을 검사한다**

검사 matrix:

```text
en/ko × light/dark × 1440px
en/ko × light/dark × 360px
6 scenarios
architecture lightbox open/close/Escape
Tab/Shift+Tab/Enter/Space
prefers-reduced-motion: reduce
console errors = 0
failed requests = 0
page horizontal overflow = 0
```

- [x] **Step 3: full-size PNG를 직접 검사한다**

다음 4개를 원본 크기로 열어 typography, sequence label, architecture readability, clipping, whitespace를 확인한다.

```text
docs/visual-companions/assets/jdbc-r2dbc-transaction-boundaries.en.light.png
docs/visual-companions/assets/jdbc-r2dbc-transaction-boundaries.en.dark.png
docs/visual-companions/assets/jdbc-r2dbc-transaction-boundaries.ko.light.png
docs/visual-companions/assets/jdbc-r2dbc-transaction-boundaries.ko.dark.png
```

- [x] **Step 4: 로컬 HTTP 서버에서 실제 한국어 HTML을 사용자 브라우저로 연다**

Run:

```bash
python3 -m http.server 4173 --bind 127.0.0.1
open http://127.0.0.1:4173/docs/visual-companions/ko/jdbc-r2dbc-transaction-boundaries.html
```

사용자가 scenario, theme, Diagram 확대, keyboard 동작을 직접 확인할 때까지 Spring Boot 해설서 구현을 시작하지 않는다.

- [ ] **Step 5: 사용자 피드백을 반영하고 시각 승인을 기록한다**

Expected evidence: 사용자의 명시적 승인 메시지와 승인된 exact local commit SHA.

---

### Task 6: Spring Boot 활성화 심층 해설서를 구현한다

**Prerequisite:** Task 5 사용자 시각 승인 PASS.

**Files:**

- Modify: `docs/visual-companions/data/spring-boot-exposed-activation.json`
- Generate: `docs/visual-companions/en/spring-boot-exposed-activation.html`
- Generate: `docs/visual-companions/ko/spring-boot-exposed-activation.html`
- Generate: `docs/visual-companions/assets/spring-boot-exposed-activation.{en,ko}.{light,dark}.png`
- Delete: `docs/visual-companions/spring-boot-exposed-activation.html`
- Delete: `docs/visual-companions/spring-boot-exposed-activation.ko.html`

- [ ] **Step 1: 7개 구성 preset과 정확한 결과를 채운다**

```json
[
  { "id": "jdbc-happy", "stack": "jdbc" },
  { "id": "r2dbc-happy", "stack": "r2dbc" },
  { "id": "dual-stack", "stack": "jdbc-r2dbc" },
  { "id": "custom-jdbc-transaction-manager", "stack": "jdbc" },
  { "id": "custom-mapping-context", "stack": "jdbc-r2dbc" },
  { "id": "missing-application-infrastructure", "stack": "invalid" },
  { "id": "entity-class-absent", "stack": "inactive" }
]
```

- [ ] **Step 2: 실제 auto-configuration source ledger를 채운다**

```text
spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/config/ExposedSpringDataAutoConfiguration.kt
spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/config/ExposedR2dbcSpringDataAutoConfiguration.kt
spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/config/EnableExposedJdbcRepositories.kt
spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/config/EnableExposedR2dbcRepositories.kt
spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/config/ExposedJdbcRepositoriesRegistrar.kt
spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/config/ExposedR2dbcRepositoriesRegistrar.kt
spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/repository/support/ExposedJdbcRepositoryFactoryBean.kt
spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/support/ExposedR2dbcRepositoryFactoryBean.kt
docs/manual/en/modules/bluetape4k-exposed-spring-boot-jdbc.md
docs/manual/en/modules/bluetape4k-exposed-spring-boot-r2dbc.md
```

- [ ] **Step 3: JDBC/R2DBC Architecture Diagram을 비교·확대 가능하게 배치한다**

큰 화면에서는 두 Diagram을 나란히, 작은 화면에서는 native tab으로 전환한다. hotspot은 `DataSource`, `springTransactionManager`, `R2dbcDatabase`, mapping context, registrar, factory의 책임 설명을 연다.

- [ ] **Step 4: 조건 sequence와 bean ownership matrix를 구현한다**

JDBC와 R2DBC의 차이를 다음 invariant로 고정한다.

```text
JDBC: DataSource present + missing springTransactionManager → auto-config creates SpringTransactionManager
JDBC: existing springTransactionManager → back-off
R2DBC: application creates R2dbcDatabase, connection pool, dispatcher, and lifecycle
R2DBC: module does not create or bridge Spring ReactiveTransactionManager
JDBC/R2DBC: registrar/factory registers repository proxies when explicitly enabled
R2DBC suspend methods do not gain Spring reactive @Transactional semantics from registration alone
```

- [ ] **Step 5: 구성 recipe와 실패 진단을 추가한다**

각 recipe는 제공해야 할 빈, 생성되는 빈, 생성되지 않는 빈, verification command를 포함한다. 각 failure는 관찰 증상, condition/log 확인 지점, source/test link를 포함한다.

- [ ] **Step 6: 생성·정적 검증을 실행한다**

Run:

```bash
node scripts/visual-companions/build.mjs
node scripts/visual-companions/build.mjs --check
node scripts/visual-companions/validate.mjs
node --test tests/visual-companions/build.test.mjs tests/visual-companions/validator.test.mjs
```

Expected: activation pair와 source contract PASS.

- [ ] **Step 7: 활성화 해설서를 커밋한다**

```bash
git add docs/visual-companions scripts/visual-companions tests/visual-companions
git commit
```

Commit intent: `Make Spring Boot activation and ownership explicit across both stacks`

---

### Task 7: Spring Boot HTML을 브라우저로 검증하고 사용자에게 보여 준다

**Prerequisite:** Task 6 PASS.

**Files:**

- Verify: `docs/visual-companions/en/spring-boot-exposed-activation.html`
- Verify: `docs/visual-companions/ko/spring-boot-exposed-activation.html`
- Generate: `docs/visual-companions/assets/spring-boot-exposed-activation.*.png`

- [ ] **Step 1: 결정적 fallback matrix를 생성·재검사한다**

```bash
node scripts/visual-companions/capture.mjs spring-boot-exposed-activation
node scripts/visual-companions/capture.mjs spring-boot-exposed-activation --check
```

Expected: 4개 PNG와 paired SHA-256 equality PASS.

- [ ] **Step 2: 7개 preset과 접근성 matrix를 검사한다**

```text
all presets × en/ko
desktop 1440px and narrow 360px
light/dark
JDBC/R2DBC Diagram compare and lightbox
keyboard-only preset change
reduced motion
console errors = 0
failed requests = 0
horizontal overflow = 0
```

- [ ] **Step 3: 4개 full-size PNG를 직접 검사한다**

Architecture Diagram의 텍스트가 읽히고, bean matrix와 sequence가 잘리지 않으며, 작은 화면에서 비교 tab이 의미를 유지하는지 확인한다.

- [ ] **Step 4: 실제 한국어 HTML을 사용자 브라우저로 연다**

```bash
open http://127.0.0.1:4173/docs/visual-companions/ko/spring-boot-exposed-activation.html
```

- [ ] **Step 5: 사용자 피드백을 반영하고 시각 승인을 기록한다**

Expected evidence: 사용자의 명시적 승인 메시지와 승인된 exact local commit SHA.

---

### Task 8: manifest와 영어·한국어 매뉴얼 연결을 새 생성물에 맞춘다

**Prerequisite:** Task 5와 Task 7 사용자 시각 승인 PASS.

**Files:**

- Modify: `docs/visual-companions/manifest.json`
- Modify: `docs/manual/en/guides/transaction-boundaries.md`
- Modify: `docs/manual/ko/guides/transaction-boundaries.md`
- Modify: `docs/manual/en/guides/spring-and-ktor.md`
- Modify: `docs/manual/ko/guides/spring-and-ktor.md`
- Modify: `tests/visual-companions/validator.test.mjs`

- [ ] **Step 1: manifest public ID와 route를 유지하며 새 파일·hash를 기록한다**

각 document는 다음 정보를 가진다.

```json
{
  "id": "jdbc-r2dbc-transaction-boundaries",
  "status": "approved",
  "public": true,
  "model": "docs/visual-companions/data/jdbc-r2dbc-transaction-boundaries.json",
  "architectureSha256": {},
  "locales": {
    "en": {
      "html": "docs/visual-companions/en/jdbc-r2dbc-transaction-boundaries.html",
      "fallbacks": {}
    },
    "ko": {
      "html": "docs/visual-companions/ko/jdbc-r2dbc-transaction-boundaries.html",
      "fallbacks": {}
    }
  }
}
```

- [ ] **Step 2: 매뉴얼의 기존 SVG/PNG와 public HTML link를 함께 유지한다**

영어와 한국어 페이지는 동일한 위치에서 같은 document ID의 locale route를 가리킨다. Markdown은 HTML을 embed하지 않는다.

- [ ] **Step 3: manifest/manual parity 테스트를 실행한다**

```bash
node scripts/visual-companions/validate.mjs
node --test tests/visual-companions/validator.test.mjs
rg -n 'jdbc-r2dbc-transaction-boundaries|spring-boot-exposed-activation' \
  docs/manual/en/guides docs/manual/ko/guides
```

Expected: 각 locale에 정확히 두 public route, 잘못된 cross-locale route 0.

- [ ] **Step 4: manifest/manual 통합을 커밋한다**

```bash
git add docs/visual-companions/manifest.json docs/manual tests/visual-companions
git commit
```

Commit intent: `Keep static diagrams and deep companions on one publication contract`

---

### Task 9: 전체 검증과 독립적인 pre-PR review를 수렴한다

**Files:**

- Verify: all files changed from `origin/develop`

- [ ] **Step 1: 결정적 생성과 캡처를 다시 검사한다**

```bash
node scripts/visual-companions/build.mjs --check
node scripts/visual-companions/capture.mjs jdbc-r2dbc-transaction-boundaries --check
node scripts/visual-companions/capture.mjs spring-boot-exposed-activation --check
```

- [ ] **Step 2: 전체 Node contract를 실행한다**

```bash
node --test tests/visual-companions/*.test.mjs
node scripts/visual-companions/validate.mjs
```

- [ ] **Step 3: 문서·링크·baseline debt를 검사한다**

```bash
./gradlew help --no-daemon
./gradlew exportManualModuleInventory --no-daemon
ruby scripts/manual/validate_manuals.rb build/manual/module-inventory-1.11.0.json docs/manual/manifest.yaml
```

Expected: Gradle help와 inventory export PASS. manual validator는 Issue #411의 기존 두 omission만 보고하며 새 오류는 0.

- [ ] **Step 4: Diagram과 diff hygiene를 검사한다**

```bash
xmllint --noout \
  docs/manual/assets/persistence/transaction-ownership.svg \
  docs/manual/assets/spring/jdbc-auto-configuration.svg \
  docs/manual/assets/spring/r2dbc-auto-configuration.svg
git diff --check origin/develop...HEAD
```

기존 SVG 자체를 변경하지 않으므로 CairoSVG 재생성은 hash/기존 PNG parity 검사로 N/A 처리한다. HTML fallback PNG 네 쌍은 Task 5와 7의 결정적 Chromium evidence를 사용한다.

- [ ] **Step 5: 최종 diff를 7개 관점으로 검토한다**

검토 관점:

```text
claim correctness
JPA comparison fairness
JDBC/R2DBC contract accuracy
English/Korean semantic parity
accessibility and responsive behavior
deterministic generation/capture
publication compatibility
```

P0/P1이 있으면 수정하고 영향받은 Step 1~4를 다시 실행한다. P2/P3는 scope가 작고 재검증이 저렴한 경우만 현재 PR에서 수정한다.

- [ ] **Step 6: plan checkbox와 PR용 DoD evidence를 갱신하고 커밋한다**

```bash
git add docs/superpowers/plans/2026-07-30-issue-410-exposed-visual-companions-plan.md
git commit
```

Commit intent: `Record the evidence required to trust the rebuilt companions`

---

### Task 10: PR #412를 exact head로 갱신하고 merge-ready에서 멈춘다

**Prerequisite:** Task 9 PASS, P0=0, P1=0.

**Files:**

- Update live: PR #412

- [ ] **Step 1: local head와 authorized refs를 확인한다**

```bash
git status --short
git branch --show-current
git rev-parse HEAD
git rev-parse origin/develop
```

Expected: clean worktree, head branch `docs/issue-410-visual-companions`, base `develop`.

- [ ] **Step 2: exact head를 force 없이 push하고 remote SHA를 읽는다**

```bash
git push origin docs/issue-410-visual-companions
git rev-parse HEAD
git ls-remote --heads origin docs/issue-410-visual-companions
```

Expected: local SHA = remote branch SHA.

- [ ] **Step 3: PR body와 metadata를 새 contract로 갱신한다**

PR body는 영어로 작성하고 다음을 포함한다.

```text
Parent epic: #409
Closes #410
JPA-versus-Exposed narrative
embedded Architecture Diagram source/hash ledger
two user visual-review approvals
deterministic capture matrix
Issue #411 baseline debt
site follow-up #304 immutable-ref handoff
final heading: ## DoD Status
```

assignee `debop`, milestone `1.12.0`, labels `documentation`과 `enhancement`를 live read-back으로 확인한다.

- [ ] **Step 4: exact-head CI와 current review state를 확인한다**

다음이 모두 current head에 대해 PASS여야 한다.

```text
required checks completed successfully
no unresolved review thread
reviewDecision has no blocker
mergeStateStatus is not BLOCKED/DIRTY
PR head equals local and remote head
DoD final heading is exact
```

- [ ] **Step 5: merge-ready 보고 후 새 merge 승인을 기다린다**

보고에는 exact PR URL, head SHA, CI, review/thread, 두 사용자 시각 승인, checklist count를 포함한다. 이전의 설계 승인이나 구현 승인을 merge 권한으로 사용하지 않는다.

Expected terminal state for this plan: `CG-16 PENDING`.

---

## 완료 시 증거 요약 형식

```text
Mode: Type E inline execution
Required checks: X/Y
N/A: count and concrete reason
Blocked: 0
Transaction user review: approved at <exact commit>
Activation user review: approved at <exact commit>
Local head: <sha>
Remote head: <sha>
PR head: <sha>
CI: successful checks on exact head
Review: P0=0, P1=0, unresolved threads=0
Merge: PENDING fresh exact-head approval
```
