# English KDoc for Public APIs Changed in 1.8.0

**Date**: 2026-05-16
**Issue**: #92
**Type**: Maintenance (docs)

## Summary

Converted Korean KDoc to English for all public API classes and methods that were
meaningfully edited during the 1.8.0 pre-release fix cycle (#79–#90).

## Files Updated

| File | Scope |
|------|-------|
| `ClickHouseDatabase.kt` | Class KDoc, `DRIVER` property, both `connect()` overloads |
| `AbstractJdbcCaffeineRepository.kt` | Class KDoc + `@param` tags |
| `AbstractJdbcRedissonRepository.kt` | Class KDoc + `@param` tags |
| `BigQueryQueryExecutor.kt` | Class KDoc, 6 method one-liners, `BigQueryResultRow` class + 2 one-liners |
| `ExposedJdbcBatchReader.kt` | Class KDoc + all `@param` tags |
| `ExposedR2dbcBatchReader.kt` | Class KDoc + all `@param` tags |

## Future Guidance

- Per CLAUDE.md policy: "New or meaningfully changed public API KDoc must be English."
- Inline code comments (non-KDoc) are not subject to this policy — they may remain in Korean.
- When a bug fix changes observable behavior, treat the affected public class as "meaningfully changed"
  and convert its KDoc to English in the same PR or a dedicated docs PR.
