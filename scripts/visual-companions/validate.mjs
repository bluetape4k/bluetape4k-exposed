#!/usr/bin/env node

import { readFile, realpath } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const EXPECTED_REPOSITORY = 'bluetape4k/bluetape4k-exposed';
const REQUIRED_LOCALES = ['en', 'ko'];
const forbiddenRuntimePatterns = [
  /<script\b[^>]*\bsrc\s*=/i,
  /<link\b[^>]*\brel\s*=\s*["']?stylesheet\b/i,
  /<(?:img|iframe|audio|video|source)\b[^>]*\bsrc\s*=\s*["'](?!data:|#)[^"']+["']/i,
  /@import\b/i,
  /url\(\s*["']?https?:/i,
  /<form\b/i,
  /\bfetch\s*\(/,
  /\bXMLHttpRequest\b/,
  /\bWebSocket\s*\(/,
  /\bnavigator\.sendBeacon\s*\(/,
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

function safePath(root, relativePath) {
  if (typeof relativePath !== 'string' || relativePath.length === 0 || path.isAbsolute(relativePath)) {
    throw new Error(`Path must be repository-relative: ${relativePath ?? '<missing>'}`);
  }
  const absolute = path.resolve(root, relativePath);
  if (!absolute.startsWith(`${root}${path.sep}`)) {
    throw new Error(`Path escapes repository: ${relativePath}`);
  }
  return absolute;
}

async function readContained(root, relativePath, errors, message) {
  try {
    return await readFile(safePath(root, relativePath), 'utf8');
  } catch {
    errors.push(message);
    return null;
  }
}

function structuralFingerprint(content) {
  return {
    sections: values(content, /<section\b[^>]*\bid=["']([^"']+)["']/gi),
    controls: values(content, /<(?:button|input)\b[^>]*\bid=["']([^"']+)["']/gi),
    views: values(content, /\bdata-view=["']([^"']+)["']/gi),
    conditions: values(content, /\bdata-condition=["']([^"']+)["']/gi),
    sourceAnchors: values(content, /\bdata-source-anchor=["']([^"']+)["']/gi),
  };
}

function validateStandaloneHtml({
  content,
  document,
  locale,
  oppositeLocale,
  errors,
}) {
  const prefix = `${document.id}.${locale}`;
  requireMatch(errors, content, /^\s*<!doctype html>/i, `${prefix} must start with doctype`);
  requireMatch(
    errors,
    content,
    new RegExp(`<html\\b[^>]*\\blang=["']${locale}["']`, 'i'),
    `${prefix} must set lang=${locale}`,
  );
  requireMatch(
    errors,
    content,
    /<meta\b[^>]*\bname=["']viewport["'][^>]*\bcontent=["'][^"']*width=device-width[^"']*["']/i,
    `${prefix} must declare a responsive viewport`,
  );
  requireMatch(
    errors,
    content,
    /<meta\b[^>]*\bname=["']color-scheme["'][^>]*\bcontent=["']light dark["']/i,
    `${prefix} must support light dark color schemes`,
  );
  requireMatch(errors, content, /:root\[data-theme=["']light["']\]/i, `${prefix} must define light theme tokens`);
  requireMatch(errors, content, /:root\[data-theme=["']dark["']\]/i, `${prefix} must define dark theme tokens`);
  requireMatch(
    errors,
    content,
    /@media\s*\(prefers-reduced-motion:\s*reduce\)/i,
    `${prefix} must define reduced-motion behavior`,
  );
  requireMatch(errors, content, /<main\b/i, `${prefix} must contain semantic main content`);
  requireMatch(
    errors,
    content,
    /<button\b[^>]*\bclass=["'][^"']*theme-toggle[^"']*["'][^>]*\baria-label=["'][^"']+["']/i,
    `${prefix} must expose an accessible theme toggle`,
  );
  requireMatch(
    errors,
    content,
    /\baria-live=["']polite["']/i,
    `${prefix} must expose a polite live region`,
  );
  requireMatch(
    errors,
    content,
    new RegExp(`\\bdata-source=["']${path.posix.basename(document.source).replaceAll('.', '\\.')}["']`, 'i'),
    `${prefix} must identify its design source`,
  );
  requireMatch(
    errors,
    content,
    new RegExp(`<a\\b[^>]*\\bhref=["'][^"']*${path.posix.basename(document.source).replaceAll('.', '\\.')}[^"']*["']`, 'i'),
    `${prefix} must link to its design source`,
  );
  requireMatch(
    errors,
    content,
    new RegExp(`href=["'][^"']*${path.posix.basename(document.locales[oppositeLocale].html).replaceAll('.', '\\.')}["']`, 'i'),
    `${prefix} must link to its ${oppositeLocale} locale`,
  );

  for (const view of document.presentation.views) {
    requireMatch(
      errors,
      content,
      new RegExp(`\\bdata-view=["']${view.replaceAll('-', '\\-')}["']`, 'i'),
      `${prefix} must represent declared view ${view}`,
    );
  }

  if (forbiddenRuntimePatterns.some((pattern) => pattern.test(content))) {
    errors.push(`${prefix} contains a forbidden runtime dependency`);
  }
}

export async function validateRepository(
  inputRoot = process.cwd(),
  manifestRelativePath = 'docs/visual-companions/manifest.json',
) {
  const root = await realpath(inputRoot);
  const errors = [];
  const manifestContent = await readContained(
    root,
    manifestRelativePath,
    errors,
    `manifest does not exist: ${manifestRelativePath}`,
  );
  if (manifestContent === null) throw new Error(errors.join('\n'));

  let manifest;
  try {
    manifest = JSON.parse(manifestContent);
  } catch (error) {
    throw new Error(`manifest is not valid JSON: ${error.message}`);
  }

  if (manifest.schemaVersion !== 1) errors.push('manifest.schemaVersion must be 1');
  if (manifest.repository !== EXPECTED_REPOSITORY) {
    errors.push(`manifest.repository must be ${EXPECTED_REPOSITORY}`);
  }
  if (!Array.isArray(manifest.documents) || manifest.documents.length !== 2) {
    errors.push('manifest.documents must contain the two approved documents');
  }

  const ids = new Set();
  const htmlOwners = new Map();
  let localeFileCount = 0;

  for (const [index, document] of (manifest.documents ?? []).entries()) {
    const field = `documents[${index}]`;
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(document.id ?? '')) {
      errors.push(`${field}.id is invalid`);
    }
    if (ids.has(document.id)) errors.push(`${field}.id is duplicated`);
    ids.add(document.id);

    if (document.status !== 'approved' || document.public !== true) {
      errors.push(`${field} must be approved and public`);
    }
    if (
      typeof document.presentation?.mode !== 'string'
      || typeof document.presentation?.defaultView !== 'string'
      || !Array.isArray(document.presentation?.views)
      || document.presentation.views.length === 0
      || !document.presentation.views.includes(document.presentation.defaultView)
    ) {
      errors.push(`${field}.presentation is invalid`);
    }

    await readContained(root, document.source, errors, `${field}.source does not exist`);
    const localeContents = {};

    for (const locale of REQUIRED_LOCALES) {
      const localeEntry = document.locales?.[locale];
      if (!localeEntry?.title || !localeEntry?.html) {
        errors.push(`${field}.locales.${locale} is required`);
        continue;
      }

      const previousOwner = htmlOwners.get(localeEntry.html);
      if (previousOwner) {
        errors.push(`${field}.locales.${locale}.html is already owned by ${previousOwner}`);
      } else {
        htmlOwners.set(localeEntry.html, `${document.id}.${locale}`);
      }

      const content = await readContained(
        root,
        localeEntry.html,
        errors,
        `${field}.locales.${locale}.html does not exist`,
      );
      if (content === null) continue;

      localeFileCount += 1;
      localeContents[locale] = content;
      validateStandaloneHtml({
        content,
        document,
        locale,
        oppositeLocale: locale === 'en' ? 'ko' : 'en',
        errors,
      });
      for (const sourceHref of values(
        content,
        /<a\b[^>]*\bdata-source-link\b[^>]*\bhref=["']([^"']+)["']/gi,
      )) {
        const match = sourceHref.match(
          /^https:\/\/github\.com\/bluetape4k\/bluetape4k-exposed\/blob\/develop\/(.+)$/,
        );
        if (!match) {
          errors.push(`${document.id}.${locale} source link must target the develop tree: ${sourceHref}`);
          continue;
        }
        await readContained(
          root,
          decodeURIComponent(match[1]),
          errors,
          `${document.id}.${locale} source link does not resolve: ${sourceHref}`,
        );
      }
    }

    if (localeContents.en && localeContents.ko) {
      const english = structuralFingerprint(localeContents.en);
      const korean = structuralFingerprint(localeContents.ko);
      const labels = {
        sections: 'section ids',
        controls: 'control ids',
        views: 'view values',
        conditions: 'condition values',
        sourceAnchors: 'source anchors',
      };
      for (const [key, label] of Object.entries(labels)) {
        if (!sameValues(english[key], korean[key])) {
          errors.push(`${document.id} locale ${label} must match`);
        }
      }
    }
  }

  if (errors.length > 0) throw new Error(errors.join('\n'));
  return { documentCount: manifest.documents.length, localeFileCount };
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  try {
    const result = await validateRepository();
    console.log(
      `Visual companion validation passed: ${result.documentCount} documents / ${result.localeFileCount} locale files`,
    );
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
