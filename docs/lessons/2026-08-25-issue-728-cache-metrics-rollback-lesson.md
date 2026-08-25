# Issue #728 lesson: rollback 예외를 삼키지 않는 설치 경계

## 관찰

Micrometer registry처럼 외부 구현이 호출되는 cleanup 경계에서
`runCatching { registry.remove(meter) }`는 성공 여부와 실패 원인을 모두 잃게 한다.
설치 primary exception이 이미 발생한 뒤라서 이 손실은 테스트를 통과하면서도 부분
registry 오염을 남긴다.

## 결정

rollback은 best-effort로 모든 owned meter를 계속 제거하고, 제거 결과와 residual 수를
구조화된 suppressed diagnostic으로 남긴다. public exception은 안정적인 분류 메시지를
유지하고, registry 원본 메시지는 secret 노출을 막기 위해 저장하지 않는다. 완전 rollback은
동일 설정 재시도를 허용하지만 residual이 있으면 새 registry를 사용해야 한다.
Coroutine 경계에서 들어온 `CancellationException`은 registration failure로 바꾸지 않고
원본을 다시 던져 cancellation을 삼키지 않는다.

## 다음 guard

- 외부 cleanup 호출을 `runCatching`으로 감싸 결과를 버리지 않는다.
- primary 예외와 cleanup 예외의 전달 순서를 테스트한다.
- 실패 경로에서도 raw exception message, credentials, runtime tag를 로그나 assertion에
  남기지 않는다.
- rollback contract가 바뀌면 영·한 README와 Ktor runbook을 함께 갱신한다.
