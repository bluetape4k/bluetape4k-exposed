#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';

import { containedPath, repositoryPath } from './lib/model.mjs';

const locales = ['en', 'ko'];
const themes = ['light', 'dark'];
const defaultChrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

export function captureTargets(documentId) {
  return locales.flatMap((locale) =>
    themes.map((theme) => `${documentId}.${locale}.${theme}.png`),
  );
}

export function chromeArguments(profileDir, port) {
  return [
    '--headless=new',
    '--disable-background-networking',
    '--disable-component-update',
    '--disable-default-apps',
    '--disable-extensions',
    '--disable-gpu',
    '--disable-sync',
    '--force-device-scale-factor=1',
    '--hide-scrollbars',
    '--lang=en-US',
    '--no-first-run',
    '--no-default-browser-check',
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profileDir}`,
    'about:blank',
  ];
}

export function auditScenarioId(scenarioIds) {
  if (scenarioIds.length === 0) throw new Error('audit requires at least one scenario');
  return scenarioIds.includes('r2dbc-flow-escape')
    ? 'r2dbc-flow-escape'
    : scenarioIds.at(-1);
}

function sha256(buffer) {
  return createHash('sha256').update(buffer).digest('hex');
}

function pngDimensions(png) {
  if (png.toString('ascii', 1, 4) !== 'PNG') throw new Error('capture is not a PNG');
  return { width: png.readUInt32BE(16), height: png.readUInt32BE(20) };
}

async function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

async function waitForEndpoint(port, child) {
  let lastError;
  for (let attempt = 0; attempt < 80; attempt += 1) {
    if (child.exitCode !== null) throw new Error(`Chrome exited before CDP was ready: ${child.exitCode}`);
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/list`);
      const targets = await response.json();
      const page = targets.find((target) => target.type === 'page');
      if (page?.webSocketDebuggerUrl) return page.webSocketDebuggerUrl;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`Chrome CDP endpoint did not become ready: ${lastError?.message ?? 'timeout'}`);
}

function cdpClient(webSocketUrl) {
  const socket = new WebSocket(webSocketUrl);
  const pending = new Map();
  let nextId = 1;
  const opened = new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true });
    socket.addEventListener('error', reject, { once: true });
  });
  socket.addEventListener('message', ({ data }) => {
    const message = JSON.parse(data);
    if (!message.id) return;
    const waiter = pending.get(message.id);
    if (!waiter) return;
    pending.delete(message.id);
    if (message.error) waiter.reject(new Error(message.error.message));
    else waiter.resolve(message.result);
  });
  return {
    async send(method, params = {}) {
      await opened;
      const id = nextId;
      nextId += 1;
      return new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject });
        socket.send(JSON.stringify({ id, method, params }));
      });
    },
    close() {
      socket.close();
    },
  };
}

async function captureOne({ root, documentId, locale, theme, audit = false }) {
  const port = await freePort();
  const profile = await mkdtemp(path.join(os.tmpdir(), 'exposed-visual-cdp-'));
  const chrome = process.env.CHROME_BIN || defaultChrome;
  const child = spawn(chrome, chromeArguments(profile, port), {
    stdio: ['ignore', 'ignore', 'ignore'],
  });
  let client;
  try {
    client = cdpClient(await waitForEndpoint(port, child));
    await client.send('Page.enable');
    await client.send('Runtime.enable');
    await client.send('Emulation.setDeviceMetricsOverride', {
      width: 1440,
      height: 1000,
      deviceScaleFactor: 1,
      mobile: false,
    });
    await client.send('Emulation.setEmulatedMedia', {
      media: 'screen',
      features: [
        { name: 'prefers-reduced-motion', value: 'reduce' },
        { name: 'prefers-color-scheme', value: theme },
      ],
    });
    const html = containedPath(root, `docs/visual-companions/${locale}/${documentId}.html`);
    const url = new URL(pathToFileURL(html));
    url.searchParams.set('theme', theme);
    url.searchParams.set('capture', '1');
    await client.send('Page.navigate', { url: url.href });
    await client.send('Runtime.evaluate', {
      expression: `(async () => {
        while (document.readyState !== 'complete') await new Promise(r => setTimeout(r, 25));
        await document.fonts.ready;
        await Promise.all([...document.images].map((image) => image.decode()));
        await new Promise((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(resolve)));
        const deadline = Date.now() + 5000;
        while (window.__VISUAL_COMPANION_READY__ !== true) {
          if (Date.now() > deadline) throw new Error('visual companion ready signal timed out');
          await new Promise(r => setTimeout(r, 25));
        }
        return true;
      })()`,
      awaitPromise: true,
      returnByValue: true,
    });
    if (audit) {
      const scenarioIdsResult = await client.send('Runtime.evaluate', {
        expression: `[...document.querySelectorAll('[data-scenario]')].map((tab) => tab.dataset.scenario)`,
        returnByValue: true,
      });
      const selectedAuditScenario = auditScenarioId(scenarioIdsResult.result.value);
      const desktop = await client.send('Runtime.evaluate', {
        expression: `(() => {
          const tabs = [...document.querySelectorAll('[data-scenario]')];
          const geometry = [];
          for (const tab of tabs) {
            tab.click();
            const panel = document.querySelector(\`[data-sequence="\${tab.dataset.scenario}"]\`);
            const participantCenters = new Map(
              [...panel.querySelectorAll('[data-participant]')].map((participant) => {
                const lifeline = participant.querySelector('.lifeline').getBoundingClientRect();
                return [participant.dataset.participant, lifeline.left];
              }),
            );
            for (const message of panel.querySelectorAll('[data-message-kind]')) {
              const line = message.querySelector('.message-line');
              const lineBounds = line.getBoundingClientRect();
              const direction = message.dataset.direction;
              const from = participantCenters.get(message.dataset.from);
              const to = participantCenters.get(message.dataset.to);
              const lineStart = direction === 'forward' ? lineBounds.left : lineBounds.right;
              const lineEnd = direction === 'forward' ? lineBounds.right : lineBounds.left;
              const lineStyle = getComputedStyle(line);
              const arrowStyle = getComputedStyle(line, '::after');
              const arrowColor = direction === 'forward'
                ? arrowStyle.borderLeftColor
                : arrowStyle.borderRightColor;
              geometry.push({
                kind: message.dataset.messageKind,
                direction,
                startDelta: Math.abs(lineStart - from),
                endDelta: Math.abs(lineEnd - to),
                colorMatches: lineStyle.color === arrowColor,
              });
            }
          }
          const target = tabs.find(
            (button) => button.dataset.scenario === ${JSON.stringify(selectedAuditScenario)},
          );
          target.click();
          const selectedPanel = document.querySelector(
            \`[data-sequence="\${target.dataset.scenario}"]\`,
          );
          const callLineColor = getComputedStyle(
            selectedPanel.querySelector('.message-call .message-line'),
          ).color;
          const roleLineColor = getComputedStyle(
            selectedPanel.querySelector('.lifeline'),
          ).borderLeftColor;
          const roleActivationColor = getComputedStyle(
            selectedPanel.querySelector('.activation'),
          ).borderLeftColor;
          const participantCardAlignment = [
            ...selectedPanel.querySelectorAll('.sequence-participant strong'),
          ].map((card) => {
            const cardBounds = card.getBoundingClientRect();
            const textRange = document.createRange();
            textRange.selectNodeContents(card);
            const textBounds = textRange.getBoundingClientRect();
            return Math.abs(
              (cardBounds.top + cardBounds.height / 2)
                - (textBounds.top + textBounds.height / 2),
            );
          });
          const clicked = {
            scenarioCount: tabs.length,
            selectedScenario: target.getAttribute('aria-pressed'),
            selectedPanelVisible: !selectedPanel.hidden,
            sequenceGeometry: {
              messageCount: geometry.length,
              maximumEndpointDelta: Math.max(...geometry.flatMap(({ startDelta, endDelta }) => [startDelta, endDelta])),
              arrowColorMismatchCount: geometry.filter(({ colorMatches }) => !colorMatches).length,
              invalidReturnDirectionCount: geometry.filter(({ kind, direction }) => kind === 'return' && direction !== 'reverse').length,
              callLineColor,
              roleLineColor,
              roleActivationColor,
              callRoleColorDistinct: callLineColor !== roleLineColor
                && callLineColor !== roleActivationColor,
              participantCardCount: participantCardAlignment.length,
              maximumParticipantTextCenterDelta: Math.max(...participantCardAlignment),
            },
          };
          tabs[0].focus();
          tabs[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
          const keyboardScenario = document.activeElement.dataset.scenario;
          const themeButton = document.querySelector('[data-theme-control]');
          themeButton.click();
          const themeAfterClick = document.documentElement.dataset.theme;
          const lightboxes = [...document.querySelectorAll('[data-lightbox-open]')].map((opener) => {
            opener.click();
            const dialog = document.querySelector(
              '#architecture-' + opener.dataset.lightboxOpen,
            );
            const opened = dialog.open;
            dialog.querySelector('[data-lightbox-close]').click();
            return {
              id: opener.dataset.lightboxOpen,
              opened,
              closedByButton: !dialog.open,
            };
          });
          return { ...clicked, keyboardScenario, themeAfterClick, lightboxes };
        })()`,
        returnByValue: true,
      });
      await client.send('Emulation.setDeviceMetricsOverride', {
        width: 360,
        height: 800,
        deviceScaleFactor: 1,
        mobile: true,
      });
      const mobile = await client.send('Runtime.evaluate', {
        expression: `new Promise((resolve) => requestAnimationFrame(() => resolve({
          viewport: innerWidth,
          documentWidth: document.documentElement.scrollWidth,
          bodyWidth: document.body.scrollWidth,
          horizontalOverflow: document.documentElement.scrollWidth > innerWidth,
        })))`,
        awaitPromise: true,
        returnByValue: true,
      });
      return { desktop: desktop.result.value, mobile: mobile.result.value };
    }
    const metrics = await client.send('Page.getLayoutMetrics');
    const width = Math.ceil(metrics.cssContentSize.width);
    const height = Math.ceil(metrics.cssContentSize.height);
    const capture = async () => {
      const screenshot = await client.send('Page.captureScreenshot', {
        format: 'png',
        fromSurface: true,
        captureBeyondViewport: true,
        clip: { x: 0, y: 0, width, height, scale: 1 },
      });
      const png = Buffer.from(screenshot.data, 'base64');
      return { png, ...pngDimensions(png) };
    };
    await capture();
    await client.send('Runtime.evaluate', {
      expression: 'new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))',
      awaitPromise: true,
    });
    const samples = new Map();
    for (let attempt = 0; attempt < 6; attempt += 1) {
      const sample = await capture();
      const key = `${sample.width}x${sample.height}/${sha256(sample.png)}`;
      if (samples.has(key)) return [samples.get(key), sample];
      samples.set(key, sample);
      await client.send('Runtime.evaluate', {
        expression: 'new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))',
        awaitPromise: true,
      });
    }
    return [...samples.values()].slice(0, 2);
  } finally {
    try {
      await client?.send('Browser.close');
    } catch {
      // The browser may already be closing after a failed capture.
    }
    client?.close();
    if (child.exitCode === null) {
      child.kill('SIGTERM');
      await Promise.race([
        once(child, 'exit'),
        new Promise((resolve) => setTimeout(resolve, 1000)),
      ]);
    }
    await rm(profile, { recursive: true, force: true });
  }
}

export async function auditDocument({
  root,
  documentId,
  locale = 'ko',
  theme = 'dark',
}) {
  return captureOne({ root, documentId, locale, theme, audit: true });
}

function assertDeterministic(first, second, target) {
  if (
    first.width !== second.width
    || first.height !== second.height
    || sha256(first.png) !== sha256(second.png)
  ) {
    throw new Error(
      `capture is not deterministic: ${target} `
      + `${first.width}x${first.height}/${sha256(first.png)} != `
      + `${second.width}x${second.height}/${sha256(second.png)}`,
    );
  }
}

async function writeOrCheckCapture({ root, target, png, check }) {
  const relativePath = `docs/visual-companions/assets/${target}`;
  const output = containedPath(root, relativePath);
  if (check) {
    let existing;
    try {
      existing = await readFile(output);
    } catch {
      throw new Error(`fallback capture is missing: ${relativePath}`);
    }
    if (sha256(existing) !== sha256(png)) {
      throw new Error(`fallback capture differs: ${relativePath}`);
    }
  } else {
    await mkdir(path.dirname(output), { recursive: true });
    await writeFile(output, png);
  }
  return {
    path: relativePath,
    sha256: sha256(png),
    ...pngDimensions(png),
  };
}

export async function captureMatrix({ root, documentId, check = false }) {
  const results = [];
  for (const locale of locales) {
    for (const theme of themes) {
      const target = `${documentId}.${locale}.${theme}.png`;
      const [first, second] = await captureOne({ root, documentId, locale, theme });
      assertDeterministic(first, second, target);
      results.push(await writeOrCheckCapture({ root, target, png: first.png, check }));
    }
  }
  return results;
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const documentId = process.argv.slice(2).find((argument) => !argument.startsWith('-'));
  if (!documentId) {
    console.error('Usage: node scripts/visual-companions/capture.mjs <document-id> [--check]');
    process.exitCode = 2;
  } else {
    try {
      const results = await captureMatrix({
        root: repositoryPath(new URL('../../', import.meta.url)),
        documentId,
        check: process.argv.includes('--check'),
      });
      for (const result of results) {
        console.log(`${result.path} ${result.width}x${result.height} ${result.sha256}`);
      }
    } catch (error) {
      console.error(error.message);
      process.exitCode = 1;
    }
  }
}
