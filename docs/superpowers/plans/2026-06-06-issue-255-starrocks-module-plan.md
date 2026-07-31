# Issue #255 StarRocks Exposed module 계획

Date: 2026-06-06
Repository: `bluetape4k-exposed`
Spec: `docs/superpowers/specs/2026-06-06-issue-255-starrocks-module-design.md`
Issue: `bluetape4k/bluetape4k-exposed#255`

## 실행 원칙

- 첫 구현은 connection, dialect registration, metadata smoke, fixture table,
  insert/select, 문서, workflow visibility로 제한한다.
- 광범위한 public API를 추가하기 전에 StarRocks container/bootstrap 경로를 증명한다.
- 적합한 부분은 기존 `exposed-trino`와 `exposed-clickhouse` 구조를 따르되 MySQL parity를 추론하지 않는다.
- Testcontainers 기반 실행은 serial로 유지한다.
- 새 test에는 `bluetape4k-assertions`만 사용한다.
- Public KDoc, PR body, commit message, GitHub artifact는 English로 유지한다.

## 작업 계획

| ID | 작업 | 입력 | 출력 | DoD |
|---|---|---|---|---|
| T1 | Container와 driver bootstrap 증명 | Official docs, Maven metadata, Docker availability | Test-scoped bootstrap fixture 설계 | Local command 또는 대상 test가 `com.starrocks.cj.jdbc.Driver`, `jdbc:starrocks://host:port`, explicit `CREATE DATABASE`, `SELECT 1`을 증명하거나 근거와 함께 environment blocker를 기록한다. |
| T2 | Module scaffold | Trino/ClickHouse/DuckDB module layout | build file, source/test resource, README locale file을 갖춘 `exposed/exposed-starrocks` | `./gradlew projects`에 `:bluetape4k-exposed-starrocks`가 표시되고 code 추가 후 module이 compile된다. |
| T3 | Dependency/catalog 갱신 | `gradle/libs.versions.toml`, Maven Central evidence | `starrocks-connector-j` alias와 module dependency | `dependencyInsight`로 dependency가 resolve되고 license 근거가 spec/PR에 남으며 central catalog claim을 하지 않는다. |
| T4 | Public connection API | Spec connection contract, existing OLAP wrappers | `StarRocksDatabase`, connection option, connection wrapper | Host/port/catalog/database 및 URL overload가 입력을 검증하고 wrapper 생성 실패 시 raw connection을 닫으며 KDoc에 지원 URL 형태와 transaction caveat가 적힌다. |
| T5 | Dialect와 metadata | Existing dialect pattern, StarRocks docs | `StarRocksDialect`, metadata adapter, optional `StarRocksTable` | Dialect가 `starrocks`로 등록되고 unsupported/unproven DDL feature는 근거를 바탕으로 disable 또는 우회하며 metadata smoke가 통과한다. |
| T6 | Test fixture와 smoke test | T1 bootstrap proof | Serial container base test와 집중 test | DB bootstrap, connection, `SELECT 1`, fixture table, insert/select, metadata catalog/schema/table/column discovery, validation failure, 선택한 DataSource 경로를 증명한다. |
| T7 | 문서 | Spec documentation contract | Root/module `README.md`, `README.ko.md` 갱신 | locale file에 StarRocks, dependency snippet, 지원/비지원 범위, Docker resource requirement, 사용 예가 포함된다. |
| T8 | 등록과 workflow | AGENTS, CI, Nightly, coverage aggregation | Module list와 workflow visibility 갱신 | `AGENTS.md`, CI path filter/job/artifact/summary `needs`, Nightly placement, BOM/check-script 검증을 마치고 workflow 변경 후 `actionlint`가 통과한다. |
| T9 | 검증과 review | 구현 diff | Local evidence와 Step 6-R review | IDE diagnostics 또는 fallback 기록, 대상 Gradle command, workflow lint, diff check, code review gate가 모두 `P0=0/P1=0`으로 통과한다. |
| T10 | Lesson, wiki evidence, commit, PR | 검증된 branch | Lesson artifact, research 보존 결정, Lore commit, PR body | lesson에 context/decision/outcome/evidence가 있고 #255가 기존 #227 note 밖의 decision-relevant research를 추가할 때만 wiki를 갱신하며 PR body는 Step DoD table로 끝나고 `debop`에게 할당되어 #255와 연결된다. |

## 세부 구현 순서

### T1. Container와 driver bootstrap

1. 최소 test를 compile할 수 있도록 `gradle/libs.versions.toml`에 StarRocks driver alias를 먼저 추가한다.
2. production launcher가 아닌 temporary/test-scoped singleton fixture를 만든다.
   - image: `starrocks/allin1-ubuntu`
   - exposed port: `9030`, `8030`, `8040`
   - username: `root`
   - password: 빈 문자열
   - readiness: `SELECT 1` poll
3. bootstrap에 `jdbc:starrocks://{host}:{port}`가 허용되는지 확인한다. 그렇지
   않으면 test database를 만들 수 있는 가장 작은 official URL과 raw SQL 순서를 사용한다.
4. stable prefix와 짧은 suffix로 deterministic test database 이름을 만들고 reusable container 환경에서 shared mutable name을 피한다.
5. 실제 bootstrap 동작을 test와 README에 기록한다.

### T2-T5. Module과 API

1. 가장 가까운 기존 module pattern으로 scaffold한다.
   - `exposed-trino` build file에 StarRocks driver dependency 추가
   - `exposed-clickhouse`/`exposed-trino` wrapper/factory lifecycle
   - `exposed-duckdb` dialect minimalism
2. public API를 구현한다.
   - `StarRocksConnectionOptions`: `Serializable` data class
   - `StarRocksDatabase`: registration 및 connect overload
   - `StarRocksConnectionWrapper`: Exposed-compatible autocommit/rollback 동작에 필요한 경우에만 추가
   - `StarRocksTable`: 실제 Exposed DDL에 StarRocks-specific SQL이 필요한 경우에만 추가
3. 가장 작은 검증된 dialect 변경만 구현한다.
   - source를 확인한 Exposed vendor dialect에서 시작한다.
   - 검증되지 않은 ALTER/type/generated-key/sequence 동작은 disable한다.
   - 실제 실패가 확인될 때까지 `DatabaseMetaData` 호출은 유지한다.
4. public class와 example에는 English KDoc을 사용한다.

### T6. 테스트

필수 test file:

- `AbstractStarRocksTest`
- `StarRocksDatabaseTest`
- `StarRocksDatabaseValidationTest`
- wrapper가 있으면 `StarRocksConnectionWrapperTest`
- `StarRocksDialectTest`
- `StarRocksMetadataTest`
- Exposed DDL을 지원하면 `SchemaUtilsTest` 또는 fixture setup test
- `insert/InsertTest`
- `query/SelectTest`
- `domain/Events.kt` 또는 대응 fixture table

필수 assertion:

- Driver/dialect registration:
  - `db.dialect.name == "starrocks"`
  - `db.dialect`가 `StarRocksDialect`
- Connection:
  - explicit DB bootstrap 후 host/port/catalog/database overload 성공
  - direct JDBC URL overload 성공
  - 구현했다면 DataSource overload 성공
- Validation:
  - 빈 host/catalog/database/user 거부
  - invalid port 거부
  - 잘못된 URL prefix 거부
  - 빈 option key/value 거부
- Backend proof:
  - `SELECT 1`
  - fixture table setup
  - 한 개 이상의 row insert
  - direct condition으로 row select
  - metadata가 생성한 table과 한 개 이상의 column 발견
- Transaction caveat:
  - wrapper가 autocommit/no-op rollback을 강제하면 기존 ClickHouse/Trino 동작과 같은 test 추가

### T7. 문서

다음을 갱신한다.

- root `README.md`
- root `README.ko.md`
- `exposed/exposed-starrocks/README.md`
- `exposed/exposed-starrocks/README.ko.md`

README 검사:

- language switch는 `English | 한국어`
- dependency snippet은 실제 artifact id 사용
- example은 실제 public API name 사용
- Docker requirement에 image, port, 4 GB RAM, 10 GB disk 명시
- 지원하지 않는 범위를 명시

### T8. 등록과 workflow

다음을 확인하고 갱신한다.

- `AGENTS.md` module 목록에 `bluetape4k-exposed-starrocks/` 포함
- `./gradlew projects`로 `settings.gradle.kts` auto-discovery 확인
- `gradle/libs.versions.toml`의 sibling JDBC driver 근처에 StarRocks alias 배치
- `.github/workflows/ci.yml`: `exposed/exposed-starrocks/**` path filter, 명시적 compile/test lane, test/coverage artifact, coverage summary `needs`
- `.github/workflows/nightly-tests.yml`: normal CI에 너무 무거우면 serial StarRocks Testcontainers lane, 아니면 CI와 explicit coverage parity, coverage summary `needs`
- BOM/catalog: `exposed/bluetape4k-exposed-bom`이 module constraint를 명시적으로 나열하는지 확인하고 필요 시 갱신
- generated check script: module/catalog을 명시적으로 나열하는 경우에만 갱신

## 검증 command

#255 worktree에서 실행한다.

```bash
./gradlew projects --no-configuration-cache --no-daemon
./gradlew :bluetape4k-exposed-starrocks:dependencyInsight --dependency starrocks-connector-j --configuration runtimeClasspath --no-configuration-cache --no-daemon
./gradlew :bluetape4k-exposed-starrocks:compileKotlin --no-configuration-cache --no-daemon
./gradlew :bluetape4k-exposed-starrocks:cleanTest :bluetape4k-exposed-starrocks:test --no-build-cache --no-configuration-cache --no-daemon
./gradlew :bluetape4k-exposed-starrocks:koverXmlReport --no-configuration-cache --no-daemon
actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml
git diff --check
```

IntelliJ MCP tool을 사용할 수 있으면 변경한 Kotlin file에 IDE diagnostics를
실행한다. 그렇지 않으면 fallback을 기록하고 Gradle compile/test를 근거로 사용한다.

Docker 또는 StarRocks 시작이 local에서 실패하면 Docker resource를 확인한 뒤
한 번 재실행하고 정확한 log excerpt로 environment, image, implementation
failure를 분류한다.

## Review와 PR

1. 구현과 검증 뒤 Step 6-R code review를 실행한다.
2. 모든 P0/P1 finding을 수정하고 영향받은 검증을 다시 실행한다.
3. `docs/lessons/2026-06-06-issue-255-starrocks-module.md`를 추가한다.
4. 기존 wiki note `bluetape4k-wiki/research/2026-06-06-exposed-olap-local-testability.md`를 확인한다. 구현 과정에서 아직 보존하지 않은 source-backed decision이 발견될 때만 갱신한다.
5. Lore trailer를 포함해 commit한다.
6. branch를 push하고 `debop`에게 할당하며 milestone `backlog`, `Closes #255`를 포함한 PR을 생성한다.
7. `gh pr view --json body`로 live PR body를 확인하고 마지막 `##` section이 `## DoD Status`인지 검증한다.

## 중단 조건

- 후속 issue 없이 spec 범위를 넘어 구현하지 않는다.
- 명시적인 사용자 merge 승인 없이 PR을 merge하지 않는다.
- native driver 또는 all-in-one image가 필요한 local proof를 제공하지 못하면 근거를 기록하고 #255/plan에 blocker를 갱신한 뒤 중단한다.
