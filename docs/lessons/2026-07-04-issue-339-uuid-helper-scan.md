# Issue 339 UUID Helper Scan

## What Changed

Direct `UUID.randomUUID()` calls in production code and tests were replaced with bluetape4k ecosystem helpers:

- UUID-valued identifiers now use `Uuid.V7.nextId()`.
- String-only owner ids, database names, thread names, and cache suffixes now use `Base58.randomString(8)`.
- The exposed benchmark keeps `UUID.randomUUID()` because UUID generation is part of the measured comparison.

## What To Repeat

- Classify random values by target type before editing:
  - `UUID` target -> `Uuid.V7.nextId()`.
  - unique string/name/suffix target -> `Base58.randomString(8)`.
- Run the final grep after edits and document intentional exceptions.
- For cache repositories, use one serial Gradle invocation for Testcontainers-backed modules.

## Evidence

- Direct call scan leaves only the benchmark exception.
- Touched module `compileTestKotlin` passed.
- Targeted non-cache tests passed with 355 passing and 7 pending.
- Cache module serial tests passed, including Redis-backed Redisson modules.
