# #875 JDBC V2 timeout·cancellation·cleanup 검증 증적

## 실행 식별자

| 항목 | 값 |
|---|---|
| 이슈 | [#875](https://github.com/bluetape4k/bluetape4k-exposed/issues/875) |
| 기준 ref | `origin/develop` `5f6f2e7a2aa03a10512b8dcd85414673ca462166` |
| 실행 branch | `feat/issue-875-timeout-cleanup` |
| 실행 기준 | 계획·설계 commit `1fd294e0` 이후의 test/docs working tree |
| Gradle task | `:bluetape4k-exposed-clickhouse:test` |
| selector | `--tests '*ClickHouseQueryLifecycleTest*'`, `--tests '*ClickHouseExtensionsTest*'` |
| integration selector | `-PclickhouseV2Integration=true` (기존 ClickHouse Testcontainers 경로) |
| 실행 제약 | `--no-parallel --max-workers=1 --no-daemon --console=plain` |
| driver | `com.clickhouse.jdbc.ClickHouseDriver` `0.9.9` (V2), V1 비교 행은 `DriverV1` |
| server image | `clickhouse/clickhouse-server:26.7.3.19` |
| image digest | `sha256:f90a77560f72b10802106ee49e9870e41668cbc496e280c3911f6e3b216657f3` |
| Docker context | `default`, Docker Server `29.2.1`, Colima running |
| XML receipt | `exposed/clickhouse/build/test-results/test/TEST-io.bluetape4k.exposed.clickhouse.ClickHouseQueryLifecycleTest.xml`, `TEST-io.bluetape4k.exposed.clickhouse.ClickHouseExtensionsTest.xml` |

## 실행 명령과 결과

```bash
colima status
docker context show
docker info --format '{{.ServerVersion}}'
./gradlew :bluetape4k-exposed-clickhouse:test \
  -PclickhouseV2Integration=true \
  --tests '*ClickHouseQueryLifecycleTest*' \
  --tests '*ClickHouseExtensionsTest*' \
  --no-parallel --max-workers=1 --no-daemon --console=plain
```

최신 combined 실행의 lifecycle XML은 `tests=31`, `failures=0`, `errors=0`,
`skipped=0`, `time=50.84s`였다. Extensions XML은 `tests=10`, `failures=0`,
`errors=0`, `skipped=0`, `time=6.633s`였다. lifecycle의 in-flight cancel 행은 세 번
반복되었고, extensions의 전체 10개 행도 통과했다. 두 XML은 별도 selector 실행
시 결과 파일이 교체될 수 있으므로, 위 경로와 명령을 함께 보존한다.

## timeout·cancellation·remote 관찰 행렬

| 경계 | 반복·관찰 | 판정 |
|---|---|---|
| pool acquisition | 기존 Hikari `connectionTimeout=500` 경로에서 pool 고갈 후 `SQLException`, pool 미종료, 후속 query 성공 | PASS |
| connect | 닫힌 loopback port에 V2 `connect_timeout`/`connection_timeout`을 적용한 3회 bounded probe | PASS; 연결 실패 범위만 입증 |
| V1 socket read | `DriverV1`와 `socket_timeout=200` delayed-row 경로에서 `Read timed out`, 행 방출 0, 후속 query 성공 | PASS; V1 계약 |
| V2 socket read | 동일 delayed-row shape를 V2에서 세 번 bounded 실행하고 local cleanup을 확인 | PASS; deterministic socket-timeout 실패는 N/A |
| server execution | V2 `Statement.queryTimeout=1`을 세 번 적용해 `SQLTimeoutException`, 행 방출 0, 후속 query 성공 | PASS |
| in-flight cancel request | 첫 행 이후 `Statement.cancel()`을 3회 호출, 매회 `requestAccepted=true`, local resources 0, 후속 query 성공 | PASS; 요청 수락만 판정 |
| remote query disappearance | test-only `system.processes` observer가 각 redacted `query_id`를 최대 5초 polling; 세 번 모두 `TIMEOUT`/`POLL_DEADLINE_EXPIRED`이고 첫 관찰 없음 | **N/A**; 원격 종료를 주장하지 않음 |

`V2_CANCEL_RECEIPT`는 다음과 같이 세 행 모두 같은 분류를 반환했다.

```text
attempt=1 requestAccepted=true remoteOutcome=TIMEOUT reason=POLL_DEADLINE_EXPIRED
attempt=2 requestAccepted=true remoteOutcome=TIMEOUT reason=POLL_DEADLINE_EXPIRED
attempt=3 requestAccepted=true remoteOutcome=TIMEOUT reason=POLL_DEADLINE_EXPIRED
```

receipt에는 query 본문·JDBC URL·credential·query id 값·raw exception message를
기록하지 않았다. observer는 query id를 parameter binding하고, 빈 id·권한 오류·
관찰 실패를 안전한 분류 토큰으로만 반환한다. `Statement.cancel()`이 반환된
사실과 ClickHouse의 비동기 `KILL QUERY` 완료는 별도 상태다.

## `queryFlow` cleanup·pool 재사용

- 정상 수집, `take(1)`, downstream cancellation, finite timeout, mapper/collector
  failure, `ResultSet`/statement/connection cleanup failure, query/execute/next
  경계를 기존 lifecycle 회귀 행으로 확인했다.
- cancellation은 primary `CancellationException` identity를 유지하고 cleanup
  failure를 distinct suppressed 항목으로 보존한다. producer join과 local
  `ResultSet` 정리 뒤 Hikari active connection/result/statement counter가 0이다.
- cleanup 이후 같은 pool의 후속 `queryFlow`가 성공한다. 외부 transaction의
  connection-local marker는 inner query에 상속되지 않고 outer connection은
  닫히지 않는다.
- 이 변경에서 production source, public API, dependency, pool 기본값은 바꾸지
  않았다. 현재 mutable session state를 변경하는 경로를 새로 추가하지 않았으므로
  1-slot borrower state leak에 대한 별도 보장은 **N/A**이며 existing ownership
  및 pool-reuse 증거만 유지한다.

## 경계·미실행 항목

- V2 `socket_timeout`이 지연 read를 결정적으로 중단한다는 보장은 확인하지 못했다.
  호출자는 유한한 connection acquisition, socket, server execution timeout을
  직접 설정해야 한다.
- in-flight remote `KILL QUERY`가 서버에서 완료되었다는 보장은 확인하지 못했다.
  generic abort API나 강제 KILL 동기화 경로를 추가하지 않았다.
- benchmark task와 throughput chart는 실행하지 않았다(`N/A`). 이번 변경은
  lifecycle/cleanup 관찰이며 성능 수치를 새로 주장하지 않는다.
- hosted CI와 merge 이후 canonical sync는 PR 단계의 별도 증적이다. Full Nightly는
  실행하지 않았다.

## 재현·롤백

동일한 V2 selector, 고정 driver/server/image, 순차 Gradle 옵션으로 재실행한다.
Docker·권한·버전 차이로 observer가 불가능하면 raw query/log를 남기지 않고
`UNAVAILABLE`/`N/A`와 분류 토큰만 새 receipt에 기록한다. production defect가
입증되지 않았으므로 rollback 대상 production diff는 없다. root의 dirty Ktor
변경과 다른 worktree에는 revert/reset/삭제를 적용하지 않는다.

## 검증 체크

- [x] timeout 종류와 V1/V2 경계를 별도 행으로 기록
- [x] in-flight cancel request와 remote termination을 독립 판정
- [x] local cleanup, primary/suppressed, pool 재사용 확인
- [x] driver/server/image/digest/selector와 XML provenance 기록
- [x] lifecycle `31`·extensions `10` 테스트에서 failures/errors/skipped `0`
- [x] V2 socket timeout·remote termination·benchmark/chart 미입증/N/A 명시
