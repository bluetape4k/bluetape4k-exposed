# #863 ClickHouse V2 timeout·cancellation 검증

## 현재 판정

`READY` — catalog 기본 V2 driver에서 연결 시도, 서버 query timeout,
transport socket timeout, `queryFlow` downstream cancellation, JDBC
`Statement.cancel()`을 서로 다른 경로로 세분화해 검증했다.
`Statement#setQueryTimeout(1)`은 세 번 모두
`SQLTimeoutException("Query execution time exceeded limit")`을 재현했고, 행
방출 없이 `ResultSet`·`Statement`·`Connection`을 정리한 뒤 후속 조회를
성공시켰다. `socket_timeout=200`은 세 번 모두 약 2.0초에 두 행을 정상
반환했으므로 이 환경의 deterministic V2 socket read-timeout은 `N/A`로
분류한다. downstream cancellation과 `Statement.cancel()`은 로컬 정리와
재사용을 확인했지만 원격 query 종료나 블로킹 JDBC read 즉시 중단은 보장하지
않는다. 이 기록은 구현과 로컬 검증만 다루며 PR·머지·Full Nightly 승인을
포함하지 않는다.

## 범위와 기준

- 이슈: [#863](https://github.com/bluetape4k/bluetape4k-exposed/issues/863)
- 기준 branch/commit: `develop` / `5fc179810189e250485c8ce48a3dd278b1898bc1`
- 대상: `exposed/clickhouse`의 기본 `com.clickhouse.jdbc.ClickHouseDriver`
  (V2, 실제 연결 `com.clickhouse.jdbc.ConnectionImpl`)와 `queryFlow` JDBC
  cleanup 경계
- dependency: central catalog `clickhouse-jdbc=0.9.9`
- server: Testcontainers `clickhouse/clickhouse-server:26.7.3.19`
- JDBC URL: `jdbc:clickhouse://<host>:<port>/default`; 서버 설정은
  `clickhouse_setting_` 접두사를 사용하고 transport 옵션은 `socket_timeout`을
  사용한다. V2 client의 연결 timeout 키는 `connection_timeout`이며,
  이슈의 `connect_timeout` 표기도 별도 probe로 확인했다.
- 구현 경계: test-only 관측 fixture와 회귀 테스트, EN/KO README,
  validation/lesson 증적만 변경했다. driver 교체, V1 기본값 전환, native
  client, 공통 abort API와 production `queryFlow` signature는 변경하지
  않았다.

## 공식 근거

- [ClickHouse Java client 개요](https://clickhouse.com/docs/integrations/language-clients/java/client)
- [ClickHouse JDBC 설정](https://clickhouse.com/docs/integrations/language-clients/java/jdbc)
- [`StatementImpl#setQueryTimeout`·`cancel` in 0.9.9](https://github.com/ClickHouse/clickhouse-java/blob/v0.9.9/jdbc-v2/src/main/java/com/clickhouse/jdbc/StatementImpl.java)
- [`KILL QUERY` 동작](https://clickhouse.com/docs/reference/statements/kill)
- [`max_execution_time` 설명](https://clickhouse.com/docs/concepts/features/configuration/settings/query-complexity)
- [0.9.9 release](https://github.com/ClickHouse/clickhouse-java/releases/tag/v0.9.9)

`StatementImpl#setQueryTimeout`은 V2의 기본 비동기 설정이 꺼진 경우
`max_execution_time` local setting을 설정하고, 서버 실행 timeout을
`SQLTimeoutException`으로 변환한다. `Statement#cancel()`은 마지막 query id로
`KILL QUERY`를 보내지만 `SYNC`를 사용하지 않는다. `socket_timeout`은 HTTP
socket read/write timeout이며 timeout 시 connection abort 경로를 사용한다.
V2 client source에 등록된 연결 timeout 키는 `connection_timeout`이고,
`connect_timeout`은 이 V2 client 속성 목록에 없다. 따라서 닫힌 loopback 포트
연결 실패 probe는 bounded 연결 시도만 증명하며 timeout 만료나 ClickHouse
handshake를 증명하지 않는다.
이 소스 근거와 실제 probe 결과를 결합하되, 원격 종료 완료를 로컬 API 성공의
의미로 확장하지 않았다.

## Probe matrix와 결과

| 경로 | 조건·반복 | 관찰 결과 | 판정 |
|---|---|---|---|
| 기본 driver metadata | `ClickHouseConnectionWrapper`를 거친 V2 연결 1회 | `driverName`에 `ClickHouse`, `driverVersion` `0.9.9`, `unwrap(ConnectionImpl)` 성공 | PASS |
| V2 connection attempt | 닫은 ephemeral loopback 포트, `connect_timeout=200&connection_timeout=200`, 3회 | 매회 `SQLException`으로 거부되고 5초 이내 종료; 이 probe에서 열린 자원 없음 | `N/A` (연결 거부이며 timeout 만료/handshake 아님) |
| server query timeout | 직접 JDBC `Statement`, `setQueryTimeout(1)`, 행마다 1초인 `sleepEachRow`, `LIMIT 3`, 3회 | 매회 `SQLTimeoutException("Query execution time exceeded limit")`, JDBC row iteration/결과 행 0건, 직접 관측 `executeQuery=3`, `setQueryTimeout=3`; 자원 반환 후 `queryFlow(limit=1)` 3회 성공 | PASS |
| V2 socket read timeout | `socket_timeout=200`, `sleepEachRow(1)`, `LIMIT 2`, 3회 | `2,006ms`, `2,005ms`, `2,006ms`에 각 2행 정상 반환, SQL 예외 없음 | `N/A` (이 probe 조건에서 deterministic timeout 미재현) |
| downstream cancellation | `queryFlow(...).take(1)`, `LIMIT 3`, 3회 | 각 수집이 첫 행 `[0]`만 반환, bounded window(`<10s`) 안에 로컬 cursor/pool connection 정리, 후속 조회 성공; 관측 `executeQuery=6` | PASS (local cleanup만) |
| JDBC cancel request | 직접 V2 `Statement`, 첫 행 수신 후 `cancel()`, 3회 | cancel 요청 3회 수락, `ResultSet`·`Statement`·`Connection` 정리, 후속 조회 성공 | PASS (request path; remote termination `N/A`) |

timeout/cancellation 뒤에는 `JdbcObservation`의 활성 connection·statement·
result 수와 Hikari active connection을 0으로 확인했다. Hikari의
`connectionTimeout`은 pool에서 connection을 빌리는 시간이고 query 실행
timeout이 아니다. `queryFlow`는 `Statement` handle을 노출하지 않으므로
server query timeout은 직접 JDBC `Statement#setQueryTimeout` 또는
호출자 JDBC/DataSource 경계의 `clickhouse_setting_max_execution_time`으로
설정해야 한다.
직접 JDBC `Statement.cancel()` 테스트는 첫 행을 받은 뒤 request path를
호출하는 형태이며, in-flight blocking read와 remote termination 완료는
별도 server-side 관찰이 없어 `N/A`로 남겼다.

## 7-Tier 검토 상태

| 관점 | 검토 내용 | 상태 |
|---|---|---|
| Source/driver | V2 0.9.9 source, JDBC URL 설정 키, `setQueryTimeout`, `cancel`, socket abort 경계를 공식 문서와 대조 | PASS |
| Callers/API/ABI | production `queryFlow` signature와 cold collection 계약 유지; test-only fixture overload만 추가 | PASS |
| Tests | V2 metadata/unwrap, connection attempt, server timeout, socket probe, downstream cancellation, cancel request와 기존 lifecycle/fixture 테스트를 순차 실행 | PASS |
| Documentation | EN/KO README, 본 validation, lesson에 timeout 계층·remote 보장 한계를 동시 반영 | PASS |
| CI/static | 대상 테스트·모듈 전체 테스트·detekt·ABI 명령을 같은 변경 상태에서 실행 | PASS |
| Design-risk | generic abort API, driver 변경, 원격 cancellation 추적을 도입하지 않고 caller 책임을 명시 | PASS |
| Independent review | 최초 독립 code-reviewer는 P0 0건·P1 2건을 지적했고, 교정 후 재검토 lane이 응답하지 않아 inline fallback을 수행 | PASS (inline fallback; 독립 재검토 N/A) |

## 문서 품질 게이트

| 게이트 | 확인 내용 | 상태 |
|---|---|---|
| SPW-01 | 독자·목적·공식 source·실행 결과를 먼저 고정 | PASS |
| SPW-02 | README, validation, lesson의 artifact contract와 EN/KO 범위 확인 | PASS |
| SPW-03 | 한국어 기술 문체와 용어를 naturalness checklist로 검토 | PASS |
| SPW-04 | URL·버전·예외·elapsed·판정을 source/test output과 대조 | PASS |
| SPW-05 | 최종 Markdown·표·링크를 다시 읽고 DoD에 기록 | PASS |
| KO-01..KO-06 | 의미 보존·과장 제거·문체·용어·reader surface 점검 | PASS |
| KO-07 | contextual terminology audit (`findings=0`) | PASS |

## 실행 명령과 증거

```text
./gradlew :bluetape4k-exposed-clickhouse:test \
  --tests 'io.bluetape4k.exposed.clickhouse.ClickHouseQueryLifecycleTest' \
  --tests 'io.bluetape4k.exposed.clickhouse.support.TrackingClickHouseConnectionTest' \
  --no-daemon --no-build-cache --no-configuration-cache --rerun-tasks --console=plain
```

현재 명령은 `ClickHouseQueryLifecycleTest` 30건과
`TrackingClickHouseConnectionTest` 1건을 실행해 `BUILD SUCCESSFUL` 및
`failures=0`, `errors=0`, `skipped=0`을 기록했다. 테스트 결과 XML은
`exposed/clickhouse/build/test-results/test/`에 생성됐다. socket probe 로그는
`attempt=1 elapsedMillis=2006 emitted=2 rows=2 failure=none`,
`attempt=2 elapsedMillis=2005 emitted=2 rows=2 failure=none`,
`attempt=3 elapsedMillis=2006 emitted=2 rows=2 failure=none`이다.

V2 connection attempt probe도 3회 모두 닫힌 loopback 포트의 `SQLException`
및 5초 이내 종료를 확인했다. 모듈 전체 테스트는 204건,
`detekt`, `checkKotlinAbi`, `git diff --check`를 같은 변경 상태에서 통과했다.
독립 review는 P0 0건, P1 2건을 지적했으며 실제 V2 unwrap과 예외 범위 교정으로
반영했다. 재검토 lane이 응답하지 않아 inline fallback review를 수행했고,
현재 diff에서 P0/P1은 0건, P2는 문서에 명시한 `N/A` 경계 외에 0건으로
확인했다. standalone 최신 `ktlint`는 저장소에 구성된 gate가 아니며 기존
파일 형식과의 baseline 차이를 보고하므로 `detekt`와 `git diff --check`를
적용 가능한 정적 gate로 사용했다. PR과 머지는 이번 요청 범위에 포함하지
않는다.

## DoD Status

- [x] 기본 V2 driver 경로·버전과 server/JDBC 기준 기록
- [x] socket, server query, coroutine/downstream cancellation을 별도 probe로 분리
- [x] timeout/cancellation 뒤 local cleanup·후속 connection reuse 확인
- [x] deterministic V2 socket timeout 및 remote cancellation을 보장하지 않는 근거 기록
- [x] 회귀 테스트와 EN/KO README 초안 반영
- [x] 모듈 전체 정적/ABI 검증 및 문서 품질 게이트 반영
- [x] 최초 독립 review 결과와 inline fallback provenance 반영
- [x] PR·머지: N/A (이번 요청 범위 밖)
