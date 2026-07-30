import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';

import { validateRepository } from '../../scripts/visual-companions/validate.mjs';

const repositoryRoot = path.resolve(fileURLToPath(new URL('../../', import.meta.url)));
const manifestRelativePath = 'docs/visual-companions/manifest.json';
const execute = promisify(execFile);

async function read(relativePath) {
  return readFile(path.join(repositoryRoot, relativePath), 'utf8');
}

async function withFixture(mutator) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'exposed-visual-companions-'));
  const manifest = JSON.parse(await read(manifestRelativePath));
  const paths = new Set([manifestRelativePath]);

  for (const document of manifest.documents) {
    paths.add(document.source);
    paths.add(document.locales.en.html);
    paths.add(document.locales.ko.html);
  }

  for (const relativePath of paths) {
    const target = path.join(root, relativePath);
    await mkdir(path.dirname(target), { recursive: true });
    await writeFile(target, await read(relativePath));
  }

  try {
    await mutator({ root, manifest });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

async function expectInvalid(mutator, expected) {
  await withFixture(async ({ root, manifest }) => {
    await mutator({ root, manifest });
    await assert.rejects(
      validateRepository(root, manifestRelativePath),
      expected,
    );
  });
}

test('approved bilingual visual companions satisfy the repository contract', async () => {
  const result = await validateRepository(repositoryRoot, manifestRelativePath);
  assert.deepEqual(result, { documentCount: 2, localeFileCount: 4 });
});

test('transaction companion preserves the source-backed execution claims', async () => {
  for (const relativePath of [
    'docs/visual-companions/jdbc-r2dbc-transaction-boundaries.html',
    'docs/visual-companions/jdbc-r2dbc-transaction-boundaries.ko.html',
  ]) {
    const content = await read(relativePath);
    for (const marker of [
      '@Transactional',
      'suspendTransaction',
      'channelFlow',
      'data-view="jdbc"',
      'data-view="r2dbc"',
      'data-view="multi-call"',
      'SimpleExposedJdbcRepository.kt',
      'SimpleExposedR2dbcRepository.kt',
    ]) {
      assert.match(content, new RegExp(marker));
    }
    const multiCall = content.match(/<div id="panel-multi-call"[\s\S]*?<\/div>\s*<div class="legend"/)?.[0];
    assert.ok(multiCall, `${relativePath} must contain the multi-call panel`);
    assert.doesNotMatch(multiCall, /data-owner="(?:jdbc|r2dbc)"/);
  }
});

test('activation companion preserves conditions, back-off, and R2DBC ownership', async () => {
  for (const relativePath of [
    'docs/visual-companions/spring-boot-exposed-activation.html',
    'docs/visual-companions/spring-boot-exposed-activation.ko.html',
  ]) {
    const content = await read(relativePath);
    for (const marker of [
      'data-condition="entity-class"',
      'data-condition="data-source"',
      'data-condition="transaction-manager"',
      'data-condition="enable-jdbc"',
      'data-condition="enable-r2dbc"',
      'data-condition="mapping-context"',
      'springTransactionManager',
      'ConnectionPool',
      'R2dbcDatabase',
      'ExposedMappingContext',
    ]) {
      assert.match(content, new RegExp(marker));
    }
    assert.match(content, /R2DBC[^<]*(?:pool|풀)[\s\S]*(?:is created|does not create|만들지 않)/i);
    assert.match(content, /(?:Only the R2DBC mapping bean backs off|R2DBC 매핑 빈만 Back-off)/);
    assert.match(content, /themeToggle\.setAttribute\('aria-pressed'/);
  }
});

test('duplicate document ids are rejected', async () => {
  await expectInvalid(async ({ root, manifest }) => {
    manifest.documents[1].id = manifest.documents[0].id;
    await writeFile(path.join(root, manifestRelativePath), `${JSON.stringify(manifest, null, 2)}\n`);
  }, /documents\[1\]\.id is duplicated/);
});

test('documents must declare exactly the en and ko locales', async () => {
  await expectInvalid(async ({ root, manifest }) => {
    manifest.documents[0].locales.fr = manifest.documents[0].locales.en;
    await writeFile(path.join(root, manifestRelativePath), `${JSON.stringify(manifest, null, 2)}\n`);
  }, /locales must contain exactly en and ko/);
});

test('external runtime dependencies are rejected', async () => {
  await expectInvalid(async ({ root, manifest }) => {
    const htmlPath = path.join(root, manifest.documents[0].locales.en.html);
    const html = await readFile(htmlPath, 'utf8');
    await writeFile(htmlPath, html.replace('</head>', '<script src="https://example.com/app.js"></script></head>'));
  }, /contains a forbidden runtime dependency/);
});

test('offline runtime validation rejects alternate network-loading surfaces', async () => {
  const injections = [
    '<script type="module">import("https://example.com/app.js")</script>',
    '<object data="https://example.com/object"></object>',
    '<embed src="https://example.com/embed">',
    '<img srcset="https://example.com/image.png 1x" alt="">',
    '<link rel="preload" href="https://example.com/font.woff2">',
    '<style>@font-face{src:url(font.woff2)}</style>',
  ];

  for (const injection of injections) {
    await expectInvalid(async ({ root, manifest }) => {
      const htmlPath = path.join(root, manifest.documents[0].locales.en.html);
      const html = await readFile(htmlPath, 'utf8');
      await writeFile(htmlPath, html.replace('</head>', `${injection}</head>`));
    }, /contains a forbidden runtime dependency/);
  }
});

test('source links must resolve inside the develop tree', async () => {
  await expectInvalid(async ({ root, manifest }) => {
    const htmlPath = path.join(root, manifest.documents[0].locales.en.html);
    const html = await readFile(htmlPath, 'utf8');
    await writeFile(
      htmlPath,
      html.replace(
        'docs/manual/en/guides/transaction-boundaries.md',
        'docs/manual/en/guides/missing-transaction-guide.md',
      ),
    );
  }, /source link does not resolve/);
});

test('locale structure drift is rejected', async () => {
  await expectInvalid(async ({ root, manifest }) => {
    const htmlPath = path.join(root, manifest.documents[0].locales.ko.html);
    const html = await readFile(htmlPath, 'utf8');
    await writeFile(htmlPath, html.replace('id="view-jdbc"', 'id="view-jdbc-ko"'));
  }, /locale control ids must match/);
});

test('required standalone document surfaces are enforced', async () => {
  await expectInvalid(async ({ root, manifest }) => {
    const htmlPath = path.join(root, manifest.documents[0].locales.en.html);
    const html = await readFile(htmlPath, 'utf8');
    await writeFile(
      htmlPath,
      html
        .replace(/<meta name="viewport"[^>]+>/, '')
        .replace(/@media \(prefers-reduced-motion: reduce\)[^{]*\{[^}]+\}/, '')
        .replace('<main', '<div')
        .replace('</main>', '</div>'),
    );
  }, /must declare a responsive viewport[\s\S]*must define reduced-motion behavior[\s\S]*must contain semantic main content/);
});

test('declared views and reciprocal locale links are enforced', async () => {
  await expectInvalid(async ({ root, manifest }) => {
    const document = manifest.documents[0];
    const htmlPath = path.join(root, document.locales.en.html);
    const html = await readFile(htmlPath, 'utf8');
    await writeFile(
      htmlPath,
      html
        .replace('data-view="multi-call"', 'data-view="multi-call-missing"')
        .replace(path.basename(document.locales.ko.html), 'missing-locale.html'),
    );
  }, /must link to its ko locale[\s\S]*must represent declared view multi-call/);
});

test('manual pages link to the locale-specific public routes', async () => {
  const expectations = [
    [
      'docs/manual/en/guides/transaction-boundaries.md',
      'https://bluetape4k.github.io/visual-companions/bluetape4k-exposed/jdbc-r2dbc-transaction-boundaries/',
    ],
    [
      'docs/manual/ko/guides/transaction-boundaries.md',
      'https://bluetape4k.github.io/ko/visual-companions/bluetape4k-exposed/jdbc-r2dbc-transaction-boundaries/',
    ],
    [
      'docs/manual/en/guides/spring-and-ktor.md',
      'https://bluetape4k.github.io/visual-companions/bluetape4k-exposed/spring-boot-exposed-activation/',
    ],
    [
      'docs/manual/ko/guides/spring-and-ktor.md',
      'https://bluetape4k.github.io/ko/visual-companions/bluetape4k-exposed/spring-boot-exposed-activation/',
    ],
  ];

  for (const [relativePath, route] of expectations) {
    assert.match(await read(relativePath), new RegExp(route.replaceAll('/', '\\/')));
  }
});

test('locale documents link to their matching manual source', async () => {
  const expectations = [
    ['docs/visual-companions/jdbc-r2dbc-transaction-boundaries.html', 'docs/manual/en/guides/transaction-boundaries.md'],
    ['docs/visual-companions/jdbc-r2dbc-transaction-boundaries.ko.html', 'docs/manual/ko/guides/transaction-boundaries.md'],
    ['docs/visual-companions/spring-boot-exposed-activation.html', 'docs/manual/en/guides/spring-and-ktor.md'],
    ['docs/visual-companions/spring-boot-exposed-activation.ko.html', 'docs/manual/ko/guides/spring-and-ktor.md'],
  ];

  for (const [relativePath, sourcePath] of expectations) {
    assert.match(await read(relativePath), new RegExp(sourcePath.replaceAll('/', '\\/')));
  }
});

test('CLI accepts an explicit manifest path', async () => {
  await withFixture(async ({ root, manifest }) => {
    manifest.documents[1].id = manifest.documents[0].id;
    await writeFile(path.join(root, 'custom-manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`);
    await assert.rejects(
      execute(
        'node',
        [path.join(repositoryRoot, 'scripts/visual-companions/validate.mjs'), 'custom-manifest.json'],
        { cwd: root },
      ),
      /documents\[1\]\.id is duplicated/,
    );
  });
});
