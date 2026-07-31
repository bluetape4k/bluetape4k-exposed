# Issue #322 Exposed 마이그레이션 드리프트 검증 설계

## 상태

- 날짜: 2026-07-17
- 저장소: `bluetape4k/bluetape4k-exposed`
- 브랜치: `feat/issue-322-migration-drift`
- 기준: `origin/develop@38d13d9`
- 이슈: [#322](https://github.com/bluetape4k/bluetape4k-exposed/issues/322)
- 작업 유형: 빌드, JDBC, R2DBC, 문서, CI 전반의 검증 강화인 Type A
- 사용자 승인 방향: 마이그레이션 경로는 옵트인으로 유지하고, JDBC와
  R2DBC를 모두 다루며, H2/PostgreSQL/MySQL 8을 실행하고, 제한 사항을
  문서화하며, 검증 후 PR 생성

## 문제

저장소는 이미 Spring JDBC 및 R2DBC 데모에 JetBrains의
`org.jetbrains.exposed.plugin`을 적용하고, 생성된 V1 스크립트를 체크인하며,
경로가 지정된 `Migration Smoke` 워크플로를 실행하고 있다. 이는 작업이 고정된
파일 이름을 전달받을 때 빈 H2 데이터베이스에서 모델로부터 스크립트를 생성할
수 있음을 증명한다. 그러나 기존 데이터베이스와 변경된 Exposed 모델이 감지
가능하고 검토 가능한 스키마 차이를 생성한다는 것은 증명하지 못한다.

이 구분이 중요한 이유는 생성된 마이그레이션 SQL이 권고 사항이기 때문이다.
Exposed는 파괴적인 구문을 생성할 수 있으며, 메타데이터 비교는 dialect에
민감하다. 빈 데이터베이스 생성 작업이 성공했다고 해서 production
PostgreSQL 또는 MySQL 스키마가 현재 모델과 일치한다는 증거로 제시해서는
안 된다.

## 현재 증거

### 저장소 상태

- 루트 `build.gradle.kts`는 중앙 Exposed 플러그인 alias를
  `apply false`와 함께 선언한다.
- `examples/jdbc-demo/build.gradle.kts`와
  `examples/r2dbc-demo/build.gradle.kts`는 플러그인을 적용한다.
- 두 데모의 플러그인 구성은 PostgreSQL 호환 모드의 JDBC H2 URL을 통해
  연결한다. R2DBC 데모는 빌드 시점 마이그레이션 생성에 여전히 JDBC를
  사용하며, 애플리케이션 런타임은 R2DBC이다.
- `.github/workflows/migration-smoke.yml`은 명시적인 `--filename` 값을
  사용해 체크인된 두 V1 파일을 재생성하고 Git diff가 있으면 실패한다.
- `exposed/jdbc-tests`는 이미 `exposed-migration-jdbc`에 의존하며 H2,
  PostgreSQL, MySQL 8 fixture를 제공한다.
- `exposed/r2dbc-tests`는 이미 `exposed-migration-r2dbc`에 의존하며 이에
  대응하는 R2DBC fixture를 제공한다.
- 기준점 H2 JDBC/R2DBC 테스트는 `38d13d9`에서 통과한다.

### 결정성 관찰

`--filename` 없이 `generateMigrations`를 실행하면 호출할 때마다
timestamp 이름의 SQL 파일이 생성되었다. 고정된 파일 이름으로 실행하면 동일한
경로를 덮어쓴다. 따라서:

- 고정 파일 이름과 `git diff --exit-code`는 체크인된 스크립트 smoke에
  결정론적으로 적합하다.
- 플러그인의 기본 파일 이름은 의도적으로 시간에 따라 달라지므로
  deterministic하다고 불러서는 안 된다.
- 결과를 Git과 비교할 때 문서와 CI 명령은 항상 고정된 파일 이름을 제공해야
  한다.

### Upstream 계약

[Exposed 1.3.1 마이그레이션 문서](https://www.jetbrains.com/help/exposed/migrations.html)는
서로 구분되는 두 가지 surface를 설명한다.

1. Gradle 플러그인은 Exposed 테이블 정의를 데이터베이스 스키마와 비교하고
   마이그레이션 스크립트를 작성한다.
2. JDBC 및 R2DBC `MigrationUtils` API는 하위 수준의 스키마 비교, 구문 생성,
   검증을 위한 building block을 제공한다.

동일한 문서는 생성된 결과에 `CREATE`, `ALTER`, `DROP` 및 기타 파괴적인
작업이 포함될 수 있으므로 수동 검토가 필요하다고 요구한다. 또한 전체
column type-change 지원은 현재 H2로 제한된다고 명시한다.

다음 두 upstream 이슈도 여전히 관련이 있다.

- [JetBrains/Exposed#377](https://github.com/JetBrains/Exposed/issues/377)은
  편리한 비변경 스키마 equality assertion을 요청하며 아직 열려 있다.
- [JetBrains/Exposed#2441](https://github.com/JetBrains/Exposed/issues/2441)은
  PostgreSQL varchar-to-text type change가 마이그레이션 구문을 생성하지
  않았다고 보고하며 아직 열려 있다.

따라서 저장소는 Exposed 1.3.1이 안정적으로 지원하는 동작만 gate하고,
잘못된 확신을 코드에 반영하는 대신 지원되지 않거나 불완전한 비교를
문서화해야 한다.

## 목표

1. H2, PostgreSQL, MySQL 8에서 JDBC 및 R2DBC `MigrationUtils`가 additive
   schema change를 감지한다는 것을 증명한다.
2. 생성된 additive statements를 적용하면 스키마가 수렴하여 다음 diff가
   비어 있음을 증명한다.
3. 고정된 파일 이름을 사용해 체크인된 데모 마이그레이션 생성의 결정성을
   유지한다.
4. 실제 데이터베이스 검사는 옵트인 또는 scheduled 방식으로 유지하고
   필수 daily gate로 만들지 않는다.
5. Gradle 플러그인이 사용하는 build-time JDBC 연결, programmatic/test-time
   JDBC 및 R2DBC 비교 API, dialect 제한 사항, 파괴적 출력 검토, migration-runner
   소유권을 설명한다.

## 목표가 아닌 것

- 새로운 published module 또는 public bluetape4k API 추가.
- production에서 생성된 SQL을 자동으로 적용.
- Flyway, Liquibase 또는 애플리케이션 소유의 마이그레이션 프로세스 대체.
- H2 PostgreSQL mode를 PostgreSQL 호환성 증명으로 간주.
- upstream 제한 사항이 남아 있는 동안 PostgreSQL/MySQL column type changes를
  필수 게이트로 적용.
- 중앙 Exposed 버전 변경 또는 해당 버전을 로컬에 중복 선언.
- 이슈에서 지원되는 모든 Exposed dialect 테스트.

## 검토한 접근 방식

### A. 기존 데모 플러그인 smoke만 확장

두 Spring 데모를 중심으로 생성된 파일과 workflow invocation을 추가한다.

장점:

- 가장 작은 code diff.
- 실행 가능한 예제에서 모든 동작을 확인할 수 있다.

기존 빈 데이터베이스 스크립트는 변경된 스키마를 실행하지 않으며, R2DBC
데모의 플러그인 생성도 여전히 JDBC metadata를 사용하고, 체크인된 SQL
파일을 늘리는 것만으로는 runtime-specific `MigrationUtils` 동작을 증명할
수 없으므로 기각했다.

### B. 전용 migration-verification Gradle module 추가

JDBC/R2DBC fixture와 dialect test를 포함하는 새로운 internal module을
생성한다.

장점:

- 마이그레이션 검증을 재사용 가능한 테스트 인프라와 분리한다.
- 하나의 명확한 task namespace를 제공한다.

저장소에는 이미 필요한 마이그레이션 의존성, 데이터베이스 selector, 그리고
Testcontainers launcher를 갖춘 JDBC 및 R2DBC test module이 있으므로 기각했다.
새 module은 설정을 중복하고 불필요한 settings, CI, BOM, manual-inventory 및
publication 위험을 초래한다.
### C. 기존 JDBC/R2DBC 테스트 모듈에 drift 회귀 테스트 추가

체크인된 파일의 결정성을 검증하기 위한 demo plugin smoke를 유지하고, 기존 데이터베이스 테스트 인프라에 스키마 진화 테스트를 추가한다.

장점:

- 정확한 JDBC 및 R2DBC migration API를 실행한다;
- 기존 H2/PostgreSQL/MySQL 8 fixture를 재사용한다;
- 새 모듈이나 public API가 필요하지 않다;
- 고정 파일 생성과 실제 데이터베이스 비교를 분리한다;
- 빠른 H2 PR 검증과 순차적인 scheduled 실제 데이터베이스 검증을 지원한다.

이 접근 방식을 채택한다.

## 아키텍처

### 계층 1: 결정론적 플러그인 스모크 테스트

두 demo 모듈은 plugin 예제로 유지한다. CI는 기존 고정 V1 파일명을 사용하여 항상
`generateMigrations`를 호출한다. 호출 전에 예상되는 두 파일만 제거하고, 두 task 모두 plugin 전용
`--rerun` 옵션과 함께 `--no-build-cache`, `--no-daemon`을 사용하며, CI는 두
파일이 다시 생성되었는지 요구한다. 그런 다음 두 migration 디렉터리만 검사한다. 제한된
`git status --porcelain --untracked-files=all`
검사를 사용하여 tracked 변경과 모든
untracked 파일에서 실패하므로, 예상하지 못한 timestamped 파일이나 두 번째 SQL 파일이
눈치채지 못한 채 통과할 수 없다.

이 계층은 다음 질문에 답한다: "현재 model과 plugin 버전이 검토된 baseline script를 예상치 못하게 변경했는가?"

다음 질문에는 답하지 않는다: "배포된 데이터베이스가 model과 일치하는가?"

### Layer 2: JDBC 및 R2DBC schema evolution regression

각 테스트 인프라 모듈에 하나의 집중된 test class를 추가한다. 각 테스트는 동일한 physical table name을
가지는 두 개의 일반 `Table` 객체와 의도적으로 최소화하고 고정한 schema를 사용한다.

- baseline model: `integer("id")`, 명시적인 `PrimaryKey(id)`, 그리고 필수
  `varchar("name", 64)` column 하나;
- evolved model: baseline column에 nullable한
  `varchar("description", 255)`을 추가;
- 두 model에서 제외: auto-increment/identity column, default, sequence, reference, secondary index, generated constraint name.

JDBC와 R2DBC는 서로 다른 physical table name을 사용하며, H2 type-change characterization은 세 번째 table을 사용한다. 이를 통해 sequence나 index와 같은 무관한 metadata가 additive-only proof를 확장하지 못하도록 한다.

각 enabled dialect에 대해 테스트는 repository의 기존 database fixture 내부에서 다음 lifecycle을 수행한다.

1. baseline table을 생성한다;
2. evolved model에 대한 migration statement를 요청한다;
3. 정확히 하나의 generated statement를 요구하고, 실행 전에 test-only additive-DDL validator로 전체 statement를 검증한다;
4. case와 whitespace를 정규화한 후, unquoted, double-quoted 또는 backtick-quoted table 및 column token이 정확히 일치하도록 요구하고, 다음만 허용한다:
   `ALTER TABLE <fixture> ADD [COLUMN] <expected-column> VARCHAR(255) NULL`;
5. comment, 여러 개 또는 trailing semicolon, compound clause, 추가 operation, 다른 table/column, `DROP`, `TRUNCATE`, `DELETE`, removal/rename/type change, `DEFAULT`, `NOT NULL`, `GENERATED`, `REFERENCES`, `CONSTRAINT`, `CHECK`, `UNIQUE`, `PRIMARY KEY`, `COLLATE`, comma, trailing operation, 그리고 다른 object를 대상으로 하는 모든 statement를 거부한다;
6. 허용된 statement를 일치하는 JDBC 또는 R2DBC transaction 내부에서 실행한다;
7. migration statement를 다시 요청한다;
8. 두 번째 결과가 비어 있음을 assert한다;
9. 독립적인 top-level cleanup에서 physical table을 drop하고, 더 이상 존재하지 않음을 assert한다.

validator에는 대표적인 H2, PostgreSQL, MySQL 8 형식에 대한 unit case와 comment, compound DDL, extra semicolon, 예상하지 못한 identifier, destructive verb를 포함하는 negative case가 있다. 그 외의 assertion은 정확한 vendor SQL text를 요구하지 않고, 관찰 가능한 lifecycle을 검증한다: 적용 전에는 drift가 존재하고 적용 후에는 사라진다.

두 test class 모두
`org.jetbrains.exposed.v1.core.ExperimentalDatabaseMigrationApi`를 import하고 opt-in한다. JDBC는
`org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils`를, R2DBC는
`org.jetbrains.exposed.v1.migration.r2dbc.MigrationUtils`를 import하며, 두 class 모두
`statementsRequiredForDatabaseMigration(EvolvedTable, withLogs = false)`를 호출한다.
정확히 검증된 string만 `JdbcTransaction.exec` 또는 suspending
`R2dbcTransaction.exec`에 도달한다. Exposed 1.3.1이 선택한 dialect에 대해 다른 additive type tail을 출력하면, implementation은 allowlist를 조용히 확장하지 않고 review를 위해 중단한다.

Cleanup은 H2, PostgreSQL, MySQL에서 DDL rollback semantics가 서로 다르므로 transaction rollback에 의존하지 않는다. private test helper는 primary throwable을 보존하고, 두 번째 database-fixture 호출을 통해 cleanup을 수행하며, `primary.addSuppressed(cleanup)`로 cleanup failure를 추가한 뒤 primary를 다시 throw한다. Non-database unit case는 primary-only, cleanup-only, dual failure 동작을 검증한다. Cleanup-only failure는 직접 throw한다. Suspending variant는 cancellation/inactive-context cleanup에 대해서만 `NonCancellable`에 진입하므로, cancellation이 두 번째 fixture 호출을 건너뛸 수 없으며 일반적인 throwable identity는 그대로 유지된다.

H2-only characterization은 추가로 column을 bounded varchar에서 text로 변경하고 type-altering statement가 생성됨을 assert한다. Exposed 1.3.1이 해당 환경에서 full type-change detection을 보장하지 않으므로 PostgreSQL과 MySQL에는 이 hard assertion을 적용하지 않는다. 별도의 type-change table은 additive fixture와 동일한 failure-preserving top-level cleanup contract 및 post-cleanup absence assertion을 사용한다.
### 레이어 3: 옵트인 검증 워크플로

집중 회귀 테스트에 `migration-drift` 태그를 지정한다. 기본 `test` 태스크에서는
해당 태그를 제외하므로, 재시도 루프를 포함한 기존의 광범위한 Nightly 작업이
마이그레이션 증거를 숨기거나 중복할 수 없다. 각 테스트 모듈은 태그를 포함하고
`EXPOSED_TEST_DB`를 입력으로 선언하며, `outputs.upToDateWhen { false }`와
`outputs.cacheIf { false }`를 모두 적용해 본질적으로 live-only인 전용
`migrationDriftTest` 태스크를 등록한다. 따라서 모든 실행은 새로운 마이그레이션
테스트를 실행하며, 종속 컴파일 및 리소스 태스크는 up-to-date 상태를 유지하거나
일반 캐시를 사용할 수 있다.

각 전용 태스크는 일반 테스트 `SourceSet`을 사용하고,
`useJUnitPlatform { includeTags("migration-drift") }`를 명시적으로 구성하며,
`build/test-results/migrationDriftTest/binary`를 바이너리 결과 디렉터리로,
`build/test-results/migrationDriftTest`를 XML 출력 디렉터리로 지정한다. 일반
`test`는 해당 태그를 명시적으로 제외한다. 전용 JUnit XML은 필수이고 HTML은
비활성화하며, `reports.junitXml.includeSystemOutLog`와
`includeSystemErrLog`는 모두 false로 설정하여 워크플로 스테이징에 명확하게
정의된 하나의 정제된 보고서 소스만 존재하도록 한다. 태스크는 `H2`,
`POSTGRESQL`, 또는 `MYSQL_V8`만 허용하고, 그 밖의 공백이 아닌 값은 모두
즉시 실패시키며, 저장소 전체 Test mutex에 참여하고, 집중 테스트 워커 힙을
2 GiB로 제한한다.

검증을 상호 보완적인 두 가지 증명 수준으로 정제한다.

- `Migration Smoke`는 일치하는 pull-request 경로와 수동 디스패치에서만 실행한다. Nightly와 조정되지 않은 중복 실행을 피하기 위해 독립적인 주간 스케줄을 제거한다. 독립적인 두 작업인 `demo-migrations`와 `h2-drift`를 사용한다. H2 작업은 안정적인 ID를 사용하는 JDBC 및 R2DBC 단계에 `continue-on-error: true`와 `timeout-minutes: 10`을 설정하고, 아티팩트를 별도로 업로드하며, 최종 `if: always()` 결과 확인 단계를 두어 하나의 API 실패가 다른 API의 증거를 억제하지 않도록 한다. `demo-migrations`에는 15분의 작업 타임아웃을, `h2-drift`에는 30분의 작업 타임아웃을 지정하여, 제한된 두 H2 시도 이후 보고서 업로드와 집계에 필요한 여유 시간을 남긴다.
- 경로 계약에는 `exposed/jdbc-tests/**`,
  `exposed/r2dbc-tests/**`, 루트 `README.md`와 `README.ko.md`, demo
  빌드/모델/마이그레이션 경로, 루트 `build.gradle.kts`,
  `settings.gradle.kts`, `gradle.properties`, `gradle/**`, 그리고 워크플로
  파일 자체가 포함된다. 이러한 경로는 주간 smoke가 이제 존재하지 않는
  상황에서 로컬 플러그인 선언, catalog import/tag authority, 태스크 기본값,
  wrapper/build 구성을 포괄한다.
- 신뢰할 수 없는 PR 경계를 유지한다. `pull_request`를 사용하고 절대로
  `pull_request_target`을 사용하지 않는다. 워크플로 및 작업 권한은
  `contents: read`만 유지한다. 시크릿이나 운영/공유 엔드포인트를 노출하지
  않는다. 두 작업 모두에서 `gradle/actions/setup-gradle`을
  `cache-read-only: ${{ github.event_name == 'pull_request' }}`와 함께
  구성한다. 모든 checkout은 `persist-credentials: false`를 사용한다.
- H2 단계에서는 JUnit XML system-out/system-err 캡처를 비활성화하고 상태,
  정제된 명령 요약, XML만 스테이징한다. 원시 HTML과 원시 명령 로그는
  업로드하지 않는다. 테스트 전 설정에서는 API별 `status.txt=started`
  마커를 생성하고, 항상 실행되는 결과 수집기는 제한된 시도 이후 두 GitHub
  단계 결과를 모두 기록하며 timeout/cancelled 상태도 포함한다. 업로드 전에
  스테이징된 XML과 요약을 대상으로 fail-closed 민감 패턴 스캔을 수행한다.
  작업은 `if: always()`, `retention-days: 14`, `if-no-files-found: error`로
  아티팩트를 업로드한다. JDBC는 `migration-drift-jdbc-h2`, R2DBC는
  `migration-drift-r2dbc-h2`를 사용하며, 각각 별도의
  `build/migration-drift-reports/h2/<api>/**` 스테이징 디렉터리에서 업로드한다.
- 전체 Nightly 범위는 `build` 이후 전용
  `migration-drift-real-databases` 작업을 하나 추가한다. 단일 runner가
  JDBC PostgreSQL, R2DBC PostgreSQL, JDBC MySQL 8, R2DBC MySQL 8을 해당
  순서로 재시도 없이 실행하며, `--no-parallel`, `--max-workers=1`,
  `--no-daemon`을 사용한다. 작업에는 `timeout-minutes: 60`을 지정한다.
  이 예산에는 selector 계약의 companion H2 실행, 컨테이너 시작, 컴파일 및
  보고서 스테이징을 의도적으로 포함한다.
- 작업은 기존 전체 범위 조건을 정확히 사용한다. 일요일 스케줄
  `3 19 * * 0` 또는 `inputs.scope == 'full'`인 수동 디스패치다.

  ```yaml
  if: ${{ (github.event_name == 'schedule' && github.event.schedule == '3 19 * * 0') || (github.event_name == 'workflow_dispatch' && inputs.scope == 'full') }}
  ```

  작업은 기존 Testcontainers/Gradle 환경을 유지한다.
  `TESTCONTAINERS_RYUK_DISABLED=true`,
  `DOCKER_HOST=unix:///var/run/docker.sock`, 그리고 Nightly의
  `GRADLE_OPTS` JVM 메모리 설정을 사용한다. 각 선택 단계는 정확한
  `EXPOSED_TEST_DB=POSTGRESQL` 또는 `EXPOSED_TEST_DB=MYSQL_V8` 값을 설정한다.
- 네 가지 선택은 안정적인 ID와 `continue-on-error: true`를 사용하는
  독립적인 단계로 구현한다. 각 단계는 Gradle 상태를 캡처하고, 테스트
  실패 이후에도 정제된 JUnit XML, 상태, `command-summary.log`를
  `build/migration-drift-reports/<api>-<database>` 아래에 스테이징한다.
  HTML은 절대로 스테이징하지 않는다. Gradle 시작 전에 단계가 보고서
  디렉터리를 생성하고 명령/선택 메타데이터를 기록한다. 각 shell은 정확한
  실패 안전 패턴을 사용한다. `set -o pipefail`, `set +e`를 설정하고,
  Gradle stdout/stderr를 `tee`를 통해 runner 임시 raw log로 전달하면서
  Actions 콘솔에는 `tee` 출력이 표시되지 않도록 억제한다. 이어서
  `gradle_status=${PIPESTATUS[0]}`를 캡처하고, 별도로 캡처한
  `evidence_status` 아래의 보호된 non-errexit 구간에서 증거 조립을 계속한다.
  두 상태를 모두 기록하고, 먼저 0이 아닌 Gradle 상태로 종료하며, 그렇지
  않으면 증거 상태로 종료한다. 최종 집계는 두 상태 중 하나라도 실패로
  처리한다. 이를 통해 `tee` 또는 증거 조립이 Gradle 결과를 대체하거나
  숨기는 것을 방지하고, `errexit`가 증거 스테이징을 건너뛰는 것도 방지한다.
- 원시 Gradle/driver 로그는 절대로 업로드하거나 Actions 콘솔에 출력하지
  않는다. trap과 일반 경로가 임시 raw log를 삭제한다. 삭제 전에 허용 목록에
  포함된 태스크 결과, 빌드 결과, 테스트 수, wrapper가 출력한 lifecycle
  label로 `command-summary.log`를 생성한다. 스테이징 전에 허용 목록에
  포함된 텍스트라도 JDBC/R2DBC URL authority/userinfo/query 값,
  user/password 속성, 토큰, 홈 디렉터리 경로를 redaction한다.
  따라서 `status.txt`와 허용 목록 기반 요약은 자격 증명이나 로컬 식별자를
  게시하지 않고도 dependency, 컴파일 및 컨테이너 시작 실패 증거를 보존한다.
  각 선택에는 `timeout-minutes: 12`를 지정한다. 60분 작업 예산은 네 가지
  제한된 시도 이후 설정, 스테이징, 업로드 및 집계를 위한 여유 시간을 남긴다.
  `if: always()` 결과 수집 단계는 각 GitHub 단계 결과를 해당
  `status.txt`에 기록하며, 명령이 초기 `started` 마커를 대체할 수 없었던
  timeout도 포함한다. 네 번의 시도가 모두 끝난 후 `if: always()` 업로드
  단계가 `build/migration-drift-reports/**`에서
  `migration-drift-real-databases` 아티팩트를 게시하며,
  `retention-days: 14`와 `if-no-files-found: error`를 사용한다. 최종
  `if: always()` 단계는 네 단계 결과 중 하나라도 `success`가 아니면 실패한다.
- 빠른 통합 H2 태스크는 PR 전달 전에 필수다. 동일한 네 가지 로컬 선택을
  같은 순서로 완료하거나, 정확한 브랜치 head에서 수동으로 디스패치한
  전체 Nightly 실행이 성공해야 실제 데이터베이스 증명을 완료할 수 있다.
  각 선택에는 기존 selector의 companion H2 사례가 포함되며, display
  name/assertion이 실제 dialect를 증명한다.

companion H2 반복은 허용된 fixture 제약이다. 네 개의 짧은 사례를 절약하기
위해 exact-dialect selector만 추가하면 기존 테스트 인프라의 의미 범위가
확장된다. 60분 CI 예산과 로컬 명령 안내는 이를 명시적으로 고려한다.

워크플로는 경로 범위가 지정되고 필수 항목이 아니다. 이는 마이그레이션
호환성을 위한 증거이지, 저장소 전체에 적용되는 필수 마이그레이션 정책이
아니다. PR 준비에는 Migration Smoke 확인이 required check가 아님을
확인하는 live ruleset/branch-protection 확인이 포함된다. `develop` 브랜치
보호의 required-status contexts와 페이지가 매겨진 모든 저장소 ruleset을
조회하고, 각 ruleset detail을 가져와 `develop`에 적용되는 active
enforcement 및 target conditions로 필터링한 뒤 status-check rules를
추출한다. classic branch-protection 404는 명시적인 부재로 처리한다.
조회 시간, 엔드포인트, ruleset ID/enforcement/conditions, 반환된 contexts,
정확한 PR head checks를
`docs/review/2026-07-17-issue-322-exposed-migration-drift-review.md`에 기록한다.
## 문서 설계

현재 develop 동작을 `README.md`와 `README.ko.md`의 동등한 마이그레이션 섹션에 문서화한다. 안정 매뉴얼 페이지를 추가하거나 `docs/manual/manifest.yaml`을 편집하지 않는다. 안정 매뉴얼은 릴리스 1.11.0에 고정되어 있으며, 새로운 1.12 전용 작업과 워크플로는 해당 릴리스 ref에 존재하지 않는다. 매뉴얼 승격은 정확한 릴리스 ref와 commit이 존재한 후 1.12 릴리스 마감 단계에서 수행한다.

두 README 섹션은 대상 독자에 따라 나뉘되, 대응하는 heading, command, warning, support matrix, upstream link, review checklist를 동일하게 포함한다.

1. **애플리케이션 사용자:** configuration과 credential을 관리하고, 애플리케이션이 관리하는 위치에 output을 작성하며, 적용된 migration을 절대 덮어쓰지 않고, 변경마다 새롭고 단조 증가하는 filename을 사용하고, SQL을 검토한 다음 Flyway, Liquibase 또는 다른 애플리케이션 migration runner에 전달한다.
2. **Repository contributor:** 교체 가능한 fixed-V1 demo baseline과 전용 H2/PostgreSQL/MySQL drift regression task를 실행한다. Fixed V1 file은 repository fixture이며 애플리케이션 migration naming example이 아니다.

애플리케이션 사용자 경로에는 자체적으로 완결된 직접 `org.jetbrains.exposed.plugin` 1.3.1 declaration과 catalog를 import하는 애플리케이션을 위한 동등한 선택적 `alias(bt4k.plugins.exposed.plugin)`, `tablesPackage`, `fileDirectory`, 일치하는 JDBC `runtimeOnly`, 그리고 `MIGRATION_JDBC_URL`, `MIGRATION_DB_USER`, `MIGRATION_DB_PASSWORD`라는 이름의 provider를 포함한 복사하여 바로 사용할 수 있는 `exposed.migrations` Kotlin DSL configuration이 포함된다. 이 configuration은 애플리케이션이 관리하는 directory에 기록하며, repository V1 fixture 중 어느 것도 사용하지 않고 새롭고 변경 불가능한 단조 증가 filename을 사용한다. Shell example은 먼저 `MIGRATION_FILE`을 설정하고, 짧은 short-circuit `test ! -e ... &&` guard로 target이 존재하지 않음을 입증한 뒤에만 `--filename="$MIGRATION_FILE"`을 전달한다. R2DBC 애플리케이션을 위한 보충 note에는 build-time plugin comparison에 여전히 JDBC URL과 JDBC driver가 필요하며, R2DBC URL이나 runtime driver만으로는 충분하지 않다는 내용이 명시된다. Example에서는 commit된 credential과 production/shared endpoint를 금지한다.

인접한 availability callout은 upstream Exposed 1.3.1 plugin capability와 이 repository의 전용 `migrationDriftTest` task 및 CI를 구분한다. 후자는 `develop`에서 사용할 수 있으며 bluetape4k-exposed 1.12.0에서 처음 제공된다. 영어와 한국어 문구는 동등하게 유지해야 한다.

독자에게 보이는 경계는 명시적이다.

| 표면 | 연결과 목적 | 금지되는 추론 |
|---|---|---|
| Gradle plugin | Build-time JDBC metadata connection 및 script generation | R2DBC를 통해 연결하거나 production migration을 적용하지 않는다 |
| JDBC `MigrationUtils` | Programmatic/test-time JDBC schema comparison | startup 또는 request-path schema management로 실행하지 않는다 |
| R2DBC `MigrationUtils` | Programmatic/test-time R2DBC schema comparison | startup 또는 request-path schema management로 실행하지 않는다 |

Support matrix에서는 additive column을 "proved here"로 표시하고, H2 type change를 "characterized only"로 표시하며, PostgreSQL/MySQL type change, rename/removal, default, index, foreign/unique/check constraint 및 vendor-specific DDL을 "not guaranteed"로 표시한다. 빈 diff는 오직 "이 API와 version에서 difference가 감지되지 않음"을 의미할 뿐, "schema가 동일함"을 의미하지 않는다.

Review checklist에는 세 가지 category가 있다.

- schema safety: `DROP`/`TRUNCATE`, removal/rename/type change, `NOT NULL`, default, index, unique/foreign/check constraint 및 statement order;
- data safety: backfill correctness, production-shaped row volume, table rewrite 및 data reinterpretation risk;
- rollout safety: lock duration, phased nullable-add/backfill/constraint enforcement, database transaction support, backup, rollback 및 migration runner ownership.

모든 command에는 prerequisite, 생성된 file/report location, pass가 증명하는 내용, 증명하지 않는 내용 및 첫 번째 diagnostic command가 포함된다. Raw SQL은 promotion 전에 disposable 또는 staging copy를 대상으로 검토한다.

집중형 Ruby parity validator와 그 self-test는 두 README에서 표시된 migration-section heading, shell/Kotlin fence, table row key, command 및 URL을 추출하고 normalize한다. 의미상 불일치가 있으면 validation이 실패한다. 별도의
`docs/superpowers/checklists/2026-07-17-exposed-1.12-manual-promotion-checklist.md`
는 1.12 release/publish workflow가 소유하며, 정확한 release ref와 commit이 존재할 때까지 pending 상태로 유지된다. 이후 gate는 영어와 한국어 매뉴얼을 함께 승격하고 manifest, inventory, parity 및 release-manual validation을 실행한다. 해당 promotion은 issue #322의 범위 밖이다.

## 실패 처리

### 생성된 file drift

Fixed-filename generation이 checked-in file을 변경하면 CI가 Git diff와 함께 실패한다. Contributor는 SQL을 검토하고 file을 의도적으로 업데이트하거나 의도하지 않은 model/plugin change를 수정한다.

### Additive drift가 감지되지 않음

Focused regression은 생성된 SQL이 적용되기 전에 실패한다. 이는 선택된 Exposed version 또는 dialect의 compatibility regression이며 migration lane을 차단한다.

### 생성된 SQL이 수렴하지 않음

생성된 additive statement가 allowlist를 통과하지 못하거나, 실행에 실패하거나, 두 번째 comparison에서도 drift를 보고하면 unsafe continuation 전에 regression이 실패한다. Assertion output에는 API, 선택된 dialect, lifecycle stage 및 생성된 statement count가 tag로 표시된다. Raw SQL은 synthetic fixture schema에 대해서만 log하며, connection URL, credential 및 production identifier는 절대 log하지 않는다. 예상치 못한 statement는 normalize하거나 redact한다. Test는 empty 또는 관련 없는 statement list를 허용하도록 assertion을 확장하지 않는다.

### Container 또는 network failure

CI는 repository의 disposable Testcontainers database와 test-database-scoped identity만 사용한다. Production/shared endpoint와 repository secret은 금지된다. Test 외부에서 metadata를 비교하는 사용자는 database가 허용하는 경우 read-only account를 사용해야 한다. 생성된 DDL은 narrowly scoped DDL right가 있는 disposable/staging database에서만 실행한다.

기존의 broad Nightly job은 retry할 수 있지만, tag가 지정된 migration-drift test는 해당 job에서 제외된다. 전용 sequential migration job은 retry하지 않는다. Container 또는 network failure가 발생하면 고유한 API/dialect artifact는 실패한 evidence로 남는다. Failure를 분류한 후에만 rerun하며, 두 run URL을 모두 유지한다. 이후의 pass가 이전 결과를 지우지는 않는다.

### 지원되지 않는 type change

PostgreSQL/MySQL type-change output은 hard gate로 사용하지 않는다. README는 공식 limitation과 upstream issue를 link하여 사용자가 이러한 변경을 database-native tooling과 manual review로 검증해야 함을 알 수 있도록 한다.

### 파괴적인 생성 구문

Test는 isolated additive fixture만 적용한다. 문서에서는 임의로 생성된 output을 자동 적용하는 것을 금지하며, script를 애플리케이션의 migration authority에 전달하기 전에 review하도록 요구한다.

## 호환성 및 롤백

- Production API, artifact coordinate, runtime default 또는 dependency version을 변경하지 않는다.
- 기존 애플리케이션은 plugin이나 migration API를 사용해야 할 의무가 없다.
- Workflow는 계속 opt-in/path-scoped로 유지되며 test와 README documentation에서 독립적으로 rollback할 수 있다.
- Regression table은 unique physical name과 fixture cleanup을 사용하며, application table을 재사용하지 않는다.
- Rollback은 새로운 test class/task, workflow job/path filter 및 대응하는 README section을 제거한다. Lesson은 fixed filename과 API-specific check가 필요했던 이유를 보여 주는 historical evidence로 남는다. Database나 consumer migration은 필요하지 않다.

## 검증 전략
### 빠른 검증

```bash
EXPOSED_TEST_DB=H2 ./gradlew \
  :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  :bluetape4k-exposed-r2dbc-tests:migrationDriftTest \
  --no-configuration-cache \
  --no-parallel --max-workers=1 --no-daemon

./gradlew \
  :exposed-spring-boot-jdbc-demo:generateMigrations \
  --filename=V1__create_products.sql \
  --rerun --no-build-cache --no-configuration-cache --no-daemon

./gradlew \
  :exposed-spring-boot-r2dbc-demo:generateMigrations \
  --filename=V1__create_webflux_products.sql \
  --rerun --no-build-cache --no-configuration-cache --no-daemon

if [[ -n "$(git status --porcelain --untracked-files=all -- \
  examples/jdbc-demo/src/main/resources/db/migration \
  examples/r2dbc-demo/src/main/resources/db/migration)" ]]; then
  git status --short --untracked-files=all -- \
    examples/jdbc-demo/src/main/resources/db/migration \
    examples/r2dbc-demo/src/main/resources/db/migration
  exit 1
fi
```

로컬 검증은 추적 중인 fixture를 삭제하지 않습니다. 플러그인별 `--rerun`은 실패 시 worktree를 보존하면서 생성을 강제합니다. 더 강한 삭제 후 재생성 검증은 임시 Migration Smoke job에서만 실행됩니다.

### 실제 데이터베이스 검증

순차적으로 실행합니다.

```bash
EXPOSED_TEST_DB=POSTGRESQL ./gradlew \
  :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  --no-configuration-cache \
  --no-parallel --max-workers=1 --no-daemon

EXPOSED_TEST_DB=POSTGRESQL ./gradlew \
  :bluetape4k-exposed-r2dbc-tests:migrationDriftTest \
  --no-configuration-cache \
  --no-parallel --max-workers=1 --no-daemon

EXPOSED_TEST_DB=MYSQL_V8 ./gradlew \
  :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  --no-configuration-cache \
  --no-parallel --max-workers=1 --no-daemon

EXPOSED_TEST_DB=MYSQL_V8 ./gradlew \
  :bluetape4k-exposed-r2dbc-tests:migrationDriftTest \
  --no-configuration-cache \
  --no-parallel --max-workers=1 --no-daemon
```

기존 selector에는 지정한 실제 데이터베이스와 함께 H2도 포함되므로, 이 명령은 "H2 plus PostgreSQL" 및 "H2 plus MySQL 8"을 검증합니다. 매개변수화된 테스트 표시 이름과 assertion message는 API, 실제 dialect, lifecycle stage, generated statement count를 식별합니다.

### Repository 검증

- 영향받는 module compilation 및 tests;
- English/Korean README parity 및 link validation;
- `actionlint`를 사용한 workflow syntax;
- 의도적으로 실패하는 piped command가 summary/status evidence를 계속 기록하고 원래의 nonzero code를 반환하는지 확인하는 격리된 shell-contract check;
- `pull_request`, `contents: read`, secret 사용 또는 production endpoint 없음, pull-request read-only Gradle caches를 입증하는 workflow security review;
- query time 및 exact PR head checks와 함께 기록한 live `develop` branch-protection 및 active-ruleset required-status context queries;
- `./gradlew detekt`;
- `git diff --check`;
- P0=0 및 P1=0인 최종 six-perspective code review.

## Acceptance Criteria 추적성

| 이슈 조건 | 설계 근거 |
|---|---|
| migration/schema drift를 실행하는 문서화된 task 또는 test | 대응하는 README sections 및 focused JDBC/R2DBC tasks |
| Output이 deterministic이거나 nondeterminism이 문서화됨 | 고정된 filename Git diff 및 timestamp-default warning |
| output을 생성하는 schema change를 regression이 포함함 | Baseline-to-evolved additive-column lifecycle |
| 알려진 upstream limitations가 연결됨 | Official 1.3.1 docs 및 Exposed #377 및 #2441 |
| 기존 builds 및 consumers가 영향을 받지 않음 | public API/module/version/default 변경 없음; opt-in workflow |
| 가능한 범위에서 JDBC 및 R2DBC를 다룸 | 일치하는 `MigrationUtils` tests; plugin/R2DBC connection boundary documented |
| H2, PostgreSQL 및 다른 dialect를 확인함 | H2 Migration Smoke 및 dedicated/local no-retry sequential PostgreSQL/MySQL 8 proof |

## 완료 조건

- 작성된 spec 및 executable plan이 모든 Type A review perspectives를 통과함.
- JDBC 및 R2DBC drift tests가 implementation 전 RED, 이후 GREEN을 보임.
- 실제 DB 명령을 순차적으로 실행하여 H2, PostgreSQL 및 MySQL 8 focused tests가 통과함.
- 고정된 filename을 사용하는 plugin generation에서 Git diff가 남지 않음.
- English 및 Korean README sections가 의미상 동등하며, stable 1.11 manual metadata가 변경되지 않음.
- Workflow syntax, affected builds, Detekt 및 diff checks가 통과함.
- deterministic-filename 및 plugin/programmatic boundary를 기록한 durable lesson이 남음.
- PR이 `develop`을 대상으로 생성되고 `debop`에 할당되며 issue #322 metadata를 반영하고 merge-ready CI/review state에 도달함.
- 새로운 exact-head approval이 있을 때까지 Merge는 차단됨.
