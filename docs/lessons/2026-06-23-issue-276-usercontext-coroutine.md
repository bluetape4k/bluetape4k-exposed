# Lessons Learned - UserContext coroutine propagation (2026-06-23)

**관련 이슈**: #276
**영향 모듈**: `:bluetape4k-exposed-core`, `:bluetape4k-exposed-r2dbc`

## L1: ThreadLocal wrapper is not a coroutine context contract

### 문제

`withThreadLocalUser`는 동기 블록 안에서만 `ThreadLocal`을 설정한다. coroutine이 다른 dispatcher에서 재개되면 해당 worker thread에는 값이 없을 수 있고, `UserContext.getCurrentUser()`가 `system`으로 떨어진다.

### 교훈

Coroutine에서 thread-bound context를 전파해야 하면 `ThreadContextElement`를 public contract로 제공해야 한다. 일반 thread용 wrapper와 coroutine용 suspend wrapper를 문서에서 분리한다.

## L2: Auditor tests should cover the actual transaction hop

### 문제

단위 테스트만으로는 auditor 컬럼이 실제 R2DBC virtual-thread transaction 경로에서 보존되는지 증명하기 어렵다.

### 교훈

Audit context fix는 최소 단위 테스트와 함께 insert/update auditor 필드를 실제 transaction hop에서 검증한다. 이번 회귀 테스트는 `createdBy`와 `updatedBy`를 모두 확인한다.
