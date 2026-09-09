# #863 ClickHouse V2 timeout·cancellation 교훈

## 맥락

[#860](https://github.com/bluetape4k/bluetape4k-exposed/issues/860)에서
명시적 `com.clickhouse.jdbc.DriverV1`의 `socket_timeout=200` read-timeout을
확인했지만, catalog 기본 driver는 `com.clickhouse.jdbc.ClickHouseDriver`
(V2)였다. 같은 결과를 V2 의미로 재사용하면 서버 실행 timeout, HTTP socket
timeout, coroutine 취소, 원격 query 종료를 한 계약으로 섞게 된다.

## 발견과 교정

- `socket_timeout=200`은 V2의 지연 행 query에서 3회 모두 약 2.0초에 두 행을
  반환했다. URL에 옵션이 있다는 사실만으로 read-timeout을 단정할 수 없으며,
  이 환경의 deterministic V2 socket timeout은 `N/A`다.
- 닫힌 ephemeral loopback 포트에 `connect_timeout=200`과 V2 키인
  `connection_timeout=200`을 함께 넣은 연결 시도는 세 번 모두 5초 이내
  `SQLException`으로 거부됐다. 이는 연결 거부가 bounded하다는 뜻이지,
  timeout 만료나 ClickHouse handshake 중단을 증명하지 않는다. V2 client
  속성 목록에는 `connect_timeout`이 없으므로 해당 표기를 V2 보장으로
  문서화하지 않는다.
- 직접 `Statement#setQueryTimeout(1)`은 V2에서 서버 `max_execution_time`으로
  적용되어 매회 `SQLTimeoutException("Query execution time exceeded limit")`을
  발생시켰다. 이 계약은 직접 statement에만 적용되며 `queryFlow`가 statement
  handle을 숨기므로 호출자 JDBC/pool 경계가 설정 책임을 가진다.
- `queryFlow(...).take(1)`은 cursor·pool connection을 정리하고 후속 조회를
  성공시켰다. 이는 로컬 producer/ResultSet 수명 증거이지, 블로킹 JDBC read의
  즉시 중단이나 ClickHouse 원격 query 종료 증거가 아니다.
- V2 `Statement#cancel()`은 0.9.9 source에서 query id를 사용한 비동기
  `KILL QUERY` 요청이다. `cancel()`이 반환되어도 ClickHouse가 종료를
  완료했다는 뜻은 아니다. 이번 테스트는 행을 받은 뒤의 request path와
  local cleanup만 다루며 in-flight blocking read나 종료 완료를 검증하지
  않는다. 종료 완료를 주장하려면 별도 server-side 관찰이 필요하다.

## 결정

1. timeout 계층을 `connection acquisition`, transport socket,
   server execution, downstream/coroutine, remote cancellation으로 분리해
   각각 조건·예외·방출·cleanup·재사용을 기록한다.
2. deterministic 결과가 있는 경로만 회귀 계약으로 고정한다. 이번 변경은
   V2 server execution timeout과 local cleanup을 테스트로 고정하고, V2
   connection refusal, socket timeout 및 remote cancellation은 각각의
   한계를 `N/A`로 문서화한다.
3. production `queryFlow`에 generic abort/timeout API를 추가하지 않는다.
   V2에서 statement handle과 remote termination 완료 신호가 노출되지 않는
   상태에서 API 보장을 넓히면 caller가 잘못된 취소 계약을 기대한다.
4. V1의 `BatchUpdateException("Read timed out")` 결과를 V2 README나 KDoc의
   공통 보장으로 번역하지 않는다.

## 검증

- ClickHouse Server `26.7.3.19`와 `clickhouse-jdbc` `0.9.9`에서 V2 전용
  probe 5종을 최소 3회 반복했다.
- 실제 `com.clickhouse.jdbc.ConnectionImpl` unwrap을 확인했고, 닫힌
  loopback 포트 connection attempt는 세 번 모두 5초 이내 `SQLException`으로
  종료됐다. 이는 timeout 만료가 아닌 연결 거부 결과다.
- `Statement#setQueryTimeout` 3회: timeout 3회, 행 방출 0건, 직접 관측
  `executeQuery=3`·`setQueryTimeout=3`, 각 시도 뒤 cleanup 및 후속 조회 성공.
- `socket_timeout=200` 3회: `2006/2005/2006ms`, 각 2행, 예외 없음.
- `queryFlow.take(1)` 3회: `[0]`, bounded window(`<10s`) 및 cleanup·후속 조회
  성공.
- `Statement.cancel()` 3회: 요청 수락, local cleanup 및 후속 조회 성공.
- lifecycle 30건과 tracking fixture 1건을 fresh `--rerun-tasks`
  실행으로 통과시켰고, 모듈 전체 204건, `detekt`, `checkKotlinAbi`도
  통과했다. 독립 review의 P1 지적(실제 V2 unwrap, 광범위 예외 catch)은
  교정했으며 최종 재검토 provenance는 validation 문서에 기록한다.

## 재발 방지 규칙

- **실패한 가정/판단:** V1에서 재현한 `socket_timeout` 예외를 기본 V2에도
  적용할 수 있다고 가정했다.
  **발견 증거 또는 교정:** V2에서 세 번 모두 2행 정상 반환과 약 2.0초
  결과를 관찰했다.
  **수정 결정:** V2 socket read-timeout은 `N/A`로 분류하고 V1 계약을 별도
  유지한다.
  **향후 예방 확인:** 새 driver/버전마다 동일 query를 최소 3회 반복하고
  예외 타입·메시지·elapsed·방출·cleanup을 표로 남긴다.
- **실패한 가정/판단:** `queryFlow.take(1)` 또는 `Statement.cancel()` 반환을
  원격 query 종료로 해석할 수 있다고 가정했다.
  **발견 증거 또는 교정:** `queryFlow`는 statement handle을 노출하지 않고,
  V2 `cancel()`은 `KILL QUERY`를 `SYNC` 없이 보낸다.
  **수정 결정:** local cleanup, request acceptance, remote termination을
  서로 다른 상태로 문서화한다.
  **향후 예방 확인:** server `system.processes`/query id 관찰 증거가 없으면
  remote cancellation을 PASS로 표시하지 않는다.
- **실패한 가정/판단:** 관측 fixture의 실행 카운터가 직접 JDBC와
  `queryFlow`를 구분한다고 가정했다.
  **발견 증거 또는 교정:** follow-up collection이 같은 `JdbcObservation`에
  합쳐져 첫 기대값이 실제 합계와 어긋났다.
  **수정 결정:** 직접 V2 probe에는 별도 `JdbcObservation`을 주입하고,
  fixture별 카운터의 소유 경계를 테스트에 명시한다.
  **향후 예방 확인:** 관측값을 assertion에 사용하기 전에 직접 경로와
  framework 경로의 연결·statement 생성 지점을 분리한다.
- **실패한 가정/판단:** `connect_timeout`을 V2의 연결 timeout 키로
  간주했다.
  **발견 증거 또는 교정:** V2 `ClientConfigProperties`에는
  `connection_timeout`만 있고, `connect_timeout`은 속성 목록에 없었다.
  **수정 결정:** 두 키를 함께 사용한 닫힌 loopback 연결 probe를 남기되,
  관찰된 연결 거부만 `N/A`로 기록한다.
  **향후 예방 확인:** 새 driver 버전마다 `getPropertyInfo`/source의 실제
  키를 확인하고, 연결 거부·timeout 만료·handshake 실패를 별도 결과로
  구분한다.

## DoD Status

- 계층 분리·V2 범위 교훈: `PASS`
- 회귀 테스트·README 반영: `PASS`
- 모듈 전체/정적/ABI: `PASS`
- 독립 review: `PASS` (최초 `REQUEST CHANGES` P1 2건 교정, 재검토 lane 무응답으로 inline fallback 수행; P0/P1 0건)
- PR·머지: `N/A` (이번 요청 범위 밖)
