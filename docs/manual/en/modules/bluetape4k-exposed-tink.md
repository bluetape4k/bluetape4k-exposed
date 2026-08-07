---
manualId: "bluetape4k-exposed-tink"
id: "bluetape4k-exposed-tink"
title: "Exposed Tink Encryption"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-tink"
sourceDir: "exposed/tink"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-tink
---

# Exposed Tink Encryption

> Library module

## Problem {#problem}

This module encrypts Exposed `VARCHAR`, binary, and BLOB values at the column boundary with Google Tink AEAD or Deterministic AEAD. It maps plaintext application values to ciphertext columns; it does not provision, store, rotate, or recover keysets.

## When to use it {#when-to-use}

Use randomized AEAD for sensitive values that are read by id and do not need equality lookup. Use Deterministic AEAD only when equality search is required and the pattern-leakage trade-off is accepted. Hashing or tokenization may be a better search design for some identifiers.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-tink")
}
```

## Core concepts {#concepts}

- AEAD produces different ciphertext for the same plaintext; equality predicates and ordinary indexes cannot match it.
- Deterministic AEAD produces the same ciphertext for the same plaintext and associated data, enabling equality lookup while revealing repetition.
- `VARCHAR` stores encoded ciphertext; binary and BLOB variants store bytes.
- Default associated data binds ciphertext to `bluetape4k-exposed-tink:v1:<table>:<column>`.
- Keyset lifecycle belongs to application secret/KMS infrastructure, separate from column mapping.

## Quick start {#quick-start}

```kotlin
val aead = TinkAead(loadAeadKeysetFromKms())
val daead = TinkDeterministicAead(loadDaeadKeysetFromKms())

object Customers : LongIdTable("customers") {
    val note = tinkAeadVarChar("note", 1024, aead)
    val email = tinkDaeadVarChar("email", 512, daead).index()
}

transaction {
    Customers.insert { row ->
        row[note] = "private note"
        row[email] = "ada@example.com"
    }
}
```

## API by task {#api-by-task}

| Task | Stable 1.11 API |
| --- | --- |
| Randomized text/bytes/blob | `tinkAeadVarChar`, `tinkAeadBinary`, `tinkAeadBlob` |
| Searchable deterministic text/bytes/blob | `tinkDaeadVarChar`, `tinkDaeadBinary`, `tinkDaeadBlob` |
| Associated-data domain | `TinkColumnAssociatedDataProvider`, `TableAndColumn`, `Empty` |
| Low-level mapping | `Tink*Aead*ColumnType` and transformers; prefer table DSL |

## Recommended patterns {#patterns}

Load durable keysets from KMS or protected secret storage before defining tables. Prefer table DSL functions so associated data is bound consistently. Record a key id/version outside the ciphertext when migration needs it. Rotate by deploying a reader that can decrypt old data, rewriting rows with the new primary key, and retiring old key material only after coverage is proven.

## Integrations {#integrations}

The module uses `bluetape4k-tink` primitives and Exposed `ColumnWithTransform`. Encryption happens when Exposed converts the application value to the database representation; decryption happens while reading the column. The database never receives the plaintext for ordinary transformed writes.

## Configuration {#configuration}

Choose AEAD versus DAEAD, ciphertext length, keyset source, rotation policy, and associated-data provider. The default provider includes stable table and column names. Renaming either changes associated data, so existing ciphertext must be migrated/re-encrypted or read with a compatibility provider during migration.

## Failure modes {#failures}

- A missing, regenerated, or wrong keyset makes stored rows undecryptable.
- Changed associated data, including a table/column rename, causes authentication failure.
- AEAD equality queries do not match because every encryption uses a new nonce.
- DAEAD reveals repeated plaintext patterns and equality frequency.
- A ciphertext longer than the declared `VARCHAR`/binary capacity is truncated or rejected by the database.
- Blank names and non-positive lengths fail validation in the table DSL.

## Operations {#operations}

Back up keysets separately from the database and test recovery together. Audit key access and rotation without logging plaintext, ciphertext, or key material. Alert on decrypt/authentication failures. Plan rename and key rotation as data migrations with resumable backfill and rollback.

## Testing {#testing}

Test round trips, wrong-key failure, wrong-associated-data failure, tampered ciphertext, nullability, maximum payload size, and restart/node sharing with a persisted keyset. For DAEAD, prove equality lookup; for AEAD, prove repeated plaintext produces different ciphertext and is not queried by equality.

```bash
./gradlew :bluetape4k-exposed-tink:test
```

## Workshops and learning path {#workshops}

Read the [serialization and encryption guide](../guides/serialization-and-encryption.md) first, then inspect `TinkTableTest` for DSL validation and associated-data behavior, `TinkColumnTypeTest` for AEAD, and `TinkDaeadColumnTypeTest` for deterministic lookup behavior.

## Limitations {#limitations}

This is field encryption, not a key-management system, authorization layer, searchable-encryption scheme, or database-wide encryption replacement. It supports equality only through deterministic encryption; range, prefix, ordering, and substring queries are not preserved. Key loss is not recoverable by this module.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### Tink encrypted column boundary diagram

[![Tink encrypted column boundary diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-tink-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-tink-diagram-01.svg)

_Release README: [`exposed/tink/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/tink/README.md)_

### AEAD and DAEAD behavior flow diagram

[![AEAD and DAEAD behavior flow diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-tink-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-tink-diagram-02.svg)

_Release README: [`exposed/tink/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/tink/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Gradle build file](../../../../exposed/tink/build.gradle.kts)
- [Table DSL](../../../../exposed/tink/src/main/kotlin/io/bluetape4k/exposed/core/tink/Tables.kt)
- [Associated-data provider](../../../../exposed/tink/src/main/kotlin/io/bluetape4k/exposed/core/tink/TinkColumnAssociatedDataProvider.kt)
- [AEAD tests](../../../../exposed/tink/src/test/kotlin/io/bluetape4k/exposed/core/tink/TinkColumnTypeTest.kt)
- [DAEAD tests](../../../../exposed/tink/src/test/kotlin/io/bluetape4k/exposed/core/tink/TinkDaeadColumnTypeTest.kt)
