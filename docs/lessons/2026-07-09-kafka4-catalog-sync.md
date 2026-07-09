# Kafka4 catalog sync

## Context

`bluetape4k-dependencies` moved the shared `kafka4` compatibility line from
`4.3.1` to `4.2.1` because Spring Kafka 4.1 embedded test infrastructure
requires the Kafka 4.2 test ABI.

## Decision

Keep the local managed catalog aligned with `bluetape4k-dependencies` instead
of keeping the newer Kafka client patch version locally.

## Outcome

`kafka4` now resolves to `4.2.1`; `spring-kafka4` remains `4.1.0`.

## Future rule

After changing a shared alias in `bluetape4k-dependencies`, run the downstream
sync checker and update every managed library repo that still carries the old
alias value.
