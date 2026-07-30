# Issue 410 Exposed Visual Companions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Publish two source-owned, bilingual, accessible visual companions for the Exposed manual and prepare the exact source contract consumed by `bluetape4k.github.io#304`.

**Architecture:** The library repository owns standalone HTML, a schema-versioned manifest, deterministic validation, and manual links. Each companion is a self-contained progressive-enhancement document with no network dependency. The site repository will snapshot the merged source commit in a later issue and will not rewrite source HTML.

**Tech Stack:** Standalone HTML/CSS/JavaScript, Node.js built-in modules and `node:test`, Markdown, JSON, Gradle manual inventory, Playwright/Chromium browser verification.

---

## Task 1: Lock the repository contract with failing tests

**Files:**

- Create: `tests/visual-companions/validator.test.mjs`
- Test: `tests/visual-companions/validator.test.mjs`

- [x] Add a `node:test` suite that imports `validateRepository` from `scripts/visual-companions/validate.mjs`.
- [x] Assert the approved repository returns exactly two documents and four locale files.
- [x] Assert a temporary manifest fixture with a duplicate ID is rejected.
- [x] Assert a temporary HTML fixture with an external runtime dependency is rejected.
- [x] Assert temporary locale fixtures with different control IDs, `data-view` values, source anchors, or section IDs are rejected.
- [x] Assert missing viewport, color-scheme, reduced-motion, semantic `<main>`, source marker, reciprocal locale link, or declared view fails with a document-scoped diagnostic.
- [x] Run `node --test tests/visual-companions/validator.test.mjs` and confirm RED because the validator and production files do not exist.
- [x] Commit the test contract using the Lore protocol.

## Task 2: Implement the manifest validator

**Files:**

- Create: `scripts/visual-companions/validate.mjs`
- Modify: `tests/visual-companions/validator.test.mjs`
- Test: `tests/visual-companions/validator.test.mjs`

- [x] Implement safe repository-contained path resolution using `path.resolve`, `realpath`, and root-prefix checks.
- [x] Validate schema version `1`, repository identity `bluetape4k/bluetape4k-exposed`, two unique kebab-case document IDs, approved/public status, presentation fields, exact `en`/`ko` locales, and unique HTML ownership.
- [x] Validate standalone HTML constraints: doctype, locale, viewport, `light dark` color scheme, light/dark tokens, reduced-motion rule, semantic `<main>`, accessible theme toggle, bounded live region, source marker/link, reciprocal locale link, and all declared views.
- [x] Reject external scripts, stylesheets, images/media, forms, `fetch`, `XMLHttpRequest`, `WebSocket`, `sendBeacon`, and absolute runtime URLs.
- [x] Compare structural fingerprints for English and Korean section IDs, control IDs, `data-view` values, `data-condition` values, `data-source-anchor` values, and source-link targets.
- [x] Export `validateRepository(inputRoot, manifestRelativePath)` for fixture tests and provide a CLI entry point with deterministic diagnostics.
- [x] Run the targeted test and confirm failures are now only missing production manifest/documents.
- [x] Commit the validator implementation using the Lore protocol.

## Task 3: Build the transaction-boundary companion pair

**Files:**

- Create: `docs/visual-companions/jdbc-r2dbc-transaction-boundaries.html`
- Create: `docs/visual-companions/jdbc-r2dbc-transaction-boundaries.ko.html`
- Modify: `tests/visual-companions/validator.test.mjs`
- Test: `tests/visual-companions/validator.test.mjs`

- [x] Add source-backed assertions for the `jdbc`, `r2dbc`, and `multi-call` views and for the JDBC/R2DBC transaction ownership claims.
- [x] Implement the English document with aligned execution lanes, explicit open/commit boundaries, active-context warnings, source links, locale link, auto/light/dark theme control, and keyboard-operable view buttons.
- [x] Implement the Korean document with identical structure, controls, source anchors, and view behavior.
- [x] Keep the initial `jdbc` view useful without JavaScript; use JavaScript only to switch the local presentation state.
- [x] Verify `aria-pressed`, the polite live summary, visible focus, 320 px layout, and reduced-motion behavior.
- [x] Parse every extracted inline script with Node and run the targeted test.
- [x] Commit the transaction pair using the Lore protocol.

## Task 4: Build the Spring Boot activation companion pair

**Files:**

- Create: `docs/visual-companions/spring-boot-exposed-activation.html`
- Create: `docs/visual-companions/spring-boot-exposed-activation.ko.html`
- Modify: `tests/visual-companions/validator.test.mjs`
- Test: `tests/visual-companions/validator.test.mjs`

- [x] Add source-backed assertions for the six activation conditions and for bean creation, back-off, repository scanning, and application-owned R2DBC infrastructure.
- [x] Implement the English condition explorer with native checkboxes, an ordered activation path, a bean/ownership table, and clear backed-off states.
- [x] Implement the Korean document with identical structure, condition IDs, source anchors, and state transitions.
- [x] Ensure the R2DBC view never claims creation of `ConnectionPool`, `R2dbcDatabase`, or a reactive transaction manager.
- [x] Verify keyboard traversal, checked-state summaries, visible focus, 320 px layout, and reduced-motion behavior.
- [x] Parse every extracted inline script with Node and run the targeted test.
- [x] Commit the activation pair using the Lore protocol.

## Task 5: Publish the source manifest and manual entry points

**Files:**

- Create: `docs/visual-companions/manifest.json`
- Modify: `docs/manual/en/guides/transaction-boundaries.md`
- Modify: `docs/manual/ko/guides/transaction-boundaries.md`
- Modify: `docs/manual/en/guides/spring-and-ktor.md`
- Modify: `docs/manual/ko/guides/spring-and-ktor.md`
- Modify: `tests/visual-companions/validator.test.mjs`

- [x] Add both approved/public documents to the manifest with their exact presentation modes, default views, locale titles, source design, and HTML paths.
- [x] Add concise English and Korean “explore the flow” links to the final locale-specific public routes while retaining the existing SVG/PNG diagrams.
- [x] Add test assertions for manifest/manual route parity and the exact four public links.
- [x] Run `node scripts/visual-companions/validate.mjs`.
- [x] Run `node --test tests/visual-companions/validator.test.mjs`.
- [x] Commit manifest and manual integration using the Lore protocol.

## Task 6: Isolate the pre-existing manual manifest debt

**Files:**

- No source changes in issue `#410`.

- [x] Re-run `./gradlew exportManualModuleInventory --no-daemon`.
- [x] Re-run `ruby scripts/manual/validate_manuals.rb build/manual/module-inventory.json docs/manual/manifest.yaml`.
- [x] Confirm the only failures remain the baseline omissions for `bluetape4k-exposed-druid` and `examples-ddd-spring-modulith-demo`.
- [x] Create a separate milestone `1.12.0` documentation issue for those omissions and link it from the `#410` PR as pre-existing validator debt.
- [x] Do not mark the full manual inventory validator green; record the exact failing output in the PR DoD.

## Task 7: Run deterministic browser and source verification

**Files:**

- Verify: `docs/visual-companions/*.html`
- Verify: `docs/visual-companions/manifest.json`
- Verify: paired manual pages

- [x] Serve the worktree on loopback, confirm zero external runtime resources, and exercise interactions with Chromium offline.
- [x] Exercise English and Korean documents at `1440x1100` and `360x800`, explicit light/dark themes, all transaction views, and representative activation condition branches.
- [x] Verify keyboard-only traversal, reduced-motion emulation, no console errors, no failed requests, no clipping, and no page-level horizontal overflow.
- [x] Capture temporary review screenshots and inspect full-size renders; do not commit screenshots.
- [x] Run `node scripts/visual-companions/validate.mjs`.
- [x] Run `node --test tests/visual-companions/validator.test.mjs`.
- [x] Run `git diff --check`.
- [x] Run a prose-aware locale residue scan over the changed HTML and Markdown files.
- [x] Run `./gradlew help --no-daemon`.
- [x] Record the visual-companion tests and static checks as passed, and record the isolated baseline manual-validator failure separately.

## Task 8: Review and open the source PR

**Files:**

- Review all files changed from `origin/develop`.

- [x] Review the complete branch diff for claim accuracy, English/Korean structural parity, accessibility, technical-anchor preservation, and accidental unrelated changes.
- [x] Update the implementation-plan checkboxes to reflect completed evidence.
- [ ] Push `docs/issue-410-visual-companions`.
- [ ] Open an English PR against `develop` with `Closes #410`, `Parent: #409`, a concise source ledger, verification evidence, the pre-existing manual-validator issue, and a final `## DoD Status`.
- [ ] Re-read live PR body, exact head, checks, reviews, threads, and mergeability.
- [ ] Stop at the exact-head merge-ready gate and request fresh user approval before merging.
