# #860 실제 ClickHouse 한도와 queryFlow 정리 교훈

## 발견

- ClickHouse JDBC URL에서 서버 설정은 `clickhouse_setting_` 접두사를 사용해야
  한다. 접두사가 없는 `max_result_rows`·`result_overflow_mode`는 client config
  속성으로 해석되어 실제 서버 한도 검증이 되지 않았다.
- `result_overflow_mode=throw`는 `TOO_MANY_ROWS_OR_BYTES` SQL 예외를 발생시키고,
  `queryFlow`는 행을 재전달하거나 쿼리를 재시도하지 않는다.
- `result_overflow_mode=break`는 부분 결과를 반환하며 서버 block boundary까지
  반올림할 수 있다. 따라서 `max_result_rows`를 정확한 행 수 계약으로 문서화하면
  안 된다.
- `socket_timeout=200`과 `com.clickhouse.jdbc.DriverV1` 조합은
  `BatchUpdateException("Read timed out")`를 발생시키는 실제 JDBC socket-read
  timeout 경로다. `queryFlow` mapper 방출은 0건이고 자원 정리 직후 후속
  수집이 성공한다.
- `max_execution_time`은 JDBC를 통해 실행되는 서버 query-timeout 경로다. socket
  read timeout과 다른 계층이므로 두 동작을 하나의 보장으로 합치지 않는다. 이번
  read-timeout 테스트는 서버 query-timeout 설정에 의존하지 않는다.
- catalog 기본 `ClickHouseDriver`는 V2 경로지만, 지연 행과 `socket_timeout`을
  사용한 bounded probe에서 deterministic read-timeout이 발생하지 않았다. 따라서
  이번에 고정한 timeout·cleanup 계약은 명시적 V1 driver 범위이며 V2 보장으로
  확장하지 않는다.

## 결정

- production `queryFlow`와 공통 API는 변경하지 않는다. 실제 V1 driver 동작을
  검증하기 위해 test-only JDBC URL 옵션, 명시적 V1 driver fixture와 관측
  fixture만 확장한다. 기본 V2 timeout/cancellation은 별도 재현 테스트 전까지
  보장하지 않는다.
- 각 실패 뒤 `ResultSet`·`Statement`·`Connection` 정리와 호출자 pool 재사용을
  확인한다. 실패 후 같은 fixture에서 후속 수집이 성공해야 cleanup 증거로 인정한다.
- timeout·limit은 해당 cold collection의 terminal failure로 취급한다. helper가
  application-level retry나 partial-row replay를 추가하지 않는다.
- driver 조사 specialist가 usage limit으로 완료되지 않았으므로 공식 문서·소스와
  로컬 캐시를 inline fallback으로 대조하고, 이를 독립 연구 PASS로 표시하지 않는다.

## 검증

- ClickHouse Server `26.7.3.19`와 JDBC `0.9.9`에서 throw/break/read-timeout
  테스트 3건을 모두 통과했다. read-timeout은 실제 드라이버의
  `BatchUpdateException("Read timed out")`와 mapper 0건을 고정했다.
- lifecycle/streaming 기존 테스트와 신규 테스트를 합쳐 31건을 순차 실행해
  `failures=0`, `errors=0`, `skipped=0`을 확인했다.
- ClickHouse 모듈 전체 테스트는 XML `tests=198`, `failures=0`, `errors=0`,
  `skipped=0`이며 같은 변경 상태에서 `detekt`와 `checkKotlinAbi`도 통과했다.
- timeout 케이스의 query expression은 행마다 일 초 `sleep`과
  `socket_timeout=200`을 사용해 부분 행 없이 실패하는 실제 JDBC socket-read
  경로를 재현한다. 서버 query-timeout과 혼동하지 않는다.

## 재발 방지

1. ClickHouse JDBC URL 옵션을 추가할 때 client option과 server setting의 접두사를
   먼저 확인하고, 잘못된 키가 무시되는 RED 테스트를 남긴다.
2. `throw`와 `break`를 모두 실행해 예외와 부분 결과의 의미를 구분한다. block
   boundary 반올림을 정확한 row-limit으로 단정하지 않는다.
3. 실제 driver timeout을 검증할 때 socket, server query, connection acquisition
   계층을 분리해 각각의 예외·정리·후속 연결 재사용을 관측한다.
4. 독립 리뷰가 실패하면 실패 원인과 inline fallback 범위를 기록하되, 독립 PASS로
   승격하지 않는다. V2에서 timeout이 재현되지 않으면 확인된 V1 범위로 문서와
   수용 주장을 축소한다.

## DoD Status

- 테스트·정리 교훈: `PASS`
- 모듈 전체/정적: `PASS`; 독립 리뷰 V2 범위 finding 반영, 최종 inline fallback: `PASS` (LSP 공백은 유지)
- PR·머지: `PENDING`
