# 이슈 285 ClickHouse DDL 강화 교훈

Date: 2026-06-23
Issue: #285

## 교훈

ClickHouse engine clause는 일반 query expression이 아니라 table-local DDL입니다. typed engine DSL 렌더링은 `table.column` 출력을 만들 때 query-style column 렌더링을 무분별하게 재사용하면 안 됩니다.

## 지침

- engine clause가 typed column을 참조해야 할 때는 column property 뒤에 선언한 `ClickHouseTable` engine override를 우선합니다.
- constructor-time raw fragment는 호환성 또는 아직 모델링할 수 없는 expression에만 유지하고 `unsafeRaw...`로 raw 경계를 명시합니다.
- setting name은 setting value와 분리해 다룹니다. safe 경로는 allowlist 또는 typed여야 하며 임의의 setting name은 unsafe API 뒤에 둡니다.
- typed engine 선언에는 `toClause()` 문자열 테스트뿐 아니라 schema 수준 create/drop coverage를 추가합니다.

## 후속 조치

engine DDL 내부에서 유효해야 하는 새 ClickHouse expression helper를 추가할 때는 전용 engine expression renderer에 넣고 `MergeTreeDslTest`로 다룹니다.
