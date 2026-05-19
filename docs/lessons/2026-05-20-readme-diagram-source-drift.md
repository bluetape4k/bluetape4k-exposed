# README diagram source drift correction

## Context
A generated class diagram made deprecated `HasIdentifier` look like a primary exposed-core API. Current source marks it deprecated and recommends `Serializable` records instead.

## Decision
Regenerate README diagrams from current source, not recovered Mermaid alone. Keep deprecated compatibility APIs out of central class diagrams unless they are explicitly documented as compatibility notes.

## Outcome
`exposed-core` now centers `AuditableIdTable` and `ExposedPage`, while Redisson/R2DBC README snippets use `Serializable`, `RedissonCacheConfig`, `table`, and `containsKey` names that match source APIs.

## Verification
Checked README and SVG text for stale `HasIdentifier`, `RedisCacheConfig`, `entityTable`, and marker-only class labels; visually reviewed regenerated exposed-core diagrams.

## Next time
Before rendering class/API diagrams, grep current source for every class, field, method, and relationship shown in the image.
