# Issue #322 Exposed 마이그레이션 드리프트 구현 계획

> **실행 계약:** 테스트 우선 증명과 함께 순서대로 구현한다. 이 이슈를 공개 API, 의존성 업그레이드, 안정 매뉴얼 업데이트 또는 프로덕션 마이그레이션 실행기로 확장하지 않는다.

**목표:** 기존 Exposed 1.3.1 마이그레이션 통합을 완성한다. 결정론적 데모 생성, H2/PostgreSQL/MySQL 8에서 JDBC 및 R2DBC의 추가적 스키마 드리프트 수렴 테스트, 옵트인 CI 증거, 오용을 방지하는 영어/한국어 가이드를 포함한다.

**아키텍처:** Gradle 플러그인은 빌드 시점의 JDBC 메타데이터/스크립트 예제로 유지한다. 전용 태그가 지정된 `migrationDriftTest` 태스크는 합성 기준 테이블과 진화된 테이블을 대상으로 프로그래밍 방식의 JDBC 또는 R2DBC 비교를 실행한다. Pull request는 고정 파일 데모 증명과 제한된 H2 검사를 사용하며, 전체 Nightly/수동 레인은 재시도 없이 실제 데이터베이스를 순차적으로 실행하고 선택별 정제 증거를 보존한다.

**기술 스택:** Kotlin 2.3, Gradle 9.6, JUnit 5 tags/parameterized tests, JetBrains Exposed 1.3.1 `MigrationUtils`, H2, PostgreSQL, MySQL 8, Testcontainers, GitHub Actions, `actionlint`.

---

## 고정 파일 구조

### 빌드 및 테스트

- `build.gradle.kts` 수정
- `exposed/jdbc-tests/build.gradle.kts` 수정
- `exposed/jdbc-tests/src/test/kotlin/io/bluetape4k/exposed/tests/migration/JdbcMigrationDriftTest.kt` 생성
- `exposed/r2dbc-tests/build.gradle.kts` 수정
- `exposed/r2dbc-tests/src/test/kotlin/io/bluetape4k/exposed/r2dbc/tests/migration/R2dbcMigrationDriftTest.kt` 생성

검증기와 실패 보존 정리 헬퍼는 각 테스트 파일에서 비공개로 유지한다. 두 테스트 지원 모듈은 여러 모듈에서 사용되며, 헬퍼를 `main`으로 이동하면 이 회귀 테스트만을 위한 게시/테스트 지원 API가 생성되므로 작은 중복은 의도적이다.

### CI 및 문서

- `.github/workflows/migration-smoke.yml` 수정
- `.github/workflows/nightly-tests.yml` 수정
- `README.md` 수정
- `README.ko.md` 수정
- `scripts/manual/validate_migration_readme_parity.rb` 생성
- `scripts/manual/validate_migration_readme_parity_test.rb` 생성
- `docs/superpowers/checklists/2026-07-17-exposed-1.12-manual-promotion-checklist.md` 생성
- `docs/lessons/2026-07-17-issue-322-exposed-migration-drift.md` 생성
- `docs/review/2026-07-17-issue-322-exposed-migration-drift-review.md` 생성
- `docs/review/2026-07-17-issue-322-exposed-migration-drift-plan-review.md` 생성
- `docs/superpowers/checklists/2026-07-17-issue-322-exposed-migration-drift-checklist.md` 수정
- 검토 및 완료 증거를 기록하는 범위에서만 이 계획 수정

`docs/manual/**`, 버전 카탈로그, 의존성 잠금 파일, 공개 라이브러리 소스, 체크인된 V1 SQL은 변경되지 않아야 한다.

## 트리거된 위험 예측

| 위험 | 가장 이른 신호 | 예방/증명 | 재실행 지점 |
|---|---|---|---|
| 생성된 문장이 합성 추가 컬럼을 넘어 확장됨 | 검증기 단위 테스트 또는 드리프트 테스트가 문장을 거부함 | 전체 문장 허용 목록, 대표 방언 양성 사례, 적대적 복합 음성 사례 | Tasks 2 and 3 |
| DDL 정리가 실제 실패를 대체하거나 테이블을 남김 | 주 오류가 사라지거나 다음 실행에서 fixture가 발견됨 | 별도 fixture 호출, 억제된 정리 오류, 주 오류/정리/이중 단위 사례, 존재 여부 검증 | Tasks 2 and 3 |
| 환경 변경이 오래된 테스트 출력을 재사용함 | 태스크가 `UP-TO-DATE`/`FROM-CACHE`를 보고함 | 환경 태스크 입력, 캐시 불가 및 항상 최신 상태가 아닌 태스크, 2회 실행 증거 | Task 4 |
| 기본 재시도 Nightly 작업이 마이그레이션 회귀를 숨김 | 태그된 클래스가 일반 `test` XML에 나타남 | 기본 태그 제외와 전용 no-retry 태스크/작업 | Tasks 1, 4, and 6 |
| 고정 파일 스모크 테스트가 타임스탬프가 붙은 추가 파일을 놓침 | CI가 정상인 동안 추적되지 않은 SQL이 남아 있음 | 예상 파일만 제거, 강제 생성, 재생성 요구, 제한된 porcelain 검사 | Task 5 |
| 하나의 중단된 API가 이후 증거를 억제함 | 이후 단계/아티팩트가 없음 | 단계별 및 작업별 타임아웃, `continue-on-error`, 항상 실행되는 업로드와 종합 판정 | Tasks 5 and 6 |
| `tee`가 Gradle 종료 코드를 숨기거나 원시 로그에서 식별자가 유출됨 | 실패한 Gradle 단계가 성공으로 표시되거나 아티팩트에 URL/home path가 포함됨 | `PIPESTATUS[0]`, 단계별 상태, 허용 목록/비식별화 요약, 격리된 셸 실패 테스트 | Task 6 |
| 테스트 리포트가 드라이버 출력 또는 러너 경로를 노출함 | 업로드 전에 민감 패턴 검사가 일치함 | XML 스트림 비활성화, HTML 생략, 스테이징된 XML/요약에 대한 fail-closed 검사 | Tasks 1, 5, and 6 |
| README가 적용된 마이그레이션을 덮어쓰도록 가르침 | fixture 경고 없이 고정된 V1 명령이 나타남 | 대상별 구분, 변경 불가 애플리케이션 파일명, 지원 매트릭스, 세 부분 안전 체크리스트 | Task 7 |
| 안정 매뉴얼이 1.11 ref에서 1.12 동작을 잘못 광고함 | `docs/manual/**` diff가 존재함 | README 전용 제공과 정확한 no-diff 검사 | Tasks 7 and 8 |

## 계획 검토 기록

이 섹션은 6개의 독립적인 계획 관점과 메인 세션 통합이 수렴한 후에만 작성한다.

| 관점 | P0 | P1 | P2 | P3 | 결과 |
|---|---:|---:|---:|---:|---|
| 성능/비용 | 0 | 0 | 0 | 0 | READY |
| 안정성/신뢰성 | 0 | 0 | 0 | 0 | READY |
| 보안/개인정보 보호 | 0 | 0 | 0 | 0 | READY |
| 운영자/Ops | 0 | 0 | 0 | 0 | READY |
| 개발자/API | 0 | 0 | 0 | 0 | READY |
| 사용자/호출자 | 0 | 0 | 0 | 0 | READY |
| 메인 세션 통합 | 0 | 0 | 0 | 0 | READY |

수용된 저장소 간 정책 위험: 이 두 워크플로는 변경 가능한 검증된 major Action 태그를 사용하는 현재 저장소 규칙을 따른다. 모든 Action 참조를 전체 커밋 SHA로 변환하는 것은 저장소 전체의 워크플로 거버넌스 변경이며 #322의 범위에 포함되지 않는다. 계획 시점에 일치하는 추적 이슈는 존재하지 않았다. 최종 검토에서는 `workflow governance`를 담당자로 기록하고, 이 PR의 범위를 확장하지 않는 별도 후속 이슈를 권장한다.

---

### Task 0: 검토된 계획 증거 동결

**파일:**

- 이 계획 수정
- 이슈 체크리스트 수정

- [x] **Step 1: 6개의 독립적인 계획 검토 실행**

성능, 안정성, 보안, Ops, 개발자/API, 사용자/호출자 우려를 각각 별도로 검토한다. 모든 P0/P1을 해결하고 영향을 받은 각 레인을 다시 실행한다.

- [x] **Step 2: 위험 및 수용 기준 추적성 기록**

모든 설계 목표, 이슈 수용 기준, 예측된 위험, 파일, 명령, 아티팩트, 롤백 지점, PR 게이트가 아래 태스크에 의해 소유되는지 검증한다.

- [x] **Step 3: 구현 전에 계획 검증 및 커밋**

```bash
git diff --check
rg -n "pending|PENDING|P0|P1|Triggered Risk Predictions" \
  docs/superpowers/plans/2026-07-17-issue-322-exposed-migration-drift-plan.md
```

예상 결과: 남은 pending 검토 셀이 없고, 모든 레인이 P0=0/P1=0이며, 계획/체크리스트가 Lore 규칙을 준수하는 결정 메시지와 함께 커밋된다.
### 작업 1: Live-Only 태그 테스트 작업 추가

**파일:**

- `exposed/jdbc-tests/build.gradle.kts` 수정
- `exposed/r2dbc-tests/build.gradle.kts` 수정

- [x] **1단계: 현재 작업 기준선 캡처**

```bash
./gradlew \
  :bluetape4k-exposed-jdbc-tests:tasks \
  :bluetape4k-exposed-r2dbc-tests:tasks \
  --group verification --no-configuration-cache --no-daemon
```

예상 결과: 편집 전에는 `migrationDriftTest`가 존재하지 않음.

- [x] **2단계: 각 모듈에 전용 작업 등록**

각 모듈에 대해:

- 일반 `test`에서 JUnit 태그 `migration-drift`를 제외;
- 두 전용 작업을 포함한 모든 `Test` 작업을 기존 저장소 전체 공유 Test 뮤텍스에 연결;
- 일반 `SourceSet` 테스트 출력과 런타임 클래스패스에 대해 `migrationDriftTest` 등록;
- 태그 `migration-drift`만 포함;
- verification 그룹과 정확한 설명 설정;
- 환경 프로바이더에서 `EXPOSED_TEST_DB`를 선언하고 기본값을 `H2`로 지정하며, 한 번 정규화하고, `H2`, `POSTGRESQL`, `MYSQL_V8` 이외의 값은 거부하고, 해당 프로바이더를 작업 입력으로 사용하며, 동일한 값을 테스트 워커 환경에 명시적으로 전달;
- `outputs.upToDateWhen { false }` 및 `outputs.cacheIf { false }` 설정;
- `maxParallelForks = 1` 설정 및 JUnit 병렬 실행 비활성화;
- preview를 포함하여 저장소 테스트에 필요한 JVM 옵션 적용;
- 집중 테스트 워커 힙을 2 GiB로 제한하고 H2/실제 데이터베이스 CI Gradle 및 Kotlin 데몬 힙을 명시적으로 제한;
- `useJUnitPlatform { includeTags("migration-drift") }`를 사용하여 작업을 명시적으로 구성하고, 일반 `test`에서는 `excludeTags("migration-drift")` 사용;
- JUnit XML을 필수로 하고 HTML을 비활성화하며, `reports.junitXml.includeSystemOutLog = false` 및 `reports.junitXml.includeSystemErrLog = false`로 JUnit XML의 system-out 및 system-err 포함을 비활성화하고, `binaryResultsDirectory`를
  `build/test-results/migrationDriftTest/binary`로 설정하며, `reports.junitXml.outputLocation`을 워크플로 스테이징 소스인
  `build/test-results/migrationDriftTest`로 설정;
- 일반 `test`에서 전용 작업으로의 의존성을 추가하지 않음.

- [x] **3단계: 작업 형태 검증**

```bash
./gradlew \
  :bluetape4k-exposed-jdbc-tests:tasks \
  :bluetape4k-exposed-r2dbc-tests:tasks \
  --group verification --no-configuration-cache --no-daemon

./gradlew \
  :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  :bluetape4k-exposed-r2dbc-tests:migrationDriftTest \
  --dry-run --no-configuration-cache --no-daemon
```

예상 결과: 두 작업이 모두 존재하고, 일반 테스트 클래스/런타임 의존성을 해석하며, 일반 `test`에 연결되지 않음.

롤백: 두 모듈 빌드 파일만 되돌림.
### 작업 2: JDBC Drift Proof 테스트 우선 구현

**파일:**

- 생성
  `exposed/jdbc-tests/src/test/kotlin/io/bluetape4k/exposed/tests/migration/JdbcMigrationDriftTest.kt`

- [x] **1단계: RED validator 및 cleanup 단위 케이스 작성**

`migration-drift` 클래스 태그 아래에 다음 테스트를 추가합니다.

- 대표적인 unquoted, double-quoted, backtick-quoted additive 형식;
- 선택적 `COLUMN`, 공백, 대소문자 변형;
- 주석, 추가 세미콜론, 여러 statement, 예상하지 못한 table/column,
  파괴적 동사, 복합 절;
- `DEFAULT`, `NOT NULL`, `GENERATED`, `REFERENCES`, `CONSTRAINT`, `COLLATE`,
  추가 column, 그리고 dialect-approved nullable
  varchar(255) 정의 이후의 모든 tail;
- primary-only, cleanup-only, dual-failure 전파/억제.

case/whitespace 정규화 후, 각 unquoted, double-quoted 또는
backtick-quoted identifier token이 정확히 일치하도록 요구한 다음,
H2, PostgreSQL, MySQL 8에 대해 완전한 dialect-approved tail
`VARCHAR(255) NULL`만 허용합니다. validator는 앞뒤에 공백이 있는 quoted
identifier, 주석, 추가 세미콜론, 여러 statement, comma, trailing operation,
예상하지 못한 identifier, 그리고
`DEFAULT`, `NOT NULL`, `GENERATED`, `REFERENCES`, `CONSTRAINT`, `CHECK`,
`UNIQUE`, `PRIMARY KEY`, `COLLATE`를 포함하는 모든 형식을 거부해야 합니다.
현재 Exposed output이 다르면 allowlist를 조용히 확장하지 말고 중단한 뒤,
검토된 plan을 다시 엽니다.

아직 구현되지 않은 private helper를 호출한 다음 다음을 실행합니다.

```bash
EXPOSED_TEST_DB=H2 ./gradlew \
  :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  --tests '*JdbcMigrationDriftTest*' \
  --no-configuration-cache --no-parallel --max-workers=1 --no-daemon
```

예상 RED: helper behavior가 아직 존재하지 않기 때문에 compilation 또는
assertion이 실패합니다. checklist에 failure excerpt를 보존합니다.

- [x] **2단계: 범위가 좁은 private helper 구현**

whole-statement additive validator와 synchronous cleanup wrapper를 구현합니다.
광범위한 substring allowlist는 사용하지 않습니다. 예상하지 못한 SQL이
일반 로그에 포함되지 않도록 합니다. assertion message에는 정규화된
synthetic identifier와 statement count를 보고할 수 있습니다.

- [x] **3단계: helper-only GREEN 도달**

데이터베이스가 아닌 케이스는 `@Nested inner class HelperContract`에 유지하고
해당 nested class만 실행합니다.

```bash
EXPOSED_TEST_DB=H2 ./gradlew \
  :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  --tests '*JdbcMigrationDriftTest*HelperContract*' \
  --no-configuration-cache --no-parallel --max-workers=1 --no-daemon
```

예상: validator 및 primary/cleanup/dual failure 케이스가 데이터베이스나
container lifecycle output 없이 통과합니다.

- [x] **4단계: JDBC lifecycle regression 작성**

`JdbcMigrationDriftTest`가 `AbstractExposedTest`를 상속하도록 하고,
`@ParameterizedTest`와 `@MethodSource(ENABLE_DIALECTS_METHOD)`를 사용합니다.
JDBC에 고유한 physical name을 사용하는 plain table을 추가합니다.
`@OptIn(ExperimentalDatabaseMigrationApi::class)`을 추가하고,
`org.jetbrains.exposed.v1.core.ExperimentalDatabaseMigrationApi` 및
`org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils`를 import한 뒤,
기존 `withDb` fixture 내부에서 다음을 수행합니다.

1. baseline `id` + `name` 생성;
2. nullable `description`이 추가된 evolved `id` + `name`에 대해
   `MigrationUtils.statementsRequiredForDatabaseMigration(EvolvedTable, withLogs = false)` 호출;
3. validator가 승인한 statement 하나를 요구;
4. `JdbcTransaction.exec`를 통해 정확히 검증된 string 실행;
5. 다시 비교하고 output이 비어 있도록 요구;
6. 두 번째 top-level fixture call에서 cleanup을 수행하고 absence를 검증.

별도의 H2-only varchar-to-text characterization table을 추가합니다.
altering statement가 제안되지만 additive executor에는 전달되지 않음을
입증합니다. 동일한 failure-preserving top-level cleanup wrapper를 통해
실행하고, 이후 type-change table이 absent인지 검증합니다.

- [x] **5단계: GREEN 도달**

JDBC command를 한 번 실행합니다. 예상: GREEN; display names에 실제
dialect가 표시되고 cleanup assertion이 통과합니다. 반복적인 live/cleanup
proof는 Task 4가 담당합니다.

Rollback: production/test-support main source를 건드리지 않고 JDBC test file을 제거합니다.
### 작업 3: R2DBC Drift Proof 테스트 우선 구현

**파일:**

- 생성
  `exposed/r2dbc-tests/src/test/kotlin/io/bluetape4k/exposed/r2dbc/tests/migration/R2dbcMigrationDriftTest.kt`

- [x] **1단계: 일치하는 RED 단위 테스트 작성**

JDBC validator matrix를 미러링하고, 두 번째 R2DBC `withDb` 호출에서 cleanup이 실행되는 suspending cleanup wrapper를 사용합니다. 다음을 실행합니다:

```bash
EXPOSED_TEST_DB=H2 ./gradlew \
  :bluetape4k-exposed-r2dbc-tests:migrationDriftTest \
  --tests '*R2dbcMigrationDriftTest*' \
  --no-configuration-cache --no-parallel --max-workers=1 --no-daemon
```

helper 구현 전에 캡처한 예상 RED입니다.

- [x] **2단계: private suspending helpers 구현**

동일한 정확한 `VARCHAR(255) NULL` whole-statement validator와 suspending cleanup wrapper를 구현합니다. primary failure가 cancellation이거나 현재 context가 inactive인 경우에만 `NonCancellable`에서 cleanup을 실행하며, 일반적인 primary/cleanup throwable identity를 보존합니다. 그런 다음 database behavior를 추가하기 전에 `@Nested inner class HelperContract`만 실행하여 GREEN 상태로 만듭니다:

```bash
EXPOSED_TEST_DB=H2 ./gradlew \
  :bluetape4k-exposed-r2dbc-tests:migrationDriftTest \
  --tests '*R2dbcMigrationDriftTest*HelperContract*' \
  --no-configuration-cache --no-parallel --max-workers=1 --no-daemon
```

예상 결과: database 또는 container lifecycle 출력이 없습니다.

- [x] **3단계: R2DBC lifecycle 구현**

`R2dbcMigrationDriftTest`가 `AbstractExposedR2dbcTest`를 상속하도록 하고, `@ParameterizedTest`와 `@MethodSource(ENABLE_DIALECTS_METHOD)`를 사용하며, `runSuspendIO`를 통해 확립된 suspending fixture에 진입합니다. `@OptIn(ExperimentalDatabaseMigrationApi::class)`를 추가하고,
`org.jetbrains.exposed.v1.core.ExperimentalDatabaseMigrationApi`와
`org.jetbrains.exposed.v1.migration.r2dbc.MigrationUtils`를 import하며,
`MigrationUtils.statementsRequiredForDatabaseMigration(EvolvedTable, withLogs = false)`를 호출하고, 검증된 정확한 문자열만 suspending
`R2dbcTransaction.exec`를 통해 실행합니다. 테이블 구조와 assertion은 JDBC와 동일하게 유지하되, 별도의 R2DBC physical table과 독립된 H2 type-change table을 사용합니다. additive 및 type-change fixture 모두 failure-preserving top-level cleanup과 post-cleanup absence assertion을 사용합니다.

- [x] **4단계: GREEN 달성**

R2DBC 명령을 한 번 실행합니다. 예상 결과: 실제 dialect 표시, convergence 및 cleanup assertion을 포함한 GREEN입니다. 반복 proof는 작업 4에서 담당합니다.

Rollback: R2DBC test file을 제거합니다.

### 작업 4: Tag, Cache 및 Environment 경계 검증

**파일:**

- 작업 1–3의 test/build files

- [x] **1단계: 기본 테스트에서 drift가 제외되는지 검증**

normal-task result directories만 제거하고, 두 normal H2 module tests를 실행한 뒤
`build/test-results/test` 아래에 `MigrationDriftTest` XML이 없는지 확인합니다.

- [x] **2단계: 전용 task가 두 번 모두 실제 실행되는지 검증**

`--build-cache --info`를 사용하여 combined H2 command를 두 번 실행하고 로그를 캡처합니다. 두 실행 모두에서 두 dedicated task가 실행되며 어느 실행에서도 `UP-TO-DATE` 또는 `FROM-CACHE`를 보고하지 않는지 확인합니다. `EXPOSED_TEST_DB`가 input으로 나열되고 worker에 명시적으로 전달되는지, dedicated XML files가 존재하는지, additive 및 H2 type-change fixtures가 두 실행 사이에 계속 absent인지 검증합니다.

Rollback: 담당 build/test task로 되돌립니다. tag 또는 cache contract를 약화하지 않습니다. Dedicated/default test execution이 compilation proof이며, test task를 시작할 수 없는 경우에만 standalone compile을 diagnostic fallback으로 사용합니다.

### 작업 5: Pull-Request Migration Smoke를 독립적이고 제한적으로 구성

**파일:**

- `.github/workflows/migration-smoke.yml` 수정

- [x] **1단계: trigger 및 trust boundary 유지**

- weekly schedule만 제거합니다;
- `workflow_dispatch`와 `pull_request`는 유지하고, `pull_request_target`는 절대 사용하지 않습니다;
- workflow와 job permissions는 `contents: read`만 유지하고 PR Gradle caches는 read-only로 유지합니다;
- 모든 checkout에 `persist-credentials: false`를 설정하고 secrets 또는 OIDC permission을 사용할 수 없는지 검증합니다;
- demo/test/README/workflow paths와 root build/settings,
  `gradle.properties`, `gradle/**` authority paths를 포함합니다.

- [x] **2단계: `demo-migrations` 강화**

15분 timeout을 유지합니다. `help --task generateMigrations`가 plugin-specific `--rerun` option을 노출하는지 확인합니다. 예상되는 V1 fixture files 두 개만 제거하고, 두 fixed-filename tasks를 `--rerun --no-build-cache --no-daemon`으로 실행하며, 두 파일이 모두 존재하도록 요구한 뒤 bounded tracked 또는 untracked migration-directory status가 있으면 실패합니다.

- [x] **3단계: 제한된 `h2-drift` 추가**

30분 job timeout과 stable-ID가 있는 10분 step 두 개를 사용하고,
`continue-on-error: true`로 설정합니다. JDBC를 먼저 실행한 뒤 R2DBC를 실행하며,
`EXPOSED_TEST_DB=H2`와 parallel Gradle execution 없이 실행합니다. 각 step은 raw log를 runner-temporary로 유지하여 Actions console에 노출하지 않고, system streams를 비활성화한 상태에서 status와 sanitized allowlisted summary 및 JUnit XML을 stage하며, raw log를 삭제한 뒤 staged text에서 credentials, URL authority/userinfo/query, tokens 및 home paths를 fail-closed 방식으로 검사합니다. HTML은 업로드하지 않습니다. 다음 artifact uploads를 각각 always-run으로 분리합니다:

- `migration-drift-jdbc-h2`
- `migration-drift-r2dbc-h2`

Pre-test setup은 두 API staging directories를 생성하고
`status.txt=started`로 설정합니다. 두 bounded attempts가 끝나면 `if: always()` outcome collector가 upload 및 aggregate evaluation 전에 timeout 또는 cancelled states를 포함한 JDBC/R2DBC GitHub step outcomes를 기록합니다.

각 artifact는 `if-no-files-found: error`와 함께 안전한 staged evidence를 14일 동안 보존합니다. 마지막에는 always-run aggregate step을 실행하여 어느 API outcome이든 failed이면 실패하도록 합니다.

- [x] **4단계: YAML 및 generated file proof를 로컬에서 검증**

`actionlint`, 두 plugin-specific `--rerun` generation tasks를 local fixtures를 삭제하지 않고 실행하고, file existence assertions와 bounded porcelain assertion을 수행합니다. 예상 결과: workflow diagnostic이 없고 migration-directory status도 없습니다. remove-and-recreate proof는 ephemeral CI checkout 내부에서만 실행되므로, 로컬 plugin failure가 tracked deletions를 남기거나 restoration을 요구하지 않습니다.

Rollback: 이전 workflow를 복원합니다. forced proof가 이슈 범위를 벗어난 intentional plugin change를 입증하지 않는 한, tracked V1 files는 `origin/develop`과 byte-equal이어야 합니다.
### 작업 6: 순차 무재시도 실제 데이터베이스 증거 추가

**파일:**

- `.github/workflows/nightly-tests.yml` 수정

- [x] **단계 1: 정확한 full-only 작업 경계 추가**

`build` 뒤에 `migration-drift-real-databases`를 추가하고, 작업 수준의
`permissions`에는 `contents: read`만 포함하며, 정확히 기존의 Sunday-full/manual-full
조건, `timeout-minutes: 60`, 저장소 Testcontainers 환경을 사용하고,
재시도 루프는 사용하지 않는다.

- [x] **단계 2: 제한된 선택 단계 네 개 구현**

JDBC PostgreSQL, R2DBC PostgreSQL, JDBC MySQL 8, R2DBC MySQL 8을 순서대로 실행한다.
각 단계는 다음을 만족한다.

- 안정적인 ID, `continue-on-error: true`, 12분 제한 시간을 가진다.
- 정확한 `EXPOSED_TEST_DB` 값을 설정한다.
- `build/migration-drift-reports/<api>-<database>` 메타데이터를 미리 생성한다.
- `set -o pipefail`, `set +e`, runner 임시 raw log로의 `tee`를 사용하며
  콘솔 출력은 억제하고, `gradle_status=${PIPESTATUS[0]}`를 캡처하며,
  원래 Gradle 상태가 대체되지 않도록 보호된 non-errexit 섹션에서 증거 조립을
  계속한다.
- 시스템 스트림을 비활성화한 상태로 JUnit XML을 스테이징하고,
  allowlist/redacted `command-summary.log`를 작성하며,
  별도로 `evidence_status`를 캡처한다.
- 모든 스테이징된 텍스트 아티팩트를 fail-closed 방식으로 검사하고, HTML은 제외하며,
  trap과 일반 경로 양쪽을 통해 raw log를 삭제하고, 위생 처리된 요약만 출력한다.
- 두 상태를 모두 기록하고, Gradle 상태가 0이 아닌 경우 이를 우선 사용하며
  그렇지 않으면 evidence 상태를 사용한다.

모든 PostgreSQL 또는 MySQL 8 선택의 스테이징된 JUnit/display 증거에는
selector의 companion H2 case와 함께 요청된 실제 dialect가 포함되어야 한다.
요청된 dialect가 없으면 선택 실패로 처리한다.

모든 단계가 끝나면 GitHub 단계 outcome을 각 status record에 기록하고,
`if: always()` 및 `if-no-files-found: error`를 사용하여 14일 보존 기간의
`migration-drift-real-databases` 아티팩트 하나를 업로드한 다음,
success가 아닌 outcome 또는 기록된 Gradle/evidence 상태가 있으면 실패한다.

- [x] **단계 3: 셸 실패 보존 검증**

`tee`를 통해 의도적으로 실패하는 명령을 파이프하는 격리된 로컬 셸 harness를 실행한다.
원래의 nonzero exit, summary/status 생성, upload 디렉터리에 raw log가 없는 것을
assert한다. Gradle은 성공하지만 evidence staging이 실패하는 두 번째 case도 추가하고,
evidence 실패가 step result가 되는지 assert한다. URL schemes, userinfo, query strings,
bare DNS/IPv4/IPv6 host-port authorities에 대한 staged upload가 거부됨을 입증하는
sensitive-pattern fixture를 추가하고, raw output이 콘솔 캡처에 없으며 raw 임시 파일이
삭제되는지 입증한다. `actionlint`를 실행한다.

- [x] **단계 4: 개인정보 보호 및 스케줄 조건 검토**

weekday smoke schedules에는 작업이 없고, Sunday/full dispatch에는 존재하며,
secret/production endpoint를 사용하지 않고, URL authority, userinfo, query strings,
passwords, tokens 또는 home paths를 업로드할 수 없음을 입증한다.

롤백: 전용 작업만 제거하며, 기존 broad Nightly 작업과 재시도 동작은 변경하지 않는다.

### 작업 7: 영어와 한국어 README에서 모호한 안내 교체

**파일:**

- `README.md` 수정
- `README.ko.md` 수정
- `scripts/manual/validate_migration_readme_parity.rb` 생성
- `scripts/manual/validate_migration_readme_parity_test.rb` 생성
- `docs/superpowers/checklists/2026-07-17-exposed-1.12-manual-promotion-checklist.md` 생성

- [x] **단계 1: 애플리케이션 사용자 안내 작성**

동등한 1.12 availability callout, three-surface boundary table, 그리고
central catalog를 이미 import하는 애플리케이션을 위한 동등한 선택 사항
`alias(bt4k.plugins.exposed.plugin)`과 직접 upstream
`org.jetbrains.exposed.plugin` version을 포함한, 자체 완결형의 복사하여 붙여넣을 수 있는
Kotlin DSL 예제를 추가한다. `tablesPackage`, `fileDirectory`, 일치하는 JDBC
`runtimeOnly`, `MIGRATION_JDBC_URL`, `MIGRATION_DB_USER`, `MIGRATION_DB_PASSWORD`
providers를 사용한다. 애플리케이션이 제어하는 output directory와 새로운 immutable
versioned filename을 사용하며, generation 전에 collision preflight를 수행한다.

```bash
MIGRATION_FILE=V202607170001__add_description.sql
test ! -e "src/main/resources/db/migration/$MIGRATION_FILE" &&
  ./gradlew generateMigrations --filename="$MIGRATION_FILE"
```

R2DBC 애플리케이션에도 build-time JDBC URL과 driver가 필요하다는 점을 명시한다.
커밋된 credentials, shared/production endpoints, startup 또는 request-path comparison,
적용된 migration 덮어쓰기를 금지한다.

- [x] **단계 2: contributor 안내 작성**

체크인된 V1 파일은 애플리케이션 convention이 아니라 교체 가능한 repository fixture임을
설명한다. 결합된 H2 및 순차 real-database commands, prerequisites, report locations,
pass/non-proof 의미, 그리고 아래의 정확한 diagnostics table을 문서화한다.

| 실패 지점 | 첫 진단 및 증거 확인 순서 |
|---|---|
| Gradle plugin | `--stacktrace --info`를 사용하여 fixed-filename command를 다시 실행한다; 제한된 migration-directory status를 검사한다 |
| H2 JDBC drift | `:bluetape4k-exposed-jdbc-tests:migrationDriftTest --tests '*JdbcMigrationDriftTest*' --stacktrace --info`를 실행한다; staged status를 검사한 다음 sanitized XML을 검사한다 |
| H2 R2DBC drift | `:bluetape4k-exposed-r2dbc-tests:migrationDriftTest --tests '*R2dbcMigrationDriftTest*' --stacktrace --info`를 실행한다; staged status를 검사한 다음 sanitized XML을 검사한다 |
| PostgreSQL/MySQL 8 | 먼저 Docker를 확인한다; `command-summary.log`, 다음 `status.txt`, 다음 sanitized JUnit XML을 검사한다 |

- [x] **단계 3: 지원 및 검토 매트릭스 추가**

두 언어에서 headings, commands, warnings, upstream links, support rows,
schema/data/rollout safety checks를 일치시킨다. empty diff는 오직
"차이가 감지되지 않음"을 의미한다고 명시한다.

- [x] **단계 4: parity 및 stable-manual ownership 검증**

focused Ruby validator와 self-test를 생성하여 두 README에서 marked migration section
headings, shell/Kotlin fences, table row keys, commands, URLs를 추출하고 정규화한 뒤
semantic parity drift가 있으면 실패하도록 한다. release/publish workflow가 소유하는
별도의 1.12 manual-promotion checklist를 생성한다. 정확한 1.12 release ref와 commit이
존재할 때까지 pending 상태로 유지하며, 이후 English/Korean manual promotion과
manifest, inventory, parity, release-manual validation을 요구한다. promotion 자체는
#322의 범위 밖이다.

다음을 실행한다.

```bash
ruby scripts/manual/validate_migration_readme_parity_test.rb
ruby scripts/manual/validate_migration_readme_parity.rb README.md README.ko.md
git diff --exit-code origin/develop -- docs/manual
git diff --check
```

예상 결과: stable-manual diff가 없다.

롤백: 두 README 섹션을 함께 제거하며, 한 locale만 앞서도록 두지 않는다.
### 작업 8: 후보 최종 헤드 수렴

**파일:** 변경된 모든 구현 파일

- [x] **1단계: 예비 신속 검증 실행**

새로운 통합 H2 드리프트 검사, 기본 H2 테스트, 로컬 fixture 삭제 없이 고정 파일
생성 강제, 제한된 상태 검사, Detekt,
`actionlint`, shell-contract 테스트, 링크/패리티 검사, `git diff --check`를 한 번씩 실행한다.

- [x] **2단계: 구현 diff 검토 6종 실행**

성능, 안정성, 보안, Ops, 개발자/API, 사용자/문서 관점에서 구현 diff를 검토한다.
모든 P0/P1 발견 사항을 수정하고 영향을 받은 테스트를 다시 실행한다. 최종 개수를
`docs/review/2026-07-17-issue-322-exposed-migration-drift-review.md`에 기록한다.

- [x] **3단계: 교훈을 기록하고 증거 조정**

`docs/lessons/2026-07-17-issue-322-exposed-migration-drift.md`를 생성하여
고정 파일 이름과 타임스탬프 기본값의 차이, 고정 V1이 저장소 fixture일 뿐인 이유,
플러그인 JDBC 메타데이터와 JDBC/R2DBC 프로그래밍 방식 비교의 차이, 빈 diff가
스키마 동일성을 의미하지 않는 이유를 설명한다. 각 이슈의 승인 기준과
설계 DoD를 증거에 매핑하고, pre-PR 게이트를 거쳐 계획/체크리스트/검토 산출물을
업데이트한다.

- [x] **4단계: 후보 최종 헤드 커밋 및 푸시**

공개 API, 의존성 버전, 체크인된 SQL 또는 안정 매뉴얼의 드리프트가 없는지 확인한다.
Lore 규격을 준수하는 커밋을 사용하고 후보 SHA를 푸시한 뒤 upstream과의 동일성을
검증하며, 정확한 헤드 검증 전에 저장소를 더 이상 변경하지 않는다.

### 작업 9: 정확한 헤드 검증 및 전달

- [ ] **1단계: 정확한 헤드 신속 검증 실행**

깨끗하게 푸시된 후보 SHA에서 통합 H2 드리프트, 기본 H2 테스트, 로컬 삭제 없이
플러그인별 고정 파일 생성, 제한된 상태 검사, Detekt, `actionlint`,
shell/privacy 검사, 패리티/링크 검사, diff 검사를 다시 실행한다.
명령은 작업 트리를 후보 헤드와 바이트 단위로 동일하게 유지해야 한다.

- [ ] **2단계: `develop`을 대상으로 PR 생성**

`debop`을 할당하고, 이슈 #322 메타데이터를 반영하며, `Fixes #322`를 포함하고,
테스트 및 알려진 Exposed 제한 사항을 요약하며, 정확한 워크플로 증거를 연결한다.

- [ ] **3단계: 정확한 헤드 실데이터베이스 검증 완료**

동일한 후보 SHA에 대해 다음 경로 중 하나를 선택한다.

1. 저장소에서 요구하는 순서로 `--no-parallel --max-workers=1 --no-daemon`을 사용하여
   PostgreSQL/MySQL 네 가지 선택을 모두 로컬에서 실행한다. 또는
2. 정확한 브랜치 헤드에서 `scope=full`로 Nightly를 수동 디스패치하고 전용
   순차 job/artifact가 통과하도록 한다.

Testcontainers 선택 항목을 병렬로 실행하지 않는다. 실제 명령 종료 코드 또는 정확한
워크플로 실행/헤드/artifact 증거를 Git 트리 외부에 기록한다.

- [ ] **4단계: 라이브 PR 상태 검증**

CI, 리뷰 및 해결되지 않은 스레드를 기다린다. `develop` 브랜치 보호와 모든 활성
ruleset에 필요한 status context를 조회한다. ruleset 목록을 페이지 단위로 조회하고,
모든 ruleset 세부 정보를 ID별로 가져오며, `develop`에 적용되는 대상 조건을 가진
활성 enforcement를 필터링하고, required-status-check 규칙을 추출한다. 기존
브랜치 보호의 404는 명시적 부재로 처리한다. 조회 시간, 명령/API endpoint, ruleset ID,
enforcement 상태, 대상 조건, 필수 context, 정확한 PR 헤드/검사 결과를 기록하고,
Migration Smoke가 필수 검사가 아님을 입증한다.

- [ ] **5단계: 병합 준비 상태에서 중지**

정확한 PR 번호, 헤드 SHA, CI 결론, 리뷰/스레드 상태 및 남은 위험을 보고한다.
사용자가 해당 정확한 헤드에 대해 새로 승인할 때까지 병합하지 않는다. 이후의 코드,
빌드, 테스트, 워크플로, README, lesson, 계획, 체크리스트 또는 검토 파일 커밋은
정확한 헤드 증거를 무효화하므로, 병합 준비 상태를 보고하기 전에 영향을 받은
모든 검증을 다시 실행한다.

## 승인 추적성

| 이슈/설계 요구 사항 | 담당 작업 |
|---|---|
| 문서화된 migration/schema drift 작업 | 1, 7 |
| 결정론적 출력 또는 문서화된 비결정성 | 5, 7, 9 |
| 스키마 변경이 출력을 생성하고 수렴함 | 2, 3 |
| JDBC 및 R2DBC 검증 | 2, 3, 5, 6 |
| H2, PostgreSQL, MySQL 8 검증 | 2, 3, 5, 6, 8 |
| upstream 제한 사항 연결 | 7, 9 |
| 필수 consumer workflow 없음 | 5, 6, 7, 9 |
| 기존 빌드에 영향 없음 | 1, 4, 8 |
| 비밀 유출 없는 실패 증거 보존 | 5, 6, 8 |
| 안정 1.11 매뉴얼 변경 없음 | 7, 8 |
| PR 생성과 병합 승인을 별도로 처리 | 9 |

## 중지 조건

정확한 PR 헤드에 완전한 H2 및 실데이터베이스 증거가 있고, 모든 필수
CI/리뷰/스레드가 통과하며, PR 준비 상태까지 모든 Type A 체크리스트 행이
조정되고, 병합 준비 보고서가 전달된 경우에만 중지한다. 병합 및 로컬 종료는
정확한 헤드에 대한 사용자의 새로운 승인이 있을 때까지 차단된다.
