---
title: "Audit History with JaVers"
locale: "en"
releaseRef: "1.12.1"
---

# Audit History with JaVers

Exposed and JaVers solve different problems. Exposed repositories read and write current relational state. JaVers records object snapshots, changes, commit metadata, and history queries. Keep those responsibilities separate so an operational delivery table or a persistence repository does not become an accidental audit model.

## Responsibility boundary

| Need | Owner |
| --- | --- |
| Current rows, queries, joins, and transaction writes | Exposed repository |
| Domain-level snapshot or change history | JaVers |
| Who/when/why commit metadata | JaVers commit properties and author context |
| Event publication delivery and retry state | Spring Modulith publication repository |
| Long-term reporting or analytical sink | Explicit downstream pipeline |

![Spring Modulith publication lifecycle](../../assets/spring/modulith-publication.png)

Spring Modulith's publication repository answers whether a listener delivery completed. It can update, delete, or archive completed publications. That ledger is not a domain audit trail and must not be presented as one.

## Integration shape

Define the business transaction around the current-state write. Decide explicitly when the JaVers commit happens and how failures are recovered. If both stores participate in one local transaction, prove that integration with the actual configuration. If they cannot share a transaction, use an outbox or another durable handoff and design for retries and duplicate delivery.

Audit identifiers should be stable domain identifiers, not database implementation details that change during migration. Keep sensitive fields out of snapshots or apply the JaVers repository's supported redaction and mapping policy before persisting them.

## What to test

1. A successful state change produces the expected current row and audit commit.
2. A rejected business transaction produces neither a visible state change nor a misleading audit entry.
3. Retrying after a partial failure is idempotent or creates an explicitly understood second commit.
4. Author, correlation identifier, reason, and timestamp are present where policy requires them.
5. Schema evolution leaves older snapshots readable or has a documented migration path.

## Learning route

Use this Exposed manual as the source of truth for relational persistence boundaries. Continue to [`bluetape4k-javers`](https://github.com/bluetape4k/bluetape4k-javers) for audit repository configuration, commit metadata, object mapping, diff queries, and history examples. For a larger composition example, link the persistence and audit stages from [`bluetape4k-workshop`](https://github.com/bluetape4k/bluetape4k-workshop) rather than duplicating their manuals here.

## Sources

- [Spring Modulith integration](../../../../spring-boot/spring-modulith/src/main/kotlin/io/bluetape4k/spring/modulith/exposed/ExposedEventPublicationRepository.kt)
- [Bluetape4k JaVers repository](https://github.com/bluetape4k/bluetape4k-javers)
- [JaVers documentation](https://javers.org/documentation/)
