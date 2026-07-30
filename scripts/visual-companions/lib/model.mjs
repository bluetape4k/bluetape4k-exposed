import { createHash } from 'node:crypto';
import { readFile, readdir, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const locales = ['en', 'ko'];

export function repositoryPath(root) {
  if (root instanceof URL) return path.resolve(fileURLToPath(root));
  return path.resolve(root);
}

export function containedPath(root, relativePath) {
  const repositoryRoot = repositoryPath(root);
  if (typeof relativePath !== 'string' || relativePath.length === 0 || path.isAbsolute(relativePath)) {
    throw new Error(`path must be repository-relative: ${relativePath ?? '<missing>'}`);
  }
  const target = path.resolve(repositoryRoot, relativePath);
  if (target !== repositoryRoot && !target.startsWith(`${repositoryRoot}${path.sep}`)) {
    throw new Error(`path escapes repository: ${relativePath}`);
  }
  return target;
}

function assertUniqueIds(model, field) {
  if (!Array.isArray(model[field])) throw new Error(`${model.id}: ${field} must be an array`);
  const ids = model[field].map(({ id }) => id);
  if (ids.some((id) => !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(id ?? ''))) {
    throw new Error(`${model.id}: invalid ${field} id`);
  }
  if (new Set(ids).size !== ids.length) {
    throw new Error(`${model.id}: duplicate ${field} id`);
  }
}

export function validateCompanionModel(model) {
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(model.id ?? '')) {
    throw new Error(`invalid companion id: ${model.id}`);
  }
  if (Object.keys(model.locales ?? {}).sort().join(',') !== locales.join(',')) {
    throw new Error(`${model.id}: locales must be exactly en and ko`);
  }
  for (const locale of locales) {
    if (!model.locales[locale]?.title) throw new Error(`${model.id}: missing ${locale} title`);
  }
  for (const field of ['sections', 'scenarios', 'sources']) assertUniqueIds(model, field);
  if (!Array.isArray(model.architecture) || model.architecture.length === 0) {
    throw new Error(`${model.id}: architecture must not be empty`);
  }
  for (const source of model.sources) {
    if (!source.sourcePath || !source.testPath) {
      throw new Error(`${model.id}.${source.id}: sourcePath and testPath are required`);
    }
  }
  return model;
}

export async function validateModelPaths(root, model) {
  for (const asset of model.architecture) {
    await stat(containedPath(root, asset.source));
    await stat(containedPath(root, asset.fallback));
  }
  for (const source of model.sources) {
    await stat(containedPath(root, source.sourcePath));
    await stat(containedPath(root, source.testPath));
  }
  return model;
}

export async function loadCompanionModels(root) {
  const directory = containedPath(root, 'docs/visual-companions/data');
  const names = (await readdir(directory))
    .filter((name) => name.endsWith('.json'))
    .sort();
  const models = [];
  for (const name of names) {
    const model = JSON.parse(await readFile(path.join(directory, name), 'utf8'));
    validateCompanionModel(model);
    await validateModelPaths(root, model);
    models.push(model);
  }
  return models;
}

export async function loadArchitectureAsset(root, asset) {
  const source = containedPath(root, asset.source);
  const fallback = containedPath(root, asset.fallback);
  await stat(fallback);
  const svg = await readFile(source, 'utf8');
  const sha256 = createHash('sha256').update(svg).digest('hex');
  if (asset.sha256 && asset.sha256 !== sha256) {
    throw new Error(`${asset.id}: architecture SVG digest mismatch`);
  }
  return {
    id: asset.id,
    source: asset.source,
    fallback: asset.fallback,
    sha256,
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

export function repositoryUrl(root) {
  return pathToFileURL(repositoryPath(root));
}
