# Issue 282 Tink Persisted Keyset Review

Date: 2026-06-23
Scope: `exposed/tink`
Issue: #282

## Verdict

P0 findings: 0
P1 findings: 0

Persisted encrypted column helpers no longer create or hide generated process-local Tink keysets. Callers must pass explicit AEAD or Deterministic AEAD encryptors, and the README examples now show encryptors reconstructed from durable keyset JSON.

## Review Notes

- `Table.tinkAead*` and `Table.tinkDaead*` helpers now require caller-supplied encryptors. `VARCHAR` helpers retain the default ciphertext length through `name + encryptor` overloads, but not a default key.
- AEAD and DAEAD transformer constructors no longer expose generated-keyset defaults for direct use.
- Existing tests now pass explicit test encryptors where they intentionally use generated in-memory keysets.
- New regressions prove ciphertext written with a keyset reconstructed from persisted JSON remains readable after table reconstruction, while a newly generated keyset cannot decrypt the same stored ciphertext.
- English and Korean READMEs now document durable keyset loading before encrypted table definitions.

## Validation

- `./gradlew :bluetape4k-exposed-tink:testClasses --rerun-tasks`
  - Result: success.
- `./gradlew :bluetape4k-exposed-tink:test --continue --rerun-tasks`
  - Result: success, 163 tests passed.
- `git diff --check`
  - Result: success.

## Residual Risk

- This intentionally changes the source-level API for unsafe convenience calls. Downstream callers must provide persisted/versioned encryptors instead of relying on module-generated defaults.
