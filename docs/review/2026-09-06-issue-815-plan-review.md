# #815 구현 계획 리뷰

## 판정과 범위

- 대상: `docs/superpowers/plans/2026-09-06-issue-815-test-support-contracts-plan.md`.
- 근거: 사용자가 승인한 명세 `b54ea75b9ea87f53ac822d46e2c09fb24a0732f1`.
- 방식: main session의 inline fallback. 독립 critic 시작 시도는 `collab spawn failed: agent thread limit reached`로 실패했다. 독립 검토나 특정 외부 모델의 판정으로 주장하지 않는다.
- 계획의 AC 추적·순서·위험 검토: 완료. 아래 수정 후 P0/P1 미해결 0건. 구현·테스트 성공 판정이 아니다.

## 발견 사항과 수정

| 우선순위 | 영역 | 발견 사항 | 계획 반영 |
|---|---|---|---|
| P1 | 호환성 | 같은 artifact/version의 두 JAR가 cache에서 혼용되면 linkage 검증이 거짓 통과할 수 있다. | 작업 1·위험 표에 별도 cache 또는 resolution checksum 검증, 실제 JAR SHA와 기준 class 불변 검사를 추가했다. |
| P1 | 검증 재실행 | 기존 출력 디렉터리 덮어쓰기 금지와 최종 소비자 재검증 절차가 충돌했다. | baseline manifest와 class는 재사용하고 candidate만 갱신한다. ref 불일치/manifest 부재는 실패 처리한다. |
| P2 | 빌드 준비 | archive에는 `.git`이 없어 기준 빌드가 실패할 수 있다. | git 의존성 사전 확인과 임시 local clone 대안을 명시했다. |
| P2 | 위험 추적 | 작업별 복잡도와 복구 지점이 분산되어 있었다. | 선행 관계·실패 신호·재검증 표를 추가했다. |

## 관점별 검토

| 관점 | 확인 결과 | 구현에서 남은 검증 |
|---|---|---|
| 성능 | fixture당 permit·cache만 유지하고 custom-key 전역 registry를 만들지 않는다. | FIFO, 다른 fixture 독립성, 종료 토큰 보존 수명 |
| 안정성 | acquire 반환 이전에 finally 책임을 정하고 초기화와 hook 등록을 한 단위로 묶었다. | 결정적 취소·hook 실패·temporary unregister 테스트 |
| 보안 | 외부 pool 소유권 유지, sentinel 로그 검증, 요청 DDL 대상 제한을 명시했다. | 실제 appender 수집과 unrelated table 보존 |
| 운영 | DB 순차 실행, scoped publication, skipped/old SHA 구분, dispatch 금지를 포함한다. | 최종 JUnit/CI 증거, 실제 발행은 범위 밖 |
| API | enum·파일 facade·기본 인자 bridge 보존과 외부 package 소비자를 포함한다. | 이전 compiled class와 신규 JAR linkage |
| 호출자 | custom key·동일 fixture 공유·seed/FK 책임·최초 configure 변경을 문서화한다. | 두 locale와 KDoc, 실제 downstream 이전은 범위 밖 |

## 작성 품질과 한계

- SPW-01–SPW-05: 대상·용어·결정 근거·추적 가능한 검증·한국어 자연스러움을 계획과 이 리뷰에 적용했다.
- 예제 코드는 핵심 경계와 회귀 패턴을 설명한다. 전체 patch를 대신하지 않는다. 실제 코드 단계는 TDD 중 컴파일 가능한 작은 단위로 작성·검증한다.
- native lane 실패는 해결 이력으로 보존하고 inline 결과에 연결한다. 모델 가용성 문제 때문에 기술 검증이나 머지 승인 경계를 생략하지 않는다.

## DoD

- [x] 여섯 관점과 명세 AC-01–AC-09 추적 검토
- [x] P1 두 건과 P2 두 건 계획에 반영
- [x] 독립 실행 실패와 inline provenance 구분
- [ ] 작성 계획 사용자 승인
- [ ] 실제 구현·테스트·ABI/POM 검증
- [ ] PR 생성·exact-head CI·별도 머지 승인
