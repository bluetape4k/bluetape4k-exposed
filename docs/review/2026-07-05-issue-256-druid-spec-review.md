# Issue #256 Druid JDBC Spec Review

Date: 2026-07-05
Scope: `docs/superpowers/specs/2026-07-05-issue-256-druid-jdbc-design.md`
Gate: Step 2-R spec review

## Review Inputs

- Live GitHub issue `bluetape4k/bluetape4k-exposed#256`
- Parent research `docs/superpowers/research/2026-06-06-issue-227-olap-local-testability.md`
- Preserved wiki note `bluetape4k-wiki/research/2026-07-05-apache-druid-jdbc-query-only.md`
- Official Apache Druid JDBC, metadata, Docker, and local quickstart docs
- Existing `exposed-trino`, `exposed-duckdb`, and `exposed-clickhouse` module patterns

## Gate Verdict

- P0=0
- P1=0
- P2=1
- P3=0
- Gate: PASS

## Findings

| Finding | Severity | Resolution |
|---|---:|---|
| Official Druid quickstart is memory-heavy and no local Druid service is currently reachable on `localhost:8888`; automatic CI container startup would be unreliable without a dedicated fixture recipe. | P2 | Spec limits the module to query/metadata APIs, adds an env-gated smoke test, documents the manual/local-container command, and keeps broad DDL/DML/repository APIs out of scope. |

## 7-Tier Review

| Tier | Result | Evidence |
|---|---|---|
| Security | PASS | No credentials required by default; auth is passed through `Properties`; no secrets are committed. |
| Ops/SRE reliability | PASS | Router/Broker stickiness and `transparent_reconnection` are explicit; heavy container startup is not hidden in default CI. |
| Structural impact | PASS | New module follows `exposed/*` auto-discovery and requires README/AGENTS/CI/Nightly registration. |
| Kotlin/API quality | PASS | Spec requires query-only helpers and rejects broad Exposed dialect parity. |
| Tests/types/silent failure | PASS | Unit tests plus env-gated fixture smoke test are required; local Druid absence is recorded as a P2 evidence gap, not ignored. |
| Performance/stability | PASS | No performance claims; blocking JDBC work must use `Dispatchers.IO` in suspend API. |
| Documentation/release readiness | PASS | README locale set and public out-of-scope section are required. |

## Step 2-R Checklist Completion Report

| Item | Status | Notes |
|---|---|---|
| Reviewed scope recorded | Done | Spec path and evidence inputs listed. |
| P0/P1 normalized | Done | No P0/P1 findings. |
| Non-blocking findings recorded | Done | P2 smoke environment gap recorded with mitigation. |
| Next step unblocked | Done | Plan may proceed with query-only implementation. |
