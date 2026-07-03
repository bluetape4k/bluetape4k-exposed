# Issue 337 Assertion Style Review

## Scope

- Branch: `test/issue-337-assertion-style`
- Stack base: `chore/issue-312-314-code-pattern-audit`
- Issue: #337
- Reviewed diff: repo-wide test assertion style normalization.

## 7-Tier Review

1. Correctness: PASS
   - Kotlin compile gate passed after fixing `Map<String, Any?>` boolean matcher casts.
   - R2DBC suspend exception helper no longer catches its own assertion failure.
2. API and compatibility: PASS
   - Production API is unchanged.
   - Test helper function names and call sites remain source-compatible.
3. Kotlin and bluetape4k style: PASS
   - Replaced `kotlin.test` assertion/fail usage in issue scope with `bluetape4k-assertions`.
   - Used infix `shouldBeEqualTo`, `shouldHaveSize`, `shouldBeTrue`, `shouldBeFalse`, and null matchers where applicable.
4. Testing strategy: PASS
   - Full test compilation ran for the repository.
   - Targeted modules covering edited helper/readable/r2dbc/batch paths passed.
5. Concurrency and coroutine safety: PASS
   - No production coroutine control flow was changed.
   - R2DBC suspend helper still treats thrown exceptions as assertion input only.
6. Maintainability: PASS
   - Assertion helper dialect failure messages are preserved by a local wrapper.
   - Mechanical changes avoid new abstractions or dependencies.
7. Regression and operational risk: PASS
   - Risk is limited to test code and test helper behavior.
   - No CI, Gradle, dependency, or public documentation surface changed.

## Findings

- P0/P1: none.
- Residual risk: some untouched tests still use `shouldBeEqualTo null` or `shouldBeEqualTo emptyList()` patterns outside the explicit #337 forbidden-pattern scope. They can be handled in a separate style sweep if desired.

## Evidence

- Forbidden pattern scan: 0 matches for `kotlin.test.assert*`, `kotlin.test.fail`, `.shouldBeEqualTo(...)`, `.size shouldBeEqualTo`, and boolean equality matcher patterns.
- `git diff --check`: pass.
- `./gradlew compileTestKotlin --no-configuration-cache`: pass.
- `./gradlew :bluetape4k-exposed-core:test :bluetape4k-exposed-fastjson2:test :bluetape4k-exposed-jackson2:test :bluetape4k-exposed-r2dbc:test :bluetape4k-exposed-batch:test --no-configuration-cache`: pass, 355 passing, 7 pending.
- `./gradlew :bluetape4k-exposed-jdbc-tests:test :bluetape4k-exposed-r2dbc-tests:test :exposed-spring-boot-r2dbc-demo:test --no-configuration-cache`: pass, 170 + 149 + 25 passing.
- `./gradlew :bluetape4k-exposed-r2dbc-tests:test --no-configuration-cache`: pass, 149 passing after suspend helper cleanup.
- `./gradlew test --no-configuration-cache`: pass, `BUILD SUCCESSFUL in 13m 6s`, 234 actionable tasks.
