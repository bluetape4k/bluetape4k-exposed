# JDBC V2 timeout·cancellation·cleanup 보강 설계

## 문서 상태

- 이슈: [#875](https://github.com/bluetape4k/bluetape4k-exposed/issues/875)
- 대상 모듈: `exposed/clickhouse`
- 기준 ref: `origin/develop` (`5f6f2e7a2aa03a10512b8dcd85414673ca462166`)
- 승인된 방향: 현재 JDBC V2 driver/server에서 timeout·cancel·cleanup 경계를 먼저 서버 관찰과 회귀 테스트로 고정하고, 증명되는 결함만 최소 수정한다.
- 독자: Exposed ClickHouse 유지보수자와 JDBC lifecycle을 운영하는 기여자
- 언어: 한국어 기술 문서; API·driver property·SQL·명령·SHA는 원문 토큰을 보존한다.

## 문제와 목표

#860/#863에서 row-limit, V1 read timeout, V2 `setQueryTimeout`, `Statement.cancel()` 요청 수락과 local cleanup을 확인했다. 그러나 V2 `socket_timeout`의 결정적 실패와 in-flight 원격 쿼리 종료는 입증하지 못했다. `queryFlow`는 `ResultSet`과 producer를 정리하지만 blocking JDBC 작업이 언제 끝나는지, cancellation 뒤 pool이 안전하게 재사용되는지에 대한 서버 관찰과 bounded 정책이 부족하다.

목표는 request acceptance, local cleanup, server-side termination을 하나의 성공으로 합치지 않고 각각 측정하는 것이다.

1. `connection_timeout`, `connection_request_timeout`, `socket_timeout`의 적용 지점과 예외·부분 결과·정리 순서를 반복 재현한다.
2. `Statement.cancel()` 호출과 query_id 기준 원격 쿼리 소멸을 별도 판정한다. 증명되지 않은 경우 N/A로 기록한다.
3. `queryFlow`의 정상·`take`·cancellation·timeout·mapper/collector/close failure에서 primary exception, suppressed cleanup, producer 종료, connection 반환과 pool 재사용을 고정한다.
4. 실제 mutable connection/session state가 borrower 사이에 누출되는지 1-slot pool에서 검증하고, 누출이 있으면 최소 격리/복원 수정을 적용한다.

## 현재 근거

| 근거 | 현재 관찰 |
|---|---|
| `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryStreaming.kt` | RENDEZVOUS channel, producer cancellation, `NonCancellable` join, ResultSet close와 suppressed cleanup을 구현한다. Statement/connection close는 Exposed transaction에 위임한다. |
| `.../ClickHouseExtensions.kt` | blocking JDBC cancellation이 즉시 끝나지 않을 수 있고 caller가 finite timeout을 설정해야 한다고 설명한다. |
| `.../ClickHouseV2Options.kt`·`ClickHouseV2PropertyMapping.kt` | `connection_timeout`, `socket_timeout`, `connection_request_timeout`, `query_id`, `log_comment`, session settings를 connection properties로 매핑한다. |
| `exposed/clickhouse/src/test/kotlin/.../ClickHouseQueryLifecycleTest.kt` | row-limit, V1 timeout, V2 metadata/query timeout/socket probe, cancel request, cleanup failure, pool reuse를 확인하지만 server-side query disappearance를 확인하지 않는다. |
| `docs/lessons/2026-09-09-issue-863-clickhouse-v2-timeout.md` | V2 socket timeout과 원격 KILL QUERY 완료를 주장하지 말아야 한다는 기존 증적 규칙을 명시한다. |
| GitHub #860/#863/#864 | 기존 테스트 범위와 제외된 generic abort/driver replacement를 확인한다. |

## 선택지와 결정

### A. 관찰 우선 + 최소 보강 (선택)

고정된 driver/server/image와 query_id를 사용해 timeout/cancel/cleanup matrix를 먼저 만든다. 재현 가능한 code defect만 `ClickHouseQueryStreaming` 또는 테스트 fixture에 최소 수정하고, 원격 종료가 관찰되지 않으면 명시적으로 N/A로 남긴다. 기존 API와 Exposed transaction 소유권을 보존한다.

### B. generic abort API와 강제 KILL QUERY 도입 (보류)

새 public abort 계약을 추가하고 별도 KILL QUERY 동기화 경로를 구현한다. 현재 driver가 비동기 KILL을 사용하고 서버 종료 증거가 없으므로, 이 접근은 호출자에게 과도한 보장을 주고 API/호환성 범위를 넓힌다. 이번 이슈의 비범위로 둔다.

## 구성과 데이터 흐름

```text
queryFlow collector
      │ take/cancel/timeout
      ▼
producer coroutine ── JDBC Statement/ResultSet ── ClickHouse server
      │                         │                       │
      │ local close + join      │ cancel request        │ system.processes/query_log
      ▼                         ▼                       ▼
primary/suppressed receipt   request accepted      terminated / N/A
      │
      ▼
Hikari 1-slot borrower reuse and state-isolation check
```

테스트는 로컬 resource counter와 server-side query observation을 별도 receipt로 기록한다. Testcontainers는 고정 image와 순차 실행을 사용한다. `system.processes` 조회가 권한·버전 때문에 불가능하면 그 사실과 반복 횟수, caller 책임을 기록하고 PASS로 승격하지 않는다.

## 오류·lifecycle 계약

1. **Timeout 분리**: pool 획득(`connection_request_timeout`), 연결 수립(`connection_timeout`), socket read/write(`socket_timeout`), server execution(`setQueryTimeout`/server setting)을 서로 다른 결과로 기록한다.
2. **Cancellation 전파**: `CancellationException`은 broad catch에서 삼키지 않고 primary로 재전파한다. close 실패는 primary에 distinct suppressed로 붙인다.
3. **Cleanup 경계**: consumer가 끝나면 channel을 cancel하고 producer를 취소한 뒤 `NonCancellable` join을 완료한다. statement·connection close가 Exposed 소유 경계에 있음을 receipt로 검증한다.
4. **Remote termination**: `Statement.cancel()` 호출 횟수와 server query disappearance를 별도 필드로 둔다. disappearance를 관찰하지 못하면 `N/A`이며 원격 종료 보장을 문서화하지 않는다.
5. **Pool reuse**: cleanup 후 active connection/result/statement가 0이고 같은 pool의 후속 query가 성공해야 한다. mutable session state가 실제로 변경되는 경로가 있을 때만 상태 저장/복원 또는 격리를 추가한다.
6. **Cleanup 오류**: 정상 read 이후 cleanup failure는 기존 정책에 따라 전달하고, timeout/cancel/mapper failure의 원인은 덮어쓰지 않는다.

## 호환성과 비범위

- `queryFlow`의 기존 public signature와 Exposed transaction ownership을 유지한다.
- generic abort API, JDBC driver 교체, native client, 미입증 remote termination/deterministic V2 socket timeout 보장은 추가하지 않는다.
- 기본 test selector와 V1 behavior는 바꾸지 않고 V2/Testcontainers 검증은 명시적 selector로만 실행한다.
- 새 dependency와 unrelated pool/framework configuration 변경은 금지한다.

## 실패 모드와 완화

| 실패 모드 | 탐지 | 완화 |
|---|---|---|
| V2 socket timeout이 실제로 적용되지 않음 | pinned delay query를 반복 실행하고 예외·경과시간·행 수 기록 | N/A와 caller finite timeout 책임을 문서화하고 false PASS를 금지한다. |
| cancel 요청은 수락되지만 query가 서버에 남음 | query_id로 `system.processes`/query log polling | request와 termination을 분리하고, bounded polling 후 N/A로 종료한다. |
| cancellation 중 producer가 join되지 않거나 pool에 연결이 남음 | resource counters, Hikari MXBean, 후속 query | join/close 순서를 회귀 테스트로 고정하고 실제 leak이면 최소 수정한다. |
| cleanup 예외가 timeout/cancel 원인을 가림 | primary identity와 suppressed list assertion | `suppressDistinct` 정책을 유지·보강하고 로그에 비밀값을 남기지 않는다. |
| session/query state가 1-slot borrower 사이에 누출됨 | 두 borrower의 query_id/log comment/timezone/role 관찰 | mutable 경로가 있으면 restore/격리, 없으면 N/A 근거와 계약 문서화. |
| Docker/권한/버전 차이로 관찰이 불안정함 | image/server/driver/version 및 repeat receipt | Testcontainers 순차 실행, 명시적 selector, 실패 로그와 재실행 지점을 남긴다. |

## 수용 기준과 DoD

- [ ] timeout/cancellation matrix가 driver/server/image/version, selector, repeat count, source SHA와 함께 남는다.
- [ ] V2 cancel request와 server-side query disappearance/termination을 별도 판정하고 미입증 항목은 N/A로 명시한다.
- [ ] queryFlow 정상·`take`·cancellation·timeout·mapper/collector/close failure 뒤 local resource 0과 pool 재사용을 검증한다.
- [ ] 증명된 cleanup/cancel 결함만 최소 수정하고 primary/suppressed 예외 계약 회귀 테스트를 통과한다.
- [ ] 1-slot pool에서 mutable connection/session state 누출 여부가 코드·테스트·문서와 일치한다.
- [ ] `README.md`와 `README.ko.md`, review/lesson 문서가 실제 증적만 주장한다.
- [ ] targeted Gradle test, detekt/API 영향 점검, `git diff --check`, 필요한 CI receipt를 확보한다.

## SPW-01~05 자체 점검

- **SPW-01 PASS**: 독자·목적·기준 SHA·소스 경로·이슈·원격 종료 미입증 상태를 고정했다.
- **SPW-02 PASS**: 문제, 선택지, 구성, lifecycle/오류 계약, 호환성, 실패 모드, 수용 기준과 DoD를 포함했다.
- **SPW-03 PASS**: 한국어 기술 문체와 `요청 수락`, `원격 종료`, `정리`, `재사용` 용어를 일관되게 사용하고 driver/API 토큰을 보존했다.
- **SPW-04 PASS**: develop의 streaming/options/tests와 #860/#863/#864/lesson 근거에 claim을 매핑했다.
- **SPW-05 PASS**: Markdown을 재독하고 표·코드 블록·체크리스트 렌더링 및 N/A 판정 규칙을 확인했다.
