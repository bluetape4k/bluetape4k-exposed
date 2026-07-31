# Issue 320 DDD 계약 리뷰

## 범위

- `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd/*`
- `exposed/core/src/test/kotlin/io/bluetape4k/exposed/core/ddd/AbstractAggregateRootTest.kt`
- `README.md`
- `README.ko.md`
- `docs/superpowers/specs/2026-07-09-issue-320-ddd-contracts-design.md`
- `docs/superpowers/plans/2026-07-09-issue-320-ddd-contracts-plan.md`

## 리뷰 관점

| 관점 | 결과 | 참고 |
|---|---:|---|
| 성능/할당 | P0/P1 = 0 | nullable 이벤트 버퍼, 빈 상태의 빠른 경로, 비어 있지 않은 스냅숏에만 적용하는 방어적 복사를 사용한다. |
| 안정성/트랜잭션 의미 | P0/P1 = 0 | 스냅숏/순서/불일치 테스트가 통과했고, 기존 저장소에는 영향이 없다. |
| 보안 | P0/P1 = 0 | 이벤트 페이로드 지침은 비밀정보/PII를 제외하며, 불일치 예외는 ID를 그대로 노출하지 않는다. |
| 운영자/Ops | 수정 후 P0/P1 = 0 | 프로세스 로컬 인계 표현을 영속 소유권과 콜백 기반 드레인으로 교체했다. |
| 개발자/API | P0/P1 = 0 | 패키지, KDoc, 테스트는 적절하며 리플렉션 기반 비공개 버퍼 테스트를 제거했다. |
| 사용자/호출자 | 수정 후 P0/P1 = 0 | `drainDomainEvents(handoff)`는 인계에 성공한 뒤에만 버퍼를 비우고, 실패하면 이벤트를 유지한다. |

## 리뷰에서 반영한 수정

- 콜백이 성공적으로 반환된 뒤에만 애그리거트 버퍼를 비우도록 드레인 API가 인계 콜백을 필수로 받게 변경했다.
- 인계 예외가 발생했을 때 이벤트를 유지하는 테스트를 추가했다.
- 테스트에서 리플렉션과 `emptyList()` 동일성 단언을 제거하고 공개 동작을 검증하도록 바꿨다.
- 모호한 인계 표현을 아웃박스, 영속 재시도 큐, 트랜잭션으로 기록한 인계와 같은 영속 소유권 표현으로 교체했다.
- README와 KDoc 코드 조각에 import와 문맥을 추가했다.
- 새 DDD 섹션의 한국어 README 표현을 다듬었다.

## 검증 근거

- RED: 프로덕션 타입이 존재하기 전 `:bluetape4k-exposed-core:compileTestKotlin`이 해석되지 않은 `DomainEvent`/`domainEvents`/`drainDomainEvents` 참조로 실패했다.
- 집중 GREEN: `repo-test-summary -- ./gradlew :bluetape4k-exposed-core:test --tests 'io.bluetape4k.exposed.core.ddd.AbstractAggregateRootTest' --no-configuration-cache --no-build-cache --no-parallel --console=plain` -> `BUILD SUCCESSFUL`, 테스트 9개.
- 영향받는 전체 모듈: `repo-test-summary -- ./gradlew :bluetape4k-exposed-core:test --no-configuration-cache --no-build-cache --no-parallel --console=plain` -> `BUILD SUCCESSFUL`, 테스트 286개, 13개 건너뜀.
- 정적 검사: `git diff --check`가 통과했다. 새 `ddd` 패키지에 대한 프레임워크 import 부정 grep과 변경된 소스 및 문서에 대한 오래된 인계/API 표현 grep도 통과했다.

## 게이트

Step 6-R PASS. 최종 수렴 결과: P0 = 0, P1 = 0.
