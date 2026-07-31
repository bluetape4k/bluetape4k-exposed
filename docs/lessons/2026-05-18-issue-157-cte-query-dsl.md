# CTE Query DSL

## 배경

Issue #157은 Exposed extension module에 PostgreSQL/MySQL Common Table Expression
support를 추가했습니다. Exposed는 현재 repository code용 first-class CTE DSL을
제공하지 않지만, 사용자는 raw SQL string 대신 typed field access와 prepared
statement binding을 계속 필요로 합니다.

## 결정

`exposed-core`에 `CteTable`을 도입하고 JDBC/R2DBC helper는 Query wrapper로
유지합니다.

- `CteTable`은 기존 `Query`의 selected field를 table-like facade로 매핑해
  downstream SELECT clause가 typed column access를 유지할 수 있게 합니다.
- `withCte`와 `withCtes`는 같은 Exposed `QueryBuilder` flow로 CTE body와 final
  SELECT를 render합니다.
- recursive CTE support는 별도 raw SQL path 대신 CTE clause의 flag로 둡니다.

## 결과

JDBC와 R2DBC는 동일한 CTE table facade를 공유하면서 각 module은 자체 query wrapper
surface를 유지합니다. public API는 SELECT query로 범위를 제한하므로, Exposed가
더 나은 internal statement contract를 제공하면 DML CTE support를 별도로 설계할 수
있습니다.

## 검증

- JDBC CTE test가 H2, PostgreSQL, MySQL 8에서 통과했습니다.
- R2DBC CTE test가 H2, PostgreSQL, MySQL 8에서 통과했습니다.
- root README, module README, WIP, CHANGELOG를 함께 업데이트했습니다.

## 향후 지침

- driver-specific limitation이 test에 문서화되지 않았다면 JDBC와 R2DBC CTE behavior를
  대칭으로 유지합니다.
- CTE body rendering과 final SELECT rendering을 분리하지 않습니다. prepared-parameter
  ordering bug 위험이 있습니다.
- public Exposed DSL helper를 추가할 때는 같은 PR에서 English와 Korean module README를
  모두 업데이트합니다.
