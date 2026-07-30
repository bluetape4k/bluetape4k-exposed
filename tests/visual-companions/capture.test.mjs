import assert from 'node:assert/strict';
import test from 'node:test';

import * as capture from '../../scripts/visual-companions/capture.mjs';

test('capture matrix is bounded to two locales and two explicit themes', () => {
  assert.deepEqual(
    capture.captureTargets('jdbc-r2dbc-transaction-boundaries'),
    [
      'jdbc-r2dbc-transaction-boundaries.en.light.png',
      'jdbc-r2dbc-transaction-boundaries.en.dark.png',
      'jdbc-r2dbc-transaction-boundaries.ko.light.png',
      'jdbc-r2dbc-transaction-boundaries.ko.dark.png',
    ],
  );
});

test('chrome runs without background network or animation drift', () => {
  const args = capture.chromeArguments('/tmp/profile', 9222);

  assert.ok(args.includes('--headless=new'));
  assert.ok(args.includes('--disable-background-networking'));
  assert.ok(args.includes('--disable-gpu'));
  assert.ok(args.includes('--force-device-scale-factor=1'));
  assert.ok(args.includes('--remote-debugging-port=9222'));
});

test('audit selects the preferred transaction scenario or a document fallback', () => {
  assert.equal(
    capture.auditScenarioId(['jdbc-single', 'r2dbc-flow-escape', 'rollback-or-cancellation']),
    'r2dbc-flow-escape',
  );
  assert.equal(
    capture.auditScenarioId(['jdbc-ready', 'r2dbc-ready', 'entity-class-absent']),
    'entity-class-absent',
  );
});
