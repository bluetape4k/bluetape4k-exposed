# Lesson: issue #290 Tink associated data binding

## Context

Tink AEAD and DAEAD APIs authenticate associated data, but the Exposed Tink column transformers previously called encrypt/decrypt helpers without associated data. Ciphertext from one encrypted column could therefore be copied into another compatible encrypted column using the same key and still decrypt.

## Decision

Default table extension helpers now bind ciphertext to a stable domain:

```text
bluetape4k-exposed-tink:v1:<tableName>:<columnName>
```

The public `TinkColumnAssociatedDataProvider` contract lets users supply a stronger domain, while `TinkColumnAssociatedDataProvider.Empty` is available only for legacy migration.

## Guardrail

Do not add new encrypted column helper paths that call Tink encrypt/decrypt without explicitly deciding the associated-data domain. For DAEAD, remember that row-scoped associated data breaks ordinary equality search because one query value can no longer produce a shared ciphertext across candidate rows.

## Tests

The regression suite must keep both cases:

- Helper default: ciphertext copied across columns/tables fails to decrypt.
- Direct registration: explicit associated data also rejects copied ciphertext.
