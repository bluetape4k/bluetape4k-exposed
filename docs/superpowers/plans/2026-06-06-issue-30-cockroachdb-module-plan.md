# Issue #30 CockroachDB module 계획

Spec: `docs/superpowers/specs/2026-06-06-issue-30-cockroachdb-module-design.md`

## 작업

1. `exposed/exposed-cockroachdb`를 생성한다.
   - `build.gradle.kts`를 추가한다.
   - `src/test/resources/junit-platform.properties`를 추가한다.
   - `src/test/resources/logback-test.xml`을 추가한다.
   - `:bluetape4k-exposed-cockroachdb` 등록은 `settings.gradle.kts` auto-discovery를 사용한다.

2. 최소 public API를 구현한다.
   - `CockroachDatabase`를 추가한다.
   - PostgreSQL JDBC driver class name을 사용한다.
   - `host`, `port`, `database`, `user`, `jdbcUrl`을 검증한다.
   - host/port/database, JDBC URL, `DataSource` connect overload를 제공한다.
   - English KDoc과 example을 추가한다.
   - custom dialect를 등록하거나 재정의하지 않는다.

3. Testcontainers smoke test를 추가한다.
   - `CockroachServer.Launcher.cockroach`를 사용하는 `AbstractCockroachDbTest`를 추가한다.
   - `CockroachDatabaseTest`에서 `SELECT 1`, URL 구성, validation, 단순 table의 `SchemaUtils.create/drop`을 검증한다.
   - `@Execution(SAME_THREAD)`과 bluetape4k assertion을 사용한다.
   - 필요한 경우 readiness/diagnostic 검사에만 raw SQL을 사용한다.

4. 사용자 문서를 갱신한다.
   - module `README.md`와 `README.ko.md`를 추가한다.
   - root `README.md`와 `README.ko.md` module 목록을 갱신한다.
   - `CHANGELOG.md`를 갱신한다.
   - repo-local `AGENTS.md` module 목록을 갱신한다.

5. CI/Nightly 등록을 갱신한다.
   - `cockroachdb` path filter output을 추가한다.
   - `:bluetape4k-exposed-cockroachdb:test` CI job을 추가한다.
   - 같은 module의 Nightly job을 추가한다.
   - CI/Nightly `needs`에 coverage artifact를 추가한다.
   - `actionlint`를 실행한다.

6. local에서 검증한다.
   - `./gradlew projects --console=plain`
   - `./gradlew :bluetape4k-exposed-cockroachdb:test --no-configuration-cache --no-daemon`
   - `./gradlew :bluetape4k-exposed-cockroachdb:koverXmlReport --no-configuration-cache --no-daemon`
   - `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
   - `git diff --check`

7. 검토하고 전달한다.
   - diff에 대해 local 7-tier Step 6-R review를 실행한다.
   - `docs/lessons/2026-06-06-issue-30-cockroachdb-module.md`를 추가한다.
   - Lore protocol로 commit한다.
   - branch를 push하고 `debop`에게 할당한 PR을 생성한다.
   - PR body의 마지막 section이 `## DoD Status`인지 확인한다.
   - 최종 보고 전 PR review gate에서 P0=0/P1=0을 확인한다.

## 위험과 통제

- CockroachDB는 Exposed 지원 dialect가 아니다.
  - 통제: custom dialect parity를 주장하지 않고 PostgreSQL-wire smoke 경로만 테스트한다.
- local 또는 CI에서 Testcontainers를 사용할 수 없을 수 있다.
  - 통제: serial로 실행하고 `CockroachServer`를 사용하며 Docker가 시작되지 않으면 구체적인 blocker를 기록한다.
- workflow 등록에서 coverage aggregation이 조용히 누락될 수 있다.
  - 통제: path filter, job, coverage artifact, status `needs`를 함께 확인한다.
