# Issue #731 batch artifact ownership 분리 설계

## 문서 상태

- Issue: [#731](https://github.com/bluetape4k/bluetape4k-exposed/issues/731)
- 저장소: `bluetape4k/bluetape4k-exposed`
- 기준 ref: `origin/develop@b993fdd89d5fdc8d09fbe7ae9d5a3aeb30376331`
- workflow: Type A `bluetape-full-feature`
- 설계 승인: 기존 `bluetape4k-exposed-batch` compatibility aggregator 유지 방향을
  2026-08-26에 승인함
- 구현 상태: 설계 단계, source mutation 전

이 문서는 `utils/batch`의 public artifact 경계와 동시성 정책을 고정한다.
구현·계획·리뷰·PR에서 이 문서를 기준으로 삼으며, 문서에 없는 public API나
dependency 확장은 별도 이슈로 분리한다.

## 문제와 목표

현재 `utils/batch` 단일 모듈은 coroutine-native batch core, JDBC adapter,
R2DBC adapter, checkpoint JSON contract를 하나의 published artifact에 함께
노출한다. `build.gradle.kts`는 JDBC/R2DBC 모듈을 `compileOnly`로 끌어오고,
R2DBC repository는 `io.bluetape4k.batch.jdbc.tables`의 table/mapping을 직접
참조한다. 따라서 core만 사용하는 소비자도 backend surface를 가진 artifact를
선택해야 하며, R2DBC와 JDBC의 소유 경계가 코드에서 드러나지 않는다.

`InMemoryBatchJobRepository`는 모든 mutation을 하나의 intrinsic monitor로
감싼다. 현재 동작은 thread-safe이지만, coroutine·virtual-thread 환경에서
정책이 명시적이지 않고 경쟁 상태를 재현하는 회귀 테스트가 부족하다.

이번 변경의 목표는 다음과 같다.

1. batch core, JDBC adapter, R2DBC adapter의 published ownership을 분리한다.
2. 기존 `bluetape4k-exposed-batch` 소비자의 source/binary migration 부담을
   compatibility aggregator로 제한한다.
3. R2DBC production source에서 JDBC-owned package import를 제거한다.
4. `InMemoryBatchJobRepository`의 serialization 정책을 명시적 lock contract로
   바꾸고 claim/update 경쟁을 stress test로 고정한다.
5. BOM, module inventory, CI/Nightly, Kover, README·manual, ABI 검사까지 새
   module graph와 일치시킨다.

## 범위와 비범위

### 변경 범위

- `utils/batch/core` — `:bluetape4k-exposed-batch-core`
- `utils/batch/jdbc` — `:bluetape4k-exposed-batch-jdbc`
- `utils/batch/r2dbc` — `:bluetape4k-exposed-batch-r2dbc`
- `utils/batch` — 기존 `:bluetape4k-exposed-batch` compatibility aggregator와
  benchmark host
- `settings.gradle.kts`, `AGENTS.md`, root/module README 영어·한국어 문서,
  BOM/module inventory, `docs/manual/manifest.yaml`·generated manifest,
  CI/Nightly path filter·job·coverage aggregation
- 각 module의 test fixture와 `bluetape4k-assertions` 기반 회귀·conformance test
- Issue-linked 설계·계획·review·lesson 및 Korean PR DoD

### 비범위

- `spring-boot/batch-exposed`의 Spring Batch integration 재설계
- Batch API 이름·status semantics·checkpoint wire format 변경
- JaVers, Spring, Ktor 또는 다른 backend에 대한 새 adapter
- 별도 published `batch-schema` artifact 추가
- 기존 aggregator 제거, major-version 강제 migration, 기존 public class 삭제
- benchmark 시나리오·측정값의 의미 변경

## 현재 근거와 source ledger

| 근거 | 현재 사실 | 설계 영향 |
|---|---|---|
| `utils/batch/build.gradle.kts` | core dependency와 JDBC/R2DBC `compileOnly`가 한 module에 섞임 | 선택적 artifact의 직접 dependency graph를 만든다 |
| `utils/batch/src/main/kotlin/io/bluetape4k/batch/api/**` | core public contract 집합 | `batch-core`의 public API로 이동한다 |
| `.../batch/core/**` | runner, DSL, in-memory repository | `batch-core`에 둔다 |
| `.../batch/internal/CheckpointJson.kt` | JDBC/R2DBC가 함께 사용하는 checkpoint JSON contract | Exposed 의존성 없이 `batch-core`가 소유하고 `io.bluetape4k.batch.CheckpointJson` stable public API로 승격한다 |
| `.../batch/jdbc/**` | JDBC repository, reader, writer, tables, mapper | `batch-jdbc`에 둔다 |
| `.../batch/r2dbc/**` | R2DBC repository, reader, writer | `batch-r2dbc`가 자체 table/mapping을 소유한다 |
| `ExposedR2dbcBatchJobRepository.kt` import | `io.bluetape4k.batch.jdbc.tables.*`를 직접 참조 | R2DBC source의 JDBC reverse edge를 금지한다 |
| `InMemoryBatchJobRepository.kt` | `synchronized(lock)`가 전 연산의 serialization 경계 | interruptible `ReentrantLock` helper와 짧은 임계구역으로 교체한다 |
| `settings.gradle.kts` | `utils/batch`만 명시적 mapped module | 세 child module과 aggregator를 모두 등록한다 |
| `.github/workflows/ci.yml`, `nightly-tests.yml` | `:bluetape4k-exposed-batch` 단일 task/path | nested module path와 aggregate coverage를 동기화한다 |
| `docs/manual/{en,ko}/modules/bluetape4k-exposed-batch.md` | 단일 artifact 중심 설명 | 선택 artifact와 compatibility 경계를 추가한다 |

## 결정된 구조

### Published module graph

```text
bluetape4k-exposed-batch-core
        ▲
        │ api
  ┌─────┴─────┐
  │           │
batch-jdbc  batch-r2dbc
  ▲           ▲
  └─────┬─────┘
        │ api
bluetape4k-exposed-batch  (compatibility aggregator + benchmark host)
```

실제 디렉터리와 Gradle project 이름은 다음과 같다.

| 디렉터리 | project | 책임 |
|---|---|---|
| `utils/batch/core` | `:bluetape4k-exposed-batch-core` | API, core runner/DSL, in-memory repository, checkpoint contract |
| `utils/batch/jdbc` | `:bluetape4k-exposed-batch-jdbc` | JDBC repository/reader/writer, JDBC table·mapper |
| `utils/batch/r2dbc` | `:bluetape4k-exposed-batch-r2dbc` | R2DBC repository/reader/writer, R2DBC table·mapper |
| `utils/batch` | `:bluetape4k-exposed-batch` | 세 artifact의 `api` aggregator, benchmark source set, benchmark 문서 생성 |

기존 package 이름(`io.bluetape4k.batch.api`, `.core`, `.jdbc`, `.r2dbc`)은
가능한 한 유지한다. 디렉터리와 artifact 분리는 published class name을 보존하며,
ABI baseline은 이동 전후 descriptor를 비교해 의도하지 않은 삭제를 차단한다.

### Dependency contract

- `batch-core`는 `bluetape4k-core`, `bluetape4k-coroutines`,
  `bluetape4k-logging`, `bluetape4k-workflow`, coroutine BOM 및 필요한
  coroutine runtime만 직접 사용한다.
- `batch-core`의 `apiElements`와 published POM에는
  `bluetape4k-exposed-jdbc`, `bluetape4k-exposed-r2dbc`, JDBC/R2DBC driver가
  나타나지 않아야 한다.
- `batch-core`는 default `CheckpointJson.jackson3()` 구현을 위해 현재와 같은
  Jackson 3 경계를 유지하되, Exposed adapter를 참조하지 않는다. Jackson 3는
  **의도적인 `compileOnly` optional runtime**으로 고정한다. 따라서 custom
  `CheckpointJson` 소비자는 Jackson 없이 실행할 수 있고, `jackson3()` 호출자는
  `bluetape4k-jackson3`를 직접 runtime classpath에 선언해야 한다. 이 계약은
  `apiElements`, `runtimeElements`, published POM/Gradle module metadata와
  Jackson 유무의 isolated consumer compile·runtime 두 profile로 fail-closed
  검증한다.
- `CheckpointJson`은 현재 `internal` package에 있지만 public adapter 생성자에
  노출되어 있으므로, split 시 `io.bluetape4k.batch.CheckpointJson`의 안정적인
  public API로 이동한다. `TypedCheckpoint`와 registry 구현 세부사항은 internal로
  남긴다. Jackson 없는 외부 consumer가 custom strategy를 구현·주입할 수 있는
  compile fixture와 Jackson 있는 `jackson3()` runtime fixture를 각각 둔다.
- `batch-jdbc`는 `batch-core`와 `bluetape4k-exposed-jdbc`, JDBC 전용
  Exposed/time/virtual-thread dependency를 소유한다.
- `batch-r2dbc`는 `batch-core`와 `bluetape4k-exposed-r2dbc`, R2DBC 전용
  Exposed/time dependency를 소유한다.
- aggregator는 세 child module을 `api(project(...))`로 노출해 기존 소비자가
  기존 artifact 하나만 선언해도 현재 public class 집합을 계속 사용할 수 있게
  한다. aggregator에 backend implementation dependency를 다시 추가하지 않는다.
- benchmark source set은 aggregator에 유지한다. benchmark compilation에는
  필요한 세 child artifact를 명시적으로 연결해 기존 task 이름과 문서 경로를
  보존한다.
- aggregator의 기존 JAR에 있던 effective public class surface가 child JAR로
  이동해도 사라지지 않는지 old aggregator consumer compile/runtime fixture,
  child JAR 포함 class inventory, `checkProductionAbi` 및 non-empty API baseline
  read-back으로 검증한다. 단순 transitive POM 노출이나 baseline suppression을
  ABI 보존 근거로 인정하지 않는다.
- aggregator benchmark source set은 `benchmarkImplementation` 상속,
  child project classpath, existing test fixture association을 명시적으로
  wiring하고, R2DBC child가 JDBC test infrastructure를 끌어오지 않는지
  configuration report로 검사한다.

### Schema와 mapping ownership

Exposed `Table`과 `ResultRow` mapper는 core에 둘 수 없다. core가 Exposed
backend에 의존하지 않아야 하기 때문이다. 또한 기존 JDBC mapper를 R2DBC가
재사용하는 reverse edge도 허용하지 않는다.

- JDBC module은 `io.bluetape4k.batch.jdbc.tables` 아래의 기존 table·mapper를
  소유한다.
- R2DBC module은 `io.bluetape4k.batch.r2dbc.tables` 아래에 동일한 column명,
  nullable 규칙, enum/status mapping, checkpoint/params encoding을 가진 자체
  table·mapper를 소유한다.
- 두 adapter의 schema contract는 production code 공유가 아니라 test-only
  schema descriptor/parity fixture로 비교한다. column명, SQL type 의도,
  unique/index/nullable 및 params/checkpoint encoding이 다르면 test가 실패한다.
- R2DBC production source와 test source에서 `io.bluetape4k.batch.jdbc`를
  참조하지 않는다. 이 규칙은 `rg` negative scan으로 검증한다.

### Existing schema migration contract

이번 변경은 table명·column명·nullable·default·status encoding·checkpoint/params
encoding·unique/index semantics를 바꾸지 않는 artifact 이동이다. 따라서
**migration은 필요하지 않다**는 결론을 먼저 고정한다. 구현은 다음 legacy-schema
oracle을 통과해야 한다.

- 기준 branch의 JDBC/R2DBC table DDL 또는 이미 생성된 legacy schema에서 새
  adapter가 `findOrCreate`, `claim`, `complete`, `checkpoint`를 읽고 쓸 수 있다.
- H2, PostgreSQL, MySQL_V8 metadata를 사용해 table/column/type/nullability와
  unique/index contract를 비교한다. PostgreSQL의 partial unique index처럼
  module 밖 SQL이 필요한 항목은 기존 문서·fixture를 그대로 재사용한다.
- migration SQL을 새로 추가하지 않으며, schema mismatch가 발견되면 partial
  DDL을 남기지 않고 구현을 중단한다. 실제 migration 필요성은 이 issue의
  범위를 넘어 별도 issue로 분리한다.

### Compatibility contract

1. `:bluetape4k-exposed-batch`가 제공하던 기존 class와 package는 유지한다.
2. 기존 dependency snippet은 aggregator로 동작하며, 선택 artifact migration
   예제를 영어·한국어 README와 manual에 함께 기록한다.
3. 기존 benchmark task(`h2JdbcBenchmark`, `h2R2dbcBenchmark`,
   `postgresJdbcBenchmark`, `postgresR2dbcBenchmark`, `mysqlJdbcBenchmark`,
   `mysqlR2dbcBenchmark`, `generateBenchmarkDocs`)는 이름과 의미를 유지한다.
4. 새 selective artifact는 `batch-core`, `batch-jdbc`, `batch-r2dbc` 순으로
   사용한다. JDBC/R2DBC adapter를 함께 써야 하는 기존 사용자는 aggregator를
   유지할 수 있다.
5. public ABI baseline, jar class inventory, isolated consumer compile, Gradle
   POM/dependency report를 모두 확인한다. `CheckpointJson`을
   `io.bluetape4k.batch.CheckpointJson`으로 승격할 때 기존
   `io.bluetape4k.batch.internal.CheckpointJson` JVM descriptor가 baseline에
   있으면 deprecated public bridge interface와 constructor overload를 한 minor
   line 동안 유지한다. bridge는 stable public interface를 상속하고 새 구현은
   stable type을 사용한다. descriptor ledger에는 interface, `Companion.jackson3()`
   반환형, `toJobExecution`/`toStepExecution` mapper, JDBC/R2DBC constructor를
   symbol별로 기록하고 필요한 bridge를 모두 유지한다. descriptor가 baseline에
   없으면 bridge를 만들지 않되 그 판단을 ABI artifact와 migration 문서에
   기록한다. baseline을 단순 suppression으로 갱신하지 않는다.

선택 artifact migration 예제는 다음 좌표와 경계를 그대로 고정한다. 모든
예제는 개별 버전을 적지 않고 `io.github.bluetape4k:bluetape4k-dependencies`
BOM을 import한다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-batch-core")
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-batch-jdbc")
    // R2DBC consumer는 위 JDBC 좌표 대신 다음 좌표를 선택한다.
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-batch-r2dbc")
}
```

Maven 예제는 동일 BOM을 `dependencyManagement`에서 import하고
`io.github.bluetape4k.exposed:bluetape4k-exposed-batch[-core|-jdbc|-r2dbc]`
좌표에 버전을 적지 않는다. 기존 사용자는
`io.github.bluetape4k.exposed:bluetape4k-exposed-batch` aggregator 하나를
그대로 유지할 수 있다. 새 package는 `io.bluetape4k.batch.CheckpointJson`이며,
ABI baseline에 옛 `io.bluetape4k.batch.internal.CheckpointJson` descriptor가
있을 때만 한 minor line 동안 deprecated bridge와 constructor overload를
유지한다. Jackson 없는 custom strategy 예제는 `CheckpointJson`을 직접
구현·주입하고, `CheckpointJson.jackson3()` 예제는
`io.github.bluetape4k:bluetape4k-jackson3`를 runtime classpath에 추가한다.
이 다섯 경계(aggregator, core/custom, JDBC, R2DBC/Jackson 3, legacy binary)는
`utils/batch/consumer-fixtures/{aggregator-runtime,core-custom-json,jdbc-runtime,r2dbc-jackson3-runtime,legacy-binary-runtime}`
독립 Gradle fixture로 compile과 runtime smoke를 각각 검증한다. `legacy-binary-runtime`은
1.12.1 aggregator를 기준으로 컴파일한 Java class를 현재 aggregator에서 재실행해
옛 `internal.CheckpointJson` JVM descriptor bridge를 검증한다.

fixture 검증은 local publication을 먼저 만들고 각 fixture를 독립 Gradle
프로세스로 실행한다. `core-custom-json`는 Jackson 없는 classpath,
`r2dbc-jackson3-runtime`은 `io.github.bluetape4k:bluetape4k-jackson3`가 있는
classpath를 강제한다.

`aggregator-runtime`, `core-custom-json`, `jdbc-runtime`,
`r2dbc-jackson3-runtime`, `legacy-binary-runtime`는 서로 다른 최소 dependency
profile로 분리하고, `maven-jdbc-runtime` Maven fixture도 추가한다. fixture는 임시 local repository와
현재 source head provenance를 확인하며 remote 또는 stale artifact fallback을
허용하지 않는다.

```bash
ISSUE731_MAVEN_LOCAL="$PWD/.gradle/issue-731-maven-local"
./gradlew publishPublicationValidation \
  -Dmaven.repo.local="$ISSUE731_MAVEN_LOCAL" \
  --no-configuration-cache --no-daemon --console=plain
for fixture in \
  aggregator-runtime core-custom-json jdbc-runtime r2dbc-jackson3-runtime legacy-binary-runtime; do
  ./gradlew -p "utils/batch/consumer-fixtures/$fixture" clean compileKotlin test \
    -Dmaven.repo.local="$ISSUE731_MAVEN_LOCAL" --offline \
    --no-configuration-cache --no-daemon --console=plain
done
mvn -o -Dmaven.repo.local="$ISSUE731_MAVEN_LOCAL" -f \
  utils/batch/consumer-fixtures/maven-jdbc-runtime/pom.xml clean test
```

### Security and trust boundary contract

- `CheckpointJson`은 caller-owned serialization strategy다. 기본 Jackson 3
  구현은 registry에 등록된 class만 해석하며 임의 `Class.forName`을 수행하지
  않는다. 이 issue는 persisted params/checkpoint에 암호화·MAC·size quota를
  새로 약속하지 않는다. secret이나 규제 데이터를 저장하는 소비자는
  encryption/MAC을 포함한 custom `CheckpointJson`을 주입해야 한다.
- params/checkpoint의 plaintext 저장은 기존 contract이며, 새 module이 이를
  log·exception message·dependency metadata로 재노출해서는 안 된다. raw
  payload, className, caller params를 추가로 출력하지 않는 redaction
  characterization test를 둔다. 기존 malformed params 처리와 checkpoint
  size/depth 제한은 이 artifact split의 동작 변경 대상이 아니다. baseline에서
  문제가 재현되면 보안 hardening follow-up으로 분리하고, 이 issue에서는 해당
  위험을 해결했다고 주장하지 않는다.
- baseline의 malformed/unknown checkpoint 예외에 `className`이 포함될 수 있는
  동작은 이번 split에서 제거하거나 확대하지 않는다. 새 adapter의 logging·예외
  wrapping이 raw payload·caller params·className을 추가 노출하지 않는 것만 이
  issue의 보안 경계로 삼고, 기존 오류 메시지까지 redaction하는 작업은 별도
  hardening issue로 추적한다.
- `BatchJobRepository.saveCheckpoint(stepExecutionId, checkpoint)` ID-only
  overload는 기존 source 호환성을 위한 **trusted/admin legacy escape hatch**로
  명시한다. 정상 `BatchStepRunner` 경로는 owner-aware overload만 사용한다.
  version을 CAS와 함께 증가시키므로 owner-aware 경로에는 additive
  `saveCheckpointAndReturn(execution, checkpoint): StepExecution`을 둔다. 기존
  Unit 반환 `saveCheckpoint(execution, checkpoint)` surface는 유지하고 새
  method를 호출한 뒤 runner local execution을 반환값으로 갱신한다. JDBC/R2DBC
  update와 반환 실행 상태는 한 transaction에서 원자적으로 처리하며, 두 번째
  chunk와 최종 complete가 stale version으로 실패하지 않는 반복 checkpoint
  회귀 test를 둔다.
  interface의 owner-aware default 구현은 ID-only overload로 위임하지 않고
  `UnsupportedOperationException`으로 fail-closed 한다. InMemory/JDBC/R2DBC
  구현은 모두 owner ID와 version을 조건으로 하는 atomic CAS를 수행하며, 영향
  row가 정확히 1이 아니면 명시적인 `IllegalStateException`을 던진다. InMemory도
  같은 mismatch 예외 계약을 사용하고 ID-only 경로만 기존 trusted 동작을 유지한다.
  owner-aware 호출에서 `execution.ownerId == null` 또는 blank이면 DB 접근 전에
  `IllegalStateException`으로 거부하며, SQL `IS NULL` 조건으로 우회하지 않는다.
  unclaimed/null owner, 다른 owner, stale version, affected-row 0건을 각각
  회귀 test로 고정한다.
  overload 제거 또는 권한 모델 승격은 별도 breaking-change issue다.
- job/step 이름은 log에 들어갈 수 있으므로 control character/newline을
  허용하지 않는 입력 검증을 선택한다. `requireValidBatchName`을
  `findOrCreateJobExecution`·`findOrCreateStepExecution`의 InMemory/JDBC/R2DBC
  직접 public entry point, `BatchJob`·`BatchStep` public constructor와
  `BatchStepRunner` DSL 경계 모두에 적용하고, 기존 저장 데이터의 read path는
  깨지 않게 한다. 검증 실패 메시지에는 원문 이름을 재삽입하지 않으며, 직접
  constructor와 repository entry point 각각의 control-character 회귀 test를
  둔다.
- `BatchJobBuilder`와 `BatchStepBuilder` public constructor/init 경계도
  `requireValidBatchName`을 즉시 적용한다. `build()` 시점에만 지연하지 않으며,
  control-character와 원문 미노출을 직접 검증한다.

## 동시성 contract

`InMemoryBatchJobRepository`의 serialization 정책은 다음과 같이 고정한다.

- `private val lock = ReentrantLock()`을 사용한다. suspend API에서 lock 대기가
  취소에 반응하도록 `runInterruptible { lock.lockInterruptibly(); ... }` 형태의
  작은 helper를 두고, lock을 획득한 뒤 memory-only block을 실행한 다음
  `finally`에서 반드시 unlock한다. lock 보유 중에는 suspend, I/O, callback,
  user code를 호출하지 않는다. 단순 `lock()`/`synchronized` 재도입은 금지한다.
- `findOrCreate*`, `claim*`, `complete*`, owner 검증과 checkpoint 저장의
  atomicity는 기존과 동일하게 보존한다.
- 현재 함수가 `suspend`인 것은 API contract이므로 유지하되, 내부 임계구역은
  메모리 map·clock·copy 연산으로 제한한다. lock 대기 중 취소, lock 보유자의
  취소, 취소 후 후속 호출의 세 상태를 별도 bounded test로 검증한다.
- `CancellationException`을 catch해 삼키지 않는다. lock 해제는 helper의
  `finally`에 맡긴다.
- `ConcurrentHashMap`과 `AtomicLong`의 역할은 보존하고 lock을 제거해
  lock-free로 위장하지 않는다. 이 issue의 목표는 잘못된 monitor 정책을
  명시적 serialization contract로 바꾸는 것이다.

이 저장소는 테스트·단순 사용 용도의 bounded in-memory 구현이며, 현재
`values.firstOrNull` O(n) 탐색과 전역 serialization을 공개 throughput SLA로
보장하지 않는다. 이번 issue에서는 정확성·취소·deadlock 부재만 acceptance로
고정한다. 대규모 cardinality를 위한 keyed index/lock striping은 별도 성능
issue로 남긴다.

필수 concurrency oracle은 다음과 같다.

| 시나리오 | 기대 결과 |
|---|---|
| 같은 job/params 동시 `findOrCreate` | 재시작 가능한 실행이 하나만 생긴다 |
| 동일 execution 동시 `claim` | 한 caller만 version/owner를 획득한다 |
| 만료 lease와 유효 lease 경쟁 | 만료된 경우에만 새 owner가 획득한다 |
| owner가 다른 `complete`/checkpoint | 저장하지 않고 기존 상태를 보존한다 |
| concurrent step creation | 동일 job·step 조합이 중복 생성되지 않는다 |
| cancellation 중 lock 경계 | 다음 호출이 교착 없이 실행되고 예외가 전파된다 |

DB adapter의 SELECT-후-INSERT step 경합은 이번 issue에서 새 분산 lock을
도입하지 않는다. 기존 unique/index contract와 winner re-query 동작을 JDBC/
R2DBC conformance test로 characterization하고, 현재 adapter가 중복 row 또는
재조회 불가를 보이면 source split을 중단해 별도 persistence-race issue로
분리한다. `BatchReader.checkpoint()` cleanup은 일반 예외만 best-effort로
기록한다. cleanup에서 발생한 `CancellationException`은 `runCatching`으로
버리지 않고 primary STOPPED `CancellationException`에 suppressed cause로
보존한 뒤 primary cancellation을 다시 전파한다. reader/writer close와 STOPPED
저장의 suspend lifecycle에서 기존 `runCatching` 경계가 primary cancellation을
삼키지 않는지 같은 정책으로 수정한다. 별도 cancellation test로 primary 전파와
suppressed 보존을 함께 고정한다.

## 테스트 전략

### RED/GREEN 순서

1. 현재 단일 module의 core/JDBC/R2DBC focused test와 ABI/dependency baseline을
   기록한다.
2. 새 module layout과 compatibility consumer 테스트가 실패하는 RED를 먼저
   고정한다.
3. source 이동과 build graph를 구현한다.
4. core unit/concurrency, JDBC H2, R2DBC H2를 먼저 GREEN으로 만든다.
5. PostgreSQL·MySQL Testcontainers 테스트는 기존 정책대로 서로 직렬 실행한다.
6. aggregator benchmark, ABI, POM, module inventory, CI/Nightly registration을
   검증한다.

이번 issue는 DB round-trip이나 checkpoint serialization을 최적화하지 않는다.
기준 branch의 `find-then-insert`, `update-then-select`, checkpoint transaction과
`CheckpointJson` envelope 순서를 source ledger로 고정하고, source 이동 후 동일한
operation sequence를 유지한다. baseline 379 tests/7 skips와 H2 JDBC/R2DBC
benchmark raw JSON을 split 후 다시 수집한다. 이 evidence는 성능이 개선되었다는
주장이 아니라 round-trip·serialization semantics가 바뀌지 않았다는 회귀 기준이다.
query-count 최적화나 payload quota 도입은 별도 성능/보안 issue로 분리한다.
각 benchmark profile 실행은 해당 profile만 지정하는 Gradle finalizer가 새
report directory에 `metadata.json` sidecar를 자동 생성한다. finalizer는 다른
profile의 기존 report나 이미 존재하는 sidecar를 다시 라벨링하지 않는다. 이미
존재하는 raw report tree를 재수집할 때는 `writeBenchmarkSidecars` task를
명시적으로 실행할 수 있으며, 기존 sidecar가 stale이면 fail-closed한다.
sidecar에는 `runId`, `sourceRef`, 현재 `sourceHead`, 실행 `environment`,
`warmups`, `iterations`, `metric`을 기록하며, 검증 시 sourceHead가 현재 HEAD와
같고 runId가 report directory와 일치해야 한다. 이전 실행의 stale JSON이나
metadata가 섞이거나 알려진 여섯 profile 밖의 결과가 있으면 benchmark gate는
실패한다. report root나 결과가 비어 있어도 성공으로 처리하지 않는다.
`generateBenchmarkDocs`는 raw
JSON과 이 metadata가 모두 있을 때만 측정 표를 생성하고, 없으면 pending
placeholder를 남기되 검증은 실패한다.

### Assertion·Kotlin 규칙

- 새 Kotlin test는 `io.bluetape4k.assertions` matcher를 사용한다.
- 예외·취소·identity·collection·status 검증에 의도에 맞는 bluetape4k
  assertion을 사용하고 raw JUnit assertion 추가를 피한다.
- `println`, 새 `!!`, 임의 `runBlocking`, monitor 재도입을 허용하지 않는다.
- log는 기존 KLogging/KLoggingChannel과 lazy message 형식을 유지한다.
- coroutine cancellation은 `CancellationException`을 그대로 전파한다.

### 검증 명령 계약

구현 후 기준 명령은 다음과 같다. 실제 test count는 fresh XML과 Gradle output으로
기록하며, 환경 의존 Testcontainers를 skip한 경우 PASS로 치환하지 않는다.

```bash
set -euo pipefail
./gradlew projects --no-daemon --console=plain
./gradlew :bluetape4k-exposed-batch-core:test --no-configuration-cache --no-daemon --console=plain
./gradlew :bluetape4k-exposed-batch-jdbc:test --no-configuration-cache --no-daemon --console=plain
./gradlew :bluetape4k-exposed-batch-r2dbc:test --no-configuration-cache --no-daemon --console=plain
./gradlew :bluetape4k-exposed-batch:test --no-configuration-cache --no-daemon --console=plain
./gradlew :bluetape4k-exposed-batch:checkKotlinAbi --no-configuration-cache --no-daemon --console=plain
./gradlew :bluetape4k-exposed-batch-core:checkKotlinAbi \
  :bluetape4k-exposed-batch-jdbc:checkKotlinAbi \
  :bluetape4k-exposed-batch-r2dbc:checkKotlinAbi \
  --no-configuration-cache --no-daemon --console=plain
./gradlew detekt --no-configuration-cache --no-daemon --console=plain
git diff --check
if command -v colima >/dev/null 2>&1; then
  colima status
fi
docker context show
docker info --format '{{.ServerVersion}}'
test -S "${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-/var/run/docker.sock}"
for db in H2 POSTGRESQL MYSQL_V8; do
  EXPOSED_TEST_DB="$db" ./gradlew :bluetape4k-exposed-batch:test \
    --no-configuration-cache --no-daemon --console=plain
done
actionlint .github/workflows/*.yml
./gradlew :bluetape4k-exposed-batch-core:koverXmlReport \
  :bluetape4k-exposed-batch-jdbc:koverXmlReport \
  :bluetape4k-exposed-batch-r2dbc:koverXmlReport \
  :bluetape4k-exposed-batch:koverXmlReport \
  --no-configuration-cache --no-daemon --console=plain
for report in \
  utils/batch/core/build/reports/kover/report.xml \
  utils/batch/jdbc/build/reports/kover/report.xml \
  utils/batch/r2dbc/build/reports/kover/report.xml \
  utils/batch/build/reports/kover/report.xml; do
  test -s "$report"
done
test -d utils/batch/core
test -d utils/batch/jdbc
test -d utils/batch/r2dbc
test -d utils/batch/src/benchmark/kotlin/io/bluetape4k/batch/benchmark/r2dbc
if rg -n 'io\.bluetape4k\.batch\.jdbc' \
  utils/batch/r2dbc/src utils/batch/src/benchmark/kotlin/io/bluetape4k/batch/benchmark/r2dbc; then
  echo 'forbidden JDBC ownership reference found in R2DBC paths' >&2
  exit 1
else
  scan_status=$?
  test "$scan_status" -eq 1
fi
./gradlew checkProductionAbi --no-configuration-cache --no-daemon --console=plain
task_output="$(./gradlew :bluetape4k-exposed-batch:tasks --all --no-configuration-cache --no-daemon --console=plain)"
printf '%s\n' "$task_output" | rg 'h2(Jdbc|R2dbc)Benchmark|generateBenchmarkDocs'
./gradlew :bluetape4k-exposed-batch:h2JdbcBenchmark \
  :bluetape4k-exposed-batch:h2R2dbcBenchmark \
  --no-configuration-cache --no-daemon --console=plain
./gradlew :bluetape4k-exposed-batch:generateBenchmarkDocs \
  --no-configuration-cache --no-daemon --console=plain
current_head="$(git rev-parse HEAD)"
for profile in h2Jdbc h2R2dbc; do
  profile_root="utils/batch/build/reports/benchmarks/$profile"
  test -d "$profile_root"
  report_file="$(find "$profile_root" -mindepth 2 -maxdepth 2 -type f -name benchmark.json -print | sort | tail -n 1)"
  test -n "$report_file"
  test -s "$report_file"
  python3 - "$report_file" <<'PY'
import json
import math
import pathlib
import sys

report_path = pathlib.Path(sys.argv[1])
document = json.loads(report_path.read_text(encoding="utf-8"))

def has_measured_entry(value):
    if isinstance(value, dict):
        benchmark = value.get("benchmark")
        if isinstance(benchmark, str) and benchmark.strip():
            score = value.get("score")
            primary_metric = value.get("primaryMetric")
            if isinstance(primary_metric, dict):
                score = primary_metric.get("score", score)
            if isinstance(score, (int, float)) and not isinstance(score, bool) and math.isfinite(score):
                return True
        return any(has_measured_entry(child) for child in value.values())
    if isinstance(value, list):
        return any(has_measured_entry(child) for child in value)
    return False

if not has_measured_entry(document):
    raise SystemExit("benchmark JSON has no finite measured benchmark/score entry")
PY
  metadata_file="$(dirname "$report_file")/metadata.json"
  test -s "$metadata_file"
  python3 - "$metadata_file" "$current_head" "$(dirname "$report_file")" <<'PY'
import json
import pathlib
import sys

metadata_path = pathlib.Path(sys.argv[1])
current_head = sys.argv[2]
run_dir = pathlib.Path(sys.argv[3])
metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
for key in ("sourceRef", "sourceHead", "environment", "metric", "runId"):
    value = metadata.get(key)
    if not isinstance(value, str) or not value.strip():
        raise SystemExit(f"benchmark metadata field must be a non-blank string: {key}")
if metadata["sourceHead"] != current_head:
    raise SystemExit("benchmark metadata sourceHead does not match current HEAD")
if metadata["runId"] != run_dir.name:
    raise SystemExit("benchmark metadata runId does not match report directory")
for key in ("warmups", "iterations"):
    value = metadata.get(key)
    if type(value) is not int or value <= 0:
        raise SystemExit(f"benchmark metadata field must be a positive integer: {key}")
PY
done
for generated_doc in utils/batch/benchmark/h2.md utils/batch/benchmark/README.md; do
  test -s "$generated_doc"
  if rg -ni 'pending' "$generated_doc"; then
    echo "pending benchmark placeholder remains in $generated_doc" >&2
    exit 1
  else
    doc_status=$?
    test "$doc_status" -eq 1
  fi
done
./gradlew :bluetape4k-exposed-batch-core:dependencies \
  --configuration apiElements --no-configuration-cache --no-daemon --console=plain
core_pom="$(find utils/batch/core/build/publications -type f -name '*.pom' -print | head -n 1)"
test -s "$core_pom"
if rg -n 'bluetape4k-exposed-(jdbc|r2dbc)|jdbc|r2dbc' "$core_pom"; then
  echo 'backend dependency leaked into batch-core POM' >&2
  exit 1
else
  pom_scan_status=$?
  test "$pom_scan_status" -eq 1
fi
git diff --name-only origin/develop...HEAD > /tmp/issue-731-changed-paths.txt
test -s /tmp/issue-731-changed-paths.txt
rg -n 'utils/batch/(core|jdbc|r2dbc)|utils/batch/build.gradle.kts|\.github/workflows' \
  /tmp/issue-731-changed-paths.txt
```

`EXPOSED_TEST_DB=H2`, PostgreSQL, MySQL_V8의 실행을 구분한다. Docker-backed
실행은 macOS Colima/Docker preflight를 확인하고, PostgreSQL·MySQL·Redis 관련
경로를 병렬 실행하지 않는다. Nightly matrix를 유지할 경우
`strategy.max-parallel: 1`을 명시하고, terminal summary가 H2/PostgreSQL/MySQL
job의 conclusion을 모두 확인한다. 단일 Gradle process의 `TestMutexService`만으로
서로 다른 runner job의 병렬성을 안전하다고 주장하지 않는다.

## 등록·문서·운영 surface

새 module은 다음 registration chain을 한 변경에서 갱신한다.

- `settings.gradle.kts` project include와 `./gradlew projects` 결과
- repo `AGENTS.md` module map 및 module README 영어·한국어
- root README와 `docs/manual/en`, `docs/manual/ko`의 artifact 선택 표
- `exposed/bom` module inventory, catalog/check script, publication metadata
- `docs/manual/manifest.yaml`에 aggregator와 세 child를 각각 등록하고,
  각 항목의 `gradlePath`, `sourceDir`, `sourcePaths`, `testPaths`, `artifact`,
  EN/KO 문서 경로를 실제 파일과 일치시킨다. child manual은
  `docs/manual/{en,ko}/modules/bluetape4k-exposed-batch-{core,jdbc,r2dbc}.md`
  로 고정하며, aggregator manual의 source link도 split 후 경로로 갱신한다.
  현재 stable release tree(`1.12.1`/
  `4cc2cce07087241ec24a597d8464615434ea2b81`)에는 nested child source path가
  없으므로 child entry에는 `releaseStatus: develop-only`를 기록한다. stable
  release 검증 대상 repository-relative link는 기존 `utils/batch` 경계만
  가리키며, 다음 release promotion에서 새 tag/commit과 EN/KO child source
  link를 함께 pin해 stable로 승격한다.
  batch runtime diagram을 child 문서에서 재사용하거나 분리할 때
  `docs/manual/release-diagrams.yaml`의 target·source 목록과 SVG/PNG pair를
  함께 갱신하고, 새 diagram이 불필요하면 그 판단을 manifest review에 기록한다.
  `releaseRef`와 `releaseCommit`은 release tree에 존재하는 값을 유지한다.
  `docs/manual/generated/manifest.json`은 수동 편집하지 않고 export 단계에서
  생성한다.
- root `build.gradle.kts`의 `productionAbiProjects` count/expected set과 `api/*.api`
  baseline. 현재 35 JVM module 불변식은 child 3개 등록 후 38개 expected set으로
  의도적으로 갱신하며, 실제 `checkProductionAbi` output으로 확인한다.
- `.github/workflows/ci.yml` path filter, job, coverage artifact와 summary `needs`
- `.github/workflows/nightly-tests.yml`의 H2/real DB job, coverage와 required summary
- Kover aggregation 및 changed-path selection
- benchmark README와 generated benchmark docs 경로

README와 manual의 소비자 예시는 개별 Bluetape 버전을 pin하지 않고
`bluetape4k-dependencies` BOM alias를 사용한다. 기존 aggregator 사용법과
selective artifact 사용법을 같은 문서 쌍에서 설명하며, `batch-core`가
JDBC/R2DBC driver를 끌어오지 않는다는 점을 명시한다.

Manual registration gate는 다음 순서를 고정한다. 새 Gradle project를 추가한
뒤 `./gradlew exportManualModuleInventory`,
`ruby scripts/manual/validate_manuals.rb build/manual/module-inventory-<version>.json docs/manual/manifest.yaml`,
그리고 release 기준 ref/commit을 지정한
`ruby scripts/manual/validate_release_manuals.rb <release-tag> <release-commit>`를
순서대로 실행한다. inventory에 있으나 manifest에 없는 child, 존재하지 않는
EN/KO 문서·source/test path, release ref에 없는 링크가 하나라도 있으면
delivery gate를 실패시킨다.

## 실패 모드와 완화

| 실패 모드 | 탐지 | 완화/중단 기준 |
|---|---|---|
| core POM에 backend dependency 누출 | `dependencies`, `dependencyInsight`, POM scan | 누출이면 P1; implementation/compileOnly 경계를 수정하고 재검증 |
| R2DBC가 JDBC package를 재참조 | source negative scan | 발견 즉시 P1; R2DBC table·mapper를 자체 소유로 이동 |
| ABI inventory가 새 child module을 누락 | `checkProductionAbi`, `api/*.api`, publication inventory | 현재 35개 baseline에 child 3개를 더한 expected set을 갱신하고, 실제 set·baseline·POM이 같아질 때까지 중단 |
| aggregator가 public class 또는 adapter를 빠뜨림 | isolated consumer compile, jar inventory, ABI | 빠진 surface가 있으면 compatibility gate 실패 |
| JDBC/R2DBC schema drift | test-only parity fixture와 각 DB integration test | column/encoding 불일치면 구현 중단 후 contract 수정 |
| lock 교체로 claim semantics 변경 | concurrency stress와 기존 state tests | 중복 claim·lost update·deadlock이면 P0/P1로 수정 |
| nested module path가 CI/Nightly에서 누락 | changed-path simulation, actionlint, workflow job/needs audit | 누락은 delivery gate 실패 |
| Docker/Testcontainers 미가동 | preflight와 test conclusion | 환경 blocker를 기록하되 skip을 PASS로 보고하지 않음 |
| benchmark report가 pending placeholder임 | task discovery, H2 benchmark, raw JSON, docs export | raw JSON과 metric metadata가 없으면 benchmark compatibility를 PASS로 보고하지 않음 |
| ABI baseline 갱신으로 삭제 은폐 | descriptor diff와 jar inventory | suppression 금지, public bridge 또는 migration 문서 추가 |
| 기존 checkpoint/params 보안 위험을 artifact split이 새로 노출함 | redaction·malformed payload characterization | 이 issue에서 secure persistence를 주장하지 않고 hardening follow-up으로 분리 |

## 수용 기준

- [ ] core consumer compile/POM에 JDBC·R2DBC surface가 없다.
- [ ] `apiElements`/`runtimeElements`와 POM/Gradle metadata가 Jackson optional
  contract와 일치하고, Jackson 없는 custom strategy profile과 Jackson 있는
  `jackson3()` profile이 각각 의도대로 동작한다.
- [ ] `CheckpointJson` public package 승격 시 기존 internal JVM descriptor의
  ABI 보존 여부를 확인하고, 필요하면 deprecated bridge/constructor overload와
  migration 문서를 함께 제공한다.
- [ ] R2DBC production/test source에 JDBC-owned import가 없다.
- [ ] 기존 aggregator dependency만으로 기존 public class·benchmark task가
  동작하고, 선택 artifact 예제가 compile된다. aggregator-runtime fixture는
  기존 class·JDBC adapter·R2DBC adapter를 실제로 실행하는 runtime smoke까지
  통과해야 한다.
- [ ] core, JDBC, R2DBC, aggregator의 테스트와 ABI 검사가 fresh evidence로
  통과한다.
- [ ] `checkProductionAbi`가 새 child publication set과 non-empty `api/*.api`
  baseline을 모두 검증하고, expected inventory 누락·고아 baseline이 없다.
- [ ] `InMemoryBatchJobRepository`의 상태·claim·owner·checkpoint 동작과
  concurrency oracle이 bluetape4k assertions로 검증된다.
- [ ] 동시성 oracle은 JDBC `MultithreadingTester`/
  `StructuredTaskScopeTester`, R2DBC `SuspendedJobTester`로 각각 매핑하고,
  `io.bluetape4k.assertions`의 exception·nullability·collection matcher를
  표로 기록한다. 새 generic assertion helper는 추가하지 않는다.
- [ ] lock 대기 중 취소와 후속 호출, legacy ID-only checkpoint와 owner-aware
  checkpoint의 경계가 각각 검증된다. owner-aware default는 fail-closed하고,
  owner/version mismatch와 affected-row != 1은 `IllegalStateException`으로
  관측된다. null/blank owner도 DB 접근 전 `IllegalStateException`으로 관측된다.
- [ ] STOPPED cancellation cleanup에서 primary `CancellationException`이
  다시 전파되고 cleanup cancellation이 suppressed cause로 보존된다.
- [ ] 모든 `findOrCreate*` public entry point와 runner DSL 경계가
  control-character batch name을 거부하고 원문을 오류 메시지에 삽입하지 않는다.
- [ ] `BatchJob`·`BatchStep` 직접 생성자도 같은 name validation을 적용하며,
  로그 경로에 newline/control-character가 들어가지 않는 회귀 test가 있다.
- [ ] Testcontainers-backed PostgreSQL·MySQL 검증은 순차 실행되고 결과가
  기록된다.
- [ ] Nightly matrix에 `max-parallel: 1` 또는 동등한 job-level gate가 있고,
  terminal summary가 각 H2/PostgreSQL/MySQL matrix entry의 `conclusion`을
  이름별로 확인한다. delivery 단계에서는 `gh run view "$NIGHTLY_RUN_ID"
  --json jobs --jq '.jobs[] | {name, conclusion}'` 출력으로 이 매핑을 남긴다.
- [ ] legacy schema metadata/upgrade oracle, H2 benchmark raw JSON 및
  `generateBenchmarkDocs` evidence가 존재한다. PostgreSQL/MySQL benchmark를
  실행하지 못한 경우 `PENDING/N/A`를 명시한다.
- [ ] settings/BOM/README/manual/AGENTS/CI/Nightly/Kover/benchmark 등록이
  `./gradlew projects`, `actionlint`, changed-path simulation, child/aggregator
  Kover XML report, `exportManualModuleInventory`, `validate_manuals`,
  `validate_release_manuals`와 일치한다.
- [ ] EN/KO migration 문서가 aggregator·core·JDBC·R2DBC의 실제 Maven/Gradle
  좌표, BOM 사용, `CheckpointJson` 구/신 package와 bridge, Jackson 없는 custom
  strategy와 Jackson 3 runtime profile을 함께 설명하고 네 consumer fixture의
  compile/runtime 검증 명령을 고정한다.
- [ ] 7-Tier review에서 P0/P1이 0이며 Kotlin checklist와 `git diff --check`가
  통과한다.
- [ ] Lore commit, Korean PR body의 `## DoD Status`, issue linkage와 exact-head
  CI evidence가 delivery 단계에 남는다.

## 대안과 결정

### 선택: 세 child module + 기존 aggregator

선택적 소비자에게 명확한 dependency graph를 제공하면서 기존 사용자의
source/binary migration을 제한한다. benchmark와 release artifact의 기존
surface도 보존한다.

### 제외: 단일 artifact + optional/compileOnly dependency

현재 문제인 core와 backend의 published ownership 혼합을 해결하지 못한다.
Gradle configuration에 따라 consumer가 예상하지 못한 classpath와 ABI를 계속
얻을 수 있다.

### 제외: 네 번째 `batch-schema` published module

Exposed-neutral schema를 별도 artifact로 만들면 중복을 줄일 수 있지만,
이번 issue의 3-way ownership 목표보다 module/publication/CI 부담이 커진다.
JDBC와 R2DBC schema parity는 test-only contract로 먼저 고정한다. 실제 공통
schema artifact가 필요한 증거는 후속 issue로 남긴다.

## Rollback과 delivery 경계

- 구현 중 module graph가 해결되지 않으면 source 이동을 중단하고 aggregator
  단일 module로 되돌릴 수 있어야 한다. 기존 package와 public descriptor를
  삭제하지 않는다.
- child source/settings/BOM/workflow/manual 등록이 부분적으로 생성된 상태에서
  gate가 실패하면 새 module directory·registration hunk를 commit하지 않고,
  receipt target worktree의 path-scoped diff만 되돌린다. 이 issue는 migration
  SQL을 실행하지 않으므로 DB rollback 단계는 없다. 이미 생성된 legacy schema가
  parity oracle을 통과하지 못하면 source delivery를 중단하고 schema 상태를
  변경하지 않는다.
- PR merge 전에는 위 path-scoped rollback으로 새 child 등록을 제거하고
  aggregator-only baseline을 복구한다. 이미 검증용 사전 배포 또는 release artifact가
  publish된 뒤 결함을 발견하면 artifact를 삭제하거나 기존 coordinate를
  재사용하지 않고, aggregator를 유지한 corrective patch/사전 배포 버전과 migration
  안내를 별도 delivery로 만든다. 소비자 downgrade는 이전 BOM과
  `io.github.bluetape4k.exposed:bluetape4k-exposed-batch` aggregator를 함께
  선택하는 절차로 문서화한다. release manual의 `releaseRef`/`releaseCommit`은
  실제 rollback target commit에 맞춘 뒤 두 manual validator를 다시 실행한다.
- commit 전에는 local worktree와 receipt target 외 파일을 변경하지 않는다.
- PR 생성 전 fresh exact-head checks, review threads, issue linkage, milestone,
  labels, assignee, Korean DoD를 다시 읽는다.
- merge는 별도 사용자의 명시적 승인 없이는 수행하지 않는다.

## 문서 품질 checklist

- [x] **SPW-01** Issue URL, 기준 ref, source anchor, 현재 구조와 외부 계약을
  source ledger에 연결했다.
- [x] **SPW-02** scope/non-scope, ownership, compatibility, rollback, acceptance와
  검증 명령을 명시했다.
- [x] **SPW-03** Korean technical prose를 사용하고 `api`, `POM`, `R2DBC`,
  `ReentrantLock`, `runInterruptible`, machine token은 원문을 보존했다.
- [x] **SPW-04** 각 주요 주장을 source path, Gradle task, negative scan,
  integration/concurrency oracle에 연결했다.
- [x] **SPW-05** 구현 전 read-back, terminology audit, `git diff --check`,
  7-Tier review gate와 P0/P1 중단 조건을 고정했다.

이 명세의 Step 2-R 독립 리뷰가 P0/P1=0으로 통과한 뒤에만 실행 계획과 source
mutation을 시작한다.
