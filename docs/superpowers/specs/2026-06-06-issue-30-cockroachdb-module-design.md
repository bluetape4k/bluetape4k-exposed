# Issue #30 CockroachDB module 설계

Date: 2026-06-06
Issue: https://github.com/bluetape4k/bluetape4k-exposed/issues/30
Parent epic: https://github.com/bluetape4k/bluetape4k-exposed/issues/24

## 목표

`bluetape4k-exposed`가 JDBC로 CockroachDB에 연결하고 실제 Testcontainers
기반 smoke test를 통과할 수 있음을 증명하는 최소
`exposed/exposed-cockroachdb` module을 추가한다.

첫 번째로 경계를 제한한 CockroachDB slice이며, 완전한 PostgreSQL dialect
parity를 주장하지 않는다.

## 범위

- 게시 가능한 Gradle module `:bluetape4k-exposed-cockroachdb`를 추가한다.
- PostgreSQL-wire CockroachDB JDBC URL을 위한 작은 `CockroachDatabase` connection helper를 제공한다.
- 기존 `bluetape4k-testcontainers`의 `CockroachServer` test fixture를 재사용한다.
- single-node smoke test에서 다음을 검증한다.
  - `CockroachServer`를 통한 connection
  - `SELECT 1`
  - 가능한 범위에서 Exposed를 통한 단순 table create, insert, select, drop
- 명시적인 제약을 포함한 `README.md`와 `README.ko.md`를 추가한다.
- root README locale set, `CHANGELOG.md`, `AGENTS.md`, CI, Nightly, coverage aggregation 등록을 갱신한다.

## 범위 밖

- 이 issue에서 custom CockroachDB Exposed dialect를 구현하지 않는다.
- global PostgreSQL dialect 등록을 재정의하지 않는다.
- transaction retry helper API를 제공하지 않는다. retry 안내는 #32가 소유한다.
- 완전한 PostgreSQL compatibility matrix를 문서화하거나 테스트하지 않는다. DDL 및 compatibility boundary는 #31이 소유한다.
- R2DBC 지원을 추가하지 않는다.

## API 계약

`CockroachDatabase`는 다음 stable entry point를 제공한다.

- `DRIVER`: PostgreSQL JDBC driver class name
- `connect(jdbcUrl, user, password, databaseConfig)`
- `connect(host, port, database, user, password, databaseConfig)`
- `connect(dataSource, databaseConfig)`
- `buildJdbcUrl(host, port, database)`

helper는 bluetape4k validation helper로 빈 host, database, user, JDBC URL을
검증한다. 기존 `CockroachServer`와 CockroachDB JDBC 경로가 PostgreSQL JDBC
driver를 사용하므로 `jdbc:postgresql://` URL만 허용한다.

## 테스트 계약

- smoke test는 raw `GenericContainer`가 아니라 `CockroachServer.Launcher.cockroach`를 사용한다.
- Testcontainers 기반 검증은 serial로 실행한다.
- bluetape4k assertion helper를 사용한다.
- test resource에 `junit-platform.properties`와 `logback-test.xml`을 포함한다.

## 문서 계약

- Public API KDoc은 English로 작성한다.
- `README.md`는 English, `README.ko.md`는 Korean으로 작성한다.
- 두 README 모두 CockroachDB가 PostgreSQL-wire-compatible이지만
  PostgreSQL-equivalent가 아니며 custom dialect/DDL boundary와 retry 안내는
  후속 작업임을 명시한다.
- contributor 대상 GitHub issue, PR, commit text는 English를 유지한다.

## 인수 기준

- `./gradlew projects`에 `:bluetape4k-exposed-cockroachdb`가 표시된다.
- `./gradlew :bluetape4k-exposed-cockroachdb:test --no-configuration-cache --no-daemon`가 local에서 통과하거나 구체적인 environment blocker가 기록된다.
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`가 통과한다.
- `git diff --check`가 통과한다.
- Step 6-R local 7-tier review가 `P0 = 0`, `P1 = 0`을 만족한다.
- PR body는 `## DoD Status`로 끝난다.
