# Issue #322 Exposed Migration Drift Plan Review

## Scope

- Artifact:
  `docs/superpowers/plans/2026-07-17-issue-322-exposed-migration-drift-plan.md`
- Gate: Type A implementation-plan review before implementation
- Perspectives: performance/cost, stability/reliability, security/privacy,
  operator/Ops, developer/API, user/caller, and main-session integration
- Implementation state: no test, workflow, README, or production-code change
  exists at this gate

## Convergence

The first plan review found gaps around exact Exposed experimental APIs,
whole-statement dialect grammar, task-owned JUnit evidence, helper-only test
isolation, generated-file collision safety, copy-pastable application DSL,
bilingual parity automation, stable-manual ownership, and diagnostic order.

The repaired plan now pins:

- exact JDBC/R2DBC imports, calls, transaction receivers, and helper filters;
- exact nullable additive `VARCHAR(255) NULL` acceptance with hostile tails
  rejected and no silent validator broadening;
- default-tag exclusion, dedicated live-only task inputs, cache behavior,
  test-worker environment, and private JUnit XML paths;
- fixed-filename collision preflight and ephemeral-CI fixture recreation;
- independent bounded H2 and sequential no-retry real-database evidence;
- allowlisted sanitized artifacts with Gradle/evidence failure precedence;
- equivalent application/contributor documentation, deterministic parity
  validation, and a separately owned 1.12 manual-promotion gate.

Every affected perspective was rerun after repair.

## Final Findings

| Perspective | P0 | P1 | P2 | P3 | Result |
|---|---:|---:|---:|---:|---|
| Performance/cost | 0 | 0 | 0 | 0 | READY |
| Stability/reliability | 0 | 0 | 0 | 0 | READY |
| Security/privacy | 0 | 0 | 0 | 0 | READY |
| Operator/Ops | 0 | 0 | 0 | 0 | READY |
| Developer/API | 0 | 0 | 0 | 0 | READY |
| User/caller | 0 | 0 | 0 | 0 | READY |
| Main-session integration | 0 | 0 | 0 | 0 | READY |

## Accepted Constraint

The two touched workflows retain the repository's current verified mutable
major Action tags. Full commit-SHA pinning is a repository-wide workflow
governance change outside issue #322. Read-only issue search found no matching
tracker. The implementation review must record `workflow governance` as owner
and recommend a separate follow-up without widening this PR.

## Acceptance Trace

- Deterministic plugin output: Tasks 5, 7, and 9
- Additive JDBC/R2DBC convergence: Tasks 2 and 3
- H2/PostgreSQL/MySQL 8 proof: Tasks 2, 3, 5, 6, and 8
- Default-build isolation and live-only cache behavior: Tasks 1 and 4
- Secret-safe failure evidence: Tasks 5, 6, and 8
- Bilingual guidance and stable-manual boundary: Tasks 7 and 8
- Exact-head PR and separate merge gate: Task 9

## Evidence

- Design spec and Type A checklist reconciled with the repaired plan
- Triggered-risk table maps each signal to prevention/proof and an owning task
- Developer/API rerun: P0=0, P1=0, P2=0, P3=0
- User/caller rerun: P0=0, P1=0, P2=0, P3=0
- Other plan lenses and main-session integration: all severities zero
- Validation: `git diff --check` and unfinished-marker scan before commit

## Verdict

**PASS: P0 = 0, P1 = 0.** The plan is implementation-ready and does not widen
the public API, dependency version, stable-manual, production migration, or
merge-authority boundary.
