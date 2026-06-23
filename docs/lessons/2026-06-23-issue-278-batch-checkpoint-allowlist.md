# Lessons Learned - Batch Checkpoint Class Allowlist (2026-06-23)

Issue: #278
Module: `:bluetape4k-exposed-batch`

## L1: Persisted type metadata is data, not authority

### Problem

The checkpoint envelope stored `className` so Jackson 3 could restore scalar types accurately. That solved Long/Int round-trip correctness, but it also made persisted rows capable of choosing the restore class.

### Lesson

Typed envelopes that cross a storage boundary need a registry. Resolve persisted type names only from known classes, and require explicit registration for application-specific checkpoint state. Regression tests should mutate the stored JSON directly, because repository-level restore is where the persisted trust boundary is crossed.
