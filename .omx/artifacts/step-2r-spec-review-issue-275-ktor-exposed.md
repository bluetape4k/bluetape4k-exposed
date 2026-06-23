# Step 2-R Spec Review - Issue #275 Ktor Exposed

Date: 2026-06-23
Spec: `docs/superpowers/specs/2026-06-23-issue-275-ktor-exposed-design.md`

## Scope

Reviewed the Ktor Exposed integration spec for module boundary, Ktor core
composition, JDBC/R2DBC transaction helpers, readiness/security semantics,
metrics, CI/Nightly wiring, BOM/publish/catalog readiness, and user-facing docs.

## Tier Results

| Tier | Perspective | Final P0 | Final P1 | Final Verdict |
|---|---:|---:|---:|---|
| 1 | Performance | 0 | 0 | PASS |
| 2 | Stability | 0 | 0 | PASS |
| 3 | Security | 0 | 0 | PASS |
| 4 | Operator/Ops | 0 | 0 | PASS |
| 5 | Developer/API | 0 | 0 | PASS |
| 6 | User/Caller | 0 | 0 | PASS |
| Main | Integration | 0 | 0 | PASS |

## P1 Fixes Applied

- Added explicit Ktor core composition boundary: this module uses Ktor core DTOs
  and helpers but does not auto-install Ktor core.
- Made Exposed StatusPages auto-install default `false` and documented the
  combined `install(StatusPages)` composition path.
- Added explicit `jdbcBlockingContext` for JDBC route and readiness execution.
- Added `jdbcQueryTimeout` and separated internal readiness timeout from
  external request cancellation.
- Locked readiness response semantics to `HealthResponse` with allowlisted
  keys and values.
- Added SQL/Exposed/R2DBC error redaction requirements and secret-bearing tests.
- Added lifecycle non-ownership, rollback/reuse, test isolation, concurrency,
  and metrics cardinality requirements.
- Expanded CI/Nightly, BOM/publish, shared catalog, README/runbook, and
  source-backed docs verification requirements.

## Remaining Nonblocking Items

- Nightly wiring exact job placement remains a plan-level detail, but the spec
  now requires Ktor module/example needs, artifacts, and status coverage.
- First implementation does not require JMH/benchmark; lightweight regression
  checks cover hidden pool/dispatcher/registry/global install risks.
- Tier 6 user/caller review raised nonblocking rollback/no-op/default/non-goal
  clarity items. These were applied to the spec after the P0/P1 PASS verdict.

## Integration Verdict

Step 2-R closed with P0 = 0 and P1 = 0.
