# Issue 283 Redis Codec Safety Review

Date: 2026-06-23
Scope: `exposed/jdbc-lettuce`, `exposed/r2dbc-lettuce`, `exposed/jdbc-redisson`, `exposed/r2dbc-redisson`
Issue: #283

## Verdict

P0 findings: 0
P1 findings: 0

The repository defaults no longer silently inherit Fory-family binary Redis codecs for repository entity values. Lettuce repositories require an explicit value codec, and Redisson repositories reject known binary codecs unless the caller opts into trusted Redis data with `trustedBinaryCache = true`.

## Review Notes

- Lettuce repository constructors now require a caller-supplied `RedisCodec<String, E>` instead of using the inherited `LettuceLoadedMap` / `LettuceSuspendedLoadedMap` defaults, which select LZ4/Fory in the backing Redis utility.
- JDBC and R2DBC Lettuce modules use local `Exposed*Lettuce*LoadedMap` implementations so repository values can be encoded with the explicit entity codec while preserving read-through, write-through, write-behind, delete, invalidate, clear, and close behavior.
- `ExposedLettuceCodecs.jackson3(valueType)` and `ExposedR2dbcLettuceCodecs.jackson3(valueType)` provide the structural default path for repository data.
- Lettuce modules declare `bluetape4k-jackson3` as an API dependency because the public `jackson3` codec helpers require `JacksonSerializer` at runtime.
- Redisson cache configuration still comes from `RedissonCacheConfig`, but repository constructors now guard unsafe Fory, Kryo, and JDK-family codecs unless `trustedBinaryCache = true` is explicit.
- Redisson JSON helper codecs were not promoted as the default mitigation because their non-security error paths can fall back to the global Fory codec. The repository layer therefore uses an explicit trust opt-in rather than pretending every JSON helper is a safe default.
- README files document the new explicit Lettuce codec argument and the Redisson trusted-binary opt-in in both English and Korean.

## Validation

- `./gradlew :bluetape4k-exposed-jdbc-lettuce:testClasses :bluetape4k-exposed-r2dbc-lettuce:testClasses :bluetape4k-exposed-jdbc-redisson:testClasses :bluetape4k-exposed-r2dbc-redisson:testClasses --continue`
  - Result: success.
- `./gradlew :bluetape4k-exposed-jdbc-lettuce:test :bluetape4k-exposed-r2dbc-lettuce:test :bluetape4k-exposed-jdbc-redisson:test :bluetape4k-exposed-r2dbc-redisson:test --continue`
  - Result: success.
- `./gradlew :bluetape4k-exposed-jdbc-lettuce:cleanTest :bluetape4k-exposed-r2dbc-lettuce:cleanTest :bluetape4k-exposed-jdbc-lettuce:test :bluetape4k-exposed-r2dbc-lettuce:test --continue --rerun-tasks`
  - Result: success after declaring the Jackson3 runtime dependency; 790 JDBC Lettuce tests and 130 R2DBC Lettuce tests passed.
- Codec safety test XML
  - Result: success, 6 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --check`
  - Result: success.

## Residual Risk

- Full Redis/Testcontainers suites remain broader than the targeted constructor-safety checks. They should stay in the normal CI/Nightly paths because this change intentionally keeps existing trusted test fixtures on their binary test codec via explicit opt-in.
