# 이슈 #30 CockroachDB 모듈 교훈

## 배경

이슈 #30은 CockroachDB epic의 첫 CockroachDB 작업 단위입니다. 이 모듈은 이후의
PostgreSQL 호환성, 커스텀 dialect, DDL 경계, 트랜잭션 재시도, R2DBC 작업에 앞서 제한된
JDBC 경로를 증명해야 합니다.

## 결정 또는 발견

CockroachDB는 PostgreSQL JDBC 드라이버로 사용하고, `exposed-cockroachdb`는 작은
`CockroachDatabase` 연결 팩터리와 실제 Testcontainers 스모크 테스트로 제한합니다.
`bluetape4k-testcontainers`의 `CockroachServer`를 재사용하며 이 저장소에서 raw
Testcontainers 컨테이너를 직접 생성하지 않습니다.

## 결과

새 모듈은 `settings.gradle.kts`에서 자동 등록되고, 두 README 로캘에 문서화되며,
`AGENTS.md`에 나열되고, CI/Nightly 범위에 추가되며, `CHANGELOG.md`에 기록됩니다.
스모크 테스트는 실제 CockroachDB 컨테이너를 대상으로 연결 준비 상태, `SELECT 1`,
기본 스키마 생성/insert/select/drop 동작을 검증합니다.

## 검증

- `./gradlew projects --console=plain | rg "bluetape4k-exposed-cockroachdb|Root project"`
- `./gradlew :bluetape4k-exposed-cockroachdb:test --rerun-tasks --no-configuration-cache --no-daemon`
- `./gradlew :bluetape4k-exposed-cockroachdb:koverXmlReport --no-configuration-cache --no-daemon`
- `./gradlew :bluetape4k-exposed-cockroachdb:compileKotlin :bluetape4k-exposed-cockroachdb:compileTestKotlin --no-configuration-cache --no-daemon`
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
- `git diff --check`

## 향후 지침

이슈 #31과 #32는 분리해 유지하세요. 새로운 호환성 근거와 테스트 없이 #30의 최소 JDBC
스모크 모듈을 커스텀 dialect나 직렬화 트랜잭션 재시도 지원으로 확장하지 마세요.
