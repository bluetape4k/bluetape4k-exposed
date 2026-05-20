# Issue 123 cache health plan

## Goal

Implement milestone 1.8.1 issue #123 by adding a Caffeine repository health
snapshot that exposes WRITE_BEHIND queue, worker, and flush error state.

## Steps

1. Add shared model.
   - Create `CacheHealthReport` in `exposed-cache`.
   - Make it `Serializable` with `serialVersionUID`.

2. Expose Caffeine contract.
   - Add `validateConsistency()` to `JdbcCaffeineRepository`.
   - Add `suspend validateConsistency()` to `R2dbcCaffeineRepository`.

3. Implement runtime state tracking.
   - Track accepted write-behind entries with an `AtomicInteger`.
   - Track last non-cancellation flush failure with an `AtomicReference`.
   - Track whether the lazy write-behind job has been started without forcing
     initialization from health reporting.

4. Add tests.
   - JDBC: idle health snapshot, blocked in-flight flush queue depth, flush
     failure report.
   - R2DBC: idle health snapshot, blocked in-flight flush queue depth, flush
     failure report.

5. Verify and publish.
   - Run targeted compile/tests.
   - Run `git diff --check`.
   - Do local in-session review.
   - Commit, push, and open a PR that closes #123.

## Constraints

- Actuator HealthIndicator integration is optional in the issue and remains out
  of scope for this increment.
- Claude advisor/review is skipped by user instruction.
- External Codex CLI review is skipped by user instruction.
- IntelliJ diagnostics are unavailable unless this worktree becomes the active
  IntelliJ project; Gradle compile/tests are the fallback.
