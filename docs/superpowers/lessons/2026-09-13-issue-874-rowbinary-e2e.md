# #874 RowBinary 실제 서버 검증 lesson

## Context

기존 RowBinary 검증은 fixture에서 preflight·fallback·sentinel을 확인했지만, 실제 ClickHouse JDBC V2 서버의 writer/fallback 선택과 flush 경계는 한 가지 통합 시나리오에 집중되어 있었다. fixture benchmark의 수치는 실제 wire throughput으로 해석하지 않아야 했다.

## Evidence

`clickhouse/clickhouse-server:26.7.3.19`와 catalog `com.clickhouse.jdbc.ClickHouseDriver` `0.9.9`를 사용해 `ClickHouseRowBinaryIntegrationTest`를 두 시나리오 각각 세 번 실행했다. RowBinary `WriterStatementImpl`, fallback `PreparedStatementImpl`, `[2,1,1,1]` batch flush, 다섯 행, `DEFAULT` 두 건, nullable `NULL` 한 건, statement/connection close exactly-once를 관찰했고 XML은 `tests=6`, `failures=0`, `errors=0`, `skipped=0`이었다.

## Decision

실제 wire 정확성은 opt-in Testcontainers integration으로 고정하고, 기존 fixture는 실패 경계와 sentinel 계약을 계속 담당한다. provider proxy는 SQL/credential을 저장하지 않고 delegate class·flush size·close counter만 기록한다. public API, driver dependency, benchmark chart는 변경하지 않는다.

## Outcome

`RecordingProvider`가 prepared statement와 connection을 감싸 batch size와 정확한 close 횟수를 검증하게 되었다. `DEFAULT`와 `setNull`을 실제 RowBinary path에서 확인해 단순 placeholder 행뿐 아니라 ClickHouse 기본값·nullable wire 표현도 회귀 범위에 포함했다. unsupported `INSERT ... SELECT`는 동일한 executor에서 JDBC fallback으로 분리되어 한 번만 실행된다.

## Verification

- `./gradlew :bluetape4k-exposed-clickhouse:test --tests '*ClickHouseRowBinaryTest' --tests '*ClickHouseRowBinaryIntegrationTest' -PclickhouseV2Integration=true --no-parallel --max-workers=1 --no-daemon --console=plain`
- `./gradlew :bluetape4k-exposed-clickhouse:detekt :bluetape4k-exposed-clickhouse:compileKotlin :bluetape4k-exposed-clickhouse:compileTestKotlin --no-parallel --max-workers=1 --no-daemon --console=plain`
- 한국어 용어 audit, `git diff --check`, XML result read-back
- benchmark task는 실행하지 않았고 throughput/chart는 N/A로 유지했다.

## Surprise

실제 V2 writer는 `setNull`을 지원해 nullable column도 fallback 없이 RowBinary로 처리했다. 또한 writer 세 행을 `maxRowsPerFlush=2`로 실행하면 하나의 statement에서 `[2,1]` 두 chunk가 발생하므로, flush 관찰은 최종 row count만 확인하는 것보다 driver 호출 경계를 더 잘 드러낸다.

## Future guard

통합 테스트를 수정할 때는 고정 driver/server/image, 명시적 V2 selector, 최소 세 반복, statement/connection cleanup counter를 유지한다. 실제 wire 측정이 없는 경우 fixture benchmark chart를 갱신하지 않는다. V2 socket timeout과 in-flight remote `KILL QUERY` 종료는 [#875](https://github.com/bluetape4k/bluetape4k-exposed/issues/875)의 별도 관찰 경계로 유지한다.
