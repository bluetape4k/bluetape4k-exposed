# Issue 284 CI Spring Modulith and Examples Lessons

Date: 2026-06-23
Issue: #284

## Lesson

Documented example and demo modules need explicit CI ownership. Compile-only coverage does not prove that examples still run, especially when a documented example depends on Testcontainers and can silently drift away from the main module matrix.

## Guidance

- Add path-filter outputs, dedicated test jobs, coverage artifacts, and final status `needs` entries together when a module or example gets its own CI lane.
- New CI lanes must include workflow/build-file paths in their filters so the PR that adds the lane can prove it on GitHub Actions.
- Example jobs should trigger from both the example source tree and the modules/build files that the examples exercise; otherwise module changes can bypass the documented usage path.
- Keep example/demo jobs visible in both PR CI and Nightly workflows when the examples are advertised as supported usage paths.
- Run Docker-backed example tests serially, and separate local compile/testClasses proof from full Testcontainers proof when local Docker is unavailable.
- Keep Docker-heavy Nightly example tests under the full-scope guard unless they are explicitly split into smoke-safe and full suites.
- Validate workflow edits with `actionlint`, a structural YAML check, and an escaped-quote fixed-string search before pushing.

## Follow-up

If a future workflow splits example modules further, keep artifact names granular enough that missing coverage can be traced to the exact example group.
