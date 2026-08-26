# 이슈 #723 Ktor assertion·null-safety 7-Tier 리뷰

## 범위와 기준

- 이슈: [#723](https://github.com/bluetape4k/bluetape4k-exposed/issues/723)
- 기준 base: `origin/develop` `1242e5eb990a1f362233dba9542aa6e4d7192730`
- 구현 branch: `refactor/ktor-assertions`
- workflow run: `20260825T084827Z-a9539226` (Type B)
- 대상 모듈: `ktor/exposed`
- 변경 범위: Ktor readiness/cache/ABI/readme parity 테스트 6개 파일
- 범위 밖: production API, dependency declaration, README/manual, workflow YAML, CI 등록

이번 작업은 이미 선언된 `bluetape4k-assertions`를 실제 테스트 전반에서 사용하도록
raw JUnit assertion을 intent-specific matcher와 `assertFailsWith`로 바꾸고, nullable
Micrometer 반환값을 `shouldNotBeNull()`로 정리하는 module-local refactoring이다.

## 승인된 접근과 수용 기준

1. `assertEquals`는 `shouldBeEqualTo`, boolean 검증은 `shouldBeTrue`/`shouldBeFalse`,
   null/identity 검증은 `shouldBeNull`/`shouldNotBeNull`/`shouldBeSameInstanceAs`로
   의도를 보존한다.
2. `assertThrows`는 `assertFailsWith`로 전환하며 exception type/message와 sensitive-data
   redaction 검증은 유지한다.
3. production source에는 새 `!!`를 만들지 않고, touched Ktor test source의 `!!`도
   제거한다.
4. `:bluetape4k-exposed-ktor:compileTestKotlin`, targeted Ktor tests, affected module
   test, detekt와 forbidden raw assertion/`!!` scan을 fresh PASS한다.

## 7-Tier 판정

| Tier | 검토 초점 | 계획 판정 | 최종 증적 |
| --- | --- | --- | --- |
| 1. 요구사항·범위·추적성 | #723의 6개 테스트와 module-local 경계가 일치하는가 | PASS | live issue와 changed-paths가 6개 테스트 + review/lesson으로 일치 |
| 2. API/ABI·Kotlin 사용성 | 기존 matcher가 consumer-facing test DSL의 의도를 보존하는가 | PASS | `compileTestKotlin` RC=0, intent-specific matcher import scan |
| 3. 동작·diagnostics | readiness/cache 결과·예외 타입·메시지·redaction이 유지되는가 | PASS | targeted 42개 테스트 RC=0, full module test에서 failure 0 |
| 4. Null-safety·lifecycle | Micrometer nullable API에 unsafe `!!`가 남지 않는가 | PASS | Ktor test source `!!` scan 0건, `shouldNotBeNull` smart-cast 적용 |
| 5. Security·side effect | secret redaction과 logging 금지 계약이 유지되는가 | PASS | health/redaction 테스트 통과, `println`·`System.out/err` scan 0건 |
| 6. Test strength·concurrency | timeout/cancellation/metrics race 케이스의 proof가 약화되지 않는가 | PASS | full module 59 tests, failures/errors/skipped=0, Detekt RC=0 |
| 7. Docs·operator·delivery | review/lesson, PR metadata와 CI 대기 상태가 추적되는가 | PENDING | PR 생성 후 exact-head CI/review 확인 |

P0/P1: **0건**. P2/P3: **0건** (최종 diff 및 fresh 검증 기준).

## 구현 전 독립 렌즈 결론

- raw JUnit assertion은 동작을 바꾸지 않는 표현 계층 문제이므로 별도 abstraction을
  만들지 않는다.
- `assertNotNull` 뒤의 `!!`는 matcher 반환값을 변수에 보관하거나 바로 체이닝해 제거한다.
- `Assumptions.assumeTrue`와 JUnit lifecycle annotation은 assertion API가 아니므로
  유지하되, `org.junit.jupiter.api.Assertions` import는 0건으로 만든다.
- Ktor `testApplication`, executor, latch, timeout과 redaction 테스트의 resource
  cleanup은 건드리지 않는다.

## 전달 상태

- 구현/검증: PASS (2026-08-25 fresh run)
- commit/push/PR: pending
- merge: 수행하지 않음 (별도 승인)
