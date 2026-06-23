# Issue 281 Spring Modulith Unloadable Event Lessons

Date: 2026-06-23
Issue: #281

## Lesson

Repository query paths must not hide durable operational records just because their payload type cannot be loaded.
For event publications, invisibility is worse than a loud failure because undelivered work can disappear from operator
views and resubmission logic.

## Guidance

- Materialize publication rows independently from payload deserialization when the repository SPI allows lazy event
  access.
- Preserve row identifiers and listener ids in diagnostic exceptions so operators can repair or delete the exact row.
- Treat class loading failures and linkage failures as undeliverable diagnostics, not as a reason to filter rows.
- Regression tests should insert rows with deliberately missing event types and verify query visibility before checking
  the explicit failure path.
- Documentation should tell operators to restore classpath compatibility, migrate stored event rows, or explicitly
  delete/resubmit after correction.

## Follow-up

If Spring Modulith adds a first-class undeliverable publication representation, adapt the repository to expose unknown
event types through that API instead of using a lazy event-access exception.
