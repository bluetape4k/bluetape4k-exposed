# Issue #728 Ktor cache metrics rollback diagnostic 설계

## 배경

`registerExposedKtorCacheMetrics`는 contributor별 gauge와 readiness timer를
설치하다가 실패하면 현재 시도에서 claim한 meter를 되돌린다. 기존 rollback은
`runCatching { registry.remove(meter) }`로 제거 결과와 예외를 모두 버려, 부분
설치가 남아도 호출자가 오염을 알 수 없었다.

## 결정

- rollback은 **best-effort**다. claim 순서의 역순으로 모든 meter의 제거를 시도하며,
  한 번의 `remove` 실패가 나머지 cleanup을 중단시키지 않는다.
- 제거 실패가 있으면 `CacheMeterRollbackDiagnostic`을 설치 실패 예외의 suppressed
  exception으로 붙인다. 진단에는 `attempted`, `removed`, `notFound`, `failed`,
  `residual` 개수가 포함된다.
- 개별 제거 실패는 meter 이름과 제한된 identity tag, 예외 타입만 보존한다. registry가
  제공한 원본 메시지와 cause는 secret 또는 내부 상태가 노출될 수 있으므로 보존하지 않는다.
- 설치 실패의 외부 메시지는 기존의 안정적인 `identity_collision` /
  `registration_failed` 분류를 유지한다. 내부 cause에는 분류와 원본 primary 예외의
  타입 이름만 담아 실패 원인을 연결하되, 원본 메시지는 노출하지 않는다.
- `CancellationException`과 `Error`는 원본을 다시 던지고 rollback diagnostic만 suppressed로
  추가한다. 정상적인 `Throwable` 경로에서는 sanitized installation exception을 primary로
  사용한다.
- `residual > 0`이면 registry가 일부 오염된 상태다. 호출자는 traffic을 회수하고 새
  registry로 재설치해야 한다. 모든 rollback이 성공하면 같은 설정의 재시도가 가능하다.

## 범위 밖

- 이 모듈에 새 logging dependency를 추가하지 않는다. 기존 Ktor health-route 보안
  검사가 production logger 출력을 금지하므로, 호출자가 구조화된 exception diagnostic을
  관찰할 수 있는 방식으로 문제를 보존한다.
- Micrometer meter identity, tag cardinality, readiness 상태 계약은 변경하지 않는다.
- 충돌한 이전 route의 meter를 자동으로 제거하거나 registry lifecycle을 소유하지 않는다.

## 검증 기준

1. remove 실패 한 건과 잔여 meter가 deterministic하게 관찰된다.
2. 모든 owned meter에 대해 best-effort 제거가 계속된다.
3. 완전 rollback 뒤 동일 설정 재설치가 성공한다.
4. raw registry secret/message가 public exception 또는 diagnostic에 나타나지 않는다.
5. Ktor metrics targeted test, module test, Detekt, diff/terminology 검사가 통과한다.
