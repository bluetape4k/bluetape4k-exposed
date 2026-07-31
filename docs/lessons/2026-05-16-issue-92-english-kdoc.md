# 1.8.0에서 변경된 Public API의 English KDoc

**Date**: 2026-05-16
**Issue**: #92
**Type**: Maintenance (docs)

## 요약

1.8.0 pre-release fix cycle(#79–#90) 동안 의미 있게 수정된 모든 public API class와
method의 Korean KDoc을 English로 전환했습니다.

## 업데이트한 파일

| File | Scope |
|------|-------|
| `ClickHouseDatabase.kt` | Class KDoc, `DRIVER` property, 두 `connect()` overload |
| `AbstractJdbcCaffeineRepository.kt` | Class KDoc + `@param` tag |
| `AbstractJdbcRedissonRepository.kt` | Class KDoc + `@param` tag |
| `BigQueryQueryExecutor.kt` | Class KDoc, 6개 method one-liner, `BigQueryResultRow` class + 2개 one-liner |
| `ExposedJdbcBatchReader.kt` | Class KDoc + 모든 `@param` tag |
| `ExposedR2dbcBatchReader.kt` | Class KDoc + 모든 `@param` tag |

## 향후 지침

- CLAUDE.md policy: "New or meaningfully changed public API KDoc must be English."
- inline code comment(non-KDoc)은 이 policy의 대상이 아니므로 Korean으로 남아도 됩니다.
- bug fix가 observable behavior를 바꾸면 영향을 받은 public class를
  "meaningfully changed"로 보고 같은 PR 또는 전용 docs PR에서 KDoc을 English로
  전환합니다.
