import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { buildRepository } from '../../scripts/visual-companions/build.mjs';
import {
  loadArchitectureAsset,
  loadCompanionModels,
  localizedArchitectureValue,
  structuralFingerprint,
} from '../../scripts/visual-companions/lib/model.mjs';
import {
  renderDocument,
  renderSequence,
} from '../../scripts/visual-companions/lib/render.mjs';

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
  assert.deepEqual(
    models[1].scenarios.map(({ id }) => id),
    [
      'jdbc-ready',
      'r2dbc-ready',
      'dual-stack',
      'custom-jdbc-manager',
      'custom-mapping-context',
      'missing-infrastructure',
      'entity-class-absent',
    ],
  );
  assert.deepEqual(
    models[1].sections.map(({ id }) => id),
    [
      'mental-model',
      'architecture',
      'scenario-explorer',
      'sequence',
      'ownership-matrix',
      'configuration-recipes',
      'failure-diagnostics',
      'tradeoffs',
      'evidence',
    ],
  );
});

test('activation companion models conditions, results, sequences, and source evidence', async () => {
  const models = await loadCompanionModels(root);
  const activation = models.find(({ id }) => id === 'spring-boot-exposed-activation');

  assert.equal(activation.kind, 'activation');
  assert.equal(activation.scenarios.length, 7);
  assert.ok(activation.sources.length >= 10);
  for (const scenario of activation.scenarios) {
    assert.ok(scenario.conditions.length >= 2, `${scenario.id} conditions`);
    assert.ok(scenario.results.length >= 2, `${scenario.id} results`);
    assert.ok(scenario.participants.length >= 4, `${scenario.id} participants`);
    assert.ok(scenario.messages.length >= 4, `${scenario.id} messages`);
    assert.ok(scenario.locales.en.label);
    assert.ok(scenario.locales.ko.label);
    assert.ok(scenario.locales.en.summary);
    assert.ok(scenario.locales.ko.summary);
    assert.ok(scenario.locales.en.outcome);
    assert.ok(scenario.locales.ko.outcome);
  }
  for (const source of activation.sources) {
    assert.ok(source.sourcePath);
    assert.ok(source.testPath);
    assert.ok(source.verificationCommand);
    assert.ok(source.locales.en.claim);
    assert.ok(source.locales.ko.claim);
  }
});

test('transaction companion compares JPA with Exposed before the Exposed detail diagram', async () => {
  const [model] = await loadCompanionModels(root);

  assert.deepEqual(
    model.architecture.map(({ id }) => id),
    ['jpa-exposed-comparison', 'transaction-ownership'],
  );
});

test('comparison diagrams connect application ownership to the outer persistence lanes', async () => {
  const [model] = await loadCompanionModels(root);

  for (const locale of ['en', 'ko']) {
    const source = localizedArchitectureValue(
      model.architecture[0].source,
      locale,
      'source',
    );
    const svg = await readFile(new URL(`../../${source}`, import.meta.url), 'utf8');
    const connectors = [...svg.matchAll(
      /<path data-connector="application-to-([^"]+)" d="([^"]+)"/g,
    )].map((match) => [match[1], match[2]]);

    assert.deepEqual(connectors, [
      ['jpa-hibernate-lane', 'M520 254 V330'],
      ['exposed-lane', 'M1480 254 V330'],
    ]);
    assert.match(svg, /data-card="application-service"/);
    assert.match(svg, /data-card="jpa-hibernate-lane"/);
    assert.match(svg, /data-card="exposed-lane"/);
    assert.doesNotMatch(svg, /data-connector="application-to-[^"]+"[^>]*V450/);
  }
});

test('localized architecture values select the requested locale and preserve shared assets', () => {
  assert.equal(
    localizedArchitectureValue({
      en: 'docs/manual/assets/persistence/jpa-exposed-comparison.en.svg',
      ko: 'docs/manual/assets/persistence/jpa-exposed-comparison.ko.svg',
    }, 'ko', 'source'),
    'docs/manual/assets/persistence/jpa-exposed-comparison.ko.svg',
  );
  assert.equal(
    localizedArchitectureValue(
      'docs/manual/assets/persistence/transaction-ownership.svg',
      'en',
      'source',
    ),
    'docs/manual/assets/persistence/transaction-ownership.svg',
  );
  assert.throws(
    () => localizedArchitectureValue({ en: 'comparison.en.svg' }, 'ko', 'source'),
    /missing ko source/,
  );
});

test('architecture assets embed the rendered PNG to remain stable across theme repaint', async () => {
  const [model] = await loadCompanionModels(root);
  const asset = await loadArchitectureAsset(root, model.architecture[0], 'ko');

  assert.match(asset.dataUri, /^data:image\/png;base64,/);
  assert.equal(
    asset.source,
    'docs/manual/assets/persistence/jpa-exposed-comparison.ko.svg',
  );
  assert.equal(
    asset.fallback,
    'docs/manual/assets/persistence/jpa-exposed-comparison.ko.png',
  );
});

test('locale prose changes do not alter the structural fingerprint', async () => {
  const [model] = await loadCompanionModels(root);
  const translated = structuredClone(model);
  translated.locales.en.title = 'Changed English title';
  translated.locales.ko.title = '변경된 한국어 제목';

  assert.equal(structuralFingerprint(translated), structuralFingerprint(model));
});

test('build check accepts generated files that match their models', async () => {
  await assert.doesNotReject(buildRepository({ root, check: true }));
});

test('sequence messages connect participant centers with direction-aware arrowheads', async () => {
  const [model] = await loadCompanionModels(root);
  const scenario = model.scenarios[0];
  const sequence = renderSequence({ scenario, locale: 'ko', active: true });
  const document = renderDocument({
    model,
    locale: 'ko',
    architectureAssets: model.architecture.map((asset) => ({
      ...asset,
      dataUri: 'data:image/png;base64,AA==',
    })),
  });

  assert.match(
    sequence,
    /data-message-kind="call"[\s\S]*data-direction="forward"[\s\S]*--line-start:12\.5%;--line-end:37\.5%/,
  );
  assert.match(
    sequence,
    /data-message-kind="return"[\s\S]*data-direction="reverse"[\s\S]*--line-start:62\.5%;--line-end:37\.5%/,
  );
  assert.match(document, /\.message-line \{[^}]*color: var\(--message-color\)[^}]*border-top: 2px solid currentColor/);
  assert.match(document, /\.message-forward \.message-line::after \{[^}]*border-left-color: currentColor/);
  assert.match(document, /\.message-reverse \.message-line::after \{[^}]*border-right-color: currentColor/);
  assert.match(document, /\.lifeline \{[^}]*var\(--role-line\)/);
  assert.match(document, /\.activation \{[^}]*var\(--role-active\)[^}]*var\(--role-active-soft\)/);
  assert.match(document, /\.message \{[^}]*--message-color: var\(--call-line\)/);
  assert.match(document, /class="sequence-scroll" tabindex="0"/);
  assert.match(document, /@media \(max-width: 800px\)[\s\S]*\.sequence-scroll \{ overflow-x: auto;/);
  assert.match(
    document,
    /\.sequence-participant strong \{[^}]*display: flex[^}]*align-items: center[^}]*justify-content: center/,
  );
  assert.doesNotMatch(document, /\.lifeline \{[^}]*var\(--call-line\)/);
  assert.doesNotMatch(document, /\.message-line::after \{[^}]*var\(--cyan\)/);
  assert.doesNotMatch(document, /message-return message-forward/);
});

test('reader-facing document omits authoring and validation metadata', async () => {
  const [model] = await loadCompanionModels(root);
  const document = renderDocument({
    model,
    locale: 'ko',
    architectureAssets: model.architecture.map((asset) => ({
      ...asset,
      dataUri: 'data:image/png;base64,AA==',
    })),
  });

  assert.doesNotMatch(document, />[^<]*Issue #410[^<]*</);
  assert.doesNotMatch(document, />SHA-256 /);
  assert.doesNotMatch(document, />오프라인 단일 HTML</);
  assert.doesNotMatch(document, />설계 문서</);
  assert.doesNotMatch(document, />jdbc-controller</);
  assert.match(document, /data-source="[^"]*issue-410[^"]*"/);
  assert.match(document, /id="status" class="sr-only" aria-live="polite"/);
  assert.match(document, />트랜잭션 경계 매뉴얼</);
});

test('evidence links keep full targets but display concise source names', async () => {
  const [model] = await loadCompanionModels(root);
  const document = renderDocument({
    model,
    locale: 'ko',
    architectureAssets: model.architecture.map((asset) => ({
      ...asset,
      dataUri: 'data:image/png;base64,AA==',
    })),
  });

  assert.match(
    document,
    /href="[^"]*exposed\/jdbc\/src\/main\/kotlin\/io\/bluetape4k\/exposed\/jdbc\/repository\/JdbcRepository\.kt"><code>JdbcRepository<\/code>/,
  );
  assert.match(
    document,
    /href="[^"]*ProductController\.kt"><code>ProductController<\/code>/,
  );
  assert.match(
    document,
    /href="[^"]*coroutine-transactions\.md"><code>coroutine-transactions\.md<\/code>/,
  );
  assert.doesNotMatch(document, /<code>exposed\/jdbc\/src\/main\/kotlin/);
});

test('activation evidence uses concise source labels without discarding link targets', async () => {
  const models = await loadCompanionModels(root);
  const model = models.find(({ id }) => id === 'spring-boot-exposed-activation');
  const document = renderDocument({
    model,
    locale: 'ko',
    architectureAssets: model.architecture.map((asset) => ({
      ...asset,
      dataUri: 'data:image/png;base64,AA==',
    })),
  });

  assert.match(
    document,
    /href="[^"]*ExposedSpringDataAutoConfiguration\.kt"><code>Exposed<wbr>Spring<wbr>Data<wbr>Auto<wbr>Configuration<\/code>/,
  );
  assert.match(
    document,
    /href="[^"]*spring-and-ktor\.md"><code>spring-and-ktor\.md<\/code>/,
  );
  assert.doesNotMatch(document, /<code>spring-boot\/jdbc\/src\/main\/kotlin/);
});

test('activation evidence keeps only reader-facing source and verification links', async () => {
  const models = await loadCompanionModels(root);
  const model = models.find(({ id }) => id === 'spring-boot-exposed-activation');
  const document = renderDocument({
    model,
    locale: 'ko',
    architectureAssets: model.architecture.map((asset) => ({
      ...asset,
      dataUri: 'data:image/png;base64,AA==',
    })),
  });

  const evidenceHeader = document.match(
    /<div class="table-wrap evidence-table">[\s\S]*?<thead><tr>([\s\S]*?)<\/tr><\/thead>/,
  )?.[1];
  assert.ok(evidenceHeader);
  assert.equal((evidenceHeader.match(/<th scope="col">/g) ?? []).length, 2);
  assert.match(evidenceHeader, />주장<\/th>[\s\S]*>근거 파일<\/th>/);
  assert.doesNotMatch(evidenceHeader, />운영 소스<\/th>|>테스트·매뉴얼<\/th>|>검증<\/th>/);
  assert.match(document, /<table class="grouped-evidence-table">/);

  const firstEvidenceFiles = document.match(
    /<td class="evidence-files">([\s\S]*?)<\/td>/,
  )?.[1];
  assert.ok(firstEvidenceFiles);
  assert.equal((firstEvidenceFiles.match(/class="evidence-file-row"/g) ?? []).length, 2);
  assert.match(firstEvidenceFiles, /class="evidence-file-kind">소스<\/span>/);
  assert.match(firstEvidenceFiles, /class="evidence-file-kind">검증<\/span>/);
  assert.match(firstEvidenceFiles, /data-source-link[\s\S]*data-source-link/);
  assert.match(
    document,
    /\.evidence-table td\.evidence-files a \{[^}]*white-space: normal;[^}]*overflow-wrap: normal;/,
  );
  assert.match(document, /\.grouped-evidence-table tbody th \{ width: 50%; \}/);
  assert.match(
    document,
    /@media \(max-width: 800px\)[\s\S]*\.grouped-evidence-table \{[^}]*min-width: 0;[^}]*table-layout: fixed;/,
  );
  assert.match(
    document,
    /@media \(max-width: 800px\)[\s\S]*\.grouped-evidence-table thead th:first-child \{ width: 38%; \}/,
  );
  assert.doesNotMatch(document, /node scripts\/visual-companions\/validate\.mjs/);
  assert.doesNotMatch(document, /<td><code>\.\/gradlew /);
});

test('activation sequences begin at application input and return framework results with localized labels', async () => {
  const models = await loadCompanionModels(root);
  const model = models.find(({ id }) => id === 'spring-boot-exposed-activation');

  for (const scenario of model.scenarios) {
    assert.equal(scenario.participants[0].id, 'application', scenario.id);
    const participantIndex = new Map(
      scenario.participants.map(({ id }, index) => [id, index]),
    );
    for (const message of scenario.messages.filter(({ kind }) => kind === 'return')) {
      assert.ok(
        participantIndex.get(message.from) > participantIndex.get(message.to),
        `${scenario.id} return must point to an earlier participant`,
      );
    }
  }

  const scenario = model.scenarios.find(({ id }) => id === 'jdbc-ready');
  const sequence = renderSequence({
    scenario,
    locale: 'ko',
    active: true,
    labels: model.locales.ko,
  });
  assert.match(sequence, /data-from="application"[\s\S]*data-to="spring-boot"/);
  assert.match(sequence, /data-from="integration"[\s\S]*data-to="application"/);
  assert.match(sequence, /class="message-kind">호출<\/span>/);
  assert.match(sequence, /class="message-kind">반환<\/span>/);
  assert.match(sequence, /outcome-created">생성됨<\/span>/);
});
