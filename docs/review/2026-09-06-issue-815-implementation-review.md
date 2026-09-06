# #815 제공자 구현 리뷰와 검증

## 범위와 판정

- 기준: `03597729429fa0fe2ae74f2548640fdf683e082a`.
- 구현: `4125687fe8f2e3f6d492ddfb3fd4bfa9dae9aca4`, 최종 테스트·CHANGELOG: `99fea3e98c193bca1fc8db864a852a5a2f117fa4`.
- 대상: JDBC/R2DBC test-support, 두 ABI 파일, 소비자 검증 script, README 네 파일. downstream·catalog·배포는 제외한다.
- 코드 검토 방식: **inline fallback review**. native `code-reviewer`와 `architect` 요청은 각각 `collab spawn failed: agent thread limit reached`로 실패했다. 기존 다른 작업의 에이전트를 중단하거나 재사용하지 않았다.
- 코드 검토에서 확인한 미해결 P0/P1은 없다. 독립 검토나 특정 모델의 검증 결과가 아니다.
- 통합 게이트: **BLOCKED**. 별도로 필수인 독립 아키텍처 검토는 미실행이다. 코드 리뷰 fallback으로 그 요건을 충족했다고 처리하지 않는다. PR·CI·머지 준비 완료를 주장하지 않는다.

## 관점별 inline 검토

두 backend를 따로 읽고 공통 소비자 경계를 마지막에 대조했다. 아래 결과는 모두 주 세션 검토이며 독립적인 여섯 명의 판정이 아니다.

| 관점 | JDBC 근거 | R2DBC 근거 | 결과 |
|---|---|---|---|
| 성능 | `JdbcFixtureExecution.kt:39`의 registry는 기존 enum만 보관하고 fair permit을 fixture가 소유한다. blocking acquire는 IO 경계 안이다. | `R2dbcFixtureExecution.kt:41`, `:64`의 enum registry와 coroutine semaphore를 확인했다. | P0/P1 0. 호출당 토큰과 context 할당은 존재한다. 성능 개선 수치나 benchmark 결과를 주장하지 않는다. |
| 안정성 | `JdbcFixtureExecution.kt:112`에서 획득 표시를 설정한 뒤 반환하며 `:148`의 finally가 permit을 반환한다. | `R2dbcFixtureExecution.kt:64`의 withPermit과 `:97`의 참조·토큰 복원을 확인했다. | P0/P1 0. FIFO, 대기·획득 후 취소, 활성/만료 토큰, 실제 임시 구성 취소 테스트 통과. |
| 보안 | `JdbcTestDbFixture.kt:46`의 초기화 로그는 고정 문자열이다. 입력 key를 오류 메시지에 삽입하지 않는다. | `R2dbcTestDbFixture.kt:49`도 같은 제한을 지킨다. | P0/P1 0. provider logger의 key·URL·설정·callback 실패 sentinel 미노출 검증. driver 로그와 신뢰되지 않은 schema 입력은 보장 범위가 아니다. |
| 운영 | `JdbcTestDbFixture.kt:34`의 초기화는 종료 hook 등록까지 성공해야 cache를 공개한다. 임시 wrapper만 unregister한다. | `R2dbcTestDbFixture.kt:37`의 초기화와 cleanup 책임을 별도로 확인했다. | P0/P1 0. 실패 후 재시도·hook 등록 횟수·fixture별 callback·외부 pool 미종료 확인. DB 단절 시 잔여물 제거 보장은 없다. |
| API | 기존 함수 파일과 descriptor를 유지하고 fixture overload만 추가했다. `WithDBSuspending.kt:10`의 dispatcher 설명을 실제 동작에 맞췄다. | 기존 enum withDb/table/schema 경로가 fixture에 위임한다. suspend callback과 기존 취소 예외 정책을 유지한다. | P0/P1 0. 기존 ABI 삭제 0, 이전 class의 신규 JAR linkage 통과. |
| 호출자 | 별도 package의 `JdbcFixtureConsumerTest`가 자체 enum·Hikari pool로 여섯 overload를 실행한다. | `R2dbcFixtureConsumerTest`가 자체 enum·ConnectionPool로 세 overload를 실행한다. | P0/P1 0. README 양 언어의 수명·첫 configure·cleanup·testImplementation 계약을 대조했다. |

## 발견과 조치

| 심각도 | 위치·증거 | 조치 |
|---|---|---|
| P1, 해결 | 초기 구현의 JDBC suspend 본문이 명시한 dispatcher 대신 IO에서 실행됨. `/tmp/issue815-jdbc-context-red.log`. | statement dispatcher를 보존하고 본문에 적용했다. dispatcher identity 회귀 테스트 통과. |
| P1, 해결 | 양쪽 legacy 첫 configure 실패 뒤 기본 db 관찰값이 null로 남음. `/tmp/issue815-*-edges-red.log`. | 바깥 finally에서도 baseline을 복원했다. 양쪽 legacy 회귀 테스트 통과. |
| P2, 해결 | `JdbcFixtureConcurrencyTest.kt:37`의 실패 정리가 다른 호출이 가진 permit을 반환할 수 있었음. | 테스트가 직접 획득한 초기 permit의 소유 여부만 추적하도록 수정했다. |
| P2, 해결 | JDBC README는 Detekt 비활성화라고 설명했고 R2DBC 실제 DB 예제는 runTest를 권장했음. | 실제 module 검사와 runSuspendIO 예제에 맞춰 두 언어를 수정했다. |
| P2, 잔여 검증 범위 | 이전 소비자는 열 개 legacy 진입점을 실행한다. deprecated `withSuspendedTables`는 ABI 보존·위임 소스로 확인했으며 독립 소비자 실행에는 포함하지 않았다. | linkage 전체 진입점 검증이라고 표현하지 않는다. 추가 소비자 행렬 보강 여지로 남긴다. |

## 수용 기준 추적

| 기준 | 구현·검증 근거 | 상태 |
|---|---|---|
| AC-01 | 기존 테스트, ABI 추가 26/24행·삭제 0행, 이전 class checksum 불변 및 후보 JAR checksum 일치 | PASS, 위 deprecated 별칭 실행 범위는 별도 표기 |
| AC-02 | backend별 외부 package custom enum·pool 소비자 | PASS |
| AC-03/04 | FIFO, 다른 fixture 독립 진행, JDBC 혼합 호출, 중첩 거부, 만료 토큰, 대기/획득 후 취소, permit 수 | PASS |
| AC-05 | 최초 configure wrapper 두 개, commit 식별자 유지, 실패·실제 취소 후 임시 manager 해제와 baseline 복원 | PASS |
| AC-06 | 생성·hook 등록 실패 후 재시도, 등록 횟수, callback 독립 실행, caller pool 유지 | 핵심 경로 PASS. unregister 자체 실패 주입과 legacy beforeConnection 계측은 아래 한계 참조 |
| AC-07 | H2/PG DDL 성공·본문 실패·실제 취소·drop/recovery 실패·cleanup 취소·self-suppression·부분 생성·opt-out·schema 미지원 | PASS |
| AC-08 | scoped POM 2개, metadata 2개, 소비자 test/main runtime 분리 | PASS |
| AC-09 | README locale·한국어 KDoc·로그 sentinel·정확한 provenance | 문서·로그 PASS, 필수 아키텍처 검토 BLOCKED |

## 실행 증거

모든 DB 작업은 순차 실행했다. 신규 계약 테스트에는 skipped가 없다.

| 검증 | 결과 | 로그/산출물 |
|---|---|---|
| JDBC 전체 test, `--rerun-tasks --no-parallel` | 213건, 실패 0, 오류 0, 기존 assumption skipped 16 | `/tmp/issue815-jdbc-final-full.log` |
| R2DBC 전체 test, 같은 옵션 | 194건, 실패 0, 오류 0, 기존 assumption skipped 14 | `/tmp/issue815-r2dbc-final-full.log` |
| `EXPOSED_TEST_DB=POSTGRESQL`, 각 모듈 `--tests '*FixtureCleanupTest'` | 각 8건 통과, skipped 0 | `/tmp/issue815-jdbc-pg-final.log`, `/tmp/issue815-r2dbc-pg-final.log` |
| 두 모듈 `detekt`, `checkKotlinAbi` | PASS | `/tmp/issue815-jdbc-final-full.log`, `/tmp/issue815-abi-check.log` |
| `ruby scripts/publication/test_validate_test_support_consumer.rb` | 5 tests, 6 assertions, 실패·오류 0 | 현재 세션 실행 결과 |
| 이전 소비자 class + 최종 provider JAR | PASS, class 불변·JAR checksum 일치·main runtime 분리 | `build/issue815/consumer-retry3/result.json`, `/tmp/issue815-final-consumer.log` |
| `validate_poms.rb` | failures=0, files=2, dependencies=624, maven_models=2 | 두 모듈 `build/publications/BluetapeExposed/pom-default.xml` |
| `validate_module_metadata.rb` | failures=0, files=2, variants=4, dependencies=87 | 같은 publication의 `module.json` |
| 신규 main fixture의 GlobalScope/runBlocking/sleep/delay/synchronized/runCatching 검색 | 해당 패턴 없음 | 현재 소스 검색 |
| `git diff --check` | PASS | 현재 세션 |

## 알려진 한계와 다음 게이트

- helper가 직접 받은 실패를 보존한다. Exposed 내부에서 로그만 남기는 cleanup 실패는 #817 범위이며 여기서 해결하지 않았다.
- unregister API 자체 실패 주입과 legacy enum의 beforeConnection 호출 횟수 계측은 실행하지 않았다. wrapper 생성 횟수·등록 실패·등록 해제 결과와 JDBC connect 내부 한 번/R2DBC bridge 한 번 호출 소스를 확인했다. 계획의 모든 실패 주입 행렬이 완료됐다고 주장하지 않는다.
- consumer의 실제 downstream workshop/clinic 이전, 중앙 catalog 변경, 개발판·정식 배포는 하지 않았다.
- 전체 Nightly·PR exact-head CI·review thread·mergeability는 미확인이다. 아직 PR을 만들거나 head를 push하지 않았다.
- 필수 아키텍처 검토가 가능해지거나 그 대체 경로가 명시적으로 승인된 뒤, 남은 검증 행렬과 승인된 PR 생성을 재개한다. 다른 세션의 에이전트를 정리하는 방법으로 우회하지 않는다.

## 문서 검증 DoD

- [x] SPW-01: 한국어 유지보수자용 리뷰, 기준/구현 SHA와 실행 로그 고정.
- [x] SPW-02: 범위·심각도·위치·조치·미검증·판정 포함.
- [x] SPW-03: 기술 용어·예외 정책·독립/inline 구분을 유지하고 KO-01–KO-06 문장·표를 검토.
- [x] SPW-04: source/test/ABI/POM과 표의 주장 대조. 미실행 검사를 PASS로 바꾸지 않음.
- [x] SPW-05: 최종 파일 read-back과 용어 audit 완료. README 두 locale의 예제·조건·제외 범위를 대조했다. CHANGELOG 기존 이력 122/254행의 용어 두 건은 DAO 캐시 상태·작업 목록 문맥의 변경 밖 항목으로 확인해 유지했다.

최종 상태: **BLOCKED — 코드 inline 검토를 독립 아키텍처 검토로 대체하지 않음**.
