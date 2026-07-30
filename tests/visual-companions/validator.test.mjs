import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { cp, mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
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
  await cp(
    path.join(repositoryRoot, 'docs/visual-companions'),
    path.join(root, 'docs/visual-companions'),
    { recursive: true },
  );
  await cp(
    path.join(repositoryRoot, 'docs/manual'),
    path.join(root, 'docs/manual'),
    { recursive: true },
  );
  for (const document of manifest.documents) {
    const model = JSON.parse(await read(document.data));
    for (const relativePath of [
      document.source,
      ...model.sources.flatMap(({ sourcePath, testPath }) => [sourcePath, testPath]),
    ]) {
      const target = path.join(root, relativePath);
      await mkdir(path.dirname(target), { recursive: true });
      await cp(path.join(repositoryRoot, relativePath), target);
    }
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

test('generated documents use locale directories and declare their data models', async () => {
  const manifest = JSON.parse(await read(manifestRelativePath));

  for (const document of manifest.documents) {
    assert.equal(document.data, `docs/visual-companions/data/${document.id}.json`);
    assert.equal(document.locales.en.html, `docs/visual-companions/en/${document.id}.html`);
    assert.equal(document.locales.ko.html, `docs/visual-companions/ko/${document.id}.html`);
    if (document.status === 'approved') {
      assert.deepEqual(Object.keys(document.locales.en.captures).sort(), ['dark', 'light']);
      assert.deepEqual(Object.keys(document.locales.ko.captures).sort(), ['dark', 'light']);
    } else {
      assert.equal(document.status, 'pending-review');
      assert.equal(document.public, false);
    }
  }
});

test('transaction companion preserves the source-backed execution claims', async () => {
  for (const relativePath of [
    'docs/visual-companions/en/jdbc-r2dbc-transaction-boundaries.html',
    'docs/visual-companions/ko/jdbc-r2dbc-transaction-boundaries.html',
  ]) {
    const content = await read(relativePath);
    for (const marker of [
      '@Transactional',
      'suspendTransaction',
      'channelFlow',
      'data-scenario="jdbc-single"',
      'data-scenario="r2dbc-single"',
      'data-scenario="r2dbc-flow-escape"',
      'JdbcRepository.kt',
      'R2dbcRepository.kt',
    ]) {
      assert.match(content, new RegExp(marker));
    }
    assert.match(content, /JPA[\s\S]*dirty checking[\s\S]*Exposed[\s\S]*(?:explicit|명시적)/i);
    assert.match(content, /data-sequence="rollback-or-cancellation"/);
  }
});

test('transaction companion is approved for public publication', async () => {
  const manifest = JSON.parse(await read(manifestRelativePath));
  const transaction = manifest.documents.find(({ id }) => id === 'jdbc-r2dbc-transaction-boundaries');
  assert.equal(transaction.status, 'approved');
  assert.equal(transaction.public, true);
});

test('activation companion stays private while its deep redesign is gated', async () => {
  const manifest = JSON.parse(await read(manifestRelativePath));
  const activation = manifest.documents.find(({ id }) => id === 'spring-boot-exposed-activation');
  assert.equal(activation.status, 'pending-review');
  assert.equal(activation.public, false);
  assert.equal(activation.locales.en.captures, undefined);
  assert.equal(activation.locales.ko.captures, undefined);
});

test('activation companion exposes condition, ownership, recipe, and failure surfaces', async () => {
  for (const relativePath of [
    'docs/visual-companions/en/spring-boot-exposed-activation.html',
    'docs/visual-companions/ko/spring-boot-exposed-activation.html',
  ]) {
    const content = await read(relativePath);
    for (const marker of [
      'data-scenario-detail="jdbc-ready"',
      'data-condition-state="matched"',
      'data-result-state="created"',
      'class="architecture-compare"',
      'id="configuration-recipes"',
      'id="failure-diagnostics"',
      'data-sequence="entity-class-absent"',
      'ExposedMappingContext',
      'transactionManagerRef',
      'R2dbcDatabase',
    ]) {
      assert.match(content, new RegExp(marker));
    }
  }
});

test('generator owns only locale-specific activation documents', async () => {
  await assert.rejects(read('docs/visual-companions/spring-boot-exposed-activation.html'), /ENOENT/);
  await assert.rejects(read('docs/visual-companions/spring-boot-exposed-activation.ko.html'), /ENOENT/);
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

test('localized architecture assets must declare both locale paths', async () => {
  await expectInvalid(async ({ root, manifest }) => {
    const modelPath = path.join(root, manifest.documents[0].data);
    const model = JSON.parse(await readFile(modelPath, 'utf8'));
    delete model.architecture[0].source.ko;
    await writeFile(modelPath, `${JSON.stringify(model, null, 2)}\n`);
  }, /jpa-exposed-comparison\.source must contain exactly en and ko/);
});

test('localized architecture digests are verified per locale', async () => {
  await expectInvalid(async ({ root, manifest }) => {
    const modelPath = path.join(root, manifest.documents[0].data);
    const model = JSON.parse(await readFile(modelPath, 'utf8'));
    model.architecture[0].sha256.ko = '0'.repeat(64);
    await writeFile(modelPath, `${JSON.stringify(model, null, 2)}\n`);
  }, /jpa-exposed-comparison: architecture SVG digest mismatch/);
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
    await writeFile(htmlPath, html.replace('id="scenario-jdbc-single"', 'id="scenario-jdbc-single-ko"'));
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
        .replace('data-scenario="jdbc-multi-repository"', 'data-scenario="missing"')
        .replace(`../ko/${document.id}.html`, '../ko/missing-locale.html'),
    );
  }, /must link to its ko locale[\s\S]*missing scenario jdbc-multi-repository/);
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
    ['docs/visual-companions/en/jdbc-r2dbc-transaction-boundaries.html', 'docs/manual/en/guides/transaction-boundaries.md'],
    ['docs/visual-companions/ko/jdbc-r2dbc-transaction-boundaries.html', 'docs/manual/ko/guides/transaction-boundaries.md'],
    ['docs/visual-companions/en/spring-boot-exposed-activation.html', 'docs/manual/en/guides/spring-and-ktor.md'],
    ['docs/visual-companions/ko/spring-boot-exposed-activation.html', 'docs/manual/ko/guides/spring-and-ktor.md'],
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
