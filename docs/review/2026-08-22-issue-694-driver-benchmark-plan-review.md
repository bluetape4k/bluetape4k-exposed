# Issue #694 benchmark 구현 계획 검토

## 검토 범위

- 계획: `docs/superpowers/plans/2026-08-22-issue-694-driver-benchmark.md`
- 설계: `docs/superpowers/specs/2026-08-22-issue-694-driver-benchmark-design.md`
- baseline: `develop@5c7e7f351ba92709029353bbacd34730847f91af`
- 구현 전 독립 관점: performance, stability/lifecycle, security

## 수렴 기록

| Priority | Area | 초기 finding | 계획 반영 |
|---|---|---|---|
| P1 | Raw evidence | 단일 task/3개 JSON으로 두 backend 결과가 섞일 수 있음 | `benchmarkJdbcDriverPostgreSQLBenchmark`와 `benchmarkJdbcDriverMySQLBenchmark`를 분리하고 6개 이름·12-entry 검증을 Task 4에 추가 |
| P1 | Source set | main support가 benchmarkImplementation 전용 dependency를 참조 | matrix/range는 main, fixture는 benchmark source로 분리하고 Task 2/3 책임을 분리 |
| P1 | DB binding | `loadAllKeys()`가 default database를 선택할 수 있음 | explicit `transaction(fixture.database)` measurement와 preflight를 Task 3에 추가 |
| P1 | Container boundary | local fallback이 green처럼 보일 수 있음 | `TestDBConfig.useTestcontainers` fail-closed와 URL-prefix check를 Task 3에 추가 |
| P1 | Cleanup | setup failure/partial resource cleanup이 모호함 | 단계별 cleanup, suppressed cause, bounded executor shutdown을 Task 3에 추가 |
| P1 | Measurement | lease count와 query metric이 혼동될 수 있음 | statement execution counter, aux primitive snapshot, hard peak assertion을 Task 3/4에 추가 |
| P1 | Security | raw diagnostics가 credential-bearing URL을 남길 수 있음 | sanitized metadata와 forbidden-token scan을 Task 4/6에 추가 |

## 순서·완전성 점검

1. Task 1 RED는 Docker 없는 순수 matrix/range만 대상으로 하며 구현 전에 실행한다.
2. Task 2 GREEN은 source-set dependency를 닫고, Task 3 fixture/JMH compile이 그 결과에만 의존한다.
3. Task 3 preflight가 correctness/cardinality를 고정한 뒤에만 Task 4 Testcontainers benchmark를 실행한다.
4. Task 4의 6개 raw file이 모두 검증된 뒤 Task 5 median/chart/docs를 만든다.
5. Task 6 fresh validation과 Task 7 six-lens review가 Task 8 PR/nightly보다 앞선다.
6. `docs/manual/**`, production API/ABI, catalog/BOM, workflow definition은 forbidden scope로 남는다.

## 게이트 판정

수정된 계획은 설계 요구사항과 Issue #694 DoD를 모두 concrete task에 매핑한다.
초기 P1은 모두 반영되었다. 두 번째 운영 wave에서 확인된 raw capture 재현성 및 exact-head
nightly assertion P1도 Task 4/6/8에 반영했다. 구현 착수 조건은 모든 독립 관점의
`P0=0, P1=0`, RED evidence, `git diff --check`다.
P2는 parser finite-value validation, JMH thread/fork 명시, rows/s 해석, image provenance와
fixture lifecycle assertion으로 실행 중 닫는다.

## 운영 wave 반영 기록

- PostgreSQL/MySQL을 각각 세 번 실행하고 verified report path를 run-numbered raw file로
  복사하며, missing/overwrite/duplicate를 거부한다.
- interrupted/OOM run은 `PENDING`으로 폐기하고 active-lease/schema preflight 뒤 재실행한다.
- full nightly는 JMH raw 결과가 아니라 existing JDBC conformance jobs를 검증한다. PR head
  equality, PostgreSQL/MySQL non-skipped terminal success, raw artifact 존재를 `gh run view`
  결과로 직접 검증한다.
- PR read-back은 exact head/body/base뿐 아니라 `isDraft`, `mergeStateStatus`, `reviewDecision`,
  checks와 review threads, milestone/labels를 포함한다.
- 사용자 wave의 P1을 반영해 `capture_jmh_run.py` 실행 명령을 실제 source/destination/metadata
  인자로 고정했다. source directory의 단일 JSON, destination 미존재, raw payload 불변,
  SHA-256 기록을 helper가 fail-closed로 검증한다.
- 보조 metric의 단위와 EN/KO parity를 README/parser gate에 명시하고, forbidden token은
  redact하지 않고 reject한다. immutable image digest 미확보는 `PENDING`이다.
- 개발자 wave에서 file당 raw cardinality가 24가 아니라 12임을 확인해 spec/plan을 수정했다.
  `@AuxCounters`는 secondary metric이며 primary result entry를 늘리지 않는다.
- 기존 target/config 결합 규칙에 맞춰 generated task 이름을
  `benchmarkJdbcDriverPostgreSQLBenchmark`와 `benchmarkJdbcDriverMySQLBenchmark`로 고정하고,
  support test assertion dependency를 명시한다.

## SPW 검토

- SPW-01: 현재 module/task/source path와 exact command를 고정했다.
- SPW-02: RED→GREEN→benchmark→docs→review→PR 순서를 의존성에 맞게 배치했다.
- SPW-03: Korean user-facing artifact와 machine token을 구분했다.
- SPW-04: Kotlin testing, module setup, repository hazards, diagram gates를 반영했다.
- SPW-05: placeholder/모호한 "적절히" 표현을 제거하고 expected result를 명시했다.
