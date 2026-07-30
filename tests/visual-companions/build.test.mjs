import assert from 'node:assert/strict';
import test from 'node:test';

import { buildRepository } from '../../scripts/visual-companions/build.mjs';
import {
  loadCompanionModels,
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
  assert.doesNotMatch(document, /\.message-line::after \{[^}]*var\(--cyan\)/);
  assert.doesNotMatch(document, /message-return message-forward/);
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
