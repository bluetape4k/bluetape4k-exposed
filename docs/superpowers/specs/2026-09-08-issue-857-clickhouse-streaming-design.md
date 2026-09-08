# #857 ClickHouse 조회 결과의 수집과 스트리밍 분리

## 목적과 승인 범위

대상은 `bluetape4k-exposed`의 `exposed/clickhouse` 모듈이다. 전체 결과가 필요한 호출자에게 `queryList`를 제공하고, 대량 결과를 점진적으로 처리하는 호출자에게 별도 `queryFlow` overload를 제공한다.

사용자는 기존 `Iterable` 기반 `queryFlow`의 소스·바이너리·동작 호환성을 유지하면서 `Query` 팩터리와 행 매퍼를 받는 overload를 추가하는 방향을 승인했다. 이 문서는 그 방향의 상세 계약이다. 구현 완료나 성능 검증 결과를 나타내지 않는다.

기준 커밋은 `d5fb9602491ad64811bffcda227e3b2deecf9eea`, 작업 브랜치는 `feat/issue-857-clickhouse-streaming`이다. 새 런타임 의존성, 다른 DB 모듈 변경, 기존 API 제거, PR 생성·머지·Full Nightly 실행은 범위 밖이다. 풀 반환 검증을 위한 `testImplementation(bt4k.hikaricp)` 추가는 후속 사용자 승인으로 허용했다. 중앙 매뉴얼 수정은 소유 저장소의 별도 작업 범위로 관리한다.

## 현재 근거

- `ClickHouseExtensions.kt`의 기존 `queryFlow`는 `transaction { block().toList() }`가 끝난 뒤 항목을 방출한다. 기존 KDoc도 전체 결과 수집을 명시한다.
- 공개 ABI는 루트 `api/bluetape4k-exposed-clickhouse.api`가 관리한다. 기존 JVM 메서드와 기본 인자 메서드를 유지해야 한다.
- 중앙 catalog 커밋 `9698c9d66bea6fcba373143ee8fa5bfbd9812d4b`는 Exposed `1.5.0`, ClickHouse JDBC `0.9.9`를 지정한다.
- 로컬 Exposed `1.5.0` sources JAR의 `Query.kt:301–308`은 `supportsMultipleResultSets`가 거짓이면 iterator 결과를 List로 만든다. 따라서 새 경로에서 `Query.iterator()`를 사용하지 않는다.
- 같은 JAR의 `JdbcTransaction.kt:288–293`은 공개 `execQuery(query, body)`로 JDBC ResultSet 접근을 제공한다. `transactions/Transactions.kt:341–412`에는 독립적인 최상위 suspend 트랜잭션, 재시도, 자원 정리 경로가 있다.
- ClickHouse JDBC V2 `0.9.9`의 [ResultSetImpl](https://github.com/ClickHouse/clickhouse-java/blob/v0.9.9/jdbc-v2/src/main/java/com/clickhouse/jdbc/ResultSetImpl.java)은 `next()`에서 reader를 읽는다. [StatementImpl](https://github.com/ClickHouse/clickhouse-java/blob/v0.9.9/jdbc-v2/src/main/java/com/clickhouse/jdbc/StatementImpl.java)은 query 응답으로 reader와 ResultSet을 만든다. 이 근거만으로 HTTP 응답 전체의 메모리 상한을 입증할 수는 없다.
- Trino의 `pagedQueryFlow`는 페이지별 List를 사용한다. 수명 경계 테스트는 참고하되 행 단위 스트리밍 구현으로 재사용하지 않는다.

외부 조사 기록은 wiki 작업 브랜치의 `research/2026-09-08-exposed-857-clickhouse-streaming.md`에 있다. 게시 및 기본 GNO collection 반영은 아직 완료하지 않았다. JDBC V1 소스를 V2 근거로 사용하지 않는다.

## API 계약과 대안

| API | 실행 시점 | 결과 보관 | 호환성 |
|---|---|---|---|
| `suspend queryList(db, dispatcher, block: JdbcTransaction.() -> Iterable<T>): List<T>` | suspend 호출 시 | 트랜잭션 안에서 전체 수집 | 신규 API |
| 기존 `queryFlow(db, dispatcher, block)` | collect마다 | 전체 수집 후 방출 | 시그니처·기본 인자·동작 유지 |
| 신규 `queryFlow(db, dispatcher, query: JdbcTransaction.() -> Query, mapper: (ResultRow) -> T)` | collect마다 | 아래에 정의한 제한된 행 수만 보관 | 인자 수로 구분하는 신규 overload |

`dispatcher` 기본값은 `Dispatchers.IO`다. 신규 overload는 `query`와 `mapper`를 명시하는 호출 예제를 제공한다. 반환 타입만 다른 overload는 사용하지 않는다. 매퍼는 suspend 함수가 아니며, 트랜잭션과 JDBC 자원이 닫혀도 사용할 수 있는 값으로 변환해야 한다.

`queryList`는 기존 `suspendTransaction`과 동일한 트랜잭션 참여·재시도 정책을 사용한다. 새 최상위 호출의 시도 상한은 `DatabaseConfig.defaultMaxAttempts`를 따르고, 외부 트랜잭션 참여 시 그 트랜잭션 설정을 따른다. 이를 스트리밍의 `maxAttempts = 1`과 혼동하지 않는다. 블록은 재실행될 수 있으므로 조회 외 부수 효과를 넣지 않는다. 테스트는 `defaultMaxAttempts`가 1과 2인 경우의 SQLException 호출 횟수와 비-SQL 예외·취소의 비재시도를 확인한다. 기존 `queryFlow`의 구현과 정책은 그대로 둔다.

선택한 대안은 현재 JDBC 의존성과 Exposed Query 실행·타입 변환을 유지하는 것이다. 기존 API를 스트리밍으로 바꾸는 대안은 방출 시점과 트랜잭션 수명이 달라져 제외한다. Java client 직접 사용은 타입 매핑 중복과 의존성 확대 때문에 제외한다. JDBC 경로의 실제 버퍼링이 수용 기준을 충족하지 못하면 결과를 보고하고 재설계하며, 다른 드라이버를 자동 도입하지 않는다.

## 수집·트랜잭션·스레드 경계

신규 Flow는 cold다. 생성만으로 연결하거나 쿼리하지 않는다. 각 collect가 별도의 Query, 트랜잭션, 연결 대여, Statement, ResultSet을 소유한다. 같은 Flow의 순차·동시 collect 사이에 커서나 변경 가능한 Query를 공유하지 않는다. 팩터리는 호출마다 새로운 Query를 반환해야 한다.

구현은 `flow` 안의 구조적 코루틴 범위에서 생산자와 소비자를 분리한다. 생산자는 주입된 dispatcher에서 Exposed의 `inTopLevelSuspendTransaction`을 사용하며 `outerTransaction = null`로 새 트랜잭션을 연다. suspend 트랜잭션 컨텍스트로 Exposed thread-local을 유지하고, 비-suspend `transaction` 블록 안에서 `emit`하거나 `send`하지 않는다. 소비자의 `emit`은 원래 Flow 컨텍스트에서 실행한다.

생산자는 `maxAttempts = 1`로 설정한다. 이미 방출한 행이 있는 쿼리를 트랜잭션 계층에서 재실행하지 않는다. 드라이버 자체의 요청 재시도 설정과 서버 실행 여부는 별개이며 exactly-once를 보장하지 않는다. 기존 API의 재시도 정책은 변경하지 않는다.

공개 `execQuery(query) { it }`로 커서를 얻은 뒤 같은 트랜잭션 안에서 직접 `ResultSet.next()`를 호출한다. Exposed `ResultRow`/`JdbcResult`의 공개 변환 API를 사용한다. 필드 인덱스는 조회별로 한 번 만들며, 중복 select 표현식 정규화와 `realFields` 순서를 Query 실행 결과와 일치시킨다. private API, reflection, 전체 `toList()` 우회 구현은 허용하지 않는다. 별칭·nullable·날짜·숫자·중복 표현식 테스트로 변환을 검증한다.

매퍼와 Query 팩터리는 조회·변환 용도다. 매퍼의 추가 SQL, lazy DAO 로딩, 커서·Blob·Clob 등 자원 종속 객체 반환, 외부 공유 Query 재사용은 지원하지 않는다. JDBC 호출은 블로킹 I/O이며, ClickHouse 트랜잭션의 원자성이나 DML 롤백을 보장하지 않는다.

이 API는 신뢰된 애플리케이션 코드용이며 SQL 실행 권한·테넌트 격리를 구현하는 보안 계층이 아니다. 신규 Flow는 외부 트랜잭션의 session 설정이나 보안 상태를 복사하지 않는다. 해당 상태에 의존하는 조회는 지원하지 않으며, 호출자는 독립 연결에서도 올바른 권한·테넌트 조건이 적용되는 Database와 Query를 제공해야 한다. 입력값은 Exposed 바인딩으로 전달하고 동적 식별자는 호출자가 허용 목록으로 제한한다. 테스트는 악성 문자열이 바인딩 값으로 유지되는지와 외부 트랜잭션 컨텍스트가 묵시적으로 재사용되지 않는지를 확인한다.

활성 스트리밍 collect 하나는 연결 하나를 종료까지 점유한다. 라이브러리에 전역 동시성 제한을 추가하지 않는다. 호출자가 pool 크기, 대여 timeout, query/socket timeout, 동시 collect 수, 서버 결과 행·바이트 제한을 설정한다. 느린 소비자에는 전체 수집 기한도 둔다. `queryList`는 결과 상한을 Query/서버에 설정할 수 있는 규모에서만 사용한다. JDBC와 매퍼 모두 주입 dispatcher에서 실행하므로 매퍼는 짧은 값 변환으로 제한하고, 무거운 후속 처리는 매핑 후 별도 코루틴 단계에서 수행한다.

확장 함수는 SQL·바인딩 값·행·자격 증명을 새로 기록하지 않는다. 원래 예외는 내부 진단을 위해 보존하며, 외부 응답 정제와 Exposed/driver logger 설정은 애플리케이션 책임이다. 예외 메시지를 자동 정제하여 원래 예외 계약을 바꾸지 않는다.

## 역압력과 메모리 계약

내부 전달 채널은 `Channel.RENDEZVOUS`로 고정한다. 공개 버퍼 인자는 추가하지 않는다. 소비자가 현재 항목을 처리하는 동안 생산자는 다음 항목 하나를 변환하여 send에서 대기할 수 있다. 내부 큐에 쌓인 항목은 0개, 생산자가 보유한 미전달 매핑 결과는 최대 1개다. 다음 행 읽기는 이 전송이 끝난 뒤 시작한다.

이 상한은 확장 함수가 보유하는 매핑 결과에 관한 것이다. 드라이버의 압축·네트워크·디코딩 버퍼, 한 행의 크기, 매퍼가 보관하는 객체, 소비자가 추가한 `buffer`/`toList`/캐시는 포함하지 않는다. 외부 `buffer`가 붙으면 하류가 추가로 미리 받아 갈 수 있지만 내부 채널 용량은 변하지 않는다. 전체 프로세스 메모리가 상수라는 주장은 실측 없이 하지 않는다.

## 종료와 오류 계약

| 상황 | 요구 동작 |
|---|---|
| 빈 결과·정상 완료 | ResultSet을 닫고 Exposed가 Statement·연결을 정리한 뒤 collect 완료 |
| `take(n)`·collector 예외 | 생산자 취소, 추가 행 읽기 중단, 자원 정리 완료를 기다린 뒤 원래 종료 원인 유지 |
| 외부 취소·전송 중 취소 | 취소를 삼키지 않고 전달, 대기 중인 send 해제, 자원 정리 |
| 팩터리·쿼리·매퍼 실패 | 부분 방출 가능성을 유지한 채 실패 전달, 내부 재시도 없음 |
| close 중 추가 실패 | 원래 예외를 교체하지 않음; 직접 소유한 정리 실패는 suppressed로 보존. Exposed가 기록만 하는 정리 실패는 그 정책을 명시 |
| 동기 JDBC 호출이 반환하지 않음 | 즉시 취소 완료를 보장하지 않음. 호출자 설정의 유한 query/socket timeout으로 최대 대기 관리 |

취소 확인은 쿼리 전·행 읽기 전·전송 경계에 둔다. 취소 시 자원을 닫기 위해 다른 스레드에서 JDBC 객체를 무조건 조작하거나 `Statement.cancel()`의 지원을 가정하지 않는다. 블로킹 구간 종료 이후 정리는 `NonCancellable` 범위를 최소화해 완료한다. 주입받은 `Database`, DataSource, pool, dispatcher는 닫지 않는다.

수명 구현은 다음 순서를 따른다. collect 범위가 채널과 생산자 Job을 소유하고, 생산자는 조회 자원과 자신의 종료 결과를 소유한다. 생산자 실패는 채널 종료 원인과 종료 결과로 전달하여 자식 실패가 collector 원인을 선점하지 않게 한다. 소비자는 수신과 emit을 수행하고, 정상 종료가 아니면 자신의 최초 원인을 저장한다. finally에서 채널·생산자를 취소한 뒤 최소한의 `NonCancellable` 구간에서 생산자 정리 완료를 join한다. 종료 결과를 확인하고 아래 우선순위를 적용한다. 한 자원을 정리하는 주체는 하나로 제한하며 Exposed가 Statement·연결을 정리한다.

| 기존 원인 | ResultSet 직접 close 실패 | Exposed Statement·연결 정리 실패 |
|---|---|---|
| 없음 | collect 실패의 주 원인으로 전달 | Exposed의 기록 후 진행 정책을 유지하며 로그 관측 테스트로 확인 |
| 쿼리·매퍼 예외 | 원래 예외 유지, 직접 close 실패를 suppressed에 추가 | 원래 예외 유지, Exposed 로그 관측 |
| collector 예외·take 종료·외부 취소 | 소비자의 원인을 유지, 추가 직접 정리 실패를 suppressed에 보존 | 소비자 원인 유지, Exposed 로그 관측 |

`ResultSet.next()` 반환 직후에도 취소를 확인하여 취소된 행을 매핑하지 않는다. 취소가 비-suspend 매퍼 실행 도중 도착하면 해당 매퍼가 반환할 때까지 기다릴 수 있으며, 그 결과는 send 전에 취소를 확인하여 전달하지 않는다. 드라이버 read나 매퍼가 반환하지 않는 경우 즉시 정리·종료를 주장하지 않는다. 애플리케이션의 유한 timeout 설정과 bounded mapper가 전제다.

## 검증 수용 기준

| ID | 검증 대상 | 필요한 증거 |
|---|---|---|
| AC-01 | queryList | 호출 시 전체 조회, 순서·빈 결과·예외·트랜잭션 내 materialization 테스트 |
| AC-02 | 기존 API | 기존 Kotlin 호출 컴파일, ABI 기존 메서드 유지, 전체 매핑 종료 후 첫 emit 회귀 테스트 |
| AC-03 | cold·독립성 | 생성 시 SQL 0회, 두 번 collect 시 2회 실행, 동시 collect의 서로 다른 연결·커서, 외부 트랜잭션과 분리 |
| AC-04 | 실제 점진적 처리 | 실제 ClickHouse에서 첫 항목 수신 시 전체 결과 미소비, 느린 소비자에서 생산자 미전달 매핑 결과 1개 이하 |
| AC-05 | 수명과 소유권 | 정상·empty·take·취소·쿼리/매퍼/collector 오류별 ResultSet·Statement close와 연결 반환 관측, pool 재사용 가능 |
| AC-06 | 예외·재시도 | 부분 방출 후 SQLException에서 쿼리 1회, 원래 mapper/collector 예외 보존, 정리 실패 주입 시 누수와 예외 우선순위 확인 |
| AC-07 | 컨텍스트·타입 | 주입 dispatcher 실행, suspend 이후 Exposed 컨텍스트 복구, nullable·alias·날짜·숫자·중복 select 표현식 매핑 |
| AC-08 | 대량 결과 메모리 | 동일 SQL/행 폭으로 queryList와 streaming 비교, 10만·100만 행, 별도 JVM·동일 heap·warmup 후 각 3회, 소비자는 count/checksum만 유지 |
| AC-09 | 검증·문서 | 모듈 test·detekt·API 검사, KDoc·README 두 언어의 계약 일치, 중앙 매뉴얼 반영 범위 및 잔여 항목 명시 |

AC-01은 앞서 정의한 queryList 시도 횟수도 확인한다. AC-05/06은 위 종료 원인 표의 각 조합, next 반환 직후 취소, 매퍼 실행 중 취소, send 대기 중 취소를 포함한다. AC-03/05는 크기 2의 pool에서 느린 collect 두 개와 대여 대기 호출을 실행해 대여 timeout·취소 후 연결 반환·후속 SELECT 성공을 관측한다. 실제 서버 응답 지연과 유한 query/socket timeout 후 pool 복구도 별도로 확인한다. 서버 결과 한도 초과는 오류를 숨기지 않고 자원을 반환해야 한다.

AC-08은 time-to-first-item, 전체 시간, peak heap, GC, 입력 행 수/폭, 서버 이미지·driver·JVM·timeout 설정을 기록한다. 처리 행 수 증가에 비례하는 라이브러리 보관 객체 증가가 없어야 하며, 프로파일에서 전체 결과 List나 배열을 유지하는 driver 경로가 발견되면 스트리밍 성공으로 판정하지 않는다. 소스 검사·단위 테스트만으로 AC-04/08을 대체하지 않는다. 실서버 검증은 순차 실행한다. Full Nightly는 자동 실행하지 않는다.

측정은 같은 설정의 별도 JVM에서 warmup 2회 뒤 측정 3회를 수행하고, queryList/streaming 실행 순서를 번갈아 배치한다. heap과 함께 RSS·direct buffer 사용량 및 JFR 할당/보관 근거를 기록한다. 10배 행 수 증가 시 기준선 차감 후 streaming peak live heap의 중앙값 증가율이 3배 이하여야 한다. RSS/direct 증가 또는 잡음 때문에 판정할 수 없으면 원인을 조사하고 `PENDING`으로 남긴다. 첫 행은 전체 매핑 전에 전달되어야 한다. throughput과 TTFI의 수치는 비교 보고하되 이번 변경에 지연 SLO를 새로 도입하지 않는다. driver가 전체 응답을 보관하면 해당 설계로 AC-08을 통과시키지 않고 재설계한다.

## 산출물과 완료 기준

구현 파일, 모듈 테스트, 루트 API baseline, 모듈 README/KDoc, 재현 가능한 실측 기록, 최종 리뷰·교훈을 남긴다. 중앙 매뉴얼은 `bluetape4k.github.io/docs/manual/bluetape4k-exposed` 소유이며 이 저장소에 `docs/manual`을 만들지 않는다. 해당 저장소의 범위 승인과 검증이 없으면 문서 전체 완료로 표시하지 않는다.

- [x] 호환성 보존 방향 승인
- [x] 현재 소스 근거와 상세 계약 작성
- [x] 6개 관점 및 메인 통합 설계 리뷰: 독립 3개 + 세션 한도로 inline fallback 3개, [검토 기록](../../review/2026-09-08-issue-857-spec-review.md)
- [x] 작성된 명세의 사용자 검토: `eb719216` 이후 현재 대화의 `승인`
- [ ] 구현 계획·계획 리뷰
- [ ] AC-01~09 구현·검증 및 최종 리뷰
- [ ] 연구 기록 게시·검색 반영

현재 상태는 `PENDING`이다. 신규 동작의 테스트 통과·실측·구현 완료를 주장하지 않는다. 명세 승인을 반영하여 실행 계획을 검토 중이며, PR 생성과 머지는 별도 승인 경계다.
