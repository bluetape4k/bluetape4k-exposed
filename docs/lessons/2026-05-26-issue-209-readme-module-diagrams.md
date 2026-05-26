# Issue 209 README Module Diagrams

## Context

Issue #209 asked for root README relationship diagrams refreshed from the current
module tables and source modules. The existing root module composition chart
lived under `docs/images/readme-charts/`, while the issue required README-facing
SVG and PNG assets under `docs/images/readme-diagrams/`.

## Decision

Keep the root overview diagram and replace the module chart with a module
relationship diagram under `docs/images/readme-diagrams/`. Generate both assets
from one validated module model so `README.md`, `README.ko.md`, and
`settings.gradle.kts` stay aligned.

## Outcome

The README visual block now embeds only PNG assets from
`docs/images/readme-diagrams/`. The SVG sources sit beside their PNGs, and the
old root module chart under `docs/images/readme-charts/` was removed.

## Verification

- `python3 tools/generate_root_readme_diagrams.py`
- `rsvg-convert` for both SVG sources
- `xmllint --noout` for both generated SVG files
- `python3 -m py_compile tools/generate_root_readme_diagrams.py`
- README image link check
- `git diff --check`

## Future Notes

For root README diagram refreshes, validate the README module table against
`settings.gradle.kts` before drawing. Keep shared English-label assets for
localized READMEs unless the diagram itself needs localized domain terms.
