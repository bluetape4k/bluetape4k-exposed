# Issue #322 Exposed Migration Drift Design Review

## Scope

- Artifact:
  `docs/superpowers/specs/2026-07-17-issue-322-exposed-migration-drift-design.md`
- Review gate: Type A design review
- Perspectives: performance, stability/reliability, security/privacy,
  operator/Ops, developer/API, user/caller, and main-session integration
- Research basis: current repository plugin/demo/test/workflow surfaces,
  Exposed 1.3.1 migration documentation and cached APIs, upstream Exposed
  issues #377 and #2441, and the stable-manual 1.11.0 pin
- Implementation state: no production code, test code, workflow, or README
  change exists yet

## Convergence

The first review round found material gaps in retry isolation, sequential
Testcontainers execution, SQL validation, cleanup failure preservation,
application-user safety, and stable-manual ownership. A second round separated
tagged drift tests from retried bulk jobs, pinned the additive fixture and
validator, moved 1.12-only guidance to the bilingual READMEs, and defined
dedicated H2 and real-database evidence.

Later affected-lane reviews closed deterministic untracked-file detection,
intrinsic task non-cacheability, bounded step timeouts, artifact assembly,
pipeline exit-code capture, untrusted-PR permissions, and raw-log privacy.
Every affected perspective was rerun after its repair.

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

## Locked Decisions

- Keep the existing fixed-file Gradle plugin demo smoke and add
  programmatic/test-time JDBC and R2DBC drift regressions.
- Use plain tables with one nullable additive column; validate exactly one
  whole `ALTER TABLE ... ADD [COLUMN]` statement before executing synthetic
  fixture SQL.
- Tag drift tests, exclude them from default retried test tasks, and expose
  intrinsically live-only `migrationDriftTest` tasks.
- Keep pull-request proof on H2 and run PostgreSQL/MySQL 8 in one no-retry,
  sequential full-Nightly lane with bounded steps and per-selection evidence.
- Preserve untrusted-PR read-only permissions and upload only sanitized,
  allowlisted command summaries plus status and test reports.
- Document current 1.12 behavior in `README.md` and `README.ko.md`; do not edit
  the stable manual pinned to 1.11.0.
- Treat fixed V1 files as replaceable repository fixtures only. Applications
  never overwrite applied migrations and retain migration-runner ownership.

## Accepted Constraints

- Existing real-database selectors also execute H2. The small duplicate H2
  cost is accepted instead of widening selector semantics solely for this
  issue, and CI timeout budgets account for it.
- PostgreSQL/MySQL type-change detection remains documented but ungated while
  Exposed 1.3.1 limitations and upstream issue #2441 remain.
- An empty comparison result means only that this API/version detected no
  difference; it is not a schema-equality guarantee.

## Evidence

- Design validation: `git diff --check`
- Unfinished-marker scan: no unresolved marker remains in the
  design/checklist artifacts
- User approval: `승인`, received 2026-07-17 after the written design handoff
- Open user decisions: none

## Verdict

**PASS: P0 = 0, P1 = 0.** The user-approved design is ready for a separate,
reviewed implementation plan. No implementation may widen the stable-manual,
public-API, dependency-version, or production-migration boundary.

## Plan-Driven Design Addendum

The implementation-plan review tightened the approved design without changing
its outcome or scope. It pinned plugin-specific `--rerun`, exact experimental
API imports/calls, the complete additive `VARCHAR(255) NULL` grammar,
failure-preserving cleanup, task-owned JUnit XML paths/privacy, exact helper
filters, environment/cache inputs, per-step CI failure precedence, deterministic
README parity validation, and a separately owned 1.12 manual-promotion gate.

The affected developer/API, user/caller, security/privacy,
stability/reliability, performance/cost, and operator/Ops lenses were rerun.
All remain **PASS: P0 = 0, P1 = 0**, with no residual P2/P3 finding. The
written-spec approval still applies because these refinements narrow proof and
safety contracts rather than changing the user-visible direction.
