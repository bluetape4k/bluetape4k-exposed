#!/usr/bin/env node

import { readFile, realpath, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  containedPath,
  loadArchitectureAsset,
  structuralFingerprint as modelFingerprint,
  validateCompanionModel,
  validateModelPaths,
} from './lib/model.mjs';

const EXPECTED_REPOSITORY = 'bluetape4k/bluetape4k-exposed';
const REQUIRED_LOCALES = ['en', 'ko'];
const REQUIRED_THEMES = ['dark', 'light'];
const forbiddenRuntimePatterns = [
  /<script\b[^>]*\bsrc\s*=/i,
  /<link\b[^>]*\brel\s*=\s*["']?stylesheet\b/i,
  /@import\b/i,
  /url\(\s*["']?(?!data:|#)/i,
  /<form\b/i,
  /\bfetch\s*\(/,
  /\bXMLHttpRequest\b/,
  /\bnavigator\.sendBeacon\s*\(/,
  /\bimport\s*(?:\(|[^;]*?\bfrom\b)/,
  /\bnew\s+(?:Worker|SharedWorker|EventSource)\s*\(/,
];

function requireMatch(errors, content, pattern, message) {
  if (!pattern.test(content)) errors.push(message);
}

function values(content, pattern) {
  return [...content.matchAll(pattern)].map((match) => match[1]).sort();
}

function sameValues(left, right) {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

function containsForbiddenRuntimeDependency(content) {
  if (forbiddenRuntimePatterns.some((pattern) => pattern.test(content))) return true;
  for (const match of content.matchAll(/<(script|link|img|iframe|audio|video|source|object|embed)\b([^>]*)>/gi)) {
    const tag = match[1].toLowerCase();
    const attributes = new Map(
      [...match[2].matchAll(/\b([:\w-]+)\s*=\s*(["'])(.*?)\2/gi)]
        .map((attribute) => [attribute[1].toLowerCase(), attribute[3]]),
    );
    if (tag === 'object' || tag === 'embed') return true;
    if (tag === 'script' && attributes.has('src')) return true;
    if (tag === 'link' && attributes.has('href')) {
      const rel = (attributes.get('rel') ?? '').toLowerCase().split(/\s+/);
      const href = attributes.get('href');
      if (!(rel.includes('icon') && href.startsWith('data:'))) return true;
    }
    if (attributes.has('srcset')) return true;
    for (const attribute of ['src', 'data', 'poster']) {
      const value = attributes.get(attribute);
      if (value && !value.startsWith('data:') && !value.startsWith('#')) return true;
    }
  }
  return false;
}

async function readContained(root, relativePath, errors, message) {
  try {
    return await readFile(containedPath(root, relativePath), 'utf8');
  } catch {
    errors.push(message);
    return null;
  }
}

async function validatePng(root, relativePath, errors, prefix) {
  try {
    const buffer = await readFile(containedPath(root, relativePath));
    if (buffer.length < 24 || buffer.toString('ascii', 1, 4) !== 'PNG') {
      errors.push(`${prefix} fallback capture must be a PNG`);
    }
  } catch {
    errors.push(`${prefix} fallback capture is missing: ${relativePath}`);
  }
}

function htmlFingerprint(content) {
  return {
    sections: values(content, /<section\b[^>]*\bid=["']([^"']+)["']/gi),
    controls: values(content, /<(?:button|input)\b[^>]*\bid=["']([^"']+)["']/gi),
    scenarios: values(content, /\bdata-scenario=["']([^"']+)["']/gi),
    sequences: values(content, /\bdata-sequence=["']([^"']+)["']/gi),
    sources: values(content, /\bdata-source-anchor=["']([^"']+)["']/gi),
    architecture: values(content, /\bdata-architecture-id=["']([^"']+)["']/gi),
  };
}

function validateStandaloneHtml({ content, document, model, locale, errors }) {
  const prefix = `${document.id}.${locale}`;
  const opposite = locale === 'en' ? 'ko' : 'en';
  requireMatch(errors, content, /^\s*<!doctype html>/i, `${prefix} must start with doctype`);
  requireMatch(errors, content, new RegExp(`<html\\b[^>]*\\blang=["']${locale}["']`, 'i'), `${prefix} must set lang=${locale}`);
  requireMatch(errors, content, /<meta\b[^>]*name=["']viewport["'][^>]*width=device-width/i, `${prefix} must declare a responsive viewport`);
  requireMatch(errors, content, /<meta\b[^>]*name=["']color-scheme["'][^>]*light dark/i, `${prefix} must support light dark color schemes`);
  requireMatch(errors, content, /:root\[data-theme=["']light["']\]/i, `${prefix} must define light theme tokens`);
  requireMatch(errors, content, /:root\[data-theme=["']dark["']\]/i, `${prefix} must define dark theme tokens`);
  requireMatch(errors, content, /@media\s*\(prefers-reduced-motion:\s*reduce\)/i, `${prefix} must define reduced-motion behavior`);
  requireMatch(errors, content, /<main\b/i, `${prefix} must contain semantic main content`);
  requireMatch(errors, content, /class=["'][^"']*theme-toggle[^"']*["'][^>]*aria-label=/i, `${prefix} must expose an accessible theme toggle`);
  requireMatch(errors, content, /class=["'][^"']*theme-toggle[^"']*["'][^>]*aria-pressed=/i, `${prefix} theme toggle must expose aria-pressed`);
  requireMatch(errors, content, /aria-live=["']polite["']/i, `${prefix} must expose a polite live region`);
  requireMatch(errors, content, /window\.__VISUAL_COMPANION_READY__\s*=\s*true/, `${prefix} must expose the workflow-ready signal`);
  requireMatch(errors, content, /<dialog\b[^>]*id=["']architecture-/i, `${prefix} must contain an architecture lightbox`);
  requireMatch(errors, content, /data-lightbox-open=/i, `${prefix} must contain an architecture lightbox control`);
  requireMatch(
    errors,
    content,
    new RegExp(`href=["']\\.\\./${opposite}/${document.id}\\.html`, 'i'),
    `${prefix} must link to its ${opposite} locale`,
  );
  requireMatch(
    errors,
    content,
    new RegExp(`data-source=["'][^"']*${path.posix.basename(document.source).replaceAll('.', '\\.')}`),
    `${prefix} must identify its design source`,
  );
  for (const section of model.sections) {
    requireMatch(errors, content, new RegExp(`<section\\b[^>]*id=["']${section.id}["']`, 'i'), `${prefix} must contain section ${section.id}`);
  }
  if (model.scenarios.length > 0) {
    for (const scenario of model.scenarios) {
      requireMatch(errors, content, new RegExp(`data-scenario=["']${scenario.id}["']`), `${prefix} missing scenario ${scenario.id}`);
      requireMatch(errors, content, new RegExp(`data-sequence=["']${scenario.id}["']`), `${prefix} missing sequence ${scenario.id}`);
    }
    for (const [pattern, message] of [
      [/class=["'][^"']*sequence-participant/, 'sequence participant'],
      [/class=["'][^"']*lifeline/, 'sequence lifeline'],
      [/class=["'][^"']*activation/, 'sequence activation'],
      [/class=["'][^"']*message-number/, 'numbered sequence message'],
      [/class=["'][^"']*sequence-alt/, 'sequence alt frame'],
    ]) {
      requireMatch(errors, content, pattern, `${prefix} must contain ${message}`);
    }
  }
  if (model.kind === 'activation') {
    for (const [pattern, message] of [
      [/data-scenario-detail=/, 'scenario condition/result panel'],
      [/data-condition-state=/, 'condition state ledger'],
      [/data-result-state=/, 'result ownership ledger'],
      [/class=["'][^"']*architecture-compare/, 'side-by-side architecture comparison'],
      [/id=["']configuration-recipes["']/, 'configuration recipes'],
      [/id=["']failure-diagnostics["']/, 'failure diagnostics'],
    ]) {
      requireMatch(errors, content, pattern, `${prefix} must contain ${message}`);
    }
  }
  if (containsForbiddenRuntimeDependency(content)) {
    errors.push(`${prefix} contains a forbidden runtime dependency`);
  }
}

async function validateSourceLinks(root, content, prefix, errors) {
  for (const sourceHref of values(content, /<a\b[^>]*data-source-link\b[^>]*href=["']([^"']+)["']/gi)) {
    const match = sourceHref.match(/^https:\/\/github\.com\/bluetape4k\/bluetape4k-exposed\/blob\/develop\/(.+)$/);
    if (!match) {
      errors.push(`${prefix} source link must target the develop tree: ${sourceHref}`);
      continue;
    }
    await readContained(root, decodeURIComponent(match[1]), errors, `${prefix} source link does not resolve: ${sourceHref}`);
  }
}

export async function validateRepository(
  inputRoot = process.cwd(),
  manifestRelativePath = 'docs/visual-companions/manifest.json',
) {
  const root = await realpath(inputRoot);
  const errors = [];
  const manifestContent = await readContained(root, manifestRelativePath, errors, `manifest does not exist: ${manifestRelativePath}`);
  if (manifestContent === null) throw new Error(errors.join('\n'));
  let manifest;
  try {
    manifest = JSON.parse(manifestContent);
  } catch (error) {
    throw new Error(`manifest is not valid JSON: ${error.message}`);
  }

  if (manifest.schemaVersion !== 1) errors.push('manifest.schemaVersion must be 1');
  if (manifest.repository !== EXPECTED_REPOSITORY) errors.push(`manifest.repository must be ${EXPECTED_REPOSITORY}`);
  if (!Array.isArray(manifest.documents) || manifest.documents.length !== 2) {
    errors.push('manifest.documents must contain the two approved documents');
  }

  const ids = new Set();
  const htmlOwners = new Set();
  let localeFileCount = 0;
  for (const [index, document] of (manifest.documents ?? []).entries()) {
    const field = `documents[${index}]`;
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(document.id ?? '')) errors.push(`${field}.id is invalid`);
    if (ids.has(document.id)) errors.push(`${field}.id is duplicated`);
    ids.add(document.id);
    const published = document.status === 'approved' && document.public === true;
    const pendingReview = document.status === 'pending-review' && document.public === false;
    if (!published && !pendingReview) {
      errors.push(`${field} must be approved/public or pending-review/private`);
    }
    if (document.data !== `docs/visual-companions/data/${document.id}.json`) errors.push(`${field}.data must identify its model`);
    const data = await readContained(root, document.data, errors, `${field}.data does not exist`);
    if (data === null) continue;
    let model;
    try {
      model = validateCompanionModel(JSON.parse(data));
      if (model.id !== document.id) errors.push(`${field}.data id must match document id`);
      await validateModelPaths(root, model);
      for (const locale of REQUIRED_LOCALES) {
        for (const asset of model.architecture) {
          await loadArchitectureAsset(root, asset, locale);
        }
      }
    } catch (error) {
      errors.push(`${field}.data is invalid: ${error.message}`);
      continue;
    }
    if (modelFingerprint(model).length === 0) errors.push(`${field}.data fingerprint is empty`);

    const localeKeys = Object.keys(document.locales ?? {}).sort();
    if (!sameValues(localeKeys, REQUIRED_LOCALES)) errors.push(`${field}.locales must contain exactly en and ko`);
    const localeContents = {};
    for (const locale of REQUIRED_LOCALES) {
      const entry = document.locales?.[locale];
      if (!entry?.title || !entry?.html) {
        errors.push(`${field}.locales.${locale} is required`);
        continue;
      }
      const expectedHtml = `docs/visual-companions/${locale}/${document.id}.html`;
      if (entry.html !== expectedHtml) errors.push(`${field}.locales.${locale}.html must be ${expectedHtml}`);
      if (htmlOwners.has(entry.html)) errors.push(`${field}.locales.${locale}.html is already owned`);
      htmlOwners.add(entry.html);
      if (published && !sameValues(Object.keys(entry.captures ?? {}).sort(), REQUIRED_THEMES)) {
        errors.push(`${field}.locales.${locale}.captures must contain exactly light and dark`);
      } else if (published) {
        for (const theme of REQUIRED_THEMES) {
          const expectedCapture = `docs/visual-companions/assets/${document.id}.${locale}.${theme}.png`;
          if (entry.captures[theme] !== expectedCapture) {
            errors.push(`${field}.locales.${locale}.captures.${theme} must be ${expectedCapture}`);
          }
          await validatePng(root, entry.captures[theme], errors, `${document.id}.${locale}.${theme}`);
        }
      }
      const content = await readContained(root, entry.html, errors, `${field}.locales.${locale}.html does not exist`);
      if (content === null) continue;
      localeFileCount += 1;
      localeContents[locale] = content;
      validateStandaloneHtml({ content, document, model, locale, errors });
      await validateSourceLinks(root, content, `${document.id}.${locale}`, errors);
    }
    if (localeContents.en && localeContents.ko) {
      const english = htmlFingerprint(localeContents.en);
      const korean = htmlFingerprint(localeContents.ko);
      for (const [key, label] of Object.entries({
        sections: 'section ids',
        controls: 'control ids',
        scenarios: 'scenario ids',
        sequences: 'sequence ids',
        sources: 'source anchors',
        architecture: 'architecture ids',
      })) {
        if (!sameValues(english[key], korean[key])) errors.push(`${document.id} locale ${label} must match`);
      }
    }
  }

  const manualRoutes = [
    ['docs/manual/en/guides/transaction-boundaries.md', 'https://bluetape4k.github.io/visual-companions/bluetape4k-exposed/jdbc-r2dbc-transaction-boundaries/'],
    ['docs/manual/ko/guides/transaction-boundaries.md', 'https://bluetape4k.github.io/ko/visual-companions/bluetape4k-exposed/jdbc-r2dbc-transaction-boundaries/'],
    ['docs/manual/en/guides/spring-and-ktor.md', 'https://bluetape4k.github.io/visual-companions/bluetape4k-exposed/spring-boot-exposed-activation/'],
    ['docs/manual/ko/guides/spring-and-ktor.md', 'https://bluetape4k.github.io/ko/visual-companions/bluetape4k-exposed/spring-boot-exposed-activation/'],
  ];
  for (const [manual, route] of manualRoutes) {
    const content = await readContained(root, manual, errors, `manual route source does not exist: ${manual}`);
    if (content && !content.includes(route)) errors.push(`${manual} must link to ${route}`);
  }

  if (errors.length > 0) throw new Error(errors.join('\n'));
  return { documentCount: manifest.documents.length, localeFileCount };
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  try {
    const result = await validateRepository(process.cwd(), process.argv[2] ?? 'docs/visual-companions/manifest.json');
    console.log(`Visual companion validation passed: ${result.documentCount} documents / ${result.localeFileCount} locale files`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
