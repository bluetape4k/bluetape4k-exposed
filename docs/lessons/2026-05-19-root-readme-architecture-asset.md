# Root README architecture asset

## Context

The root README architecture section used Mermaid diagrams in both English and Korean README files.

## Decision

Replace the Mermaid blocks with one shared SVG asset under `docs/assets/`, following the repo-local rule that root README visual assets live there and are shared by localized READMEs.

## Outcome

`README.md` and `README.ko.md` now embed `docs/assets/exposed-architecture.svg`.

## Verification

Validated the SVG with `xmllint --noout`, confirmed both README links resolve to the shared asset, and confirmed no Mermaid block remains in the architecture sections.

## Future Guidance

For text-heavy README diagrams, prefer deterministic SVG assets over generated bitmap images so module names and API terms stay legible in GitHub rendering.
