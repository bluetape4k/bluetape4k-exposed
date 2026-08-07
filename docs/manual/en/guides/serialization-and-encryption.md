---
manualId: serialization-and-encryption
title: Serialization, encryption, and typed columns
locale: en
releaseRef: 1.12.1
---

# Serialization, encryption, and typed columns

These modules all transform a Kotlin value at the Exposed column boundary, but they solve different problems. JSON codecs preserve a document shape, Tink protects a value with authenticated encryption, and measured columns normalize a physical value to one numeric base unit. Selecting one does not remove the lifecycle work around it.

## Choose by the stored contract

| Requirement | Module | Database representation | Compatibility owner |
| --- | --- | --- | --- |
| Jackson 2 JSON ecosystem | `bluetape4k-exposed-jackson2` | JSON/JSONB | Application JSON model and Jackson 2 mapper |
| Jackson 3 JSON ecosystem | `bluetape4k-exposed-jackson3` | JSON/JSONB | Application JSON model and Jackson 3 mapper |
| Fastjson2 JSON ecosystem | `bluetape4k-exposed-fastjson2` | JSON/JSONB | Application JSON model and Fastjson2 serializer |
| Confidential field, no equality lookup | `bluetape4k-exposed-tink` AEAD | Encoded/binary ciphertext | Application/KMS keyset and AAD policy |
| Confidential field with equality lookup | `bluetape4k-exposed-tink` DAEAD | Repeatable ciphertext | Application/KMS plus accepted pattern leakage |
| Type-safe physical measurement | `bluetape4k-exposed-measured` | Base-unit `DOUBLE` | Schema owner and unit/precision policy |

The first question is not which API looks shortest. Ask what must remain readable after a deploy, restart, rename, dependency upgrade, or rollback.

## JSON is a persisted API

`jackson`, `jacksonb`, `fastjson`, and `fastjsonb` serialize Kotlin values, but the database records neither the Kotlin class nor serializer settings. Property names, subtype ids, date/time formatting, numeric coercion, defaults, and unknown-field behavior form the real stored contract.

For additive evolution, give new fields a default and test old payloads. For an incompatible change:

1. Save representative old rows as test fixtures.
2. Deploy a reader that accepts old and new shapes while the writer keeps the old shape.
3. Change the writer and observe decode failures.
4. Backfill old rows in bounded, resumable batches.
5. Remove compatibility code only after coverage and rollback requirements are satisfied.

JSONB changes how the database stores and queries a document. It does not version the document or make indexes automatic. Validate `contains`, `exists`, and `extract` against every production dialect and review the generated query plan.

## Jackson 2 to Jackson 3 is a migration

The two modules expose similar Exposed DSL, but their package and mapper ecosystems differ. Jackson 2 uses `io.bluetape4k.exposed.core.jackson` and `com.fasterxml.jackson` types; the 1.11 Jackson 3 module uses `io.bluetape4k.exposed.core.jackson3` and Jackson 3 types. Similar function names do not make tree nodes, annotations, modules, or serializer configuration interchangeable.

Prove compatibility in three layers:

- source: application imports and custom modules compile with Jackson 3;
- stored data: the Jackson 3 reader decodes every supported Jackson 2 fixture;
- operations: dual-reader rollout, backfill, metrics, and rollback are rehearsed.

If the stored JSON does not need to change, keep its serialized shape stable during the library migration. Separate the dependency migration from a document redesign.

## Tink column mapping is not key management

The Tink module accepts an already constructed `TinkAead` or `TinkDeterministicAead`. The application must load a durable keyset from KMS or protected secret storage. A process-local key generated at startup cannot decrypt rows after restart and cannot be shared safely across nodes.

Randomized AEAD is the default security choice when lookup is unnecessary. Deterministic AEAD makes equality search possible because the same plaintext and associated data produce the same ciphertext, but this exposes repeated-value patterns. It still does not support range, prefix, ordering, or substring search.

By default, table DSL functions bind ciphertext to `bluetape4k-exposed-tink:v1:<table>:<column>`. That prevents ciphertext copied to another column from decrypting, but it makes a table or column rename a cryptographic data migration. Keep old associated data readable during the transition, rewrite rows with the new binding, and verify completion before removing compatibility.

For key rotation, retain old decryption keys while the new primary key writes ciphertext, backfill rows, verify counts and failure metrics, then retire old keys according to policy. Store key/version metadata when the rotation design needs deterministic routing.

## Measurements store a number, not its unit

Measured columns convert inputs to a declared base unit and store `DOUBLE`. `length` stores metres, `energy` stores joules, `temperature` stores Kelvin, and the other convenience DSL functions have similarly fixed bases. The original input unit is not recoverable.

A base-unit change without rewriting data silently changes meaning. Treat it as a data migration and make the unit visible in column names, migration notes, exports, dashboards, and alerts. Because storage uses binary floating point, define tolerances and reject `NaN`, infinity, or impossible domain values at the application boundary when they are invalid.

## Verification matrix

| Change | Required proof |
| --- | --- |
| JSON field added | Old fixture reads with documented default; new fixture round-trips |
| JSON field/type/subtype changed | Dual reader, backfill, metrics, rollback |
| Jackson 2 → 3 | Compile/import check plus cross-version stored fixtures |
| JSON → JSONB | DDL/data conversion, predicates, indexes, query plans per dialect |
| Tink key rotation | Restart/multi-node key availability, old decrypt/new encrypt, backfill coverage |
| Table/column rename with Tink | Old and new AAD compatibility plus re-encryption proof |
| Measured base unit changed | Numeric backfill and raw-value assertions |
| Numeric precision policy changed | Boundary/tolerance tests and migration of affected values |

Never log document bodies, plaintext, ciphertext, or key material to diagnose a failed transform. Log the table, column, record id, codec/key version when safe, and exception category.

## Learning path

1. Select the stored contract in this guide.
2. Read the matching module page: [Jackson 2](../modules/bluetape4k-exposed-jackson2.md), [Jackson 3](../modules/bluetape4k-exposed-jackson3.md), [Fastjson2](../modules/bluetape4k-exposed-fastjson2.md), [Tink](../modules/bluetape4k-exposed-tink.md), or [measured](../modules/bluetape4k-exposed-measured.md).
3. Run the module tests and replace sample values with production-shaped fixtures.
4. Place the transformation inside the transaction model described by [transaction boundaries](./transaction-boundaries.md).
5. Write migration and rollback evidence before changing an established column contract.

## Sources

- [Jackson 2 column](../../../../exposed/jackson2/src/main/kotlin/io/bluetape4k/exposed/core/jackson/JacksonColumnType.kt)
- [Jackson 3 column](../../../../exposed/jackson3/src/main/kotlin/io/bluetape4k/exposed/core/jackson3/JacksonColumnType.kt)
- [Fastjson2 column](../../../../exposed/fastjson2/src/main/kotlin/io/bluetape4k/exposed/core/fastjson2/FastjsonColumnType.kt)
- [Tink table DSL](../../../../exposed/tink/src/main/kotlin/io/bluetape4k/exposed/core/tink/Tables.kt)
- [Tink associated data](../../../../exposed/tink/src/main/kotlin/io/bluetape4k/exposed/core/tink/TinkColumnAssociatedDataProvider.kt)
- [Measured columns](../../../../exposed/measured/src/main/kotlin/io/bluetape4k/exposed/core/measured/MeasuredColumnTypes.kt)
