# 2026-05-20 — Batch benchmark charts

## Context

The batch benchmark details for H2, MySQL, and PostgreSQL used Mermaid xychart
templates. The rendered output was less useful than direct PNG charts and still
needed legend workarounds.

## Decision

Replace the Mermaid benchmark chart blocks with static SVG + PNG charts under
`docs/images/readme-charts/`. Keep the detailed result tables as the measured
source of truth.

## Outcome

Each database detail page now has three charts: seed throughput by data size,
seed throughput by pool size, and end-to-end throughput by parallelism.

## Verification

- `xmllint --noout docs/images/readme-charts/*.svg`
- `identify docs/images/readme-charts/*.png`
- Searched the touched benchmark detail files for remaining Mermaid/ASCII chart
  blocks.

## Future

Use log scale for data-size and end-to-end comparisons when JDBC and R2DBC values
span multiple orders of magnitude.
