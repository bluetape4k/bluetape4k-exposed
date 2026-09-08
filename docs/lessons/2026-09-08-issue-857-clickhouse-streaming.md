# #857 ClickHouse 조회 스트리밍 교훈

## 결정

- 기존 `queryFlow(db, block)`는 Exposed transaction 안에서 `Iterable`을 모두
  materialize하는 호환 API로 유지한다. 새 `queryList`도 같은
  `suspendTransaction` 재시도 정책을 따르며 기존 JVM 메서드와 동작을 바꾸지 않는다.
- 새 `queryFlow(db, query, mapper)`는 별도 top-level suspend transaction에서
  `execQuery`로 얻은 `ResultSet`을 직접 순회한다. `Query.iterator()`를 사용하면 이
  dialect에서 전체 결과를 모으므로, `ResultRow.create(JdbcResult(cursor), fields)`로
  현재 행만 detached 값으로 변환한다.
- 생산자와 소비자는 `Channel.RENDEZVOUS`로 연결한다. 따라서 API 내부 pending item은
  하나이며, `buffer()`나 ClickHouse JDBC driver의 내부 버퍼는 이 계약에 포함되지
  않는다.

## 수명과 취소

- 호출자가 제공한 `Database`, pool, dispatcher는 닫지 않는다. API가 직접 소유하는
  `ResultSet`만 닫고 Statement/Connection은 Exposed transaction 정리에 맡긴다.
- consumer의 취소·collector 예외·mapper 예외가 있으면 그 원인을 우선한다. producer를
  취소한 뒤 `NonCancellable` 영역에서 `join`해 JDBC 정리가 끝난 다음 반환한다.
  `ResultSet.close()` 실패는 주 원인에 중복 없이 suppressed로 남긴다.
- `CancellationException`은 broad `Throwable` 처리보다 먼저 분기한다. 코루틴
  stacktrace recovery로 예외 인스턴스가 복사될 수 있으므로, identity를 요구하는
  테스트는 추가 상태가 있는 marker 예외와 호출자 관찰 지점을 사용한다.
- query factory, `ResultSet.next`, mapper, rendezvous `send` 앞뒤의 취소를 별도로
  고정한다. JDBC blocking call 자체는 취소 즉시 중단되지 않으므로 호출자는 유한한
  connection/socket/query timeout을 설정해야 한다.

## 보안과 API 호환성

- 외부 transaction의 연결 지역 상태나 marker를 새 stream transaction으로 전달하지
  않는다. tenant/security predicate는 매 수집의 `query`에 명시하고 바인딩 컬럼을
  실제로 조회하는 테스트로 SQL injection 회귀를 막는다.
- 공개 overload는 생성된 ABI를 확인하고 기존 3-parameter trailing-lambda
  `queryFlow`를 보존한다. 새 mapper는 transaction 밖에서도 유효한 detached 값만
  반환해야 하며 lazy Exposed row나 추가 SQL을 캡처하지 않는다.

## benchmark와 측정

- benchmark source set은 모듈 `testImplementation`을 상속하지 않는다. 기존
  `ClickHouseServer.Launcher`를 재사용하려면 공개 runtime dependency를 늘리지 않고
  `benchmarkImplementation(libs.testcontainers.clickhouse)`만 명시해야 한다.
- API/행 수/run마다 fresh JVM을 실행하고 `run=1/3: list→flow`, `run=2: flow→list`로
  순서를 교대한다. List 결과 참조를 해제한 뒤 1,000행 bounded warmup과 5회 bounded
  GC settle을 수행해야 row-count-dependent baseline contamination을 피할 수 있다.
- forced-GC live heap, raw allocation peak, RSS/direct buffer, JFR old-object sample은
  서로 다른 신호다. `Object[1000000]` retained sample은 List materialization의
  강한 증거지만, Flow sample에 배열이 없다는 사실만으로 driver 내부 버퍼가 없다고
  단정하지 않는다. JMH throughput은 별도 결과이며 profile latency SLO와 결합하지
  않는다.

## 도구와 검증

- Gradle은 repository helper가 허용하는 context-mode 실행으로 수행하고, Testcontainers
  테스트에만 정상 Colima socket 환경을 전달한다. configuration-cache 직렬화 오류가
  있으면 같은 조합을 `--no-configuration-cache`로 재실행하며, 실패 실행을 성공
  증거로 합산하지 않는다.
- 대상 모듈의 ABI, detekt, 전체 test XML의 tests/failures/errors/skipped를 읽고,
  benchmark compile/JAR/detekt와 JMH report 경로를 별도로 확인한다. 녹색 Gradle 한
  줄은 실제 Docker dispatch, driver timeout, 전체 조합을 증명하지 않는다.
- 이번 범위에서는 중앙 manual/wiki 게시, PR 생성, push, merge, Full Nightly를
  수행하지 않았다. 실제 driver 응답 timeout·row limit과 승인 명세의 모든 cleanup
  조합은 후속 검증 항목으로 남긴다.

## 재발 방지 체크

1. 새 Exposed query API를 추가하기 전에 기존 overload의 ABI와 materialization 시점을
   고정하는 RED 테스트를 작성한다.
2. ResultSet/Statement/Connection 각각의 정상·take·mapper·collector·취소 정리와
   호출자 pool 재사용을 실제 driver에서 확인한다.
3. 프로파일 결과를 문서화할 때 warmup, GC settle, 실행 순서, fresh JVM, raw/JFR의
   해석 한계를 함께 기록한다.
4. 독립 리뷰가 불가능하면 미실행 상태를 PASS로 바꾸지 말고 inline fallback과
   근거를 기록한다.
