# Issue #31 CockroachDB DDL 경계 계획

설계 문서: `docs/superpowers/specs/2026-06-06-issue-31-cockroachdb-ddl-boundary-design.md`

## 결정

현재의 헬퍼 전용 `exposed-cockroachdb` 모듈에서 시작한다. 먼저 호환성
매트릭스와 실행 가능한 CockroachDB 테스트를 추가한다. 허용된 DDL 하위 집합이
실패하고 그 원인이 Exposed의 기본 PostgreSQL dialect가 안전하지 않은
CockroachDB 기능을 지원한다고 표시하거나 그런 SQL을 생성하는 데 있을 때만
사용자 정의 `CockroachDbDialect`를 추가한다.

## 작업

1. 내부 호환성 매트릭스를 추가한다.
   - `exposed-cockroachdb` 아래에 내부 또는 테스트에서 볼 수 있는 소스 파일을
     추가한다.
   - 범주는 `Supported`, `Deferred`, `OutOfScope`로 표현한다.
   - 기본 키 DDL, unique constraint/index DDL, 명시적 index DDL, 생성 ID,
     `RETURNING`, 메타데이터 탐색, migration diff 경계, PostgreSQL range type,
     `CREATE DOMAIN`, XML 동작, 기본 키 삭제 workflow, transaction retry,
     R2DBC에 관한 근거 메모를 포함한다.
   - 구현 근거상 달리 해야 할 이유가 없다면 매트릭스를 안정적인 공개 API에
     포함하지 않는다.

2. CockroachDB DDL 테스트를 확장한다.
   - `CockroachDdlCompatibilityTest`를 추가한다.
   - `AbstractCockroachDbTest`와 `CockroachServer.Launcher.cockroach`를
     재사용한다.
   - 허용된 Exposed DDL 경로에는 `SchemaUtils.create/drop`을 사용한다.
   - 고유한 테이블 이름과 정리 guard를 사용한다.
   - 다음 항목을 다룬다.
     - 기본 키 생성/삭제
     - unique constraint 중복 실패
     - 명시적 index 생성/삭제
     - `insertAndGetId`를 통한 생성 ID 조회
     - PostgreSQL JDBC를 통한 원시 `INSERT ... RETURNING`
     - JDBC 메타데이터의 테이블/컬럼 탐색
     - 허용된 스키마 생성 후 관찰한 migration diff 출력
   - 직접 JDBC 근거에는 임시 `DriverManager` 테스트 연결 대신 bluetape4k
     JDBC/HikariCP 헬퍼를 사용한다.

3. 지원하지 않거나 보류한 기능의 smoke check를 추가한다.
   - 로컬 검증에서 CockroachDB가 안정적인 SQLSTATE를 보고할 때만
     `CREATE DOMAIN`과 PostgreSQL range type 사용을 직접 SQL로 검사한다.
   - 그렇지 않으면 해당 기능을 범위 밖으로 문서화하고 직접 테스트를 추가하지
     않은 이유를 기록한다.
   - retry 헬퍼 테스트는 추가하지 않는다. retry 동작은 #32가 담당한다.

4. dialect 도입 여부를 결정한다.
   - 헬퍼 전용 모듈로 확장된 테스트 모음을 실행한다.
   - 허용된 DDL이 통과하면 #31에서는 사용자 정의 dialect를 추가하지 않는다.
   - Exposed dialect 기능 때문에 허용된 DDL이 실패하면 근거상 필요한 최소
     `CockroachDbDialect` override와 등록 경로를 추가한다.
   - 허용된 DDL은 성공하고 migration diff만 불필요한 차이를 계속 만들면,
     성급하게 dialect를 추가하지 말고 해당 항목을 보류 상태로 유지하면서
     sequence ownership diff를 문서화한다.
   - dialect를 추가하면 `db.dialect` assertion과 KDoc도 추가한다.

5. 문서를 갱신한다.
   - `exposed/exposed-cockroachdb/README.md`를 갱신한다.
   - `exposed/exposed-cockroachdb/README.ko.md`를 갱신한다.
   - 매트릭스, 공식 문서의 주의 사항, Exposed 지원 데이터베이스 관련 주의
     사항, 검증 명령을 추가한다.
   - `CHANGELOG.md`를 갱신한다.

6. 조사 근거를 보존한다.
   - 이 이슈에서 사용한 CockroachDB/Exposed 공식 문서에 관한 간결하고
     지속적인 조사 메모를 추가한다.
   - 원문 인용은 짧게 유지하고 공식 페이지를 링크한다.

7. 로컬에서 검증한다.
   - `./gradlew :bluetape4k-exposed-cockroachdb:test --rerun-tasks --no-configuration-cache --no-daemon`
   - `./gradlew :bluetape4k-exposed-cockroachdb:koverXmlReport --no-configuration-cache --no-daemon`
   - `./gradlew :bluetape4k-exposed-cockroachdb:compileKotlin :bluetape4k-exposed-cockroachdb:compileTestKotlin --no-configuration-cache --no-daemon`
   - `git diff --check`

8. 검토하고 전달한다.
   - 최종 diff에 Step 6-R 로컬 7단계 검토를 수행한다.
   - `docs/lessons/2026-06-06-issue-31-cockroachdb-ddl-boundary.md`를 추가한다.
   - Lore protocol에 따라 커밋한다.
   - 브랜치를 push하고 `debop`에게 할당한 PR을 생성한다.
   - 가능하면 milestone `1.11.0`과 #31에 맞는 label을 설정한다.
   - 라이브 PR 본문을 확인하고 마지막 `##` heading이 `## DoD Status`인지
     검증한다.
   - Step 6-R/Step 7-R gate 근거를 PR comment와 formal review로 추가한다.
   - PR CI를 모니터링하고 최종 Step 9 DoD를 보고한다. 사용자 요청 없이
     merge하지 않는다.

## 검증 기대 사항

- 허용된 DDL 하위 집합은 PostgreSQL이 아닌 실제 CockroachDB Testcontainers
  인스턴스에서 통과해야 한다.
- README 매트릭스의 각 항목은 테스트나 명시적인 공식 문서 근거로 추적할 수
  있어야 한다.
- PR은 PostgreSQL dialect와 완전히 동등하다고 주장해서는 안 된다.
- Testcontainers 검증은 순차 실행을 유지해야 한다.

## 위험과 통제

| 위험 | 통제 |
|---|---|
| CockroachDB는 많은 PostgreSQL 경로를 허용하지만 edge case에서 다르게 동작한다. | 매트릭스를 근거 중심으로 유지하고 테스트한 경로로 제한한다. |
| 사용자 정의 dialect가 공개 영역을 너무 일찍 늘린다. | 헬퍼 전용 방식을 기본값으로 유지하고, 근거가 있을 때만 dialect를 추가한다. |
| 지원하지 않는 SQL의 오류 메시지가 버전에 따라 달라진다. | SQLSTATE나 안정적인 예외 클래스를 우선 사용하고, 그렇지 않으면 깨지기 쉬운 테스트 없이 지원하지 않는 경로를 문서화한다. |
| Exposed 버전에 따라 schema diff API가 다르다. | 정확한 API를 compile/test한다. 헬퍼 전용 dialect가 불필요하지만 비파괴적인 sequence diff를 만들면 보류 항목으로 문서화하고 근거를 기록한다. |
| Testcontainers가 불안정하다. | 기존 singleton `CockroachServer`, 순차 실행, 제한된 readiness, `--rerun-tasks` 검증을 사용한다. |
