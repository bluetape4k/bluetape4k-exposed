# Issue 318 Exposed Modulith 관측성 교훈

## 배경

Issue #318에서는 Exposed 기반 Spring Modulith 이벤트 발행 저장소에 선택적으로 적용할 수 있는 관측성을 요구했다.

## 결정

Spring Modulith 자체의 `module.events.published` 메트릭 계열을 사용자 정의하거나 이름을 바꾸는 대신, `bluetape4k.exposed.modulith.publications` 아래에 영속 저장소 상태 게이지를 노출하는 선택적 Micrometer 자동 구성을 추가한다.

## 결과

- Micrometer 또는 `MeterRegistry`가 없는 애플리케이션은 영향을 받지 않는다.
- Micrometer를 사용하는 애플리케이션은 미완료, 완료, 실패, 로드 불가 발행 건수를 확인할 수 있다.
- 이제 README와 한글 README에 활성화 조건, 미터 태그, 태그 카디널리티 제약 조건이 문서화되어 있다.
- 새 동작은 새로운 아키텍처나 이벤트 시퀀스가 아니라 운영 메트릭 계약이므로 다이어그램을 추가하지 않았다. 간결한 미터/태그 섹션이 더 명확하고 유지보수 비용도 낮다.

## 검증

- 자동 구성 집중 테스트: 테스트 7개 통과.
- 전체 `:bluetape4k-exposed-spring-modulith:test`: 테스트 61개 통과.
- `git diff --check`: PASS.

## 향후 주의 사항

Modulith 메트릭을 더 추가할 경우 Spring Modulith 이벤트 방출 메트릭과 Exposed 영속 저장소 상태 메트릭을 분리한다. 저장소의 실제 상태에는 낮은 카디널리티의 게이지를 우선 사용하고 새 태그를 모두 문서화한다.
