# 리뷰 - Issue #278 Batch Checkpoint Class Allowlist

날짜: 2026-06-23
이슈: #278
모듈: `:bluetape4k-exposed-batch`

## 발견 사항

`CheckpointJson.read`는 저장된 `className` 값을 신뢰하고, payload를 Jackson에 넘기기 전에 `Class.forName`을 호출했습니다. JDBC와 R2DBC repository는 모두 이 경로로 DB checkpoint를 복원합니다.

## 원인

typed checkpoint envelope는 scalar type 복원을 위해 원래 runtime class를 보존했지만, restore 경로는 저장된 class name을 권위 있는 값으로 취급했습니다. 변조된 DB row가 classpath의 임의 class 복원을 시도하게 만들 수 있었습니다.

## 수정

checkpoint class registry를 도입했습니다. default registry는 일반적인 scalar/collection checkpoint type을 허용하고, custom checkpoint data class는 `CheckpointJson.jackson3(...)`에 명시적으로 전달해야 합니다. read는 등록된 class name만 resolve하고, write는 등록되지 않은 checkpoint object를 거부합니다.

## 검증

- unknown, disallowed, unexpected checkpoint class에 대한 JSON tamper test를 추가했습니다.
- `loadCheckpoint` 전에 persisted checkpoint row를 변조하는 JDBC/R2DBC repository tamper test를 추가했습니다.
- 구현 전 새 API가 RED compile failure를 만든다는 점을 확인했습니다.
- 수정 후 `:bluetape4k-exposed-batch:test`가 통과함을 확인했습니다.
