# 설계 — 이슈 #256 Druid JDBC 쿼리 전용 실험

## 범위

`bluetape4k-exposed-druid`를 쿼리 전용 Apache Druid JDBC 실험 모듈로 추가한다.
이 모듈은 Apache Calcite Avatica JDBC를 사용하며, Exposed ORM dialect 전체 호환성을
의미해서는 안 된다.

## 포함 범위

- Druid Router/Broker endpoint용 Avatica JDBC 연결 옵션 빌더.
- `java.sql.Connection` 기반 쿼리 실행 헬퍼.
- `INFORMATION_SCHEMA`를 사용하는 Druid datasource 메타데이터 검색 헬퍼.
- 쿼리 전용 포지셔닝, Router/Broker stickiness, Avatica properties, smoke-test 명령을
  설명하는 이중 언어 모듈 README.
- 직렬 모듈 테스트 job으로 CI/Nightly 등록.

## 제외 범위

- Exposed `Database`/dialect 등록.
- DDL, DML, DAO, repository, migration, schema generation, batch-write API.
- 안정적인 Druid fixture datasource recipe를 별도로 입증하지 않는 한 광범위한
  Testcontainers launcher.

## 수용 기준 매핑

| 이슈 수용 기준 | 설계 답변 |
|---|---|
| JDBC 연결 smoke | `EXPOSED_DRUID_SMOKE=true`일 때 `DruidJdbcSmokeTest`가 로컬 또는 컨테이너 Druid endpoint를 대상으로 실행된다. |
| 메타데이터 검색 | `DruidJdbc.listColumns()`가 `INFORMATION_SCHEMA.COLUMNS`를 조회한다. |
| SELECT 쿼리 | `DruidJdbc.query()`와 smoke test가 `SELECT`를 실행한다. |
| 쿼리 전용 문서 | README 파일에 지원하지 않는 DDL/DML/DAO/repository/migration을 명시한다. |
| CI/Nightly 배치 | 전용 직렬 모듈 테스트 job이 일반 테스트를 compile/run하며 smoke는 환경으로 제어한다. |

## 위험

- 공식 Druid Docker quickstart는 다중 컨테이너이고 메모리 사용량이 크므로 기본 CI에서
  암묵적으로 시작해서는 안 된다. smoke test는 datasource가 로드된 준비된 로컬 또는
  컨테이너 Druid에서 실행할 수 있다.
- Druid 문서의 최소 버전보다 최신인 Avatica driver 버전은 transitive dependency를
  바꿀 수 있으므로, targeted compile/test로 classpath를 검증해야 한다.
