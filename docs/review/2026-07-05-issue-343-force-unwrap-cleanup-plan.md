# Issue #343 Cleanup Plan — force unwrap hotspot

## 대상 범위

1. Production code hotspot:
   - `ktor/exposed/.../ExposedKtorHealthRoutes.kt` JDBC dispatcher invariant.
   - `utils/batch/.../BatchStepBuilder.kt` required reader/writer builder state.
   - `exposed/jdbc-redisson/.../AbstractJdbcRedissonRepository.kt` and suspended variant cache map values.
2. Shared test-helper hotspot:
   - JDBC `withDb` / `withDbSuspending` / `withTables` helpers.
   - R2DBC `withDb` / `withTables` helpers, including nullable default isolation level.

## 이번 pass의 비목표

- repository의 모든 test-only `!!`를 기계적으로 제거하지 않습니다.
- shared helper 안에 있는 경우가 아니면 순수 assertion 또는 fixture unwrap은 후속 issue #337로 남깁니다.

## 동작 고정

- 코드 변경 전후로 영향 module compile/test를 실행합니다.
- exception class 의미론을 보존합니다. caller-provided required input에는 `requireNotNull`, internal state/invariant에는 `checkNotNull`을 사용합니다.

## 예상되는 잔여 `!!`

- 범위 밖의 test-only assertion fixture와 example.
- 직접적으로 오해를 만들지 않는 한, 과거 `!!` 근거를 언급하는 기존 comment/KDoc.
