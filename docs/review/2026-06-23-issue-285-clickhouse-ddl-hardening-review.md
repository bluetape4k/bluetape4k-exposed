# Issue 285 ClickHouse DDL Hardening 리뷰

날짜: 2026-06-23
범위: `:bluetape4k-exposed-clickhouse`, ClickHouse engine DDL DSL hardening
이슈: #285

## 판정

P0 지적 사항: 0
P1 지적 사항: 0

최종 diff는 암묵적인 string 기반 engine DSL 호출을 typed Exposed expression과 명시적인 `unsafeRaw...` escape hatch로 대체합니다. raw fragment는 DDL로 렌더링되기 전에 statement delimiter, comment, quote, newline, clause-boundary token 검증을 거칩니다.

## 리뷰 메모

- typed engine expression은 이제 table-local ClickHouse engine clause에서 unqualified column name을 렌더링합니다. 따라서 `ORDER BY`, `PARTITION BY`, `PRIMARY KEY`, engine argument에서 잘못된 `table.column` DDL을 피합니다.
- `ClickHouseTable`은 constructor compatibility를 유지하면서 column 초기화 이후 `override val engine` 선언을 허용합니다. 일반 table definition은 constructor-time raw string 대신 typed `orderBy(id)`를 사용할 수 있습니다.
- safe setting은 allowlist된 MergeTree setting name으로 제한됩니다. 임의 setting name에는 `unsafeRawSetting`이 필요하고, unsafe raw setting value는 raw expression과 같은 delimiter/comment/clause 검증을 사용합니다.
- 알려진 ClickHouse helper function은 Exposed transaction 없이 재귀적으로 렌더링됩니다. 알 수 없는 expression은 `QueryBuilder`로 fallback하여, 현재 renderer 밖의 modeled expression에 대한 compatibility path를 보존합니다.

## 검증

- `./gradlew :bluetape4k-exposed-clickhouse:test --tests 'io.bluetape4k.exposed.clickhouse.engine.MergeTreeDslTest' --tests 'io.bluetape4k.exposed.clickhouse.SchemaUtilsTest' --no-build-cache --console=plain`
  - 결과: success, 25 tests executed.
  - typed expression rendering, raw fragment rejection, allowlisted settings, typed `ClickHouseTable` engine override, typed engine table의 real schema create/drop을 검증했습니다.

## 잔여 위험

- `assumeNotNull(created_at)`처럼 Exposed가 아직 모델링하지 않은 ClickHouse grammar fragment를 위해 raw escape hatch는 의도적으로 남아 있습니다. 단, 명시적이고 validator-gated입니다.
- fallback `QueryBuilder` 경로는 dialect-specific expression을 ClickHouse engine DDL 기대와 다르게 렌더링할 수 있습니다. engine clause에서 사용하는 새 ClickHouse expression helper를 추가할 때는 dedicated renderer를 확장해야 합니다.
