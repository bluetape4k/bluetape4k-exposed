import assert from 'node:assert/strict';
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
});

test('transaction companion compares JPA with Exposed before the Exposed detail diagram', async () => {
  const [model] = await loadCompanionModels(root);

  assert.deepEqual(
    model.architecture.map(({ id }) => id),
    ['jpa-exposed-comparison', 'transaction-ownership'],
  );
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
