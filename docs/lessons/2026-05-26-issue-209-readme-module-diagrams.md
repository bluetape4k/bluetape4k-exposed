# Issue 209 README Module Diagrams

## Context

Issue #209 asked for root README relationship diagrams refreshed from the current
module tables and source modules. The existing root module composition chart
lived under `docs/images/readme-charts/`, while the issue required README-facing
SVG and PNG assets under `docs/images/readme-diagrams/`.

## Decision

Keep the root overview diagram and replace the module chart with a module
relationship diagram under `docs/images/readme-diagrams/`. Validate the module
model against `README.md`, `README.ko.md`, and `settings.gradle.kts` before
editing assets, then render and inspect each SVG/PNG pair.

## Outcome

The README visual block now embeds only PNG assets from
`docs/images/readme-diagrams/`. The SVG sources sit beside their PNGs, and the
old root module chart under `docs/images/readme-charts/` was removed.

## Verification

- Source model check against the README module table and `settings.gradle.kts`
- CairoSVG render for both SVG sources
- `xmllint --noout` for both generated SVG files
- README image link check
- `git diff --check`

## Future Notes

For root README diagram refreshes, validate the README module table against
`settings.gradle.kts` before drawing. Keep shared English-label assets for
localized READMEs unless the diagram itself needs localized domain terms. Do not
reintroduce stale generator scripts that can overwrite manually validated
README-scale SVG assets.
