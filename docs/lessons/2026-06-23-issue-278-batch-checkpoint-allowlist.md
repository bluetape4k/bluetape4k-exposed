# 교훈 - Batch Checkpoint 클래스 allowlist (2026-06-23)

Issue: #278
Module: `:bluetape4k-exposed-batch`

## L1: 영속 type 메타데이터는 데이터이지 권한이 아닙니다

### 문제

checkpoint envelope는 Jackson 3가 scalar type을 정확하게 복원하도록 `className`을 저장했습니다. 이는 Long/Int round-trip 정확성을 해결했지만, 영속 행이 복원 클래스를 선택할 수 있게 만들었습니다.

### 교훈

저장소 경계를 넘는 typed envelope에는 registry가 필요합니다. 영속 type 이름은 알려진 클래스에서만 해석하고, 애플리케이션 전용 checkpoint state는 명시적으로 등록하게 해야 합니다. 영속 신뢰 경계는 repository 수준 복원에서 넘어가므로 회귀 테스트는 저장된 JSON을 직접 변경해야 합니다.
