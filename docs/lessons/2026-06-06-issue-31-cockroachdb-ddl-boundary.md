# 이슈 #31 CockroachDB DDL 경계 교훈

Date: 2026-06-06
Repo: `bluetape4k-exposed`

## 배경

이슈 #31은 CockroachDB를 광범위한 PostgreSQL 별칭으로 만들지 않으면서
`exposed-cockroachdb`의 실행 가능한 경계를 정의해야 했습니다.

## 결정

- 허용된 DDL 경로에는 CockroachDB Testcontainers 근거를 사용합니다.
- 새 테스트와 예제에서는 임시 `DriverManager.getConnection` 대신 `bluetape4k-jdbc`와
  HikariCP로 직접 JDBC 근거를 만듭니다.
- 기존의 간단한 URL 팩터리는 helper-only로 유지합니다. 숨은 HikariCP 생성은 풀 소유권과
  close 책임을 불명확하게 만듭니다.
- `MigrationUtils`가 생성한 ID 시퀀스 소유권 출력은 실패한 허용 DDL 경로가 아니라 보류된
  마이그레이션 diff 경계로 취급합니다.

## 결과

이 모듈은 이제 다음 지원 경계를 문서화하고 테스트합니다.

- 기본 키 DDL
- unique/index DDL
- 생성 ID
- raw `INSERT ... RETURNING`
- JDBC 메타데이터
- 보류된 마이그레이션 diff 의미론
- `CREATE DOMAIN` 및 range type처럼 보류된 미지원 PostgreSQL 구문

## 검증 근거

- `./gradlew :bluetape4k-exposed-cockroachdb:compileKotlin :bluetape4k-exposed-cockroachdb:compileTestKotlin --no-configuration-cache --no-daemon`
- `./gradlew :bluetape4k-exposed-cockroachdb:test --rerun-tasks --no-configuration-cache --no-daemon`
- `./gradlew :bluetape4k-exposed-cockroachdb:koverXmlReport --no-configuration-cache --no-daemon`
- `git diff --check`

## 향후 보호 장치

bluetape4k 저장소에 호환성 테스트를 추가할 때는 먼저 `bluetape4k-jdbc`,
`bluetape4k-junit5`, `bluetape4k-testcontainers`, Exposed helper 모듈 같은 생태계
helper를 찾아 사용하세요. raw 서드파티 또는 JDK API를 의도적으로 유지한다면 사유를
기록해야 합니다.
