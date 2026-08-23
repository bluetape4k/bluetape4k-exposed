# Issue #699 Exposed queryTimeout 단위와 statement 경계 lesson

## Context

JDBC Redisson 동기·suspended loader와 R2DBC Redisson loader가 Exposed
`Transaction.queryTimeout`에 `30_000`을 전달하고도 이를 30초라고 설명하고 있었다.
Exposed의 해당 속성은 초 단위이므로 실제 statement 예산은 30,000초였고, R2DBC의
전체 key enumeration 60초 예산과도 다른 경계다.

## Decision or Finding

- 세 production loader의 statement timeout 값을 private
  `DEFAULT_QUERY_TIMEOUT_SECONDS = 30`으로 고정했다.
- JDBC와 R2DBC 문서·KDoc은 statement별 30초와 suspended/R2DBC 전체 열거 60초를
  별도 계약으로 설명한다. 전체 열거 timeout은 producer/`AsyncIterator` 경계에
  전달되며 statement timeout을 대체하지 않는다.
- H2 회귀 테스트는 loader가 현재 Exposed transaction에 30초를 설정하는 wiring을
  확인한다. PostgreSQL nightly 테스트는 `SELECT pg_sleep(31)` 실제 statement를
  실행해 JDBC 예외와 R2DBC `AsyncIterator` 원인을 확인하고, timeout 직후 transaction
  context가 비어 있는지와 후속 `SELECT 1` transaction 성공을 검증한다. 물리적
  connection 선택이나 pool lease cardinality는 이 테스트의 계약으로 주장하지 않는다.
- timeout statement 테스트는 30초 이상 걸리므로
  `EXPOSED_ISSUE_699_STATEMENT_TIMEOUT_TEST=true`로 nightly selector에만 활성화한다.
  일반 로컬 H2 테스트와 전체 module test 시간에는 영향을 주지 않는다.

## Outcome

production 값·문서·회귀 테스트가 하나의 초 단위 계약으로 정렬됐다. public API와
ABI는 변경하지 않았고 #707의 driver abort 재설계나 #698 MySQL conformance는 범위에
포함하지 않았다.

## Verification

| 검증 | 결과 |
| --- | --- |
| JDBC 동기/suspended H2 timeout wiring | targeted `BUILD SUCCESSFUL` |
| R2DBC H2 timeout wiring | targeted `BUILD SUCCESSFUL` |
| PostgreSQL 실제 statement timeout | nightly selector에 환경 변수로 연결, hosted 결과 대기 |
| detekt / Kotlin ABI / diff check | fresh run에서 통과 |
| 전체 JDBC/R2DBC Redisson module | Redis/Testcontainers Docker Java 환경 탐색 실패로 local infrastructure blocked |

## Future Guidance

1. Exposed `Transaction.queryTimeout`을 다룰 때 상수 이름에 `_SECONDS`를 포함하고,
   `Duration`/`withTimeout` 밀리초 경계와 같은 값처럼 취급하지 않는다.
2. 값 wiring 테스트만으로 실제 driver statement 계약을 승격하지 않는다. PostgreSQL
   같은 non-H2 driver에서 느린 statement, 원인 전달, timeout 뒤 transaction context
   cleanup과 후속 query를 함께 검증하고 hosted/nightly 결과를 별도 증거로 남긴다.
3. producer/consumer 오류와 caller cancellation은 statement timeout과 별도 테스트로
   유지한다. 하나의 timeout이 모든 lifecycle 계약을 증명한다고 해석하지 않는다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — Issue #699와 #692/#698/#707 경계를 고정했다.
- [x] SPW-02 — 30초 statement와 60초 전체 열거 timeout의 값·단위·검증 위치를
  production source, tests, README와 대조했다.
- [x] SPW-03 — 한국어 technical register와 `queryTimeout`, `AsyncIterator`,
  `nightly`, `hosted`, `blocked` token을 일관되게 유지했다.
- [x] SPW-04 — H2 targeted, detekt, ABI, diff-check 및 PostgreSQL selector 연결을
  확인했다. hosted PostgreSQL 결과는 아직 생성되지 않았다.
- [x] SPW-05 — Markdown read-back으로 수치, 환경 변수, 후속 owner와 범위를 확인했다.
