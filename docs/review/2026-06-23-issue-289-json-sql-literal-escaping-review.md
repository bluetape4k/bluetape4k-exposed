# Issue #289 JSON SQL Literal Escaping Review

## Scope

- `exposed/jackson2`
- `exposed/jackson3`
- `exposed/fastjson2`
- Issue #289: escape serialized JSON before rendering SQL literals/default strings.

## Findings

No P0/P1 findings in the local review.

Independent code-reviewer subagent also reported P0/P1 = 0.

## Evidence

- `JacksonColumnType.nonNullValueToString` now delegates the serialized JSON string through Exposed `TextColumnType.nonNullValueToString`.
- `FastjsonColumnType.nonNullValueToString` uses the same escaping path.
- H2 keeps the required `JSON ` prefix and reuses the escaped string literal body.
- Unit tests cover single quote, CR, and LF in rendered SQL literals and default strings for Jackson2, Jackson3, and Fastjson2.

## Verification

- RED: Jackson2 unit test failed before production code changed.
- GREEN: `./gradlew :bluetape4k-exposed-jackson2:test --tests "io.bluetape4k.exposed.core.jackson.JacksonColumnTypeUnitTest" :bluetape4k-exposed-jackson3:test --tests "io.bluetape4k.exposed.core.jackson3.Jackson3ColumnTypeUnitTest" :bluetape4k-exposed-fastjson2:test --tests "io.bluetape4k.exposed.core.fastjson2.FastjsonColumnTypeUnitTest"` passed.
- Module tests: `./gradlew :bluetape4k-exposed-jackson2:test :bluetape4k-exposed-jackson3:test :bluetape4k-exposed-fastjson2:test` passed.
- Build/static: `./gradlew :bluetape4k-exposed-jackson2:build :bluetape4k-exposed-jackson3:build :bluetape4k-exposed-fastjson2:build detekt` passed.
- Whitespace: `git diff --check` passed.
- Independent reviewer: scoped review over 6 files reported no P0/P1 correctness or security regressions.

## Residual Risk

- The fix intentionally follows Exposed string literal escaping semantics. If Exposed changes literal escaping behavior in a future version, JSON literal rendering will track that change.
