# Issue 337 Assertion Style Review

## Scope

- Issue: #337 `test: normalize repo-wide assertions to bluetape4k-assertions style`
- Current branch base already contains the earlier assertion cleanup lesson and broad rewrites.
- Remaining live scan found only five `kotlin.test.Test` imports; assertion-specific forbidden patterns were already zero.

## Changes

Replaced `kotlin.test.Test` with `org.junit.jupiter.api.Test` in:

- `exposed/fastjson2/src/test/kotlin/io/bluetape4k/exposed/core/fastjson2/ReadableExtensionsTest.kt`
- `exposed/jackson2/src/test/kotlin/io/bluetape4k/exposed/core/jackson/ReadableExtensionsTest.kt`
- `exposed/jackson3/src/test/kotlin/io/bluetape4k/exposed/core/jackson3/ReadableExtensionsTest.kt`
- `exposed/r2dbc/src/test/kotlin/io/bluetape4k/exposed/r2dbc/QueryExtensionsTest.kt`
- `exposed/r2dbc-redisson/src/test/kotlin/io/bluetape4k/exposed/r2dbc/redisson/map/R2dbcExposedEntityMapLoaderTest.kt`

## Pattern scan

| Pattern | Result |
| --- | --- |
| `import kotlin.test` | 0 |
| `.shouldBeEqualTo(` | 0 |
| `.size shouldBeEqualTo` | 0 |
| `shouldBeEqualTo true/false` | 0 |
| `.shouldBeEqualTo(true/false)` | 0 |

## 7-Tier lite review

| Tier | Result | Evidence |
| --- | --- | --- |
| 1 Correctness | PASS | Test annotation provider changed only; test bodies unchanged. |
| 2 Assertion style | PASS | Remaining `kotlin.test` imports removed and forbidden assertion scans are zero. |
| 3 Scope | PASS | Five affected files only; no production code changed. |
| 4 Maintainability | PASS | All touched tests now use JUnit Jupiter annotations consistently. |
| 5 Compatibility | PASS | JUnit Jupiter is already used throughout the repo. |
| 6 Test evidence | PASS | Affected module tests passed. |
| 7 Documentation | PASS | Existing lesson retained; this review records the final live closeout. |

## Validation

- `./gradlew --no-parallel :bluetape4k-exposed-fastjson2:test :bluetape4k-exposed-jackson2:test :bluetape4k-exposed-jackson3:test :bluetape4k-exposed-r2dbc:test :bluetape4k-exposed-r2dbc-redisson:test` — BUILD SUCCESSFUL in 1m 37s.

## Verdict

P0/P1: 0. Ready for PR after `git diff --check` and `gno update`.
