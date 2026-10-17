# PR #914 병합 전략 편차를 보상할 때 확인할 점

## 배경

작업공간의 `AGENTS.md`는 사용자가 다른 방식을 명시하지 않는 한 GitHub의 rebase 병합을 기본으로 정한다. PR #914는 별도 전략 요청 없이 2026-10-16에 squash 방식으로 병합되었고, 기존 DoD도 CG-17을 `FAIL`로 기록했다. squash 커밋 `9240cda1b0c7df127eccfab80e532d0115f1aa7c`의 트리는 PR head `d59946ea92d9effaf21bba83e4c3d90c2a715132`와 같지만, 트리 동일성은 병합 전략 준수를 증명하지 않는다.

[이슈 #913](https://github.com/bluetape4k/bluetape4k-exposed/issues/913)의 직렬 write-behind CI 행렬은 [실행 38788902610](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/38788902610)에서 R2DBC Caffeine H2 결과를 `121 tests, 1 failed, 2 skipped`로 기록했다. 전용 H2 job은 통과했지만, 같은 테스트는 로컬에서도 조회 시점 전에 두 항목이 만료되어 `Expected <0> to equal to <1>`로 실패했다. PR #914는 용량과 만료를 분리하고 내부 `Ticker` seam으로 시간을 제어해 이 문제를 해결했다. 선택된 보상 절차는 그 deterministic test 변경을 잠시 원복한다.

## 결정

이미 끝난 병합의 방식을 바꿀 수 없으므로 원격 `develop`의 이력을 다시 쓰지 않는다. 두 PR을 순서대로 사용한다. 첫 PR은 squash 커밋의 변경을 되돌려 rebase 방식으로 병합한다. 그 다음 새 `develop`에서 원래 변경을 새 커밋으로 다시 적용하고 두 번째 PR을 rebase 방식으로 병합한다. 각 PR은 정확한 head의 CI와 리뷰 상태를 다시 확인하고, 별도의 fresh merge approval을 받은 뒤 병합한다. 자동 병합을 켜지 않는다.

## 위험과 결과

첫 PR이 병합된 뒤 두 번째 PR이 병합될 때까지 `expireAfterWrite = 2ms`에 의존하는 기존 테스트가 다시 활성화된다. CI 스케줄링에 따라 같은 간헐 실패가 재발할 수 있으므로 첫 PR의 정확한 head CI가 실패하면 병합을 멈추고 원인을 확인한다. 두 PR이 모두 rebase 방식으로 병합되어도 PR #914의 과거 squash 이력과 해당 PR의 CG-17 `FAIL` 기록은 그대로 남는다. 보상 절차는 과거 사실을 소급 변경하지 않는다.

일반 [CI workflow](../../.github/workflows/ci.yml)의 `test-r2dbc-caffeine` job은 최대 다섯 번 재시도할 수 있으므로 집계된 최종 녹색만으로 통과 처리하지 않는다. PR1의 정확한 head SHA에서 [직렬 write-behind 검증기](../../scripts/verification/run_write_behind_matrix.py)로 R2DBC Caffeine H2 검증을 단 한 번 실행해 동일 SHA의 `PASS` receipt를 확보한다. 일반 job의 어느 시도든 실패했거나 receipt의 SHA가 다르면 최종 상태가 녹색이어도 멈추고 원인을 확인한다.

## 검증 기준

- 첫 PR 이후 세 원복 대상 파일은 PR #914의 부모 트리와 같아야 한다.
- 두 번째 PR 이후 세 대상 파일은 squash 병합 결과 `9240cda1b0c7df127eccfab80e532d0115f1aa7c`와 같아야 한다.
- 각 PR은 병합 직전 정확한 head, CI, 리뷰와 대화를 다시 읽고 별도 승인을 받아야 한다.
- 첫 PR 병합 직후 CG-17A에 따라 canonical integration checkout의 `develop`을 동기화한다. 그 동기화된 `develop`에서 두 번째 PR을 준비한다.
- PR 작업 트리와 브랜치 정리(CG-17B), 최종 GNO 색인(CG-18)은 두 번째 병합 뒤에 수행한다.

## 다음 변경에서 확인할 점

`gh pr merge`를 실행하기 전에 현재 작업공간의 병합 전략 규칙과 PR의 정확한 head를 다시 읽는다. 기본 전략을 사용할 수 없으면 다른 방식으로 조용히 전환하지 말고 멈춘다. 각 rebase 병합 직후 CG-17A 동기화를 하고, 마지막 PR 병합 뒤 CG-17B 정리와 CG-18 GNO 색인을 수행한다.
