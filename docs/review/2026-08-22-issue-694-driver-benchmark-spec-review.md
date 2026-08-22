# Issue #694 benchmark 설계 독립 검토

## 검토 범위와 근거

- 검토 대상: `docs/superpowers/specs/2026-08-22-issue-694-driver-benchmark-design.md`
- 기준 source: existing H2 JMH benchmark, `Containers.kt`, `TestDB.kt`, Issue #694 acceptance
- 독립 관점: performance, stability/lifecycle, security
- 검토 시점: 구현 전, base `develop@5c7e7f35`
- 초기 verdict: `REQUEST CHANGES` (P1 8건)

## 초기 findings와 적용한 수정

| Priority | Lens | 근거 | 적용한 설계 수정 | 재검증 조건 |
|---|---|---|---|---|
| P1 | performance/stability/security | backend별 3회인데 `run-1..3.json`만 정의 | `postgresql-run-{1,2,3}.json`과 `mysql-run-{1,2,3}.json`, driver-specific Gradle tasks, 12-entry/file cardinality를 고정 | 6개 raw file과 backend별 median 재생성 |
| P1 | performance | `src/main` support가 benchmark-only Testcontainers dependency를 필요로 함 | 순수 `JdbcDriverBenchmarkMatrix.kt`를 main, `DriverBenchmarkFixture.kt`를 benchmark source로 분리 | Docker 없는 support test와 `benchmarkClasses` 통과 |
| P1 | performance/stability | connection lease를 query round-trip으로 표현할 위험 | statement execution count를 tracker/aux counter로 추가하고 metric 명칭을 명시 | PG/MySQL raw JSON의 statement metric 확인 |
| P1 | stability | sequential loader가 ambient/default DB를 사용할 수 있음 | explicit `transaction(database) { loader.loadAllKeys().count() }` preflight/measurement로 고정 | 모든 driver·row·pool case가 대상 DB row count와 일치 |
| P1 | stability/security | `useTestcontainers=false`가 localhost fallback | fixture가 flag를 fail-closed로 요구하고 selected container URL prefix를 검증 | false 설정은 실패, true 설정은 container/image evidence |
| P1 | stability | setup 실패 뒤 부분 datasource/executor가 남을 수 있음 | 단계별 try/catch cleanup, primary/suppressed 보존, bounded teardown을 설계 | setup/teardown failure injection과 active lease 0 |
| P1 | stability/performance | Hikari 기본 `minimumIdle`/timeout이 측정을 흔듦 | `minimumIdle=0`, bounded `connectionTimeout`, pool metadata 기록 | pool 1/2/4 peak와 timeout 재검증 |
| P1 | security | raw 오류에 URL/credential/DOCKER_HOST가 섞일 위험 | metric payload만 보존, sanitized metadata와 forbidden-token scan 고정 | raw/docs scan에서 `jdbc:`, `password`, `DOCKER_HOST` 0 |

## P2 보완 사항

- `@Threads(1)`와 `@Fork(1)`을 명시해 shared container/table 경쟁을 JMH default에 맡기지 않는다.
- aux counter는 평균값만으로 peak 상한을 증명하지 않도록 iteration hard assertion과 primitive snapshot을 함께 사용한다.
- `ops/s`와 `rows/s`를 분리해 row count 간 throughput을 잘못 비교하지 않는다.
- parser가 NaN/Infinity/음수/누락 case/허용되지 않은 enum을 거부하고 chart 생성 전에 종료한다.
- shared image tag/digest와 resolved dependency version은 sanitized metadata에 기록하되 credential은 기록하지 않는다.

## 통합 판정

초기 P1은 설계와 계획에 반영했다. 수정 후에는 backend output separation, source-set dependency,
explicit DB binding, fail-closed Testcontainers, partial-resource cleanup, bounded Hikari,
statement metric, sanitized artifact 경계가 서로 일치한다.

## 두 번째 독립 검토 wave — 운영·재현성

| 등급 | 근거 | 문제 | 반영한 수정 |
|---|---|---|---|
| P1 | 계획 Task 4와 `docs/superpowers/plans/2026-08-22-issue-694-driver-benchmark.md:163-183` | backend별 세 번이라는 수용 기준에 비해 실행 명령·출력 복사·덮어쓰기 방지가 단일 호출로만 적혀 있어 여섯 raw run을 재현할 수 없음 | PostgreSQL/MySQL 각각 `for run in 1 2 3` 순차 실행, 단일 verified source path 복사, missing/existing/duplicate fail-closed, run ID/SHA/checksum 기록을 고정 |
| P1 | 계획 Task 8와 `.github/workflows/nightly-tests.yml:2126-2135` | `skipped`를 성공으로 오인할 수 있고 exact PR head, non-skipped JDBC jobs, raw artifact 검증 명령이 구체적이지 않음 | `gh run view --json headSha,status,conclusion,jobs`, head equality, PostgreSQL/MySQL terminal success, non-empty artifact와 skipped 거부를 merge-ready 증거로 고정 |
| P2 | 계획 Task 6 및 `.github/workflows/nightly-tests.yml:503-506,557-560` | full nightly는 JMH task가 아니라 기존 JDBC conformance jobs만 실행하므로 두 증거 범위가 혼동될 수 있음 | nightly conformance와 로컬 JMH six raw JSON을 별도 증거로 명시 |
| P2 | 계획 Task 4 | SIGTERM/OOM partial output과 다음 run의 orphan lease/schema 경계가 없음 | interrupted run을 `PENDING`으로 폐기하고 bounded preflight 후 재실행, shared container 중지 금지 명시 |
| P2 | 계획 Task 8 | PR metadata의 `isDraft`, mergeability, review/check/thread 상태 assert가 부족함 | exact head/body/base와 해당 metadata/status/thread 필드 read-back을 DoD에 추가 |
| P2 | 계획 Task 4/5 | 보조 지표의 invocation 단위와 `ops/s`·`rows/s`·statement/lease 해석 경계가 README/parser에 고정되지 않음 | setup/teardown 제외, iteration-local auxiliary semantics와 비교 금지 사항을 EN/KO 문서·parser schema에 추가 |
| P2 | 계획 Task 5 | EN/KO가 동일 headings/data라고 선언하지만 구조·수치·링크 parity 검증 명령이 없음 | headings/table/link count 및 technical token/numeric cell deterministic parity check 추가 |
| P2 | 계획 Task 4/6 | raw forbidden-token 처리의 redact/reject 표현이 혼재함 | raw JSON은 절대 수정하지 않고 parser가 fail-closed reject만 수행하도록 명시 |
| P2 | 설계 `:37-39`, 계획 Task 4 | image digest 기록이 선택적이면 재현성 판정이 흔들림 | immutable digest 미관찰 시 raw evidence를 `PENDING`으로 고정 |

## 두 번째 wave — 개발자·API·Gradle

| 등급 | 근거 | 문제 | 반영한 수정 |
|---|---|---|---|
| P1 | 설계 `:116-124`, 계획 Task 4 | driver-specific task 하나는 `2 methods × 2 row counts × 3 pool sizes × 1 driver = 12` primary result만 만든다. `@AuxCounters`는 별도 entry가 아니므로 file당 24 요구는 정상 raw를 거부함 | file당 cardinality를 12로 통일하고 six raw file 계약을 재검증 |
| P1 | 기존 build `benchmark/exposed-benchmark/build.gradle.kts:37-43,65-74` | Kotlinx Benchmark task 이름은 target/config/suffix 결합으로 `benchmarkJdbcDriverPostgreSQLBenchmark` 형태가 되는데 문서가 prefix를 생략함 | generated task를 `tasks --all`로 확인하고 설계·계획·capture 명령을 `benchmarkJdbcDriverPostgreSQLBenchmark`/`benchmarkJdbcDriverMySQLBenchmark`로 고정 |
| P2 | 계획 Task 1/3 | support test가 Bluetape assertions를 사용하지만 benchmark 모듈의 test dependency가 조건부로만 언급됨 | `testImplementation(bt4k.bluetape4k.assertions)`를 명시하거나 RED에서 kotlin.test로 대체하고 compile evidence를 남김 |

운영 wave의 P1 두 건을 위와 같이 반영했다. 구현 전 gate는 모든 독립 관점에서
`P0=0, P1=0`이어야 하며, 남은 P2는 구현·검증 증거로 닫거나 명시적 위험으로 보고한다.

개발자/API wave에서 확인된 raw cardinality와 generated task prefix P1도 반영했다. 현재
설계·계획 문서의 독립 검토 gate는 `P0=0, P1=0`으로 충족되며, 실제 구현 후에는 동일한
여섯 관점으로 변경 diff와 raw artifact를 다시 검증한다.

## SPW 검토

- SPW-01: 각 finding을 실제 source/line과 승인 acceptance에 연결했다.
- SPW-02: 수정 사항을 설계 구조·실패 모드·증거 계약에 매핑했다.
- SPW-03: `lease`, `statement execution`, `rows/s`, `sanitized artifact` 용어를 고정했다.
- SPW-04: 기존 H2 benchmark와 shared Testcontainers/TestDB fallback을 대조했다.
- SPW-05: Korean prose를 유지하고 code token/command/URL은 원문을 보존했다.
