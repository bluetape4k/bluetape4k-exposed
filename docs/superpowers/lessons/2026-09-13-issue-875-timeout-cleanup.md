# #875 JDBC V2 timeout·cancellation·cleanup lesson

## Context

기존 #860/#863 증적은 V2 query timeout, local `queryFlow` cleanup, 직접적인
`Statement.cancel()` 호출을 각각 확인했지만, cancel request가 서버의 remote
query 종료로 이어졌는지와 pool 재사용 경계를 한 receipt로 분리하지 못했다.

## Evidence

고정된 `clickhouse-jdbc` `0.9.9` V2와 ClickHouse Server `26.7.3.19` 조합에서
lifecycle 31개와 extensions 10개를 순차 실행했다. 첫 행 이후 in-flight
`Statement.cancel()` 세 번은 모두 요청 수락과 local resource 정리를 보였고,
test-only `system.processes` observer는 세 번 모두 첫 관찰 없이
`TIMEOUT/POLL_DEADLINE_EXPIRED`를 반환했다. 따라서 remote termination은 N/A다.

## Decision

request acceptance, local cleanup, server-side termination을 하나의 성공 상태로
합치지 않는다. bounded observer는 query id를 parameter binding하고 안전한 결과
분류만 반환한다. 원격 종료가 관찰되지 않으면 N/A와 caller 책임을 유지하며,
generic abort API·강제 KILL QUERY 동기화·production source 변경은 추가하지 않는다.

## Outcome

`ClickHouseQueryObservation` test helper와 lifecycle receipt가 timeout/cancel
경계를 반복 가능하게 기록한다. 기존 `queryFlow`의 producer cancellation,
`NonCancellable` join, primary/suppressed cleanup 정책과 Hikari pool reuse는
회귀 테스트로 유지했다. 변경 범위는 test/docs-only다.

## Verification

- `:bluetape4k-exposed-clickhouse:test` lifecycle 31개, extensions 10개 통과
- V2 selector와 고정 image/digest, driver/server metadata를 receipt에 기록
- module `detekt`, `compileKotlin`, `compileTestKotlin`, 한국어 용어 audit,
  `git diff --check` 통과
- benchmark/chart와 Full Nightly는 실행하지 않음

## Surprise

V2 `Statement.cancel()` 호출은 즉시 요청 수락으로 관찰되었지만, 별도
`system.processes` connection에서 query가 먼저 나타나는 것을 세 번 모두 볼 수
없었다. 이는 query가 즉시 사라졌다는 뜻도, 계속 남아 있다는 뜻도 아니며, 현재
driver의 비동기 KILL 경로만으로 remote 완료를 주장할 수 없음을 재확인했다.

## Future guard

timeout 종류, request acceptance, local resource counter, remote outcome을 항상
별도 필드로 기록한다. remote 관찰이 권한·버전·driver timing 때문에 불가능하면
PASS로 승격하지 않는다. 실제 termination 보장이 필요해지면 별도 이슈에서
server-side query log/process 권한, bounded KILL acknowledgement, cancellation
API 계약을 먼저 설계하고 검증한다.
