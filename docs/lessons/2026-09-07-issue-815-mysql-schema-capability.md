# #815 MySQL fixture의 table·schema capability 경계

## 상황

Full Nightly의 MySQL JDBC·R2DBC job에서 fixture 테스트가 `MYSQL_V8`을 허용
목록에서 거부했다. 목록에 MySQL을 추가한 뒤 실제 Testcontainers 계정으로 table
cleanup은 통과했지만, schema cleanup은 `CREATE SCHEMA`/database 권한 부족으로
실패했다. MySQL Testcontainers의 `test` 사용자는 schema(database) 생성 권한을
갖지 않는다.

## 결정

- JDBC·R2DBC fixture의 DB 허용 목록에는 `MYSQL_V8`을 포함해 table cleanup 계약을
  MySQL에서도 실행한다.
- schema lifecycle 검사는 schema 생성 권한이 검증된 H2·PostgreSQL에만 적용하고,
  MySQL에서는 JUnit assumption으로 명시적으로 보류한다.
- DB를 통째로 제외하거나 schema 오류를 숨기지 않는다. capability별 범위와 보류
  사유를 테스트 결과에 남긴다.

## 검증

- MySQL JDBC·R2DBC targeted fixture: 각각 `7 passing / 1 pending`.
- H2·PostgreSQL JDBC·R2DBC targeted fixture: 각 `8 passing`.
- Nightly와 동일한 MySQL 전체 경로도 JDBC `390 passing / 21 pending`, R2DBC
  `303 passing / 11 pending`으로 성공했다.

## 다음 변경에서 지킬 규칙

새 DB를 fixture 행렬에 추가할 때 table DDL, schema/database DDL, transaction,
cleanup 권한을 별도로 확인한다. 기존 `WithSchemasTest`의 지원 DB 집합과 실제
Testcontainers 계정 권한을 먼저 대조하고, capability가 없으면 해당 테스트만
명시적인 assumption으로 제한한다.
