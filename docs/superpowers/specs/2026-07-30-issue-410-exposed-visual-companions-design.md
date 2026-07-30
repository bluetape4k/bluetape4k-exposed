# Issue 410 Exposed Visual Companions Design

## Context

The manual already contains static diagrams for transaction ownership and Spring
Boot auto-configuration. Those diagrams explain the default topology, but they
cannot let a reader compare execution paths, toggle activation conditions, or
inspect the source evidence behind each state.

The ecosystem site already publishes six bilingual visual companions from three
source repositories. Each source repository owns a manifest and standalone HTML
files; `bluetape4k.github.io` snapshots an exact source ref and exposes the
documents through its shared catalog and locale routes.

Issue [#410](https://github.com/bluetape4k/bluetape4k-exposed/issues/410)
extends that contract to this repository without changing production behavior.

## Goals

- Add the source-owned manifest and validator for public visual companions.
- Publish two representative, source-backed companions in English and Korean.
- Make transaction ownership and Spring Boot activation easier to explore than
  the existing static diagrams.
- Keep the English and Korean documents structurally and semantically
  equivalent.
- Link the companions from the relevant English and Korean manual pages.
- Hand a pinned, validation-ready source contract to
  `bluetape4k.github.io#304`.

## Non-goals

- Changing JDBC, R2DBC, Spring Boot, repository, or transaction behavior.
- Replacing the existing SVG/PNG manual diagrams.
- Adding a runtime JavaScript, CSS, font, or visualization dependency.
- Embedding live HTML inside Markdown.
- Publishing site snapshots from an unmerged source commit.
- Generalizing the first delivery to every bluetape4k repository.

## Approaches considered

### 1. Add more static SVG/PNG diagrams

This would reuse the current manual asset pipeline, but it would duplicate the
existing transaction and auto-configuration diagrams without giving readers a
meaningful comparison or condition explorer.

### 2. Store all interactive documents in `bluetape4k.github.io`

This would simplify site routing but separate claims from the code that proves
them. Source changes could silently invalidate site-owned explanations.

### 3. Source-owned standalone HTML with site snapshots

This reuses the existing ecosystem contract. The library repository reviews the
claims, interactions, locale parity, and source anchors. The site repository
only snapshots an immutable source ref and publishes shared routes.

This design selects approach 3.

## Publication architecture

```text
bluetape4k-exposed
  docs/visual-companions/manifest.json
  docs/visual-companions/*.html
  scripts/visual-companions/validate.mjs
  docs/manual/{en,ko}/...
          |
          | exact merged commit
          v
bluetape4k.github.io
  scripts/visual-companions/sync.mjs
  src/data/visual-companions/bluetape4k-exposed.snapshot.json
  src/data/visual-companions/catalog.json
          |
          v
  /visual-companions/bluetape4k-exposed/<id>/
  /ko/visual-companions/bluetape4k-exposed/<id>/
```

The source repository is authoritative for document content. The site snapshot
records the repository, source ref, source path, source SHA-256, locale, title,
and rendered HTML. Site code must not rewrite the source HTML.

## Source manifest

The new `docs/visual-companions/manifest.json` follows schema version 1:

```json
{
  "schemaVersion": 1,
  "repository": "bluetape4k/bluetape4k-exposed",
  "documents": [
    {
      "id": "jdbc-r2dbc-transaction-boundaries",
      "source": "docs/superpowers/specs/2026-07-30-issue-410-exposed-visual-companions-design.md",
      "status": "approved",
      "public": true,
      "presentation": {
        "mode": "comparison",
        "defaultView": "jdbc",
        "views": ["jdbc", "r2dbc", "multi-call"]
      },
      "locales": {
        "en": {
          "title": "JDBC and R2DBC Transaction Boundaries",
          "html": "docs/visual-companions/jdbc-r2dbc-transaction-boundaries.html"
        },
        "ko": {
          "title": "JDBC와 R2DBC 트랜잭션 경계",
          "html": "docs/visual-companions/jdbc-r2dbc-transaction-boundaries.ko.html"
        }
      }
    }
  ]
}
```

The second document uses the same shape with ID
`spring-boot-exposed-activation`.

New companion names follow the current diagram contract: the English document
uses `*.html` and the Korean document uses `*.ko.html`.

## Companion 1: JDBC and R2DBC transaction boundaries

### Reader question

Where is a transaction opened, how long does it remain active, and what changes
when several repository calls must commit together?

### Interaction model

The document offers three source-backed views:

1. `JDBC repository call`
2. `R2DBC repository call`
3. `Multi-call business operation`

Selecting a view updates one dominant execution path. Each path shows:

- caller or service;
- Spring proxy or explicit Exposed transaction entry;
- repository method;
- Exposed transaction context;
- driver and database;
- commit boundary;
- whether a returned DAO entity or `Flow` still requires an active transaction.

The multi-call view contrasts independent repository transactions with one
caller-owned business boundary. It does not claim that framework-specific
propagation behaves identically across JDBC and R2DBC.

### Source ledger

| Visible claim | Source |
| --- | --- |
| JDBC repository methods participate in Spring transactions | `spring-boot/jdbc/.../SimpleExposedJdbcRepository.kt` |
| JDBC auto-configuration creates `springTransactionManager` only when missing | `spring-boot/jdbc/.../ExposedSpringDataAutoConfiguration.kt` |
| R2DBC CRUD methods call `suspendTransaction` through `inTransaction` | `spring-boot/r2dbc/.../SimpleExposedR2dbcRepository.kt` |
| R2DBC streaming keeps `suspendTransaction` active while rows are sent | `SimpleExposedR2dbcRepository.streamAll` |
| Application services own multi-statement business invariants | `docs/manual/{en,ko}/guides/transaction-boundaries.md` |

### Invariants

- JDBC is described as blocking and Spring-managed in this module.
- R2DBC is described as coroutine-based with explicit Exposed transactions.
- A single repository method is not presented as proof of multi-call atomicity.
- Cancellation is not presented as proof that rollback and connection return
  have already completed.
- External I/O is never shown as rollback-safe.

## Companion 2: Spring Boot Exposed activation

### Reader question

Which conditions activate JDBC and R2DBC integration, which beans are created,
and which infrastructure remains application-owned?

### Interaction model

The document exposes a compact condition explorer with native checkboxes:

- `EntityClass` on the classpath;
- `DataSource` bean available;
- existing `springTransactionManager`;
- JDBC repository enable annotation;
- R2DBC repository enable annotation;
- existing `ExposedMappingContext`.

The result updates one activation path and a short bean/ownership table. It
shows both activated and backed-off states. It never simulates Spring's entire
condition evaluation engine.

### Source ledger

| Visible claim | Source |
| --- | --- |
| JDBC auto-configuration requires `EntityClass` | `ExposedSpringDataAutoConfiguration.kt` |
| JDBC transaction manager requires `DataSource` and backs off by bean name | `ExposedSpringDataAutoConfiguration.springTransactionManager` |
| JDBC enable annotation imports registrar and auto-configuration | `EnableExposedJdbcRepositories.kt` |
| R2DBC auto-configuration runs after JDBC-side mapping configuration | `ExposedR2dbcSpringDataAutoConfiguration.kt` |
| R2DBC mapping context backs off when one already exists | `ExposedR2dbcSpringDataAutoConfiguration.exposedMappingContext` |
| R2DBC enable annotation imports only its repository registrar | `EnableExposedR2dbcRepositories.kt` |
| The application owns `ConnectionPool` and `R2dbcDatabase` | `docs/manual/{en,ko}/guides/spring-and-ktor.md` |

### Invariants

- The JDBC path may create `springTransactionManager`; the R2DBC path does not.
- The R2DBC path does not claim to create `ConnectionPool`, `R2dbcDatabase`, or
  a Spring reactive transaction manager.
- Repository scanning is distinct from auto-configuration bean creation.
- Back-off behavior is visible and is never represented as an error.

## HTML and accessibility contract

Each locale is a complete standalone document:

- no network requests, remote fonts, external scripts, or external stylesheets;
- semantic header, main content, sections, buttons, checkboxes, tables, and
  links;
- native tab order and visible focus styles;
- every control has a visible label and accurate `aria-pressed` or checked
  state;
- dynamic summaries use a bounded `aria-live="polite"` region;
- `prefers-reduced-motion: reduce` removes transitions;
- layout supports 320 px through wide desktop widths without horizontal page
  scrolling;
- `auto`, `light`, and `dark` themes use CSS custom properties;
- locale documents link to each other and to the canonical GitHub source;
- source anchors are visible reader links, not hidden validation metadata.

The first render is useful without interaction. JavaScript only changes local
presentation state.

## Visual design

The companions reuse the visual language established by the six published
companions: dark/light theme support, restrained cards, clear source anchors,
and one dominant explorable model.

The transaction companion uses aligned lanes rather than a free-form node
graph. The activation companion uses a condition list plus one ordered
activation path. Color is paired with labels and line or surface changes; it is
never the only carrier of meaning.

Static PNG exports remain optional for this delivery because the manual already
has source-backed SVG/PNG diagrams for the default states. The manual links to
the companions as an exploratory extension rather than replacing its existing
images.

## Validator

`scripts/visual-companions/validate.mjs` performs deterministic static checks:

- manifest schema version and repository identity;
- non-empty, unique document IDs;
- approved status for every public document;
- exact `en` and `ko` locale pair;
- existing source and HTML paths inside the repository;
- unique HTML path ownership;
- matching reciprocal locale links;
- no `http://`, `https://`, `fetch`, `XMLHttpRequest`, `WebSocket`, external
  script, stylesheet, image, or font dependency in companion runtime content;
- required viewport, color-scheme, reduced-motion, semantic main, and source
  link markers;
- declared views represented in both locale files;
- locale structural parity for IDs, controls, data attributes, and source
  anchors.

The script accepts the manifest path as an optional argument and exits non-zero
with document-scoped diagnostics.

## Manual integration

The following paired manual pages receive a short “Explore the flow” link:

- `docs/manual/en/guides/transaction-boundaries.md`
- `docs/manual/ko/guides/transaction-boundaries.md`
- `docs/manual/en/guides/spring-and-ktor.md`
- `docs/manual/ko/guides/spring-and-ktor.md`

Links use the final public route:

```text
https://bluetape4k.github.io/visual-companions/bluetape4k-exposed/<id>/
https://bluetape4k.github.io/ko/visual-companions/bluetape4k-exposed/<id>/
```

The static SVG/PNG images remain embedded. English pages link to English routes
and Korean pages link to Korean routes.

## Verification

### Static source checks

- `node scripts/visual-companions/validate.mjs`
- `git diff --check`
- targeted link and source-anchor checks
- HTML parser and inline JavaScript syntax checks
- locale structure comparison

### Browser checks

Use deterministic Chromium with network disabled:

- English and Korean;
- 1440 x 1100 and 360 x 800 viewports;
- explicit light and dark themes;
- default state and every declared view or condition branch;
- keyboard-only control traversal;
- no console errors, failed requests, clipping, or page-level horizontal
  overflow;
- reduced-motion emulation.

Screenshots are review evidence and are not committed unless a later manual
fallback requirement explicitly adds them.

### Existing manual validator debt

At the design baseline, these commands pass:

```text
./gradlew help --no-daemon
./gradlew exportManualModuleInventory --no-daemon
```

The current manual validator fails before this work because
`docs/manual/manifest.yaml` does not contain:

- `bluetape4k-exposed-druid`
- `examples-ddd-spring-modulith-demo`

Issue 410 does not silently hide this failure. The implementation plan must
either repair those manifest omissions as a bounded documentation prerequisite
or record a separate blocking issue before treating the manual validator as
green. The new visual companion validator remains independently required.

## Delivery sequence

1. Commit and review this design.
2. Write the implementation plan.
3. Add the validator tests before the validator implementation.
4. Build and verify the transaction companion locale pair.
5. Build and verify the activation companion locale pair.
6. Add the manifest and paired manual links.
7. Resolve or isolate the pre-existing manual manifest failure.
8. Run static, browser, locale, and manual verification.
9. Open the issue 410 PR against `develop`.
10. After that source PR is merged, pin its exact commit in
   `bluetape4k.github.io#304` and open the site PR.

The source PR and site PR each stop at the exact-head merge-ready gate.
