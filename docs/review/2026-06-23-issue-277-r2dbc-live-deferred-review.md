# 리뷰 - Issue #277 R2DBC Live Deferred

날짜: 2026-06-23
이슈: #277
모듈: `:bluetape4k-exposed-r2dbc`

## 발견 사항

`virtualThreadTransactionAsync`는 `Deferred<T>`를 반환했지만 `async`를 `coroutineScope` 안에서 감쌌습니다. `coroutineScope`는 child 작업을 기다리므로, caller는 transaction 작업이 이미 완료된 뒤에야 `Deferred`를 받았습니다.

## 원인

구현은 두 가지 concurrency 계약을 섞었습니다. suspend function 내부의 structured waiting과 반환된 `Deferred`를 통한 caller-controlled completion입니다. KDoc은 후자를 약속했지만, `coroutineScope` 구현은 전자를 강제했습니다.

## 수정

caller coroutine context를 capture하고, 그 context에서 설정된 virtual-thread dispatcher 위에 child `async`를 생성했습니다. 반환된 `Deferred`는 caller cancellation에 묶인 상태를 유지하면서도 반환 시점에 live 상태가 됩니다.

## 검증

- 첫 transaction이 release되기 전에 여러 `Deferred` 값을 만들 수 있음을 증명하는 barrier regression test를 추가했습니다.
- 이전 구현에서는 해당 regression test가 timeout됨을 확인했습니다.
- 수정 후 `:bluetape4k-exposed-r2dbc:test`가 통과함을 확인했습니다.
