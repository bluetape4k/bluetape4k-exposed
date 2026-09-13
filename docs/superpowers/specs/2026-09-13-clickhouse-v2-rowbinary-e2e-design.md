# JDBC V2 RowBinary 실제 서버·배치 경계 보강 설계

## 문서 상태

- 이슈: [#874](https://github.com/bluetape4k/bluetape4k-exposed/issues/874)
- 대상 모듈: `exposed/clickhouse`
- 기준 ref: `origin/develop` (`5f6f2e7a2aa03a10512b8dcd85414673ca462166`)
- 승인된 방향: 기존 RowBinary executor/provider와 실제 ClickHouse 통합 테스트를 확장하고, 재현 가능한 실제 측정이 있을 때만 benchmark artifact를 추가한다.
- 독자: Exposed ClickHouse 유지보수자와 실제 driver 검증을 수행하는 기여자
- 언어: 한국어 기술 문서; API·driver key·명령·SHA는 원문 토큰을 보존한다.

## 문제와 목표

#867/#871에서 단순 INSERT의 RowBinary opt-in 경계와 bounded in-memory fixture 증적을 추가했다. 현재 `ClickHouseRowBinaryIntegrationTest`는 실제 서버를 사용하지만 writer/fallback 선택과 기본 row count를 한 시나리오로만 확인한다. fixture benchmark의 결과를 실제 wire 성능으로 해석할 근거도 없다.

이 설계의 목표는 다음 계약을 현재 catalog의 JDBC V2 driver와 고정된 ClickHouse Testcontainers image에서 반복 재현하는 것이다.

1. eligible simple INSERT는 `WriterStatementImpl`, 비지원 SQL은 `PreparedStatementImpl` fallback을 사용한다.
2. empty/single/multi-flush, default/`NULL`, before-first-byte setter 불가, partial sentinel 결과가 명확한 결과 계약으로 남는다.
3. first-byte 이후에는 fallback/replay하지 않고, statement·connection close와 후속 pool 재사용이 exactly-once로 관찰된다.
4. 실제 서버 통합 artifact는 driver/server/image/version, source SHA, selector, dirty 상태를 기록한다. fixture와 실제 wire 측정은 별도 provenance로 표시한다.

## 현재 근거

| 근거 | 현재 관찰 |
|---|---|
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseRowBinaryExecutor.kt` | preflight, writer 실행, before-first-byte fallback, terminal failure 이후 no-replay, provider 소유권/close 정책을 구현한다. |
| `.../ClickHouseRowBinaryPreflight.kt` | 단순 `INSERT ... VALUES`만 보수적으로 분류하고 `INSERT SELECT`와 복합 표현식을 제외한다. |
| `.../ClickHouseRowBinaryIntegrationTest.kt` | `clickhouseV2Integration=true`에서 실제 ClickHouse를 사용하지만 enabled/fallback 각 1회와 row count만 확인한다. |
| `.../ClickHouseRowBinaryBenchmarkTest.kt` | bounded in-memory fixture의 raw JSON을 생성하며 `container=not-run`이다. 이 결과는 wire throughput을 증명하지 않는다. |
| `docs/lessons/2026-09-09-issue-863-clickhouse-v2-timeout.md` | driver 동작과 로컬 cleanup의 증거를 원격 종료 증거와 분리해야 한다는 기존 규칙을 확인한다. |
| GitHub #867/#871 | 기존 RowBinary 범위가 general streaming writer, async_insert, remote KILL을 제외했음을 확인한다. |

## 선택지와 결정

### A. 기존 통합 테스트 확장 (선택)

현재 executor/provider와 `AbstractClickHouseTest`를 유지하고, 실제 server-backed 테스트를 행렬로 확장한다. 장점은 public API와 dependency를 늘리지 않고 현재 lifecycle 경계를 직접 검증하는 점이다. 단점은 driver 내부 wire buffer나 독립적인 throughput SLO를 측정하지 못한다.

### B. 별도 wire benchmark harness 추가 (보류)

실제 driver로 대량 행을 전송하는 별도 benchmark harness와 chart pipeline을 추가한다. wire 측정에는 유리하지만 container startup·환경 편차·CI 비용이 커지고 fixture baseline과 숫자를 비교하기 어렵다. 이번 이슈에서는 실제 측정이 재현될 때만 B의 artifact 부분을 선택 적용하며, 전용 SLO나 새 dependency는 추가하지 않는다.

## 구성과 데이터 흐름

```text
Testcontainers ClickHouse
        │ DriverManager + pinned JDBC V2 profile
        ▼
Recording ClickHouseConnectionProvider
        │ open(rowBinaryEnabled)
        ▼
ClickHouseRowBinaryExecutor
   ├─ simple INSERT → WriterStatementImpl
   └─ unsupported/preflight miss → PreparedStatementImpl
        │
        ├─ flush/update counts/sentinel
        └─ close statement → close borrowed connection → follow-up query
```

테스트 provider는 각 profile의 statement class, flush 횟수, update counts, close 횟수와 예외를 기록한다. 실제 table count와 default column count는 별도 read connection으로 확인한다. executor의 caller-owned provider 계약은 유지하고, 테스트가 pool 전체를 소유하거나 닫지 않도록 한다.

## 오류·경계 계약

1. **Preflight miss**: SQL이 단순 INSERT가 아니면 writer를 열지 않고 JDBC fallback만 사용한다.
2. **Before-first-byte unsupported**: setter 단계에서만 `UnsupportedBeforeFirstByte`가 발생하면 writer connection을 닫고 한 번만 fallback한다. fallback 원인은 결과 metadata 또는 테스트 receipt에 남긴다.
3. **First-byte failure**: 첫 byte 이후 예외는 재생하지 않고 원래 예외를 전달한다. cleanup 예외는 primary 예외에 suppressed로만 붙인다.
4. **Partial sentinel**: flush별 update count와 `acceptedCountMayBeIncomplete`가 부분 결과를 나타내면 `updateCounts`·`acceptedCount`·incomplete flag를 그대로 검증하고 성공으로 과장하지 않는다.
5. **Cleanup failure**: statement/connection close failure가 있어도 primary write/read 원인을 덮지 않는다. 정상 경로의 cleanup failure는 기존 결과 계약에 따라 전달한다.
6. **Empty input**: driver connection 또는 statement를 만들지 않고 zero-count 결과를 반환한다.

## 호환성과 비범위

- `ClickHouseRowBinaryExecutor`, `ClickHouseRowBinaryOptions`, `ClickHouseRowBinaryResult`, `ClickHouseConnectionProvider`의 public signature는 변경하지 않는다.
- 새 dependency, native client, async_insert, general streaming writer, remote KILL QUERY 보장은 추가하지 않는다.
- 통합 테스트는 명시적인 `-DclickhouseV2Integration=true` selector로만 실행한다. 기본 빠른 test selector와 기존 V1 경로는 바꾸지 않는다.
- 실제 socket/wire 수치가 없으면 benchmark chart를 만들지 않고 fixture baseline의 한계를 문서화한다.

## 실패 모드와 완화

| 실패 모드 | 탐지 | 완화 |
|---|---|---|
| driver가 WriterStatementImpl 대신 일반 prepared statement를 사용 | statement class receipt | profile/property와 driver version을 고정하고 경로를 실패시킨다. |
| setter 또는 flush 중 예외 후 이중 실행 | server row count와 provider execute count | before-first-byte만 fallback 허용, first-byte 이후 no-replay assertion을 둔다. |
| partial write 뒤 connection이 pool에 반환되지 않음 | active connection/close counter와 후속 query | `use` 경계와 close exactly-once를 검증하고 실패하면 primary/suppressed를 분리한다. |
| default/NULL semantics가 fixture와 실제 서버에서 다름 | 실제 table row/default count | fixture 결과를 wire 계약으로 사용하지 않고 실제 server assertion을 기준으로 삼는다. |
| Testcontainers 환경 편차로 통합 테스트가 불안정 | 고정 image, selector, repeat count, container log | Docker 검증을 순차 실행하고 실패 receipt를 남긴다. |
| benchmark 숫자가 실제 wire 성능으로 오해됨 | raw JSON provenance audit | `container=not-run` fixture와 실제 server artifact를 이름·문서에서 분리한다. |

## 수용 기준과 DoD

- [ ] 실제 ClickHouse server에서 writer/fallback statement class와 row count가 반복 재현된다.
- [ ] empty/single/multi-flush, default/`NULL`, before-first-byte fallback, first-byte 이후 no-replay, partial sentinel, close exactly-once, pool 재사용을 검증한다.
- [ ] 실행 명령, selector, driver/server/image/version, source SHA, dirty 상태를 artifact에 기록한다.
- [ ] 실제 wire benchmark를 추가한 경우 raw JSON·chart·한국어 분석이 동일한 측정 범위와 provenance를 가리킨다. 그렇지 않으면 fixture-only N/A 근거를 남긴다.
- [ ] `README.md`와 `README.ko.md`의 예제·제약·실행 selector가 동기화된다.
- [ ] targeted Gradle test, detekt/API 영향 점검, `git diff --check`, 필요한 CI receipt를 확보한다.

## SPW-01~05 자체 점검

- **SPW-01 PASS**: 독자·목적·기준 SHA·소스 경로·이슈·미입증 wire 주장 범위를 고정했다.
- **SPW-02 PASS**: 문제, 선택지, 구성, 오류 계약, 호환성, 실패 모드, 수용 기준과 DoD를 포함했다.
- **SPW-03 PASS**: 한국어 기술 문체와 `행`, `연결`, `정리`, `증적` 용어를 일관되게 사용하고 API 토큰을 보존했다.
- **SPW-04 PASS**: develop의 executor/provider/integration/benchmark 소스와 #867/#871/#863 근거에 claim을 매핑했다.
- **SPW-05 PASS**: Markdown을 재독하고 표·코드 블록·체크리스트 렌더링 및 미확정 benchmark 범위를 확인했다.
