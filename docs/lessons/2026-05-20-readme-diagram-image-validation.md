# README Diagram Image Validation

## Context

README diagram assets were regenerated as pastel infographic PNG images while
preserving SVG sources for reuse.

## Decision

Use PNG embeds in README files, keep SVG assets beside them, and keep diagram
labels English-only. Class diagrams must keep UML compartments and visible
inheritance stems; sequence diagrams must grow vertically instead of covering
messages with notes.

## Outcome

The exposed README diagrams were regenerated and linked as PNG files. A stale
Mermaid tail in `exposed-jdbc-redisson` was removed. The `exposed-core`
ID-table hierarchy image was adjusted so inheritance arrows show visible line
segments instead of only triangle markers.

## Verification

- Full regeneration: `rendered=188`, `missing=[]`.
- README image links: `missing=0`.
- Local SVG image embeds: `0`.
- Mermaid residue: `0`.
- Asset counts: `png=155`, `svg=155`.
- Shape sanity check: `shapeCandidates=0`.
- Whitespace check: `git diff --check`.

## Future Guidance

Do not accept a class diagram if inheritance or realization arrows collapse into
marker-only visuals. Increase class row spacing before review.
