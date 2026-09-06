# #815 JDBC·R2DBC test-support 제공자 계약

## 상태와 승인 범위

- 상태: 설계 방향 승인 완료, 작성 명세 검토 대기. 구현은 아직 시작하지 않았다.
- 이슈: <https://github.com/bluetape4k/bluetape4k-exposed/issues/815>
- 기준: `03597729429fa0fe2ae74f2548640fdf683e082a`, Exposed catalog ref `9698c9d66bea6fcba373143ee8fa5bfbd9812d4b`.
- 브랜치: `feat/issue-815-test-support-contracts`, PR 대상: `bluetape4k/bluetape4k-exposed`의 `develop`.
- 사용자는 제공자 구현·검증·PR 생성을 승인했고, 이후 기존 API 유지와 최소 adapter 추가 방향을 승인했다. 머지는 별도 승인이다.
- 이번 PR은 제공자 계약만 보강한다. workshop 두 곳과 clinic의 실제 이전, 배포, catalog 변경은 제외한다. #815 전체를 닫지 않는다.

## 문제와 현재 근거

| 근거 | 현재 동작과 설계에 미치는 영향 |
|---|---|
| `exposed/jdbc-tests/.../tests/WithDB.kt`, `WithDBSuspending.kt` | enum별 fair semaphore와 연결 캐시를 공유한다. suspend 경로는 permit 획득을 별도 dispatcher에서 수행한 뒤 바깥 `try/finally`에 진입한다. 취소 시 permit 반환을 별도로 검증해야 한다. |
| `exposed/r2dbc-tests/.../tests/withDb.kt` | 같은 permit 인계 경계가 있고, 연결 성공 전에 shutdown 등록 상태를 변경한다. 첫 `configure` 호출은 구성된 연결을 기본 연결로 남긴다. |
| 양쪽 `TestDB.kt` | 공개 enum과 `connect` API가 이미 사용된다. JDBC `connect`는 자체 `beforeConnection`을 호출하고, R2DBC는 외부에서 호출한다. 공용화 과정에서 중복 호출하면 안 된다. |
| `exposed/r2dbc-tests/.../tests/withTables.kt`, `TestSupportsTest.kt` | 일반 본문 실패에는 cleanup/recovery 실패를 suppressed로 추가하지만 취소에는 추가하지 않는 기존 계약이 있다. |
| JDBC `WithSchemas.kt`, `WithSchemasSuspending.kt` | `finally`의 drop 실패가 본문 실패를 덮을 수 있다. 실패 주입 회귀 테스트로 보강한다. |
| JDBC `build.gradle.kts` | Spring Boot starter가 `implementation`이다. 현재 main source의 Spring import 검색 결과는 없지만, 제거 판단에는 POM과 소비자 검증도 필요하다. |
| downstream 자체 `TestDB` enum | provider enum과 타입이 다르다. clinic의 `H2_COMMITMENT`, tenant seed, FK 역순 삭제는 provider enum에 추가하지 않는다. |

downstream 근거는 `exposed-workshop/00-shared/exposed-shared-tests`, `exposed-r2dbc-workshop/00-shared/exposed-r2dbc-shared`, `clinic-appointment/appointment-core/src/test/.../test`의 `TestDB`와 `withDb` 소스다. 다른 저장소는 읽기만 했다.

기준 검증은 JDBC와 R2DBC 모듈을 `--no-parallel`로 실행했다. JUnit XML 기준 JDBC 186건 중 170건 통과·16건 skipped, R2DBC 21건 통과, 실패·오류는 0건이다. 이 명령에서 JDBC는 요청한 `WithDbTest`보다 넓게 실행되었다. skipped 16건은 기존 `SelectTest` 14건과 JDBC/suspend schema 테스트 각 1건이며 신규 계약의 통과 증거로 사용하지 않는다.

## 대안과 결정

1. 기존 enum API만 보강: 변경은 작지만 별도 enum과 custom DB를 사용하는 downstream의 lifecycle 복사를 제거하지 못한다.
2. **기존 API를 유지하고 backend별 fixture adapter를 추가: 채택.** 연결과 도메인 데이터는 호출자가 정의하고 제공자는 반복되는 테스트 lifecycle을 실행한다.
3. 새 공용 모듈이나 JDBC/R2DBC 통합 상위 계층: 배포 범위와 추상화가 늘어난다. 기존 두 artifact로 충분하므로 채택하지 않는다.

## 공개 API와 호환성

기존 package와 `TestDB`, `withDb`, `withDbSuspending`, `withSuspendedDb`, `withTables`, `withSchemas` 및 suspend 변형의 JVM descriptor, 기본 인자 진입점, enum 항목을 보존한다. 기존 enum을 새 interface 구현체로 변경해 기본 인자 bridge가 달라지는 방식은 사용하지 않는다.

신규 adapter는 각 기존 artifact 안의 `JdbcTestDbFixture<K>`와 `R2dbcTestDbFixture<K>`다. `K`는 호출자가 정한 DB 식별자이며, 문자열이나 provider enum으로 강제하지 않는다.

- 공개 factory `jdbcTestDbFixture` / `r2dbcTestDbFixture`는 `key`, 연결 생성 callback, JVM 종료 callback을 받는다. R2DBC의 생성 callback은 suspend를 지원한다.
- callback에 전달하는 일시 구성은 기존 `DatabaseConfig.Builder.() -> Unit` 계약과 맞춘다. URL, dialect별 설정, pool 설정과 초기 연결 hook은 생성 callback 안에서 호출자가 구성한다.
- adapter는 `key`와 읽기 전용 현재 `database`를 노출한다. 생성 직후 `database`는 null이다. 외부에서 캐시를 임의 교체하는 API는 추가하지 않는다.
- adapter 인스턴스 하나가 직렬화와 캐시의 단위다. 같은 물리 DB를 쓰는 호출자는 같은 adapter 인스턴스를 공유한다. key의 동등성으로 서로 다른 인스턴스를 전역 등록하지 않는다.
- factory는 외부 pool/container를 새로 소유하는 범용 자원 factory가 아니다. 매번 연결을 생성하더라도 이미 호출자가 소유한 연결 공급원을 참조하는 Exposed wrapper를 반환한다. 호출마다 별도 장기 pool/container를 생성하는 callback은 지원하지 않는다. fixture와 callback의 수명은 테스트 스위트/JVM 수명이며, 요청마다 생성하는 용도로 사용하지 않는다.
- 기존 이름의 additive overload로 adapter를 받는다. 본문 receiver는 JDBC/R2DBC transaction이고 본문 인자는 `K`다. 기존 enum overload는 같은 내부 실행 경로에 위임하면서 기존 인자와 receiver를 유지한다.
- adapter overload는 `withDb`, JDBC `withDbSuspending`, 양쪽 `withTables`/`withSchemas`와 JDBC suspend 변형까지 제공한다. deprecated 별칭은 기존 enum 호출 호환성만 유지하며 새 별칭을 만들지 않는다.
- 트랜잭션 스코프의 `currentJdbcTestDbFixture` / `currentR2dbcTestDbFixture`로 현재 adapter를 읽는다. commit 후에도 식별자를 유지하고 트랜잭션 종료 후 이전 컨텍스트를 복원한다. custom key를 legacy `currentTestDB`로 강제 변환하지 않는다.
- 기존 enum 호출은 기존 `currentTestDB`와 `TestDB.db` 관찰 결과를 유지하도록 내부 bridge를 사용한다. 기존과 새 API에 서로 다른 semaphore를 두지 않는다.

downstream wrapper는 DB별 adapter를 한 번 생성하고 자신의 enum 값을 key로 전달한다. 일반 lifecycle은 provider overload에 위임한다. 현재 enum이 필요한 코드는 fixture key를 읽고, tenant seed·정리 순서·DB 선택 정책은 wrapper에 둔다. 이번 PR의 소비자 fixture는 이 사용 형태를 별도 package에서 컴파일·실행하며 실제 downstream 저장소는 수정하지 않는다.

## 실행과 자원 소유권

### 직렬화와 취소

- 같은 adapter의 JDBC blocking/suspend 진입점은 같은 fair permit을 사용한다. R2DBC도 adapter별 FIFO 대기 계약을 제공한다. 서로 다른 adapter는 독립적으로 진행한다.
- suspend 대기 중 취소는 본문을 실행하지 않고 종료한다. permit을 이미 획득한 시점의 취소도 반환 책임이 정해진 영역 안에서 처리하여 누수를 막는다. 테스트는 dispatcher 반환 경계의 취소를 포함한다.
- 본문과 연결 생성은 `NonCancellable`로 감싸지 않는다. 취소 후 필수 정리만 제한적으로 실행한다.
- 같은 adapter의 중첩 호출은 지원하지 않는다. 무한 대기 대신 명확한 예외로 거부하며 이미 진행 중인 바깥 본문은 정상적으로 정리한다. JDBC thread-local만으로 suspend 중첩을 판단하지 않는다.
- 중첩은 아직 실행 중인 같은 adapter의 진입 토큰을 상속한 호출을 뜻한다. 독립 coroutine의 동시 호출은 대기시킨다. 바깥 호출이 끝나 무효화된 토큰은 후속 호출을 거부하는 근거가 아니다. 이 구분을 실제 suspend/dispatcher 전환과 자식 coroutine 테스트로 검증한다.
- `maxAttempts = 1`로 본문 재실행을 막는다. 새로운 전체 트랜잭션 retry 정책을 추가하지 않는다.

### 연결과 일시 구성

- 첫 초기화는 기본 구성으로 수행하고 성공한 연결만 캐시한다. 생성 실패 뒤 다음 호출은 다시 초기화할 수 있어야 한다.
- `configure`가 있으면 기본 연결과 분리된 일시 구성을 사용하고 성공·예외·실제 취소 모두에서 이전 연결 참조를 복원한다. 첫 호출에 `configure`가 있어도 일시 구성을 기본값으로 남기지 않는다.
- 첫 `configure` 호출에는 기본 wrapper와 일시 wrapper를 각각 생성한다. 이후 구성 없는 호출은 기본 wrapper를 재사용한다. 구성 callback은 일시 wrapper 생성에만 1회 실행하며, 테스트는 생성 횟수도 검증한다. 기존 초기화의 각 `beforeConnection`은 실제 wrapper 생성마다 정확히 1회 실행한다.
- 일시 wrapper는 진행 중인 트랜잭션이 완전히 끝난 후 provider가 만든 transaction-manager 등록만 해제한다. 기존 기본 wrapper나 외부 pool/container를 닫지 않는다. R2DBC의 필요한 suspend 정리만 `NonCancellable`로 실행한다. 해제 실패도 아래 본문/cleanup 우선순위를 적용하고 다음 호출의 permit과 참조 복원을 보장한다.
- callback이 일시 구성을 요청받고도 기본 wrapper와 같은 인스턴스를 반환하면 `IllegalArgumentException`으로 거부한다. 기본 wrapper를 일시 wrapper처럼 정리하지 않는다.
- legacy `TestDB.db` 변경은 해당 DB permit 안에서만 실행하고 복원한다. unrelated `TransactionManager.defaultDatabase`를 호출마다 전역 교체하지 않는다.
- 최초 성공한 초기화 이후 JVM 종료 callback을 한 번 등록한다. 등록 실패 시 등록 상태를 성공으로 남기지 않고 명확히 전파한다. 테스트에서는 실제 JVM 종료 대신 내부 등록 seam으로 횟수와 실패를 검증한다.
- 기본 wrapper 생성과 종료 hook 등록을 한 초기화 단위로 취급한다. 등록까지 성공해야 캐시를 공개한다. 등록 실패 시 새 wrapper의 provider 등록만 정리하고 캐시를 이전 상태로 돌린다. 다음 호출은 초기화 전체를 다시 시도한다. hook 실행 시 이미 성공한 callback을 재호출하지 않는다.
- provider는 callback으로 제공받은 외부 pool/container를 임의로 닫지 않는다. 연결 생성 도중 획득한 외부 자원의 실패 정리와 JVM 종료 callback 구현은 호출자 책임이다. provider가 자체 생성한 임시 등록 상태는 provider가 정리한다.
- 운영용 `close`/`reset` 서비스 API나 임의 key의 영구 전역 registry는 추가하지 않는다. 종료 callback 실패는 다른 adapter의 종료 정리를 막지 않는다.

### 로그와 입력 경계

factory callback과 `Table`/`Schema` 정의는 신뢰된 테스트 코드다. HTTP 입력이나 외부 tenant 이름을 그대로 받아 schema를 삭제하는 서비스 API로 사용하지 않는다. key의 `toString()`, JDBC/R2DBC URL, 연결 설정, SQL 인자, callback 예외 메시지는 provider 로그에 출력하지 않는다. provider가 새로 남기는 로그는 backend·초기화/정리 단계·예외 타입 등 고정된 진단 항목으로 제한한다. 원래 예외 객체는 호출자에게 그대로 전달하며, 외부 driver/Exposed 로깅 정책은 호출자 책임이다.

## 테이블·스키마 정리와 예외

| 경로 | 계약 |
|---|---|
| 일반 본문 실패 + cleanup 실패 | 원래 본문 예외를 유지하고 cleanup 실패를 발생 순서대로 suppressed에 추가한다. 같은 예외 인스턴스를 자기 자신에게 추가하지 않는다. |
| 본문 성공 + cleanup 실패 | cleanup 실패를 호출자에게 전달한다. recovery도 실패하면 recovery 실패를 그 아래 suppressed로 남긴다. |
| R2DBC `withTables`의 본문 취소 | 기존 계약대로 원래 취소를 유지하고 cleanup/recovery 실패를 그 취소에 추가하지 않는다. legacy와 adapter overload에 동일하게 적용한다. |
| 그 밖의 취소 경로 | 원래 취소를 유지하고 helper가 직접 받은 cleanup 실패를 suppressed로 보존한다. cleanup은 필요한 범위에서만 `NonCancellable`로 실행한다. |
| cleanup 자체의 취소 | 이미 본문 실패가 있으면 cleanup 때문에 본문 실패를 바꾸지 않는다. 본문 실패가 없으면 취소를 전달한다. R2DBC `withTables` 예외 정책은 위 행을 우선한다. |
| 생성 도중 실패 | 호출자가 생성·삭제 권한을 준 요청 대상만 정리한다. 전역 schema 정리나 unrelated table 삭제를 하지 않는다. 원래 생성 실패가 우선한다. |

helper가 소유하는 테이블·스키마 정리와 Exposed 내부 connection/statement 정리는 구분한다. upstream이 로그만 남기는 내부 cleanup 예외까지 보존한다고 주장하지 않는다. 그 한계는 #817과 기존 lesson을 유지한다.

schema 미지원 dialect는 기존처럼 본문을 실행하지 않는다. `dropTables=false`는 종료 후 테이블을 남기는 opt-out이다. 정상적인 cleanup이 가능한 DB에서 성공·본문 실패·취소 후 요청 테이블/schema의 부재를 검증한다. DB 단절이나 의도적인 drop 실패가 있을 때 잔여물 제거를 보장하지 않으며, 실패 증거와 명시적인 테스트 복구를 남긴다.

입력으로 전달한 테이블/schema는 호출자가 전용 테스트 대상으로 소유해야 한다. 기존 `withTables`의 사전 drop과 schema의 cascade 정리를 유지하므로 공유 production schema를 인자로 넘기면 안 된다. 생성 도중 실패해도 `dropTables=false`의 opt-out을 우회하지 않는다. schema 미지원 경로는 완료로 오인하지 않도록 기존 테스트의 조건부 제외와 신규 지원 backend 검증을 구분한다.

## 의존성과 문서

- 새 module·외부 dependency·catalog 승격은 없다. JDBC/R2DBC 구현을 억지로 하나의 상위 추상화로 합치지 않는다.
- 실제 main source에 필요하지 않은 application framework starter의 전이 의존성을 제거한다. fixture 사용에 필요한 Exposed·코루틴·테스트 지원 의존성과 이를 위한 BOM은 단순히 이름에 `spring`이 있다는 이유로 제거하지 않는다.
- 소비자 의존성은 `testImplementation`을 사용한다. 테스트 artifact의 test runtime dependency 자체와 application main runtime으로의 유출을 구분해 검증한다.
- 양쪽 README 영어·한국어, 새 API와 기존 변경 계약의 한국어 KDoc를 함께 갱신한다. central manual tree는 이 저장소에 복제하지 않는다.
- 이슈의 `scripts/verify-publication-poms.py`는 이 저장소가 아니라 sibling `bluetape4k-dependencies/scripts`에 있다. 로컬 `scripts/publication/validate_poms.rb` 및 module metadata audit와 함께 실제 도구의 입력 계약을 확인해 검증 계획에 반영한다. 배포 없이 로컬 생성 POM을 대상으로 검증한다.

## 검증 기준

| ID | 수용 기준 | 필요한 증거 |
|---|---|---|
| AC-01 | 기존 호출·기본 인자·deprecated 별칭 호환 | 기존 테스트 + 이전 기준으로 컴파일한 소비자에 신규 JAR를 넣는 linkage 검증 |
| AC-02 | provider enum과 custom key adapter 모두 사용 가능 | 별도 package 소비자 두 종류, JDBC/R2DBC 컴파일 및 실행 |
| AC-03 | 같은 fixture 직렬화, 다른 fixture 독립 진행 | latch/deferred 기반 결정적 동시성 테스트, JDBC blocking/suspend 혼합·중첩 fail-fast·종료 토큰 무효화 포함 |
| AC-04 | 대기 취소·획득 직후 취소에 permit 누수 없음 | 취소 후 후속 호출 완료, 본문 미실행 및 permit 초과 반환 없음 |
| AC-05 | 기본/임시 구성과 컨텍스트 복원 | 최초 configure, 이후 configure, 본문 실패, 실제 Job 취소 및 commit 이후 식별자 검증 |
| AC-06 | 초기화·종료 등록 실패에서 재진입 가능 | 생성 실패 후 재시도, hook 중복 없음, wrapper 생성마다 beforeConnection 1회, 일시 등록 해제, shutdown callback 독립 실행 |
| AC-07 | 예외 우선순위와 조건부 DDL 정리 | H2와 PostgreSQL에서 성공·실패·취소·cleanup 실패 주입, R2DBC 취소 예외 정책 회귀 |
| AC-08 | 불필요한 application framework 의존성 없음 | 생성 POM·Gradle module metadata audit + 소비자 test/main runtime 구분 |
| AC-09 | 문서·API·호환 경계와 안전한 진단 일치 | README locale 동등성, KDoc, 명세 추적, 민감값 sentinel 로그 미노출, 리뷰 P0/P1 해소와 provenance 기록 |

모든 Testcontainers와 실제 DB 검증은 순차 실행한다. 새 계약 테스트의 skipped는 PASS로 계산하지 않는다. 시간 제한은 deadlock 검출용이며 성능 개선 수치나 안정성의 유일한 증거로 사용하지 않는다.

## 실패 모드와 복구

1. permit 인계 시 취소: 결정적 제어 지점에서 재현하고 후속 호출과 permit 수를 검증한다. 고친 경계 밖으로 `NonCancellable`을 넓히지 않는다.
2. 연결 생성·hook 등록 실패: 캐시와 등록 상태를 성공으로 남기지 않는다. callback 소유 자원과 provider 소유 상태를 구분한다.
3. DDL cleanup 실패: 본문 예외를 덮지 않는다. 의도적으로 생성한 테스트 잔여물은 테스트의 별도 정리 경로에서 회수한다.
4. 같은 물리 DB에 adapter를 매번 생성: 동일 인스턴스 공유 계약을 README와 consumer fixture에 고정한다. URL을 전역 key로 삼지 않는다.
5. ABI 또는 POM 변경으로 소비자 실패: 제공자 변경을 되돌리고 원래 공개 descriptor와 의존성을 복원한다. downstream 이전과 배포는 시작하지 않는다.

## 단계별 DoD

- [x] 제공자 한정 작업 범위와 설계 방향 승인
- [x] 격리 worktree와 기준 테스트 확인
- [x] 작성 명세와 inline fallback 설계 리뷰 완료
- [ ] 작성 명세의 사용자 검토
- [ ] AC-01부터 AC-09까지 연결된 구현 계획 승인·리뷰
- [ ] RED/GREEN 구현과 backend 순차 검증
- [ ] ABI/POM·문서·독립 코드 리뷰와 lesson
- [ ] 승인된 PR 생성과 exact-head CI
- [ ] 별도 머지 승인·머지·로컬 동기화

제공자 완료와 #815 전체 완료는 다르다. downstream 이전 및 배포 증거가 없는 동안 이슈의 해당 DoD는 미완료로 남긴다.
