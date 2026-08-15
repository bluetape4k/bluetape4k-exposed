# 이슈 #676 driver timeout CI 정책 교훈

날짜: 2026-08-15
이슈: #676
마일스톤: 1.13.0

## 상황

Issue #674와 PR #675에서 PostgreSQL, MySQL 8, MariaDB, CockroachDB 및 Toxiproxy를 사용하는
`driverTimeoutTest` 전용 Gradle task를 추가했지만, PR CI와 Nightly는 Ktor H2 `test`만 실행하고
있었습니다. 무거운 non-H2 Testcontainers 검증이 최종 상태 gate에 연결되지 않아 회귀를 놓칠 수
있는 상태였습니다.

기존 R2DBC cancellation 테스트는 query timeout과 request `finally` cleanup을 같은 5초 예산으로
묶고 있었습니다. hosted-like 로컬 실행에서 cleanup signal이 약 6.3초에 도착해 해당 테스트만
반복 실패했으므로, cleanup 대기를 10초로 분리했습니다.

## 결정

- PR에서 Ktor 변경이 감지되면 `Test / exposed-ktor (driver-timeout)`를 별도 job으로 실행합니다.
- Nightly는 Sunday full schedule과 `workflow_dispatch(scope=full)`에서만 heavy job을 실행하고,
  daily smoke는 빠른 H2 경로만 유지합니다.
- Gradle `--no-parallel --max-workers=1`, Testcontainers Ryuk 비활성화, bounded retry를 사용하고,
  전용 JUnit XML이 없으면 artifact upload도 실패하게 합니다.
- `coverage-report`, `CI Status`, `nightly-status`가 job 결과를 기다리도록 `needs`를 연결합니다.
- query statement timeout과 request cleanup latency는 같은 budget으로 취급하지 않습니다. 이 조정은
  테스트 harness에만 적용하며 production API와 driver capability 계약은 변경하지 않습니다.

## 검증 근거

- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`: PASS
- Ruby YAML/contract validator: 두 workflow의 task, 순차 옵션, Docker 환경, artifact failure policy,
  final status dependency, nightly full-only 조건 PASS
- `driverTimeoutTest`: 5개 통과, 1분 8초
- `:bluetape4k-exposed-ktor:test --rerun-tasks`: 60개 통과
- `:bluetape4k-exposed-ktor:detekt`: PASS
- `git diff --check`: PASS

## 다음 guard

1. heavy Testcontainers task는 daily smoke와 분리하고 최종 status gate에 직접 연결합니다.
2. artifact 경로와 `if-no-files-found: error`를 함께 검증해 실패한 matrix가 빈 결과로 숨겨지지
   않게 합니다.
3. exact-head PR CI와 다음 Sunday/manual full Nightly에서 hosted 결과 및 artifact를 확인한 뒤,
   그 exact head에 대해 별도 merge 승인을 받습니다.

## Writer gate

- `SPW-01`: PASS — issue, current workflow, test output, and scope are fixed.
- `SPW-02`: PASS — context, decision, outcome, verification, and future guard are recorded.
- `SPW-03`: PASS — Korean technical register and identifiers are preserved.
- `SPW-04`: PASS — local test, static, workflow, and diff evidence are cross-checked.
- `SPW-05`: PASS — Markdown read-back is complete.
