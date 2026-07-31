# CTE Edge Coverage

## 배경

Issue #167은 #157의 CTE query DSL을 이어서 다뤘습니다. 초기 test는 기본 `WITH`와
recursive CTE behavior를 증명했지만 multi-CTE rendering, `ALL` 없는 `UNION` rendering,
missing field lookup error, expression alias mapping branch는 검증하지 않았습니다.

## 결정

JDBC와 R2DBC `CteQueryTest` 모두에 같은 네 focused regression case를 추가합니다.

- `withCtes(cte1, cte2)`는 comma-separated `WITH` list를 render하고 실행 가능합니다.
- `unionAll = false`는 `UNION`을 render하며 `UNION ALL`을 render하지 않습니다.
- CTE query set 밖 field 접근은 기존 error message를 던집니다.
- `IExpressionAlias` field는 `CteTable`을 통해 다시 mapping할 수 있습니다.

## 결과

CTE test suite는 runtime code나 public API 변경 없이 primary edge case를 다룹니다.
JDBC와 R2DBC는 대칭을 유지합니다.

## 검증

- `./gradlew :bluetape4k-exposed-jdbc:compileTestKotlin :bluetape4k-exposed-r2dbc:compileTestKotlin --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-jdbc:test --tests "io.bluetape4k.exposed.jdbc.CteQueryTest" :bluetape4k-exposed-r2dbc:test --tests "io.bluetape4k.exposed.r2dbc.CteQueryTest" --console=plain --no-daemon`

## 향후 지침

- CTE edge test는 JDBC와 R2DBC 사이에 mirrored로 유지합니다.
- 여러 Exposed expression으로 정렬할 때는 명시적인 `SortOrder` pair를 사용합니다.
- `select(...)`에는 alias variable을 concrete로 두고 그 branch를 test할 때 CTE lookup
  call만 `IExpressionAlias<T>`로 cast합니다.
