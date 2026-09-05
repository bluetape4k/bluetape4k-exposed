# #808 트랜잭션 취소 계약 리뷰

## 판정과 범위

- 코드 리뷰: **APPROVE**, P0/P1 지적 0건. 메인 세션이 수행한 inline 리뷰다.
- 독립 설계 리뷰: **CLEAR**, P0/P1 지적 0건. 별도 architect의 완료된 판정을 유지한다.
- 기준 커밋: `4ca5446465ab157f6b2c64361a1ba24baf6f1bb6`.
- 대상: Ktor 트랜잭션 확장 1개, JDBC/R2DBC/Ktor/Spring 회귀 테스트 4개,
  README 영어·한국어 문서, 교훈 및 이 리뷰 기록.
- 이슈: [#808](https://github.com/bluetape4k/bluetape4k-exposed/issues/808).
  upstream cleanup 실패의 suppressed 보존은 승인된 후속
  [#817](https://github.com/bluetape4k/bluetape4k-exposed/issues/817) 범위다.

독립 코드 리뷰는 agent thread 제한과 응답 시간 초과로 완료하지 못했다.
사용자가 정한 workspace fallback 규칙에 따라 추가 승인 없이 inline 리뷰로
전환했다. 실패한 독립 리뷰를 성공으로 바꾸지 않았으며, 실제 결함 지적,
테스트, CI 및 머지 승인 조건은 그대로 유지한다.

## 코드 근거

| 대상 | 확인한 계약과 근거 |
|---|---|
| [Ktor 트랜잭션](../../ktor/r2dbc/src/main/kotlin/io/bluetape4k/exposed/ktor/r2dbc/ExposedKtorTransactions.kt) | 31행부터 CancellationException과 Error를 원본 그대로 전달한다. 45행의 보조 함수는 metric 기록 실패를 원인 예외의 suppressed에 추가하며 자기 자신을 추가하지 않는다. 일반 예외의 기존 래핑 계약은 유지한다. |
| [JDBC 회귀 테스트](../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/JdbcTransactionCancellationLifecycleTest.kt) | 98행부터 실제 연결의 acquire/commit/rollback/close 호출 수와 statement-close 순서를 확인한다. 획득 직후, begin, statement, begin 실패를 구분한다. JDBC 호출 중단이나 Statement.cancel 보장을 주장하지 않는다. |
| [R2DBC 회귀 테스트](../../exposed/r2dbc/src/test/kotlin/io/bluetape4k/exposed/r2dbc/R2dbcTransactionCancellationLifecycleTest.kt) | 69행의 획득 barrier를 해제한 뒤 실제 연결 정리를 확인한다. 133행부터 publisher 취소, rollback 완료 후 close, close 완료의 단일 실행을 검증한다. 영구 대기 중인 획득의 즉시 취소를 증명하는 테스트는 아니다. |
| [Spring 회귀 테스트](../../spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/repository/support/SimpleExposedR2dbcCancellationTest.kt) | saveAll(Flow) 수집 중 취소하면 부분 저장이 rollback되고 결과를 방출하지 않는다. 83행부터 저장 건수 0과 후속 트랜잭션 성공을 확인한다. Spring Batch writer의 별도 #803/#805 검증을 대체하지 않는다. |
| [Ktor 회귀 테스트](../../ktor/r2dbc/src/test/kotlin/io/bluetape4k/exposed/ktor/r2dbc/ExposedKtorR2dbcCancellationTest.kt) | 원인 예외 동일성, Error, 실제 Job 취소, metric 실패의 suppressed 보존, 후속 쿼리 성공을 확인한다. |

공개 API 및 의존성은 바뀌지 않는다. Database, registry와 애플리케이션 자원 수명은
호출자가 소유하고, 트랜잭션 연결 정리는 Exposed가 소유한다. 새로운 blocking
호출이나 suspend runCatching, !!, 모니터 잠금은 프로덕션 코드에 추가하지 않았다.
README 두 언어의 예외 우선순위 설명도 일치한다.

## 검증 결과

2026-09-05, 위 기준 커밋의 작업 변경본에서 확인했다.

| 검증 | 결과 |
|---|---|
| H2: JDBC/R2DBC/Ktor R2DBC/Spring Boot R2DBC 전체 test | 557개 통과, 조건부 미실행 32개, 실패·오류 0개 |
| PostgreSQL 선택, 네 신규 회귀 클래스 cleanTest 후 --no-build-cache 실행 | JDBC 8 + R2DBC 8 + Ktor 8 + Spring 2 = 26개 통과, 미실행·실패·오류 0개. H2 사례도 포함한다. |
| 네 모듈 detekt | 성공. 입력이 동일하여 UP-TO-DATE 결과를 재사용했다. |
| 네 모듈 checkKotlinAbi | 실행 성공 |
| RED 재현 | 기존 구현에서 metric 기록 실패가 원래 취소 원인을 가리는 실패를 확인한 후 수정했다. |

실행 시 `EXPOSED_TEST_DB=H2` 또는 `POSTGRESQL`과
`--no-parallel --max-workers=1 --no-configuration-cache`를 사용했다.
PostgreSQL 회귀 실행은 네 모듈의 `cleanTest` 후 각 신규 클래스 이름으로
`test --tests '*클래스명'`을 선택하고 `--no-build-cache`를 추가했다.
작업 세션 로그는 `.omx/808-inline-full-h2.log`,
`.omx/808-inline-postgresql-fresh.log`에 남겼다.

## 남은 한계와 전달 조건

- IDE 진단 도구 대신 Kotlin 컴파일, detekt 및 테스트 결과를 사용했다.
- 모든 DB 조합이나 Full Nightly를 실행한 것은 아니므로 릴리스 전체 검증으로 보지 않는다.
- 이전 PostgreSQL 실행의 Connection reset 원인은 확정하지 않았다.
  이번 캐시 없는 성공으로 해당 과거 실패를 수정했다고 주장하지 않는다.
- upstream이 소비한 cleanup 예외 복구는 #817에서 다룬다.
- 이 문서는 로컬 리뷰·검증 기록이다. PR exact-head CI, 현재 리뷰 스레드 확인,
  사용자에게 머지 준비 상태 보고 및 새로운 머지 승인은 별도 조건이다.

## 완료 조건

- [x] 대상 소스와 호출 경로, 취소 우선순위 및 자원 소유권 검토
- [x] 원인 동일성, rollback/close 및 결과 미방출 회귀 테스트
- [x] H2 전체 테스트와 PostgreSQL 대상 테스트, 정적 분석 및 ABI 검증
- [x] inline 리뷰 출처와 독립 설계 리뷰를 구분
- [x] 검증 한계 및 #817 분리 범위 명시
- [ ] PR exact-head CI와 리뷰 스레드 확인
- [ ] 새로운 머지 승인 및 머지·동기화
