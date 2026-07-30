#!/usr/bin/env node

import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  containedPath,
  loadArchitectureAsset,
  loadCompanionModels,
  repositoryPath,
} from './lib/model.mjs';
import { renderDocument } from './lib/render.mjs';

function normalized(content) {
  return `${content.replaceAll('\r\n', '\n').trimEnd()}\n`;
}

async function writeOrCheckOutputs(root, outputs, check) {
  for (const output of outputs) {
    const target = containedPath(root, output.path);
    const expected = normalized(output.content);
    let actual;
    try {
      actual = await readFile(target, 'utf8');
    } catch {
      actual = null;
    }
    if (check) {
      if (actual !== expected) {
        throw new Error(`generated visual companion differs: ${output.path}`);
      }
      continue;
    }
    await mkdir(path.dirname(target), { recursive: true });
    await writeFile(target, expected);
  }
  return outputs.map(({ path: outputPath }) => outputPath);
}

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

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  try {
    const outputs = await buildRepository({
      root: repositoryPath(new URL('../../', import.meta.url)),
      check: process.argv.includes('--check'),
    });
    console.log(`${process.argv.includes('--check') ? 'Checked' : 'Generated'} ${outputs.length} visual companion files`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
