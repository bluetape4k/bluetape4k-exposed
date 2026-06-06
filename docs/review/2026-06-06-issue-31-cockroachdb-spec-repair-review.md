# Issue #31 Spec Repair Review

Date: 2026-06-06
Spec: `docs/superpowers/specs/2026-06-06-issue-31-cockroachdb-ddl-boundary-design.md`
Workflow gate: Step 2-R repair

## Repair Trigger

Implementation evidence narrowed two spec assumptions:

- Direct JDBC evidence should use the bluetape4k ecosystem (`bluetape4k-jdbc`
  plus HikariCP) instead of ad hoc `DriverManager.getConnection`.
- `MigrationUtils.statementsRequiredForDatabaseMigration` still proposes
  generated-ID sequence ownership updates after `SchemaUtils.create`, so the
  spec must document migration diff no-op semantics as deferred.

## 7-Tier Review

| Tier | Reviewed scope | P0 | P1 | P2 | P3 | Evidence |
|---|---|---:|---:|---:|---:|---|
| 1 Security | Direct JDBC and unsupported SQL evidence | 0 | 0 | 0 | 0 | Test SQL is fixed test input; no secrets or caller-controlled SQL were added. |
| 2 Ops/SRE | Testcontainers, HikariCP lifecycle, cleanup requirements | 0 | 0 | 0 | 0 | Spec requires `CockroachServer.Launcher.cockroach`, serial Testcontainers proof, and cleanup guards. |
| 3 Structural impact | Helper-only contract and dialect decision rule | 0 | 0 | 0 | 0 | Spec keeps custom dialect evidence-gated and avoids expanding public API when accepted paths pass. |
| 4 Kotlin/API quality | bluetape4k ecosystem reuse and public API contract | 0 | 0 | 0 | 0 | Spec requires bluetape4k JDBC/HikariCP helpers and no new public API unless dialect evidence requires it. |
| 5 Tests/types/silent failure | Accepted, deferred, and unsupported paths | 0 | 0 | 0 | 0 | Spec requires direct CockroachDB evidence, non-brittle unsupported-path checks, and matrix validation. |
| 6 Performance/stability | Pool/container/resource lifecycle | 0 | 0 | 0 | 0 | Serial container and caller-managed pool cleanup are explicit test constraints. |
| 7 Docs/release/evidence | README locale pair, changelog, verification commands | 0 | 0 | 0 | 0 | Spec requires README matrix, Exposed caveat, verification command, changelog, and PR DoD body. |

## Integrated Findings

| Priority | Area | Finding | Resolution |
|---|---|---|---|
| P0 | N/A | No blocking spec defect found after repair. | Gate can close. |
| P1 | N/A | No high-priority spec defect found after repair. | Gate can close. |

Rejected: claiming migration diff no-op support in #31 because current
`MigrationUtils` evidence shows generated-ID sequence ownership updates after
schema creation.

## Verdict

P0 = 0
P1 = 0

Step 2-R repair PASS.
