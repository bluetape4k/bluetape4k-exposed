# Issue #252: ClickHouse snapshot retry backoff

## Context

After the general exposed retry hardening, exposed Nightly full still failed in
`Test / exposed-clickhouse`. The repeated failure was Central snapshot metadata
HTTP 403 for `bluetape4k-logging`, while local HEAD and GET checks returned
200.

## Decision

Keep the broader exposed retry policy unchanged, but extend the ClickHouse gate
to eight attempts with a 60 second delay and a 35 minute timeout. This keeps the
extra wait isolated to the job that repeatedly hit the longer Central edge
failure.

## Outcome

The ClickHouse test gate can now absorb a longer Central snapshot metadata
outage without widening every exposed Nightly job.

## Verification

Run `git diff --check` and `actionlint` before PR creation. Rerun exposed
Nightly full before continuing to downstream repositories.

## Future guidance

If Central 403 appears only in one Testcontainers-backed job after general
retry hardening, avoid broad workflow churn. Widen that job first, then rerun the
dependency-ordered Nightly chain.
