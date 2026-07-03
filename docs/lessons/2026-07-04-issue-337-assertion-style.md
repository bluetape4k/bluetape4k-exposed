# Issue 337 Assertion Style Cleanup

## Context

Issue #337 required normalizing repo-wide test assertions to bluetape4k assertion style on top of the existing #335 stacked PR base.

## Decision

- Keep the work as a stacked branch based on `chore/issue-312-314-code-pattern-audit`.
- Prefer mechanical assertion rewrites only for explicit issue patterns.
- Preserve dialect-aware helper messages with small local wrappers instead of changing call sites.

## Outcome

- Removed issue-scope `kotlin.test` assertion/fail usage.
- Replaced Java-style `.shouldBeEqualTo(...)` and boolean equality assertions with Kotlin/infix bluetape4k assertion style.
- Fixed one R2DBC suspend helper branch so a successful block produces the intended assertion failure instead of being caught as an unexpected exception.

## Verification

- Forbidden pattern scan returned 0 matches.
- `git diff --check` passed.
- Full `compileTestKotlin` passed.
- Targeted core/readable/r2dbc/batch/JDBC/R2DBC/demo tests passed.
- Full repository `test` passed with `--no-configuration-cache`.

## Future Guidance

- When a value is typed as `Any?`, cast to the expected Kotlin type before using boolean-specific matchers such as `shouldBeTrue()`.
- Avoid reintroducing `kotlin.test.assert*` or `kotlin.test.fail` in bluetape4k tests; use `bluetape4k-assertions` matchers and `assertFailsWith`.
