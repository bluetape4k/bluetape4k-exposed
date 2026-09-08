# #860 ClickHouse driver timeout·row-limit 검증

## 현재 판정

`IN REVIEW` — 실제 ClickHouse 응답 경로를 고정하는 신규 테스트와 양국어
README를 반영했고 ClickHouse 모듈 전체 198건, detekt, ABI 검사를 통과시켰다.
read-timeout은 `com.clickhouse.jdbc.DriverV1`의 `socket_timeout=200`으로 실제
`BatchUpdateException("Read timed out")`을 재현한다. exact-head 독립 재리뷰와
PR 원격 검증을 남겨두었으며, 이 기록은 구현·로컬 검증을 다루고 머지·Full
Nightly·배포를 승인하지 않는다.

## 범위와 기준

- 이슈: [#860](https://github.com/bluetape4k/bluetape4k-exposed/issues/860)
- 대상: `exposed/clickhouse`의 `queryFlow` 실제 driver 응답·정리 경로
- 기준 버전: central catalog `clickhouse-jdbc=0.9.9`, Testcontainers image
  `clickhouse/clickhouse-server:26.7.3.19`
- 변경 경계: 테스트 fixture와 양국어 README, review/lesson 증적만 변경한다.
  공개 `queryFlow` API, driver 교체, native TCP 경로, 공통 모듈 계약은 변경하지
  않는다.

## 근거와 매트릭스

| 경로 | 실제 설정과 기대 결과 | 자원·재수집 검증 |
|---|---|---|
| row limit `throw` | `clickhouse_setting_max_result_rows=2` + `clickhouse_setting_result_overflow_mode=throw`; `TOO_MANY_ROWS_OR_BYTES` SQL 예외 | `ResultSet`·`Statement`·`Connection` 반환, 실행 1회, 다음 `limit=1` 수집 성공 |
| row limit `break` | `clickhouse_setting_max_result_rows=2`, `clickhouse_setting_max_block_size=2`, `clickhouse_setting_result_overflow_mode=break`; `[0, 1]` 부분 결과 | cold collection 2회가 같은 prefix를 반환, 실행 2회, 매번 자원 반환 |
| driver read timeout | `com.clickhouse.jdbc.DriverV1` + `socket_timeout=200` + 행별 1초 `sleep`; `BatchUpdateException("Read timed out")` | mapper 방출 0건, 연결 반환 직후 같은 pool의 후속 수집 성공 |
| lifecycle 조합 | query/execute/next/beforeNext/afterNext/mapper/collector/take/cancel 및 close 오류 | 기존 decorator 관측치와 Hikari active connection 0 정책을 함께 확인 |

`break`의 결과는 서버 block boundary까지 반올림될 수 있으므로
`clickhouse_setting_max_result_rows`를 정확한 클라이언트 절단 상한으로
해석하지 않는다. read-timeout 케이스는 서버
`clickhouse_setting_max_execution_time` query-timeout이 아니라 JDBC socket
read timeout을 검증한다.

## 7-Tier 검토 상태

| 관점 | 수행 내용 | 상태 |
|---|---|---|
| Source/driver | JDBC URL의 `clickhouse_setting_` 규칙, 0.9.9 cached source의 V1 `socket_timeout`, `max_result_rows`, `result_overflow_mode` 경로를 공식 문서·소스와 대조 | PASS (inline fallback) |
| Callers/API/ABI | `queryFlow` public signature와 cold collection 계약을 유지하고 test-only `Fixture` 옵션만 추가 | PASS |
| Tests | 실제 서버 throw/break/read-timeout 및 기존 lifecycle/flow 테스트를 순차 실행 | PASS, 대상 31건 + 모듈 198건 |
| Documentation | EN/KO README에 버전·설정 키·부분 결과·driver timeout·cleanup 책임을 동시 반영 | PASS |
| CI/static | 모듈 전체 `test`, `detekt`, `checkKotlinAbi` | PASS (local) |
| Design-risk | driver replacement/native API/common abstraction을 만들지 않고 호출자 timeout·pool 소유 경계를 유지 | PASS |
| Independent review | 1차 `REQUEST CHANGES`의 P1/P2를 반영한 exact-head 재리뷰 대기 | PENDING |

ClickHouse driver 조사 specialist는 usage limit으로 완료되지 않았다. 이를 성공한
독립 연구로 계산하지 않고, 공식 [ClickHouse JDBC URL 문서](https://github.com/ClickHouse/clickhouse-java/blob/v0.9.9/clickhouse-jdbc/README.md#jdbc-url)와
로컬 0.9.9 source cache를 메인 inline fallback으로 사용했다.

## 실행 근거

```text
./gradlew :bluetape4k-exposed-clickhouse:test \
  --tests '*ClickHouseQueryLifecycleTest.실제 driver row limit 초과는 예외와 정리를 보존하고 재수집하지 않는다' \
  --tests '*ClickHouseQueryLifecycleTest.실제 driver row limit break는 부분 결과를 반복 수집마다 재현한다' \
  --tests '*ClickHouseQueryLifecycleTest.실제 driver read timeout은 부분 결과 없이 실패하고 연결을 반환한다' \
  --no-build-cache --rerun-tasks --no-configuration-cache --no-daemon --console=plain
```

위 명령은 3건 모두 `PASSED`, `BUILD SUCCESSFUL`이다. read-timeout은
`ExposedSQLException` → `BatchUpdateException("Read timed out")` cause와 mapper
방출 0건을 확인했다. lifecycle/flow 두 클래스 재실행은
`SUCCESS: Executed 31 tests`, failures/errors/skipped 0으로 완료했다. 모듈 전체
재실행은 XML `tests=198`, `failures=0`, `errors=0`, `skipped=0`이며
`detekt`와 `checkKotlinAbi`도 `BUILD SUCCESSFUL`이다.

## 남은 게이트

- [ ] 최종 diff exact head에 독립 `code-reviewer`를 실행하고 P0/P1/P2를 기록한다.
- [x] 모듈 전체 test XML에서 tests/failures/errors/skipped를 읽는다.
- [x] `detekt`와 `checkKotlinAbi`를 같은 변경 상태에서 실행한다.
- [x] `git diff --check`, README 링크·용어·버전 read-back을 수행한다.
- [ ] PR 생성 후 exact-head CI/review를 확인한다. 머지는 별도 fresh approval 대상이다.

## DoD Status

- 구현: `PASS`
- 로컬 대상 테스트: `PASS`
- 모듈/정적: `PASS`, 독립 리뷰: `PENDING`
- PR/머지: `PENDING`
