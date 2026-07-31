# Issue #31 CockroachDB DDL 경계 설계

날짜: 2026-06-06
이슈: https://github.com/bluetape4k/bluetape4k-exposed/issues/31
상위 epic: https://github.com/bluetape4k/bluetape4k-exposed/issues/24
이전 작업 단위: https://github.com/bluetape4k/bluetape4k-exposed/issues/30

## 목표

실행 가능한 CockroachDB 근거를 바탕으로 `exposed-cockroachdb` 1.11.0의
지원 DDL 및 PostgreSQL 호환성 경계를 정의한다.

이 이슈에서 CockroachDB를 포괄적인 PostgreSQL alias로 만들어서는 안 된다.
실제 CockroachDB container가 허용하는 Exposed 생성 스키마 경로, 보류할
PostgreSQL 파생 경로, 1.11.0 계약에 사용자 정의 `CockroachDbDialect`가
필요한지를 명확히 답해야 한다.

## 현재 근거

- #30에서 `CockroachDatabase`, `CockroachServer` Testcontainers smoke
  coverage를 포함하고 사용자 정의 dialect는 없는 최소
  `exposed-cockroachdb` 모듈을 도입했다.
- 현재 CockroachDB stable docs는 v26.2.2를 제공한다. CockroachDB가
  PostgreSQL wire protocol을 사용하고 PostgreSQL 구문의 대부분을 지원한다고
  설명하지만, 지원하지 않거나 다르게 동작하는 PostgreSQL 기능도 문서화한다.
- CockroachDB v26.2 문서는 `CREATE DOMAIN`, PostgreSQL range type, event,
  단일 기본 키 삭제, XML 함수, 컬럼 수준 권한, XA 구문, template database
  생성, 단일 partition 삭제, foreign data wrapper, advisory lock 의미론을
  지원하지 않거나 다르게 동작하는 영역으로 나열한다.
- CockroachDB SQL 기능 지원 문서는 기본 키, unique, check, foreign key,
  default value, index, `ALTER TABLE`, `RETURNING`, sequence, identity column을
  지원 영역으로 나열한다.
- JetBrains Exposed 1.3.0 문서는 CockroachDB를 내장 지원 데이터베이스로
  나열하지 않는다.
- 기존 bluetape4k 모듈은 허용된 SQL 영역에 이름이 지정된 Exposed dialect,
  metadata adapter, 비활성화할 기능이 필요할 때만 사용자 정의 dialect를
  등록한다(`TrinoDialect`, `DuckDBDialect`, `StarRocksDialect`).

## 범위

### 구현 범위

- 기존 `exposed/exposed-cockroachdb` 테스트에 집중된 호환성 테스트 모음을
  추가한다.
- README 파일에서 표시할 수 있고 테스트에서 검증할 수 있는 소스 수준 호환성
  매트릭스를 추가한다.
- 허용, 보류, 미지원 DDL 경계를 `README.md`와 `README.ko.md`에 반영한다.
- `CHANGELOG.md`를 갱신한다.
- 근거상 PostgreSQLDialect에 로컬 기능 override가 필요하면 최소
  `CockroachDbDialect`를 추가하고 `CockroachDatabase`에서 등록한다.

### 허용 근거 범주

호환성 테스트 모음은 다음의 허용 또는 보류 범주를 다뤄야 한다.

| 범주 | 필수 근거 |
|---|---|
| 기본 키 DDL | 기본 키 테이블에 대해 `SchemaUtils.create/drop`이 성공한다. |
| Unique constraint/index DDL | 생성/삭제가 성공하고 중복 insert가 실패한다. |
| 명시적 index DDL | 생성/삭제가 성공하고 metadata/query 경로에서 테이블을 확인할 수 있다. |
| 생성 ID | 허용된 테이블 형식에서 Exposed insert로 생성 ID를 얻을 수 있다. |
| `RETURNING` | PostgreSQL JDBC를 통한 원시 CockroachDB `INSERT ... RETURNING` smoke query가 성공한다. |
| 스키마 메타데이터 | JDBC `DatabaseMetaData`가 생성된 테이블/컬럼을 탐색할 수 있다. |

### 보류 또는 미지원 근거 범주

이 이슈의 CockroachDB 직접 테스트로 달리 입증하지 않는 한 README 매트릭스는
다음 항목을 1.11.0에서 보류 또는 미지원으로 명시해야 한다.

- 사용자 정의 CockroachDB dialect 동등성
- 완전한 PostgreSQL type 동등성
- PostgreSQL range type
- `CREATE DOMAIN`
- XML 함수/XML type 동작
- 단일 기본 키 삭제 workflow
- Schema migration diff no-op 의미론. 구현 근거에서 `SchemaUtils.create`
  이후에도 `MigrationUtils`가 생성 ID sequence ownership 변경을 제안했으므로,
  #31에서는 no-op migration 지원을 주장하지 않고 보류 항목으로 문서화한다.
- 관찰된 sequence diff 경계를 넘어서는 고급 migration 의미론
- #32가 담당하는 serializable transaction retry 헬퍼
- R2DBC 지원

## Dialect 결정 규칙

허용 근거 범주 중 하나가 Exposed의 기본 PostgreSQL dialect가 지원하지 않는
CockroachDB 기능을 지원한다고 표시하거나, 최소 CockroachDB dialect로
안전하게 고칠 수 있는 SQL을 생성한 탓에 실패하는 경우가 아니라면 1.11.0에서
헬퍼 전용 모듈 계약을 유지한다.

사용자 정의 dialect를 추가한다면 최소 범위로 제한해야 한다.

- 전역 PostgreSQL 동작을 override하지 않고 별도 dialect 이름을 등록한다.
- 안전하지 않다고 입증한 기능만 비활성화한다.
- 동작하는 PostgreSQL-wire query 및 DDL 하위 집합을 보존한다.
- `db.dialect`가 사용자 정의 dialect이고 허용된 DDL이 계속 통과함을 테스트로
  입증한다.

## 공개 API 계약

헬퍼 전용 결정을 유지하면 새로운 공개 API가 필요하지 않다. dialect를 추가하는
경우 공개 API 변경은 다음으로 제한한다.

- `io.bluetape4k.exposed.cockroachdb.dialect.CockroachDbDialect`
- 직접 근거상 필요할 때만 선택적으로 추가하는 metadata adapter

공개 KDoc는 영어로 작성하고 제한된 1.11.0 범위를 명시해야 한다.

## 테스트 계약

- 원시 container를 직접 만들지 말고 `CockroachServer.Launcher.cockroach`를
  사용한다.
- Testcontainers 기반 검증은 순차 실행한다.
- bluetape4k assertion 헬퍼를 사용한다.
- 허용된 DDL 근거에는 Exposed `SchemaUtils`를 우선 사용한다.
- 근거 범주에 대한 Exposed 직접 API가 없을 때만 원시 SQL을 사용한다
  (`RETURNING`, 지원하지 않는 PostgreSQL 기능 직접 검사, metadata 진단).
- 직접 JDBC 근거에는 테스트에서 임시 `DriverManager` 연결을 열지 말고
  bluetape4k JDBC/HikariCP 헬퍼를 사용한다.
- 재실행 결과가 결정적이도록 고유한 테이블 이름이나 정리 guard를 사용한다.
- 미지원 경로 검사는 쉽게 바뀌는 전체 오류 메시지 문자열에 의존하지 않는다.
  가능하면 SQLSTATE나 안정적이고 좁은 오류 분류를 확인한다.

## 문서 계약

두 모듈 README에 다음 내용을 포함해야 한다.

- `Supported`, `Deferred`, `Out of scope` 상태를 포함한 호환성 매트릭스
- CockroachDB는 PostgreSQL wire 호환이지만 PostgreSQL과 동등하지 않다는
  짧은 경고
- Exposed가 CockroachDB를 내장 지원 데이터베이스로 나열하지 않는다는 설명
- 정확한 검증 명령
- 관련되는 곳에 #30, #31, #32 링크

## 인수 기준

- 현재 문서 근거를 반영해 #31 GitHub issue 본문을 갱신한다.
- 새로운 호환성 테스트 모음으로
  `./gradlew :bluetape4k-exposed-cockroachdb:test --rerun-tasks --no-configuration-cache --no-daemon`
  명령이 통과한다.
- 테스트 후
  `./gradlew :bluetape4k-exposed-cockroachdb:koverXmlReport --no-configuration-cache --no-daemon`
  명령이 통과한다.
- README locale pair가 매트릭스를 문서화하고 PostgreSQL 동등성을 과장하지
  않는다.
- CHANGELOG에 호환성 경계 작업을 기록한다.
- Step 2-R, Step 3-R, Step 6-R 로컬 7단계 검토를 모두 `P0 = 0`,
  `P1 = 0`으로 마친다.
- PR 본문의 마지막 `##` section은 `## DoD Status`이다.
