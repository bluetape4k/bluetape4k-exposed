# Issue #323 Transaction-Aware Domain Event Publisher Plan Review

- Date: 2026-07-11
- Status: PASS
- Gate: Step 3-R implementation-plan review
- Final severity: P0 = 0, P1 = 0, P2 = 0, P3 = 0
- Source implementation started: no

## Reviewed Basis

| Artifact | Blob |
|---|---|
| Approved design spec | `94ff1250d3431e0051049d273ff846ce64d7a229` |
| Final implementation plan | `72c99fa1f661274e491f44bc82201d9c6e0c304b` |
| Approved spec commit | `c665505910c4b41aa9d27d0262d3c02c97dce19c` |

The plan/review commit SHA is recorded in the execution log after commit. A tracked file cannot contain the SHA of the commit that contains itself.

## Convergence History

| Wave | Result | Decisive repairs |
|---|---|---|
| Initial six-lens review | Rerun required | Split minimal GREEN from hardening, required real refreshed Spring contexts, strengthened fail-closed behavior, structured logging, runbook, multi-manager proof, documentation parity, and CI/Kover evidence. |
| Second six-lens review | Rerun required | Added exact TDD boundaries, context cleanup, root and non-additive logger capture, per-file publication-store checks, reviewed-basis verification, live PR/CI gates, and explicit report-only Kover scope. |
| Third review wave | Performance, security, and operations PASS; stability, developer/API, and user/docs required repair | Added the missing MDC import, corrected Common Gates semantics, made `transactionManagerRef` executable, strengthened RED/GREEN ordering, exact coverage paths, exact commit/blob checks, and source-to-README verification. |
| Fourth affected-lens wave | Stability PASS; developer/API and user/docs required repair | Replaced `save()` as manager-selection evidence with proxy `count()`/`deleteAll()`, separated behavioral manager RED from auto-configuration compile RED, handled clean-new `git diff --no-index` status, and added per-file/ordered documentation checks. |
| Fifth affected-lens wave | Stability and developer/API PASS; user/docs P2 remained | Required each JDBC locale to embed the lifecycle PNG independently. |
| Final user/docs rerun | PASS | Confirmed per-file diagram embeds and no remaining P2/P3. |

## Resolved Blocking Findings

- Preserved the required TDD sequence: manager behavioral RED and GREEN complete before the separate publisher auto-configuration compile RED.
- Repaired the existing public `transactionManagerRef` contract by planning Spring Data factory-property forwarding and removal of hard-coded base-repository manager qualifiers.
- Required proxy-owned `count()` and `deleteAll()` against distinguishable stores; `save()` is explicitly rejected as manager-selection evidence.
- Restored `CG` to Common Gates `CG-01..17`, with CodeGraph availability recorded separately, and declared `WF`, `CL`, `A`, `KT`, `KT-TEST`, and `KT-SPR` applicability.
- Added the missing `org.slf4j.MDC` import and deterministic transaction/MDC/database cleanup.
- Made untracked-file whitespace checks executable under zsh by accepting clean-new status `1`, rejecting other statuses, checking empty diagnostic output, and avoiding the read-only `status` variable.
- Required fresh application contexts for real commit/rollback listener behavior, exact `Throwable` identity, transaction-local synchronization state, and `REQUIRES_NEW` isolation.

## Resolved Non-Blocking Findings

- The compiled multi-manager source region is compared mechanically with each JDBC README locale, not only between READMEs.
- README contract tokens, publication-store controls, and lifecycle image embeds are checked per file.
- Reconciliation state/action mappings and rollout failure order use stable semantic markers with exact ordered comparison.
- The locale-link loop is pipe-delimited and parses under both bash and zsh.
- Encryption at rest and in transit are verified separately.
- PR coverage checks require the exact non-empty core, JDBC, Spring Modulith, and DDD example `report.xml` paths.

## Scope Decisions

- The repository-wide Kover workflow remains report-only, as required by workspace policy. Issue #323 adds local and PR evidence that the four affected XML reports are non-empty; it does not introduce a hard coverage threshold.
- R2DBC, savepoint callback support, durable outbox semantics, retry configuration, and manager/DataSource identity claims remain outside issue #323.
- One JDBC lifecycle sequence diagram is required because commit, rollback, immediate handoff, default `AFTER_COMMIT`, and committed cleanup are difficult to communicate reliably in prose. The plan requires matching SVG/PNG assets, full-size inspection, and all diagram audits.

## Final Lens Table

| Lens | P0 | P1 | P2 | P3 | Verdict |
|---|---:|---:|---:|---:|---|
| Performance | 0 | 0 | 0 | 0 | PASS |
| Stability | 0 | 0 | 0 | 0 | PASS |
| Security | 0 | 0 | 0 | 0 | PASS |
| Operations | 0 | 0 | 0 | 0 | PASS |
| Developer/API | 0 | 0 | 0 | 0 | PASS |
| User/docs | 0 | 0 | 0 | 0 | PASS |
| Main-session integration | 0 | 0 | 0 | 0 | PASS |

Performance, security, and operations passed before the final affected-lens reruns. Later changes were limited to manager test executability, shell evidence, and stricter documentation validation; they did not alter the hot-path, fail-closed, or operator contracts those lanes approved. Stability and developer/API were rerun after the manager/TDD changes. User/docs was rerun after the final per-file diagram check.

## Verification Evidence

- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`: PASS
- Required CI/Nightly job structure check for core, Spring Boot, Spring Modulith, examples, coverage, and status jobs: PASS
- Plan clean-new whitespace check with explicit `git diff --no-index` status handling: PASS
- Task 7 validation block parsed by both `bash -n` and `zsh -n`: PASS
- Plan code fences: 104, balanced
- Plan placeholder markers: none
- Approved spec committed blob equals working-tree blob: PASS

## Gate Decision

Step 3-R is PASS. Implementation remains blocked until the user approves the reviewed implementation plan. No source file, workflow, README, or diagram implementation was changed during this planning gate.
