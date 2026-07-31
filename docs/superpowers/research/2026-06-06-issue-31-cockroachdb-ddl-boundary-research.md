# 이슈 #31 CockroachDB DDL 경계 조사

날짜: 2026-06-06
이슈: https://github.com/bluetape4k/bluetape4k-exposed/issues/31
위키 노트: `/Users/debop/work/bluetape4k/bluetape4k-wiki/research/2026-06-06-cockroachdb-exposed-ddl-boundary.md`

## 출처

- CockroachDB PostgreSQL 호환성:
  https://www.cockroachlabs.com/docs/stable/postgresql-compatibility
- CockroachDB SQL 기능 지원:
  https://www.cockroachlabs.com/docs/v26.2/sql-feature-support
- JetBrains Exposed 지원 데이터베이스:
  https://www.jetbrains.com/help/exposed/about.html
- HikariCP 구성 예시:
  https://github.com/brettwooldridge/HikariCP

## 조사 결과

- CockroachDB는 PostgreSQL 와이어 프로토콜과 많은 PostgreSQL 구문
  경로를 지원하지만, 공식 문서는 여전히 지원되지 않거나 다른 PostgreSQL 기능을
  식별합니다.
- CockroachDB는 기본 키, 고유 제약 조건, 인덱스, `RETURNING`, 시퀀스, ID 열과
  같은 일반적인 DDL 영역을 지원 영역으로 문서화하지만, Exposed가 생성한 SQL은
  CockroachDB에서 직접 검증되어야 합니다.
- JetBrains Exposed 1.3.0 문서에는 CockroachDB가 기본 제공 지원
  데이터베이스로 나열되어 있지 않습니다.
- HikariCP는 예상되는 JDBC 풀 옵션이며, `bluetape4k-jdbc`는 이미 테스트와 예제를
  위해 `hikariDataSourceOf`, `withConnect`, `withStatement`, `runQuery`
  헬퍼를 제공합니다.

## 결정

1.11.0에서는 `exposed-cockroachdb`를 헬퍼 전용으로 유지합니다. 기본 키 DDL,
고유/인덱스 DDL, 생성된 ID, `RETURNING`, 메타데이터는 CockroachDB Testcontainers
테스트가 검증하는 경우에만 지원으로 표시합니다. 마이그레이션 diff no-op 의미론,
PostgreSQL 범위 타입, `CREATE DOMAIN`은 연기된 상태로 표시합니다.
