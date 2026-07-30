import assert from 'node:assert/strict';
import test from 'node:test';

import { buildRepository } from '../../scripts/visual-companions/build.mjs';
import {
  loadCompanionModels,
  structuralFingerprint,
} from '../../scripts/visual-companions/lib/model.mjs';

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
