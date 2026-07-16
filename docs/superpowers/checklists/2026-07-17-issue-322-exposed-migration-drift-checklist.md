# Issue #322 Type A Checklist

## Scope

- Repository: `bluetape4k/bluetape4k-exposed`
- Branch: `feat/issue-322-migration-drift`
- Base: `origin/develop@38d13d9`
- Approved outcome: complete Exposed 1.3.1 migration drift verification for
  JDBC/R2DBC across H2, PostgreSQL, and MySQL 8; keep the lane opt-in; document
  limitations; create a PR; hold merge for fresh exact-head approval.
- N/A: new module, public API, release, publish, dependency version change,
  catalog mutation, benchmark, cache, HTTP, security credential flow, and
  diagrams. The implementation changes test-only database fixtures, an
  existing workflow, and bilingual documentation.

## Type A Gates

- [x] **A-01 — Isolate and confirm requirements**
  - **Action:** Create an isolated feature worktree from current
    `origin/develop` and pin boundaries and side-effect authority.
  - **Evidence:** Worktree
    `.worktrees/feat-issue-322-migration-drift`, branch
    `feat/issue-322-migration-drift`, base `38d13d9`; PR creation approved;
    merge requires a later exact-head approval.
  - **Failure:** Stop before research or mutation if the worktree or authority
    boundary changes.

- [x] **A-02 — Ground the design in current evidence**
  - **Action:** Inspect existing plugin/demo/test/workflow surfaces, current
    Exposed 1.3.1 JAR APIs, official documentation, and upstream issues.
  - **Evidence:** Existing demo plugin configuration and workflow inspected;
    baseline H2 JDBC/R2DBC tests passed; default timestamp output reproduced;
    official migration documentation and Exposed #377/#2441 inspected.
  - **Failure:** Reopen research before changing the approved design.

- [x] **A-03 — Approve and review the design spec**
  - **Action:** Self-review the written design and run six independent review
    lenses plus main-session integration; obtain written-spec user approval.
  - **Evidence:** Design and spec-review artifacts; all six perspectives plus
    main integration at P0=0/P1=0; user response `승인`; Lore decision commit
    containing this checklist row.
  - **Failure:** Revise the spec and rerun affected lenses; stop planning.

- [ ] **A-04 — Approve and review the implementation plan**
  - **Action:** Write an ordered TDD plan with exact files, commands, hazards,
    rollback points, and complete spec traceability; run six plan lenses.
  - **Evidence:** Plan path, P0=0/P1=0 review result, and committed spec/plan.
  - **Failure:** Repair missing order, proof, ownership, or acceptance mapping
    before implementation.

- [ ] **A-05 — Predict triggered risks**
  - **Action:** Record Testcontainers lifecycle, dialect variance, generated SQL
    safety, workflow duration, and nondeterministic filename risks with signals
    and rollback points.
  - **Evidence:** Risk entries attached to implementation tasks.
  - **Failure:** Stop implementation until each risk has a proving command or
    explicit containment.

- [ ] **A-06 — Implement with test-first proof**
  - **Action:** Observe RED then GREEN for JDBC and R2DBC migration drift
    behavior before workflow or documentation claims are finalized.
  - **Evidence:** Fresh failing and passing focused test commands, scoped diff,
    diagnostics, and integrated status.
  - **Failure:** Return to the failing behavior; do not weaken assertions.

- [ ] **A-07 — Verify tests, spec, plan, and repository hazards**
  - **Action:** Run H2 then sequential PostgreSQL/MySQL 8 tests, deterministic
    generation, README parity/link validation, actionlint, Detekt, and diff
    checks; verify
    exact spec/plan acceptance.
  - **Evidence:** Fresh command results and verifier PASS.
  - **Failure:** Return to implementation or reopen the approved artifact.

- [ ] **A-08 — Converge the final pre-PR review**
  - **Action:** Run Kotlin/document/workflow checklists and six implemented-diff
    review lenses plus integration.
  - **Evidence:** Current diff, review artifact, P0=0/P1=0, and refreshed tests.
  - **Failure:** Keep PR creation blocked until repaired.

- [ ] **A-09 — Commit durable learning**
  - **Action:** Commit a lesson covering fixed filenames and the build-time
    plugin versus programmatic migration API boundary.
  - **Evidence:** Tracked lesson and Lore-compliant commit.
  - **Failure:** PR creation remains blocked.

- [ ] **A-10 — Deliver PR through live CI and review**
  - **Action:** Push the exact approved head, create the PR against `develop`,
    mirror issue metadata, rerun review, and wait for required checks.
  - **Evidence:** Live PR metadata/body/head, CI conclusions, reviews, and
    unresolved-thread count.
  - **Failure:** Keep delivery pending or return to diagnosis/fix.

- [ ] **A-11 — Capture knowledge and report merge readiness**
  - **Action:** Preserve research, update knowledge indexes, and render the
    complete Type A DoD tied to the live PR head.
  - **Evidence:** Wiki/index validation, reconciled checklist counts, exact
    PR/head/CI/review state, and CG-16 pending.
  - **Failure:** Do not request merge approval until evidence reconciles.

- [ ] **A-12 — Close out after fresh merge approval**
  - **Action:** After a fresh exact-head approval, merge, verify the merge SHA,
    sync `develop`, and clean integrated branch/worktree state.
  - **Evidence:** User approval, live merged PR, merge SHA, clean synced local
    state, and cleanup result.
  - **Failure:** Preserve state and report the pending or failed closeout row.

## Conditional Kotlin and Documentation Gates

- [ ] **KT-01 — Load triggered Kotlin guidance**
  - **Action:** Apply Exposed and Kotlin testing guidance to the new JDBC/R2DBC
    tests.
  - **Evidence:** Trigger-to-reference map in the plan and final review.
  - **Failure:** Block Kotlin implementation or review.

- [ ] **KT-02 — Inspect impact and reuse**
  - **Action:** Reuse existing database selectors, transactions,
    Testcontainers launchers, and assertions instead of adding infrastructure.
  - **Evidence:** Exact fixture anchors and raw-fallback rationale.
  - **Failure:** Remove duplicate or unsafe infrastructure.

- [ ] **KT-03 — Enforce Kotlin and Exposed contracts**
  - **Action:** Verify transaction type, cleanup, dialect-neutral assertions,
    coroutine behavior, and no public API impact.
  - **Evidence:** Current-file review with P0=0/P1=0.
  - **Failure:** Repair before validation.

- [ ] **KT-04 — Prove behavior with Kotlin validation**
  - **Action:** Run diagnostics, targeted compiles/tests, sequential container
    tests, and diff checks.
  - **Evidence:** Fresh commands and results.
  - **Failure:** Kotlin verdict remains pending.

- [ ] **KT-05 — Render the final Kotlin checklist**
  - **Action:** Complete the Kotlin and testing reference rows with counts.
  - **Evidence:** Checklist totals and zero blockers.
  - **Failure:** Expose the unchecked row instead of claiming completion.

- [ ] **DOC-01 — Preserve bilingual current-documentation parity**
  - **Action:** Add and validate equivalent English and Korean README migration
    sections. Keep the stable 1.11 manual and manifest unchanged until 1.12
    release closeout can pin an exact ref and commit.
  - **Evidence:** README heading/command/warning/matrix/link parity review and
    proof that manual metadata did not change.
  - **Failure:** PR readiness remains blocked on missing or drifted locale.

- [ ] **CI-01 — Validate the dedicated workflow extension**
  - **Action:** Keep independent fast H2 checks path-scoped, exclude tagged
    drift tests from retried bulk tests, run real DB drift checks in one
    scheduled/manual no-retry sequential job, preserve per-dialect artifacts,
    verify the exact smoke/full event conditions, and validate YAML with
    `actionlint`.
  - **Evidence:** Workflow diff, syntax result, and event-condition review.
  - **Failure:** Revert or repair workflow changes before PR.

## Checklist Contract Repair

The design spec was created before this checklist file. This is a CL-01
ordering miss. The repair is to instantiate the checklist now and rerun every
dependent proof from spec self-review onward. No implementation, plan, commit,
push, PR, merge, or external database mutation occurred before the repair.
