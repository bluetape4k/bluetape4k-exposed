const escapeHtml = (value) => String(value ?? '')
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;');

const repositoryBlob = 'https://github.com/bluetape4k/bluetape4k-exposed/blob/develop/';

function inlineCode(value) {
  return escapeHtml(value).replaceAll(/`([^`]+)`/g, '<code>$1</code>');
}

function evidenceLinkLabel(value) {
  return value.split('/').at(-1).replace(/\.(?:java|kt|kts)$/, '');
}

function renderTable(headers, rows, className = '') {
  return `
    <div class="table-wrap">
      <table class="${className}">
        <thead><tr>${headers.map((header) => `<th scope="col">${escapeHtml(header)}</th>`).join('')}</tr></thead>
        <tbody>${rows.map((row) => `<tr>${row.map((cell, index) => `<${index === 0 ? 'th scope="row"' : 'td'}>${inlineCode(cell)}</${index === 0 ? 'th' : 'td'}>`).join('')}</tr>`).join('')}</tbody>
      </table>
    </div>`;
}

export function renderArchitecture({ model, locale, architectureAssets }) {
  const copy = model.locales[locale];
  return architectureAssets.map((asset) => `
    <figure class="architecture-card" data-architecture-id="${escapeHtml(asset.id)}"
            data-architecture-sha256="${escapeHtml(asset.sha256)}">
      <button class="architecture-open" type="button" data-lightbox-open="${escapeHtml(asset.id)}">
        <span aria-hidden="true">↗</span> ${escapeHtml(copy.openArchitecture)}
      </button>
      <img src="${asset.dataUri}" alt="${escapeHtml(copy.architectureAlt[asset.id])}">
      <dialog id="architecture-${escapeHtml(asset.id)}" aria-label="${escapeHtml(copy.architectureAlt[asset.id])}">
        <div class="dialog-bar">
          <strong>${escapeHtml(copy.architectureAlt[asset.id])}</strong>
          <button type="button" data-lightbox-close="${escapeHtml(asset.id)}">${escapeHtml(copy.close)} <kbd>Esc</kbd></button>
        </div>
        <img src="${asset.dataUri}" alt="${escapeHtml(copy.architectureAlt[asset.id])}">
      </dialog>
    </figure>
  `).join('');
}

export function renderScenarioExplorer({ model, locale }) {
  return model.scenarios.map((scenario, index) => `
    <button type="button"
            id="scenario-${escapeHtml(scenario.id)}"
            class="scenario-tab"
            data-scenario="${escapeHtml(scenario.id)}"
            aria-controls="sequence-${escapeHtml(scenario.id)}"
            aria-pressed="${index === 0}">
      <span>${String(index + 1).padStart(2, '0')}</span>
      ${escapeHtml(scenario.locales[locale].label)}
    </button>
  `).join('');
}

function participantIndex(scenario, id) {
  return scenario.participants.findIndex((participant) => participant.id === id) + 1;
}

export function renderSequence({ scenario, locale, active }) {
  const participants = scenario.participants.map((participant) => `
    <div class="sequence-participant" data-participant="${escapeHtml(participant.id)}">
      <strong>${escapeHtml(participant.locales[locale].label)}</strong>
      <span class="lifeline" aria-hidden="true"></span>
      <span class="activation" aria-hidden="true"></span>
    </div>
  `).join('');
  const messages = scenario.messages.map((message, index) => {
    const from = participantIndex(scenario, message.from);
    const to = participantIndex(scenario, message.to);
    const participantCount = scenario.participants.length;
    const start = ((from - 0.5) / participantCount) * 100;
    const end = ((to - 0.5) / participantCount) * 100;
    const left = Math.min(start, end);
    const right = Math.max(start, end);
    const direction = end >= start ? 'forward' : 'reverse';
    return `
      <li class="message message-${escapeHtml(message.kind)} message-${direction}"
          data-message-kind="${escapeHtml(message.kind)}"
          data-direction="${direction}"
          data-from="${escapeHtml(message.from)}"
          data-to="${escapeHtml(message.to)}"
          style="--line-start:${start}%;--line-end:${end}%;--line-left:${left}%;--line-right:${right}%;--row:${index + 1}">
        <span class="message-copy">
          <span class="message-number">${index + 1}</span>
          <span class="message-kind">${escapeHtml(message.kind.replace('-', ' ').toUpperCase())}</span>
          <span class="message-label">${escapeHtml(message.locales[locale].label)}</span>
        </span>
        <span class="message-line" aria-hidden="true"></span>
      </li>`;
  }).join('');
  return `
    <article class="sequence-panel" id="sequence-${escapeHtml(scenario.id)}"
             data-sequence="${escapeHtml(scenario.id)}"
             data-view="${escapeHtml(scenario.id)}"
             ${active ? '' : 'hidden'}>
      <header class="sequence-summary">
        <div>
          <span class="scenario-outcome outcome-${escapeHtml(scenario.outcome)}">${escapeHtml(scenario.outcome.replace('-', ' ').toUpperCase())}</span>
          <h3>${escapeHtml(scenario.locales[locale].label)}</h3>
          <p>${escapeHtml(scenario.locales[locale].summary)}</p>
        </div>
        <dl>
          <div><dt>${locale === 'ko' ? '경계 소유자' : 'Boundary owner'}</dt><dd><code>${escapeHtml(scenario.locales[locale].owner ?? scenario.owner)}</code></dd></div>
        </dl>
      </header>
      <div class="sequence-canvas" style="--participants:${scenario.participants.length}">
        <div class="sequence-participants">${participants}</div>
        <ol class="sequence-messages">${messages}</ol>
      </div>
      <div class="sequence-alt outcome-${escapeHtml(scenario.outcome)}" data-outcome="${escapeHtml(scenario.outcome)}">
        <strong>${escapeHtml(scenario.locales[locale].outcome.split(' · ')[0])}</strong>
        <span>${escapeHtml(scenario.locales[locale].outcome.split(' · ')[1] ?? '')}</span>
      </div>
    </article>`;
}

export function renderEvidenceLedger({ model, locale }) {
  const copy = model.locales[locale];
  return `
    <div class="table-wrap evidence-table">
      <table>
        <thead><tr>
          <th scope="col">${escapeHtml(copy.whyLabel ?? 'Claim')}</th>
          <th scope="col">${escapeHtml(copy.sourceLabel ?? 'Production source')}</th>
          <th scope="col">${escapeHtml(copy.testLabel ?? 'Test')}</th>
          <th scope="col">${escapeHtml(copy.verifyLabel ?? 'Verification')}</th>
        </tr></thead>
        <tbody>
          ${model.sources.map((source) => `
            <tr id="source-${escapeHtml(source.id)}" data-source-anchor="${escapeHtml(source.id)}">
              <th scope="row">${escapeHtml(source.locales[locale].claim)}</th>
              <td><a data-source-link href="${repositoryBlob}${escapeHtml(source.sourcePath)}"><code>${escapeHtml(evidenceLinkLabel(source.sourcePath))}</code></a></td>
              <td><a data-source-link href="${repositoryBlob}${escapeHtml(source.testPath)}"><code>${escapeHtml(evidenceLinkLabel(source.testPath))}</code></a></td>
              <td><code>${escapeHtml(source.verificationCommand)}</code></td>
            </tr>`).join('')}
        </tbody>
      </table>
    </div>`;
}

function renderMentalModel(model, locale) {
  const copy = model.locales[locale];
  return `
    <div class="section-heading">
      <span class="section-number">01</span>
      <div><p class="kicker">${escapeHtml(copy.invariant)}</p><h2>${escapeHtml(copy.mentalHeading)}</h2></div>
    </div>
    <div class="mental-grid">
      <article class="mental-card jpa-card">
        <span class="badge">JPA / Hibernate</span>
        <h3>${escapeHtml(copy.jpaTitle)}</h3>
        <p class="flow-line">${escapeHtml(copy.jpaFlow)}</p>
        <p class="strength">${escapeHtml(copy.jpaStrength)}</p>
        <p class="cost">${escapeHtml(copy.jpaCost)}</p>
      </article>
      <div class="trade-arrow" aria-hidden="true"><span>${locale === 'ko' ? '전환' : 'TRADE'}</span><b>⇄</b></div>
      <article class="mental-card exposed-card">
        <span class="badge">JetBrains Exposed</span>
        <h3>${escapeHtml(copy.exposedTitle)}</h3>
        <p class="flow-line">${escapeHtml(copy.exposedFlow)}</p>
        <p class="strength">${escapeHtml(copy.exposedStrength)}</p>
        <p class="cost">${escapeHtml(copy.exposedCost)}</p>
      </article>
    </div>
    <aside class="invariant-callout">
      <strong>${escapeHtml(copy.invariant)}</strong>
      <p>${escapeHtml(copy.invariantText)}</p>
    </aside>`;
}

function renderTransactionDocument({ model, locale, architectureAssets }) {
  const copy = model.locales[locale];
  const labels = locale === 'ko'
    ? {
      sourceDiagram: '원본 다이어그램',
      boundaryLab: '경계 시나리오',
      sequence: '호출 → 반환 → 종료',
      responsibility: '책임',
      migrationMap: '마이그레이션 대응',
      decisionGuide: '선택 기준',
      traceableClaims: '검증 근거',
    }
    : {
      sourceDiagram: 'SOURCE DIAGRAM',
      boundaryLab: 'BOUNDARY LAB',
      sequence: 'CALL → RETURN → TERMINAL',
      responsibility: 'RESPONSIBILITY',
      migrationMap: 'MIGRATION MAP',
      decisionGuide: 'DECISION GUIDE',
      traceableClaims: 'TRACEABLE CLAIMS',
    };
  const sequences = model.scenarios.map((scenario, index) =>
    renderSequence({ scenario, locale, active: index === 0 })).join('');
  return renderStandaloneShell({
    model,
    locale,
    body: `
      <section id="mental-model">${renderMentalModel(model, locale)}</section>
      <section id="architecture">
        <div class="section-heading"><span class="section-number">02</span><div><p class="kicker">${labels.sourceDiagram}</p><h2>${escapeHtml(copy.architectureHeading)}</h2><p>${escapeHtml(copy.architectureIntro)}</p></div></div>
        ${renderArchitecture({ model, locale, architectureAssets })}
      </section>
      <section id="scenario-explorer">
        <div class="section-heading"><span class="section-number">03</span><div><p class="kicker">${labels.boundaryLab}</p><h2>${escapeHtml(copy.scenarioHeading)}</h2><p>${escapeHtml(copy.scenarioIntro)}</p></div></div>
        <div class="scenario-tabs" role="group" aria-label="${escapeHtml(copy.scenarioHeading)}">${renderScenarioExplorer({ model, locale })}</div>
      </section>
      <section id="sequence">
        <div class="section-heading"><span class="section-number">04</span><div><p class="kicker">${labels.sequence}</p><h2>${escapeHtml(copy.sequenceHeading)}</h2></div></div>
        <div class="sequence-stack">${sequences}</div>
      </section>
      <section id="ownership-matrix">
        <div class="section-heading"><span class="section-number">05</span><div><p class="kicker">${labels.responsibility}</p><h2>${escapeHtml(copy.ownershipHeading)}</h2></div></div>
        ${renderTable(copy.matrixHeaders, copy.matrix)}
      </section>
      <section id="code-mapping">
        <div class="section-heading"><span class="section-number">06</span><div><p class="kicker">${labels.migrationMap}</p><h2>${escapeHtml(copy.mappingHeading)}</h2></div></div>
        ${renderTable(copy.mappingHeaders, copy.mapping, 'mapping-table')}
      </section>
      <section id="tradeoffs">
        <div class="section-heading"><span class="section-number">07</span><div><p class="kicker">${labels.decisionGuide}</p><h2>${escapeHtml(copy.tradeoffHeading)}</h2></div></div>
        ${renderTable(copy.tradeoffHeaders, copy.tradeoffs, 'tradeoff-table')}
      </section>
      <section id="evidence">
        <div class="section-heading"><span class="section-number">08</span><div><p class="kicker">${labels.traceableClaims}</p><h2>${escapeHtml(copy.evidenceHeading)}</h2></div></div>
        ${renderEvidenceLedger({ model, locale })}
      </section>`,
  });
}

function renderActivationPlaceholder({ model, locale, architectureAssets }) {
  const copy = model.locales[locale];
  return renderStandaloneShell({
    model,
    locale,
    body: `
      <section id="mental-model" class="pending-card">
        <p class="kicker">USER REVIEW GATE</p>
        <h2>${escapeHtml(copy.title)}</h2>
        <p>${escapeHtml(copy.lede)}</p>
      </section>
      <section id="architecture">
        <div class="section-heading"><span class="section-number">01</span><div><p class="kicker">SOURCE DIAGRAMS</p><h2>${escapeHtml(copy.openArchitecture)}</h2></div></div>
        ${renderArchitecture({ model, locale, architectureAssets })}
      </section>
      <section id="scenario-explorer"><span data-view="conditions"></span></section>
      <section id="sequence"></section>
      <section id="ownership-matrix"></section>
      <section id="code-mapping"></section>
      <section id="configuration-recipes"></section>
      <section id="failure-diagnostics"></section>
      <section id="tradeoffs"></section>
      <section id="evidence">${renderEvidenceLedger({ model, locale })}</section>`,
  });
}

function renderStandaloneShell({ model, locale, body }) {
  const copy = model.locales[locale];
  const opposite = locale === 'en' ? 'ko' : 'en';
  const manual = model.kind === 'transaction'
    ? `docs/manual/${locale}/guides/transaction-boundaries.md`
    : `docs/manual/${locale}/guides/spring-and-ktor.md`;
  const manualLabel = model.kind === 'transaction'
    ? (locale === 'ko' ? '트랜잭션 경계 매뉴얼' : 'Transaction boundary manual')
    : (locale === 'ko' ? 'Spring Boot 및 Ktor 통합 매뉴얼' : 'Spring Boot and Ktor integration manual');
  const source = 'docs/superpowers/specs/2026-07-30-issue-410-exposed-visual-companions-design.md';
  return `<!doctype html>
<html lang="${locale}" data-theme="auto">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="light dark">
  <title>${escapeHtml(copy.title)}</title>
  <style>
    :root {
      --bg: #f3f6fa; --surface: #ffffff; --surface-2: #e9eef5; --ink: #142033;
      --muted: #526176; --line: #c9d3df; --navy: #132a46; --cyan: #007f91;
      --cyan-soft: #d8f3f6; --amber: #a65d00; --amber-soft: #fff0cc;
      --call-line: #007f91; --role-line: #7d6fb2; --role-active: #6e59b8; --role-active-soft: #eee9ff;
      --teal: #167c73; --red: #a33a3a; --red-soft: #ffe4e4; --green: #1d7b52; --green-soft: #dcf5e8;
      --shadow: 0 18px 50px rgb(20 32 51 / .12); --radius: 20px;
      color-scheme: light;
    }
    :root[data-theme="light"] { color-scheme: light; }
    :root[data-theme="dark"] {
      --bg: #07111f; --surface: #0e1d2e; --surface-2: #17293c; --ink: #edf5ff;
      --muted: #a8bbcf; --line: #31475f; --navy: #dcecff; --cyan: #55d6e8;
      --cyan-soft: #103c47; --amber: #ffbd5c; --amber-soft: #49351a;
      --call-line: #55d6e8; --role-line: #a99ad6; --role-active: #b9a7ff; --role-active-soft: #2a254b;
      --teal: #5ee0d3; --red: #ff9898; --red-soft: #4a2529; --green: #6ee7ae; --green-soft: #163c2d;
      --shadow: 0 20px 60px rgb(0 0 0 / .38); color-scheme: dark;
    }
    @media (prefers-color-scheme: dark) {
      :root[data-theme="auto"] {
        --bg: #07111f; --surface: #0e1d2e; --surface-2: #17293c; --ink: #edf5ff;
        --muted: #a8bbcf; --line: #31475f; --navy: #dcecff; --cyan: #55d6e8;
        --cyan-soft: #103c47; --amber: #ffbd5c; --amber-soft: #49351a;
        --call-line: #55d6e8; --role-line: #a99ad6; --role-active: #b9a7ff; --role-active-soft: #2a254b;
        --teal: #5ee0d3; --red: #ff9898; --red-soft: #4a2529; --green: #6ee7ae; --green-soft: #163c2d;
        --shadow: 0 20px 60px rgb(0 0 0 / .38); color-scheme: dark;
      }
    }
    * { box-sizing: border-box; }
    html { scroll-behavior: smooth; }
    body { margin: 0; background: var(--bg); color: var(--ink); font: 16px/1.65 Inter, Pretendard, ui-sans-serif, system-ui, sans-serif; }
    .sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
    body::before { content: ""; position: fixed; inset: 0 0 auto; height: 320px; z-index: -1; background: radial-gradient(circle at 75% 0%, rgb(0 173 194 / .18), transparent 42%), linear-gradient(135deg, var(--surface), transparent); }
    a { color: var(--cyan); text-underline-offset: 3px; overflow-wrap: anywhere; }
    button { font: inherit; }
    button:focus-visible, a:focus-visible { outline: 3px solid var(--amber); outline-offset: 3px; }
    code, kbd { font-family: "SFMono-Regular", Consolas, monospace; font-size: .86em; }
    code { overflow-wrap: anywhere; }
    .skip-link { position: fixed; left: 1rem; top: -5rem; z-index: 100; padding: .7rem 1rem; background: var(--surface); border: 2px solid var(--cyan); border-radius: 8px; }
    .skip-link:focus { top: 1rem; }
    .topbar { width: min(1180px, calc(100% - 32px)); margin: 18px auto 0; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
    .brand { display: flex; gap: 10px; align-items: center; color: var(--ink); text-decoration: none; font-weight: 800; letter-spacing: -.02em; }
    .brand-mark { width: 34px; height: 34px; display: grid; place-items: center; border-radius: 10px; background: var(--cyan); color: #04141a; }
    .toolbar { display: flex; align-items: center; gap: 8px; }
    .theme-toggle, .locale-link { min-height: 42px; border: 1px solid var(--line); border-radius: 999px; padding: 8px 13px; color: var(--ink); background: var(--surface); text-decoration: none; cursor: pointer; }
    .hero { width: min(1180px, calc(100% - 32px)); margin: 70px auto 50px; display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(250px, .5fr); gap: 42px; align-items: end; }
    .hero h1 { font-size: clamp(2.45rem, 7vw, 5.4rem); line-height: .98; letter-spacing: -.065em; margin: 12px 0 24px; max-width: 960px; }
    .eyebrow, .kicker { margin: 0; color: var(--cyan); font-size: .76rem; font-weight: 850; letter-spacing: .14em; text-transform: uppercase; }
    .lede { font-size: clamp(1.05rem, 2vw, 1.28rem); color: var(--muted); max-width: 860px; }
    .hero-aside { border-left: 3px solid var(--cyan); padding-left: 20px; color: var(--muted); }
    .hero-aside strong { color: var(--ink); display: block; margin-bottom: 6px; }
    main { width: min(1180px, calc(100% - 32px)); margin: 0 auto 100px; }
    section { scroll-margin-top: 24px; margin: 0 0 70px; }
    .section-heading { display: grid; grid-template-columns: 52px minmax(0, 1fr); gap: 18px; margin-bottom: 24px; align-items: start; }
    .section-heading h2, .pending-card h2 { margin: 1px 0 6px; font-size: clamp(1.65rem, 4vw, 2.7rem); line-height: 1.1; letter-spacing: -.04em; }
    .section-heading p { color: var(--muted); max-width: 820px; margin: 8px 0 0; }
    .section-number { width: 45px; height: 45px; display: grid; place-items: center; border: 1px solid var(--line); border-radius: 50%; font: 700 .78rem/1 monospace; color: var(--cyan); }
    .mental-grid { display: grid; grid-template-columns: 1fr 90px 1fr; gap: 18px; align-items: stretch; }
    .mental-card, .pending-card { border: 1px solid var(--line); border-radius: var(--radius); padding: clamp(22px, 4vw, 38px); background: var(--surface); box-shadow: var(--shadow); }
    .mental-card h3 { font-size: 1.45rem; margin: 15px 0 5px; }
    .badge { display: inline-flex; padding: 4px 9px; border-radius: 999px; background: var(--surface-2); color: var(--muted); font: 750 .75rem/1.5 monospace; }
    .flow-line { padding: 18px; border-radius: 13px; background: var(--surface-2); font-family: monospace; }
    .strength { color: var(--green); }
    .cost { color: var(--amber); }
    .trade-arrow { display: grid; place-content: center; text-align: center; color: var(--muted); font: 700 .7rem/1.2 monospace; letter-spacing: .12em; }
    .trade-arrow b { font-size: 2rem; color: var(--cyan); }
    .invariant-callout { display: grid; grid-template-columns: 140px 1fr; gap: 18px; margin-top: 18px; padding: 22px; border-radius: 14px; background: var(--navy); color: var(--bg); }
    :root[data-theme="dark"] .invariant-callout { color: #07111f; }
    @media (prefers-color-scheme: dark) { :root[data-theme="auto"] .invariant-callout { color: #07111f; } }
    .invariant-callout p { margin: 0; }
    .architecture-card { position: relative; margin: 0 0 22px; padding: 16px; border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface); box-shadow: var(--shadow); }
    .architecture-card > img { display: block; width: 100%; height: auto; border-radius: 12px; background: #07111f; }
    .architecture-open { position: absolute; z-index: 2; top: 28px; right: 28px; padding: 9px 13px; border: 1px solid var(--line); border-radius: 999px; background: rgb(7 17 31 / .88); color: #f2f8ff; cursor: pointer; }
    dialog { width: min(96vw, 1500px); max-height: 94vh; padding: 0; border: 1px solid var(--line); border-radius: 18px; background: var(--surface); color: var(--ink); box-shadow: 0 30px 100px #0009; }
    dialog::backdrop { background: rgb(0 7 17 / .84); backdrop-filter: blur(4px); }
    dialog img { display: block; width: 100%; height: auto; }
    .dialog-bar { position: sticky; top: 0; z-index: 2; display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 12px 16px; background: var(--surface); border-bottom: 1px solid var(--line); }
    .dialog-bar button { border: 1px solid var(--line); border-radius: 9px; padding: 7px 10px; background: var(--surface-2); color: var(--ink); cursor: pointer; }
    .scenario-tabs { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
    .scenario-tab { min-height: 66px; display: flex; align-items: center; gap: 12px; text-align: left; border: 1px solid var(--line); border-radius: 13px; background: var(--surface); color: var(--ink); padding: 12px 15px; cursor: pointer; }
    .scenario-tab span { font: 700 .74rem monospace; color: var(--muted); }
    .scenario-tab[aria-pressed="true"] { border-color: var(--cyan); background: var(--cyan-soft); box-shadow: inset 0 0 0 1px var(--cyan); }
    .sequence-panel { border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface); overflow: hidden; box-shadow: var(--shadow); }
    .sequence-summary { display: grid; grid-template-columns: 1.2fr .8fr; gap: 30px; padding: 26px; border-bottom: 1px solid var(--line); }
    .sequence-summary h3 { font-size: 1.6rem; margin: 10px 0 3px; }
    .sequence-summary p { margin: 0; color: var(--muted); }
    .sequence-summary dl { margin: 0; }
    .sequence-summary dl div { margin-bottom: 12px; }
    .sequence-summary dt { font-size: .74rem; text-transform: uppercase; letter-spacing: .09em; color: var(--muted); }
    .sequence-summary dd { margin: 3px 0 0; }
    .scenario-outcome { display: inline-flex; border-radius: 999px; padding: 4px 9px; font: 800 .72rem monospace; letter-spacing: .05em; }
    .outcome-commit { background: var(--green-soft); color: var(--green); }
    .outcome-rollback, .outcome-boundary-error { background: var(--red-soft); color: var(--red); }
    .sequence-canvas { position: relative; min-width: 760px; padding: 22px 26px 32px; overflow: hidden; }
    .sequence-participants { display: grid; grid-template-columns: repeat(var(--participants), 1fr); min-height: 350px; }
    .sequence-participant { position: relative; text-align: center; }
    .sequence-participant strong { position: relative; z-index: 2; display: flex; align-items: center; justify-content: center; min-height: 54px; margin: 0 5px; padding: 9px 8px; border: 1px solid var(--role-line); border-radius: 10px; background: var(--surface-2); font-size: .86rem; }
    .lifeline { position: absolute; top: 54px; bottom: 0; left: 50%; border-left: 2px dashed var(--role-line); }
    .activation { position: absolute; top: 82px; bottom: 34px; left: calc(50% - 5px); width: 10px; border: 2px solid var(--role-active); border-radius: 5px; background: var(--role-active-soft); }
    .sequence-messages { position: absolute; inset: 92px 26px 28px; display: grid; grid-template-rows: repeat(4, 1fr); margin: 0; padding: 0; list-style: none; pointer-events: none; }
    .message { --message-color: var(--call-line); position: relative; grid-row: var(--row); align-self: center; min-height: 48px; color: var(--message-color); }
    .message-return { --message-color: var(--teal); }
    .message-commit { --message-color: var(--green); }
    .message-rollback, .message-boundary-error { --message-color: var(--red); }
    .message-copy { position: absolute; z-index: 2; top: 0; left: var(--line-left); width: calc(var(--line-right) - var(--line-left)); display: flex; align-items: center; justify-content: center; min-width: max-content; }
    .message-line { position: absolute; top: 34px; left: var(--line-left); right: calc(100% - var(--line-right)); color: var(--message-color); border-top: 2px solid currentColor; }
    .message-line::after { content: ""; position: absolute; top: -7px; width: 0; height: 0; border: 6px solid transparent; }
    .message-forward .message-line::after { right: -12px; border-left-width: 10px; border-left-color: currentColor; }
    .message-reverse .message-line::after { left: -12px; border-right-width: 10px; border-right-color: currentColor; }
    .message-return .message-line { border-top-style: dashed; }
    .message-number, .message-kind, .message-label { background: var(--surface); }
    .message-number { display: inline-grid; flex: 0 0 auto; place-items: center; width: 24px; height: 24px; border: 2px solid currentColor; border-radius: 50%; color: var(--message-color); font: 800 .68rem monospace; }
    .message-kind { margin-left: 6px; color: var(--message-color); font: 800 .67rem monospace; letter-spacing: .06em; }
    .message-label { margin-left: 5px; padding-right: 6px; font-size: .78rem; color: var(--muted); }
    .sequence-alt { display: flex; gap: 12px; align-items: baseline; padding: 16px 26px; border-top: 1px solid var(--line); }
    .sequence-alt strong { font: 850 .78rem monospace; }
    .sequence-alt span { color: var(--muted); }
    .table-wrap { overflow-x: auto; border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface); box-shadow: var(--shadow); }
    table { width: 100%; border-collapse: collapse; min-width: 720px; }
    th, td { padding: 15px 17px; text-align: left; vertical-align: top; border-bottom: 1px solid var(--line); }
    thead th { color: var(--muted); background: var(--surface-2); font: 800 .72rem monospace; letter-spacing: .08em; text-transform: uppercase; }
    tbody th { width: 24%; color: var(--ink); }
    tbody td { color: var(--muted); }
    tr:last-child th, tr:last-child td { border-bottom: 0; }
    .evidence-table code { font-size: .72rem; }
    .evidence-table td:nth-child(2) a, .evidence-table td:nth-child(3) a { white-space: nowrap; }
    footer { width: min(1180px, calc(100% - 32px)); margin: 0 auto 50px; padding-top: 22px; border-top: 1px solid var(--line); display: flex; justify-content: flex-end; gap: 20px; color: var(--muted); font-size: .82rem; }
    [hidden] { display: none !important; }
    @media (max-width: 800px) {
      .hero { grid-template-columns: 1fr; margin-top: 48px; }
      .hero-aside { display: none; }
      .mental-grid { grid-template-columns: 1fr; }
      .trade-arrow { min-height: 54px; transform: rotate(90deg); }
      .scenario-tabs { grid-template-columns: 1fr 1fr; }
      .sequence-summary { grid-template-columns: 1fr; }
      .sequence-canvas { overflow-x: auto; }
      footer { flex-direction: column; }
    }
    @media (max-width: 480px) {
      .topbar, .hero, main, footer { width: min(100% - 22px, 1180px); }
      .brand span:last-child { display: none; }
      .toolbar { gap: 4px; }
      .theme-toggle, .locale-link { padding: 7px 9px; font-size: .78rem; }
      .hero h1 { font-size: 2.55rem; }
      .section-heading { grid-template-columns: 42px 1fr; gap: 10px; }
      .section-number { width: 38px; height: 38px; }
      .scenario-tabs { grid-template-columns: 1fr; }
      .invariant-callout { grid-template-columns: 1fr; }
      .architecture-open { position: static; width: 100%; margin-bottom: 10px; }
    }
    @media (prefers-reduced-motion: reduce) {
      *, *::before, *::after { scroll-behavior: auto !important; animation-duration: .01ms !important; transition-duration: .01ms !important; }
    }
  </style>
</head>
<body data-source="${escapeHtml(source)}">
  <a class="skip-link" href="#scenario-explorer">${escapeHtml(copy.skip)}</a>
  <nav class="topbar" aria-label="Document controls">
    <a class="brand" href="#top"><span class="brand-mark">E</span><span>bluetape4k · Exposed</span></a>
    <div class="toolbar">
      <button type="button" class="theme-toggle" aria-label="${escapeHtml(copy.theme)}" aria-pressed="false" data-theme-control>◐ <span>auto</span></button>
      <a class="locale-link" href="../${opposite}/${escapeHtml(model.id)}.html" hreflang="${opposite}">${escapeHtml(copy.language)}</a>
    </div>
  </nav>
  <header class="hero" id="top">
    <div>
      <p class="eyebrow">${escapeHtml(copy.eyebrow)}</p>
      <h1>${escapeHtml(copy.title)}</h1>
      <p class="lede">${escapeHtml(copy.lede)}</p>
    </div>
    <aside class="hero-aside">
      <strong>${model.kind === 'transaction' ? 'JPA → EXPOSED' : 'SPRING BOOT → EXPOSED'}</strong>
      ${escapeHtml(copy.invariantText ?? copy.lede)}
    </aside>
  </header>
  <main>${body}</main>
  <span id="status" class="sr-only" aria-live="polite">${escapeHtml(copy.statusPrefix ?? copy.title)}</span>
  <footer>
    <span><a data-source-link href="${repositoryBlob}${manual}">${manualLabel}</a></span>
  </footer>
  <script>
    (() => {
      const themes = ['auto', 'light', 'dark'];
      const params = new URLSearchParams(location.search);
      const forcedTheme = params.get('theme');
      let theme = themes.includes(forcedTheme) ? forcedTheme : 'auto';
      const root = document.documentElement;
      const themeToggle = document.querySelector('[data-theme-control]');
      const applyTheme = (next) => {
        theme = next;
        root.dataset.theme = theme;
        themeToggle.querySelector('span').textContent = theme;
        themeToggle.setAttribute('aria-pressed', String(theme !== 'auto'));
      };
      applyTheme(theme);
      themeToggle.addEventListener('click', () => applyTheme(themes[(themes.indexOf(theme) + 1) % themes.length]));

      const status = document.querySelector('#status');
      const scenarioButtons = [...document.querySelectorAll('[data-scenario]')];
      const selectScenario = (id, announce = true) => {
        for (const button of scenarioButtons) {
          const active = button.dataset.scenario === id;
          button.setAttribute('aria-pressed', String(active));
        }
        for (const panel of document.querySelectorAll('[data-sequence]')) {
          panel.hidden = panel.dataset.sequence !== id;
        }
        const selected = scenarioButtons.find((button) => button.dataset.scenario === id);
        if (selected && announce) status.textContent = '${escapeHtml(copy.statusPrefix ?? 'Selected')}' + ' ' + selected.textContent.trim();
      };
      for (const button of scenarioButtons) {
        button.addEventListener('click', () => selectScenario(button.dataset.scenario));
        button.addEventListener('keydown', (event) => {
          if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return;
          event.preventDefault();
          const direction = ['ArrowRight', 'ArrowDown'].includes(event.key) ? 1 : -1;
          const target = scenarioButtons[(scenarioButtons.indexOf(button) + direction + scenarioButtons.length) % scenarioButtons.length];
          target.focus();
          selectScenario(target.dataset.scenario);
        });
      }

      for (const opener of document.querySelectorAll('[data-lightbox-open]')) {
        opener.addEventListener('click', () => document.querySelector('#architecture-' + opener.dataset.lightboxOpen).showModal());
      }
      for (const closer of document.querySelectorAll('[data-lightbox-close]')) {
        closer.addEventListener('click', () => document.querySelector('#architecture-' + closer.dataset.lightboxClose).close());
      }
      for (const dialog of document.querySelectorAll('dialog')) {
        dialog.addEventListener('click', (event) => {
          if (event.target === dialog) dialog.close();
        });
      }
      window.__VISUAL_COMPANION_READY__ = true;
    })();
  </script>
</body>
</html>
`;
}

export function renderDocument({ model, locale, architectureAssets }) {
  return model.kind === 'transaction'
    ? renderTransactionDocument({ model, locale, architectureAssets })
    : renderActivationPlaceholder({ model, locale, architectureAssets });
}
