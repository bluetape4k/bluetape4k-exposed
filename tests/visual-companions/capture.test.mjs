import assert from 'node:assert/strict';
import test from 'node:test';

import {
  captureTargets,
  chromeArguments,
} from '../../scripts/visual-companions/capture.mjs';

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
