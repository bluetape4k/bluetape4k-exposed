# 이슈 #32 사양 검토

사양: `docs/superpowers/specs/2026-06-07-issue-32-cockroachdb-transaction-retry-design.md`

## 검토 결과

- P0 = 0
- P1 = 0
- 게이트: PASS

## 발견 사항

P0/P1 차단 요소를 찾지 못했습니다.

이 사양은 모듈을 헬퍼 전용으로 유지하고, #31 방언 경계를 보존하며, CockroachDB에 문서화된 재시도 가능한 트랜잭션 시그니처를 넘어 재시도 동작을 확장하지 않습니다. 또한 Exposed가 모든 `SQLException` 인스턴스를 재시도하므로 Exposed의 일반적인 `maxAttempts`를 유일한 헬퍼 경로로 사용하는 것을 명시적으로 배제합니다.

## DoD 점검

- 현재 이슈 증거 포함: 완료.
- 공식 CockroachDB 재시도 시그니처 포함: 완료.
- Exposed 재시도 경계 위험 포함: 완료.
- 공개 API 및 테스트 계약 포함: 완료.
- 범위 외 경계 포함: 완료.
