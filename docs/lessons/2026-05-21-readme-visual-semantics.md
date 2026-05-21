# README visual semantics and placement

## Context

README images had several placeholder-style alt labels, benchmark sections used
diagram-shaped output where charts were clearer, and test infrastructure
diagrams appeared after usage details.

## Decision

Keep architecture and test-support diagrams near the top of their README files,
use chart images for measured benchmark results, and make generated image labels
English-only.

## Outcome

The root README visual order, exposed-jdbc benchmark chart, exposed-batch
benchmark map/chart, and JDBC/R2DBC test infrastructure diagrams now match the
section intent and current source layout.

## Verification

- `xmllint --noout` on changed SVG assets
- `rsvg-convert` PNG rendering for new and changed SVG assets
- README/Benchmark image-link scan
- Placeholder image-alt and broken graph pattern scan
- `./gradlew :bluetape4k-exposed-batch:compileBenchmarkKotlin`

## Future note

When benchmark docs are regenerated, prefer durable PNG chart references over
Mermaid `xychart-beta` blocks so README rendering stays stable across GitHub and
presentation/blog reuse.
