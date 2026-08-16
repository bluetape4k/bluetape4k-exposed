# 이슈 #642 JDBC FluentQuery 구현 계획 검토

## 대상

- 설계: `docs/superpowers/specs/2026-08-16-issue-642-jdbc-fluentquery-projection-design.md`
- 계획: `docs/superpowers/plans/2026-08-16-issue-642-jdbc-fluentquery-projection-plan.md`
- 검토 관점: architecture/sequencing, TDD readiness, acceptance traceability,
  workflow receipt 실행 가능성

## 검토 결과

초기 독립 검토는 `P0=0/P1=5`와 `P0=0/P1=2`로 계획 수정을 요구했다.
다음 항목을 계획에 반영했다.

- Task 간 RED 방치 제거와 ABI baseline PASS 분리
- Java/Kotlin consumer fixture, `javap`, checked-in public ABI baseline
- factory/direct collaborator와 transaction 생성 모드의 명시적 전달
- `TransactionStatus.isNewTransaction` 기반 cursor 허용 경계
- factory/direct projection 및 예외 parity matrix
- 단일 persistent-property resolver 책임
- module README 의미 키·링크·identifier parity validator
- class-level cursor/대표 DB test filter
- canonical workflow state root, owner lane, topology, required check 계약
- pre-complete `completion-check` 분기와 실패 시 `complete` 차단

수정본의 최종 독립 closure 검토 결과는 다음과 같다.

- Architecture/sequencing: `P0=0/P1=0` — PASS
- TDD readiness/acceptance traceability: `P0=0/P1=0` — PASS
- 파일 변경: 검토자는 없음
- heavy test: 계획 검토 단계이므로 실행하지 않음

## 결정

설계와 구현 계획 gate를 닫는다. 구현은 계획의 Task 0부터 시작하며, Task 1 ABI
baseline PASS 뒤 Task 2 RED를 관찰하기 전에는 production 코드를 변경하지 않는다.

## DoD Status

- [x] 설계 acceptance와 계획 task traceability 대조
- [x] TDD RED/GREEN dependency order 검증
- [x] API/ABI, transaction/cursor, factory/direct 경계 검증
- [x] workflow topology 및 completion command 실행 가능성 검증
- [x] 최종 독립 검토 `P0=0/P1=0`

상태: `DONE`
