import { createHash } from 'node:crypto';
import { readFile, readdir, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const locales = ['en', 'ko'];

export function localizedArchitectureValue(value, locale, field = 'value') {
  if (!locales.includes(locale)) throw new Error(`unsupported architecture locale: ${locale}`);
  if (typeof value === 'string' && value.length > 0) return value;
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    if (typeof value[locale] !== 'string' || value[locale].length === 0) {
      throw new Error(`missing ${locale} ${field}`);
    }
    const keys = Object.keys(value).sort();
    if (keys.join(',') !== locales.join(',')) {
      throw new Error(`${field} must contain exactly en and ko`);
    }
    return value[locale];
  }
  throw new Error(`${field} must be a shared string or an en/ko object`);
}

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
  const architectureIds = model.architecture.map(({ id }) => id);
  if (architectureIds.some((id) => !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(id ?? ''))) {
    throw new Error(`${model.id}: invalid architecture id`);
  }
  if (new Set(architectureIds).size !== architectureIds.length) {
    throw new Error(`${model.id}: duplicate architecture id`);
  }
  for (const asset of model.architecture) {
    for (const locale of locales) {
      localizedArchitectureValue(asset.source, locale, `${asset.id}.source`);
      localizedArchitectureValue(asset.fallback, locale, `${asset.id}.fallback`);
      if (asset.sha256) {
        const digest = localizedArchitectureValue(asset.sha256, locale, `${asset.id}.sha256`);
        if (!/^[a-f0-9]{64}$/.test(digest)) {
          throw new Error(`${asset.id}: sha256 must contain a lowercase SHA-256 digest`);
        }
      }
    }
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
    const paths = new Set();
    for (const locale of locales) {
      paths.add(localizedArchitectureValue(asset.source, locale, `${asset.id}.source`));
      paths.add(localizedArchitectureValue(asset.fallback, locale, `${asset.id}.fallback`));
    }
    for (const relativePath of paths) await stat(containedPath(root, relativePath));
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

export async function loadArchitectureAsset(root, asset, locale) {
  const sourcePath = localizedArchitectureValue(asset.source, locale, `${asset.id}.source`);
  const fallbackPath = localizedArchitectureValue(asset.fallback, locale, `${asset.id}.fallback`);
  const source = containedPath(root, sourcePath);
  const fallback = containedPath(root, fallbackPath);
  await stat(fallback);
  const svg = await readFile(source, 'utf8');
  const png = await readFile(fallback);
  const sha256 = createHash('sha256').update(svg).digest('hex');
  const expectedDigest = asset.sha256
    ? localizedArchitectureValue(asset.sha256, locale, `${asset.id}.sha256`)
    : null;
  if (expectedDigest && expectedDigest !== sha256) {
    throw new Error(`${asset.id}: architecture SVG digest mismatch`);
  }
  return {
    id: asset.id,
    source: sourcePath,
    fallback: fallbackPath,
    sha256,
    dataUri: `data:image/png;base64,${png.toString('base64')}`,
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
