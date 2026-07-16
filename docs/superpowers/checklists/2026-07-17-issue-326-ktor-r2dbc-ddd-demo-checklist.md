# Issue #326 Ktor R2DBC Cache and DDD Demo Delivery Checklist

Scope: expand `examples/ktor-exposed-demo` with an Order Confirmation scenario
using PostgreSQL R2DBC, a Caffeine-backed repository, Spring-neutral domain
events, bilingual documentation, and Architecture/Sequence Diagram assets.

Machine-readable run: `20260716T161309Z-91be30b9` under the workspace
`.bluetape` state root. Target branch:
`feat/issue-326-ktor-r2dbc-ddd-demo` from `origin/develop`.

## Router

- [x] **WF-01 — Classify**
  - **Action:** Perform read-only discovery and select Type A/B/C/D/E/P/F.
  - **Evidence:** Type A Full Feature; the change spans Ktor routes, PostgreSQL
    R2DBC resources, a cache-backed repository, DDD events, tests, bilingual
    docs, and two diagrams. No production library API or new module is planned.
  - **Failure:** Stop; do not plan an execution lane from an ambiguous type.
- [x] **WF-02 — Write the first concrete plan**
  - **Action:** Give every step an `Action` and `Expected DoD`.
  - **Evidence:** The active thread presented discovery, design, plan,
    implementation, verification, PR, and merge-gate phases with explicit
    diagram and example-scenario outcomes.
  - **Failure:** Stop before mutation or durable artifacts.
- [x] **WF-03 — Obtain first-plan approval**
  - **Action:** Wait for explicit approval of the first concrete plan.
  - **Evidence:** The user approved the Type A path, selected Order
    Confirmation, selected Architecture B, and approved the PostgreSQL variant.
  - **Failure:** Remain read-only.
- [x] **WF-04 — Load execution contracts**
  - **Action:** Read the selected leaf skill, common gates, and only triggered
    references before the first mutation.
  - **Evidence:** Loaded `bluetape-full-feature`, `bluetape-kotlin-patterns`,
    `bluetape-writer`, `bluetape-diagram`, `brainstorming`,
    `using-git-worktrees`, checklist/common-gate contracts, review
    perspectives, model routing, topology contract, and `writing-plans`.
  - **Failure:** Stop before editing.
- [x] **WF-04A — Initialize machine-readable evidence**
  - **Action:** Snapshot the workflow type, repository root, and approved
    topology components with `bluetape-flow.py`.
  - **Evidence:** Run `20260716T161309Z-91be30b9`, manifest-backed Type A,
    components `design`, `plan`, `implementation`, `docs-diagrams`,
    `verification`, and `delivery`; state transitioned to `running`.
  - **Failure:** Remain on the documented checklist path and report the missing
    runtime surface.
- [ ] **WF-05 — Execute gates in dependency order**
  - **Action:** Follow the physical row order in the common and Type A gates.
  - **Evidence:** Check each row only after fresh proof is read.
  - **Failure:** Mark FAIL/PENDING and block downstream.
- [ ] **WF-06 — Repair any skipped or weak gate**
  - **Action:** Reconstruct any missed checklist item and rerun affected proof.
  - **Evidence:** Repair result, or final confirmation that no repair was needed.
  - **Failure:** Keep a recoverable repair PENDING; never report DONE.

## Type A Full Feature

- [x] **A-01 — Isolate and confirm requirements**
  - **Action:** Create the worktree, preserve unrelated changes, inspect issue
    #326, and define outcome, boundaries, compatibility, side effects, and stop
    condition.
  - **Evidence:** Isolated worktree and branch above; base
    `53ffe54f0b88a2886bdd3e2f467527741642acfc`; issue #326 is open in milestone
    `1.12.0`. Exclusions: no production API, module, publishing aggregation,
    Spring/Modulith dependency, catalog upgrade, or issue #322 work.
  - **Failure:** Stop before research or artifacts.
- [x] **A-02 — Ground the design in current evidence**
  - **Action:** Inspect current repository patterns, issue history, local APIs,
    docs, diagrams, and PostgreSQL/Testcontainers conventions.
  - **Evidence:** The design spec records current Ktor demo, R2DBC transaction,
    UUID repository, read-through/write-through, cache readiness, aggregate
    event-buffer, README/diagram, CI Docker, Testcontainers, and stable-manual
    anchors. It adopts existing APIs, borrows the UUID repository and
    PostgreSQL container patterns, and rejects route-owned orchestration,
    publisher decorators, write-behind, H2 compatibility mode, and runtime
    Testcontainers.
  - **Failure:** Do not design from recall.
- [x] **A-03 — Approve and review the design spec**
  - **Action:** Write the approved PostgreSQL Architecture B spec and converge
    six independent review lenses plus main-session integration.
  - **Evidence:**
    `docs/superpowers/specs/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-design.md`
    records the approved PostgreSQL Architecture B, alternatives, failure
    modes, exact lifecycle/HTTP/test/docs/diagram contracts, and final review
    table. Performance, stability, security, Ops, developer/API, user/caller,
    and main integration all converged at P0=0, P1=0, P2=0, P3=0.
  - **Failure:** Revise and reapprove any material design change.
- [x] **A-04 — Approve and review the implementation plan**
  - **Action:** Write an ordered executable TDD plan and converge all plan
    review perspectives.
  - **Evidence:**
    `docs/superpowers/plans/2026-07-17-issue-326-ktor-r2dbc-ddd-demo-plan.md`
    contains the ordered TDD tasks, exact commands, Lore commits, risk
    traceability, and final review table. Performance, stability, security,
    Ops, developer/API, user/caller/docs/diagrams, and main integration all
    converged at P0=0, P1=0, P2=0, P3=0.
  - **Failure:** Repair ordering, proof, ownership, or hazard coverage.
- [x] **A-05 — Predict triggered risks**
  - **Action:** Record cache consistency, coroutine cancellation, resource
    lifecycle, real PostgreSQL, event handoff, and diagram risks with signals,
    mitigations, and rerun points.
  - **Evidence:** The plan maps dirty write-through cache state, cancellation,
    process-wide R2DBC default ownership, non-durable event handoff,
    Docker-task isolation, startup/shutdown diagnostics, and diagram
    readability to earliest signals, prevention/proof, implementation tasks,
    and rerun points.
  - **Failure:** Do not begin implementation.
- [ ] **A-06 — Implement with test-first proof**
  - **Action:** Follow RED/GREEN for each route, repository, event, lifecycle,
    and failure behavior; integrate only the approved scoped diff.
  - **Evidence:** RED/GREEN sequence, diagnostics, diff, and cleanup/performance
    results or evidence-backed N/A.
  - **Failure:** Return to the failing behavior or violated boundary.
- [ ] **A-07 — Verify tests, spec, plan, and repository hazards**
  - **Action:** Run targeted then proportional broader checks, including
    serialized Testcontainers PostgreSQL verification and diagram audits.
  - **Evidence:** Fresh commands/results, verifier PASS, complete acceptance
    mapping, and triggered hazards PASS or valid N/A.
  - **Failure:** Return to implementation or reopen the artifact.
- [ ] **A-08 — Converge the final pre-PR review**
  - **Action:** Run the final checklist and six code-review lenses, fix blockers,
    and rerun affected proof.
  - **Evidence:** Final diff, clean diagnostics/diff check, and P0=0/P1=0.
  - **Failure:** Keep PR creation blocked.
- [ ] **A-09 — Commit durable learning**
  - **Action:** Commit a lesson containing context, decision, outcome, proof,
    misses, and a future guard before PR creation.
  - **Evidence:** Tracked lesson commit.
  - **Failure:** Repair lesson evidence before delivery.
- [ ] **A-10 — Complete authorized PR delivery through live CI and review**
  - **Action:** Complete common gates CG-11 through CG-14 against the exact
    authorized head and live PR.
  - **Evidence:** Matching remote head, verified PR metadata/DoD, green required
    checks, review convergence, and diagram inspection artifacts.
  - **Failure:** Keep delivery PENDING or FAIL as the common gate requires.
- [ ] **A-11 — Capture knowledge and report merge readiness**
  - **Action:** Capture durable knowledge and render the exact merge-ready report
    with reconciled counts.
  - **Evidence:** Knowledge result, exact PR/head, and unchecked CG-16 through
    CG-18.
  - **Failure:** Do not claim DONE or request merge approval.
- [ ] **A-12 — Close out only after fresh merge approval**
  - **Action:** After fresh approval of the exact merge-ready PR/head, merge,
    verify, sync `develop`, and perform proven-safe cleanup.
  - **Evidence:** Approval, merge result/SHA, local/upstream parity, and cleanup.
  - **Failure:** Waiting at CG-16 is normal PENDING; preserve state.

## Current totals

- Required checks: 10/19
- N/A: 0
- Blocked: 0
- Pending: `WF-05`, `WF-06`, `A-06` through `A-12`
