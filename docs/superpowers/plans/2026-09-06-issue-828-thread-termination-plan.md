# #828 persisted hook 종료 검증 계획

## 승인 범위

저장소 `bluetape4k/bluetape4k-exposed`, base `develop`,
head `fix/persisted-hook-close-test`에서 테스트 경쟁 조건 수정과 PR 생성을 진행한다.
기준 커밋은 `fe7724304a1ffd744fa2e6aa6863a6c775ce0402`다.
production 코드, 의존성, workflow, 머지 및 #815 발행은 변경·실행하지 않는다.

전체 H2에서 발견한 별도 close 테스트의 완료 신호 문제도 사용자의 `확대해` 승인에 따라 포함한다.
`JdbcCaffeineRepositoryExtraTest`에서 persisted hook 확인을 close보다 먼저 수행하고,
캐시 쓰기 차단·timeout·늦은 캐시 반영 차단 검증은 유지한다. 새 동기화 추상화는 추가하지 않는다.

## 순서와 완료 기준

1. 완료: CI run `34033943197`의 실제 실패 XML과 소스로 원인을 확인하고 #828을 등록한다.
2. 완료: 완료 latch를 제거하고 `join(1_000)` 뒤 `isAlive == false`로 실제 종료를 검증한다.
3. 완료: 확대 수정 후 대상 7개, 전체 H2 179개 성공·기존 제외 2개, detekt·diff 검사 통과. 확대 리뷰는 독립 실행 시간 초과 후 inline fallback PASS다.
4. 진행: 교훈·리뷰를 기록했다. 승인된 PR을 생성하고 exact-head CI를 확인한다.
5. 대기: 머지 준비 결과를 보고한다. 새 머지 승인 전에는 머지·정리·발행하지 않는다.

기존 1초 제한, hook 해제 전 worker 상태 검사, finally의 hook 해제·interrupt·5초 join을 유지한다.
실패하면 원인을 재분석하며 제한 시간 증가나 CI 재시도로 덮지 않는다.
