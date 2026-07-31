# 교훈 - R2DBC Live Deferred (2026-06-23)

Issue: #277
Module: `:bluetape4k-exposed-r2dbc`

## L1: 반환하는 Deferred를 대기 scope 안에서 만들면 안 됩니다

### 문제

`coroutineScope { async { ... } }`는 비동기 작업을 반환하는 것처럼 보이지만 scope가 반환하기 전에 child를 기다립니다. 호출자는 첫 작업이 끝나기 전에 여러 job을 예약할 수 없으므로 `Deferred` API가 오해를 일으킵니다.

### 교훈

suspend API가 의도적으로 `Deferred`를 반환한다면 일시적인 대기 scope가 아니라 호출자가 소유한 coroutine context에서 child를 생성합니다. cancellation은 호출자에 연결하고, child가 여전히 suspend 상태일 때 함수가 반환함을 증명하는 barrier 테스트를 사용합니다.
