# #875 JDBC V2 timeout·cancellation·cleanup 7-Tier 코드 리뷰

## 검토 provenance와 범위

- 대상 branch: `feat/issue-875-timeout-cleanup`
- 기준 ref: `origin/develop` `5f6f2e7a2aa03a10512b8dcd85414673ca462166`
- 변경 범위: `ClickHouseQueryObservation.kt`,
  `ClickHouseQueryLifecycleTest.kt`, ClickHouse module EN/KO README,
  verification/lesson 및 계획·설계 review 문서
- production Kotlin/public API/dependency/workflow 변경: 없음
- 독립 code-review lane을 사용할 수 없어 주 세션에서 수행한 **비독립 inline
  fallback**이다. 1인 개발자 human-review lane은 N/A로 기록한다.
- 관련 이슈: [#875](https://github.com/bluetape4k/bluetape4k-exposed/issues/875)

## Tier별 판정

| Tier | 확인 내용 | 근거 | 판정 |
|---|---|---|---|
| 1 성능 | bounded polling과 Testcontainers 비용, benchmark 경계 | observer deadline은 최대 5초이며 반복은 3회다. throughput/chart 주장은 하지 않았다. | PASS |
| 2 안정성 | cancellation/close, primary/suppressed, pool reuse | 기존 lifecycle의 `NonCancellable` join·resource counter·후속 query와 cancel receipt를 확인했다. | PASS |
| 3 보안 | query id binding과 receipt redaction | query id는 parameter binding하고 SQL·URL·credential·raw exception·id 값을 저장하지 않는다. | PASS |
| 4 운영 | selector·driver/server/image/digest·재현성 | V2 selector, pinned `0.9.9`/`26.7.3.19`, image digest, Docker context, XML 경로와 N/A rerun 절차를 기록했다. | PASS |
| 5 개발/API | Kotlin 계약·Exposed ownership·공개 변경 | helper는 test-only internal이고 public `queryFlow`, transaction ownership, dependency를 유지한다. | PASS |
| 6 사용자 | EN/KO timeout·cancel·remote wording | 두 README가 request acceptance, local cleanup, remote termination N/A와 caller timeout 책임을 같은 수치로 설명한다. | PASS |
| 7 통합 | issue/DoD/lesson/PR traceability | verification·lesson·plan·spec가 #875에 trace되고 PR body는 `Closes #875`를 사용한다. | PASS |

## 테스트와 정적 검증 증거

| 검사 | 결과 |
|---|---|
| `ClickHouseQueryLifecycleTest` | `tests=31`, `failures=0`, `errors=0`, `skipped=0`, `time=50.84s` |
| `ClickHouseExtensionsTest` | `tests=10`, `failures=0`, `errors=0`, `skipped=0`, `time=6.633s` |
| module detekt/compile | `BUILD SUCCESSFUL` |
| Korean terminology audit / `git diff --check` | PASS |
| benchmark/chart / Full Nightly | 실행하지 않음, N/A |

## 결론과 잔여 위험

- P0: 0
- P1: 0
- P2: 0
- P3: 0

V2 socket timeout의 결정적 실패와 remote `KILL QUERY` 완료는 입증되지 않았으므로
README와 receipt에서 N/A로 유지한다. observer가 첫 query presence를 보지 못한
것은 종료 성공 증거가 아니다. hosted CI는 명시적 V2/Testcontainers selector를
기본 경로와 분리해 확인해야 한다.

**Step 6-R verdict: PASS (P0=0, P1=0).** PR 생성 직전에 exact head, issue metadata,
`Closes #875`, checks와 working-tree clean 상태를 다시 읽는다.
