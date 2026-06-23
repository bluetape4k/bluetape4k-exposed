# Issue 282 Tink Persisted Keyset Lessons

Date: 2026-06-23
Issue: #282

## Lesson

Encrypted database column helpers must not generate their own process-local keysets. A convenient default key becomes a data-loss trap because stored ciphertext depends on key material that disappears across restart, redeploy, or another node.

## Guidance

- Require explicit `TinkAead` or `TinkDeterministicAead` at persisted column boundaries.
- Keep generated keysets visible in tests and examples only, or wrap them in deliberately ephemeral helper names.
- Preserve convenience only when it does not choose key material, such as a default ciphertext length overload.
- Regression tests should reconstruct encryptors from serialized keyset material and also prove a fresh generated keyset cannot read existing ciphertext.
- README examples for persisted encrypted columns should start from durable keyset loading, not from generated singleton factories.

## Follow-up

If bluetape4k-tink later exposes a first-class KMS-backed keyset loader, update these examples to prefer that loader over cleartext JSON snippets.
