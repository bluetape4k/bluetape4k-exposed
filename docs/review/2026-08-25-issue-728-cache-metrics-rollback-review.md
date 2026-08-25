# Issue #728 7-Tier code review

검토 대상은 `ExposedKtorCacheMetrics.kt`, `ExposedKtorCacheMetricsTest.kt`, Ktor
README 영·한 계약과 rollback diagnostic 설계다. Type C bug-fix 범위로 제한했으며,
기존 meter identity와 Ktor caller-owned lifecycle은 변경하지 않았다.

## Tier 1 — 성능

- 설치 시에만 전역 lock과 registry scan을 사용한다.
- rollback은 현재 시도에서 claim한 meter만 순회하므로 기존 registry 전체를 복사하거나
  request path를 변경하지 않는다.
- 진단 생성은 실패 경로에서만 수행하고, 정상 등록·요청 처리에는 allocation이나 logging을
  추가하지 않는다.
- 판정: **통과**, P0/P1/P2 없음.

## Tier 2 — 안정성·신뢰성

- `remove` 결과를 `removed`/`notFound`로 구분하고 RuntimeException을 개별 failure로
  보존한 뒤 다음 meter 제거를 계속한다.
- `residual` 수로 부분 오염을 명시하고, 완전 rollback 후 동일 설정 retry를 회귀 테스트로
  고정했다.
- `CancellationException`과 `Error`는 원본을 유지하고 diagnostic만 suppressed로 추가한다.
- 판정: **통과**, P0/P1/P2 없음.

## Tier 3 — 보안

- registry 원본 예외 메시지(`registry-secret`, `remove-secret`)와 cause를 public message나
  nested diagnostic에 복사하지 않는다.
- meter 이름과 `component`/`kind` tag만 구조화하며, 기존 component validation 계약을
  그대로 따른다.
- 기존 `ExposedKtorCacheHealthRoutesTest`의 production logger 금지 guard를 존중해 새
  logger나 `println`을 추가하지 않았다. 이 범위의 관찰 채널은 sanitized exception이다.
- 판정: **통과**, P0/P1/P2 없음.

## Tier 4 — 운영·진단

- 안정적인 `identity_collision`/`registration_failed` 메시지를 유지한다.
- suppressed `CacheMeterRollbackDiagnostic`의 다섯 카운터와 개별 failure를 통해 cleanup
  상태를 구조적으로 수집할 수 있다.
- README Runbook에 `residual > 0` 시 새 registry로 재설치하는 운영 절차를 기록했다.
- 판정: **통과**, P0/P1/P2 없음.

## Tier 5 — Kotlin·테스트 패턴

- mutable state는 rollback 로컬 카운터와 private ownership 목록에 한정하고, public
  contract는 immutable 값과 명시적 sealed-like enum 분류를 사용한다.
- `Throwable`/`Error` 경계를 명시하고, 원본 예외를 무분별하게 출력하지 않는다.
- 새 회귀 검증은 `bluetape4k-assertions`의 `assertFailsWith`, `shouldBeEqualTo`,
  `shouldBeTrue`를 사용한다. 기존 JUnit assertion은 범위 밖 테스트와 일관성을 위해
  그대로 둔다.
- 판정: **통과**, P0/P1/P2 없음.

## Tier 6 — 사용자·호출자 계약

- 기존 public-facing 설치 이유와 meter identity 계약을 보존한다.
- registry lifecycle과 route traffic 회수 책임은 계속 caller-owned다.
- residual 상태를 자동 복구하지 않고 명시적인 운영 action을 요구해 조용한 관측 왜곡을
  피한다.
- 판정: **통과**, P0/P1/P2 없음.

## Tier 7 — 통합·전달

- Ktor module targeted test 14개, module 전체 test, compile/Detekt, diff/문서 용어 및
  `println` negative guard를 실행한다.
- README 영·한 문단은 best-effort, suppressed diagnostic, residual/retry 의미가
  대응하도록 갱신했다.
- PR은 develop base와 issue #728을 연결하고 bug/test/tech-debt metadata를 미러링한다.
- 판정: **전달 전 통과**, hosted CI와 human review는 PR 생성 후 pending으로 남긴다.

## 결론

발견된 P0/P1/P2 이슈는 0건이다. 구현은 cleanup failure를 primary installation failure와
분리된 structured suppressed diagnostic으로 보존하고, security guard를 약화하지 않는다.
