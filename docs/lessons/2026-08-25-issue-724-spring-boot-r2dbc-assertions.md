# Issue #724 `spring-boot/r2dbc` assertion 표준화 lesson

## Context

`spring-boot/r2dbc` 테스트 10개가 `kotlin.test` assertion과 직접 의존하지 않는
전이 dependency에 기대고 있었다. 목표는 테스트 source set이
`bluetape4k-assertions`를 직접 사용하고, 새 legacy assertion 유입을 모듈 검증에서
즉시 발견하도록 만드는 것이었다.

직접 dependency 추가는 단순한 assertion 치환보다 넓은 build 계약이므로 Type B가
아닌 Type A로 재분류했다. 설계·계획·6개 관점 검토와 main 통합을 먼저 완료하고
구현했다. receipt의 native performance/stability lane startup 지연은 liveness
절차로 기록했으며, replacement와 main-session fallback evidence를 성공으로
추정하지 않고 별도로 남겼다.

## Decisions

1. `testImplementation(bt4k.bluetape4k.assertions)`만
   `spring-boot/r2dbc`에 직접 선언하고 catalog·convention·production dependency는
   변경하지 않는다.
2. `checkSpringBootR2dbcAssertionStyle`를 모듈 `check`에 연결한다. 검사는
   `src/test/kotlin`의 Kotlin 파일을 정렬된 순서로 읽고 legacy import, wildcard,
   fully-qualified/unqualified assertion 호출을 fail-closed로 차단한다.
3. guard의 진단은 위치와 rule만 `logger.error`로 출력하고 통과 메시지는
   `logger.lifecycle`로 출력한다. `println`, `System.out`, `System.err`, source
   값은 출력하지 않는다. report는
   `build/reports/spring-boot-r2dbc/assertion-style.txt`에 deterministic하게 쓴다.
4. 값 동등성은 `shouldBeEqualTo`, Boolean은 `shouldBeTrue`/`shouldBeFalse`, null은
   `shouldBeNull`/`shouldNotBeNull`, 참조 의미는 same-instance matcher로
   변환한다. ByteArray/ByteBuffer는 내용 비교를 유지하고 예외는 기존
   `assertFailsWith`를 사용한다.

## Outcome

- 대상 10개에서 legacy import/call 0건, `!!` 0건, `println`·`System.out/err` 0건.
- guard가 Kotlin test source 22개를 검사해 통과했고 configuration cache 저장·재사용을
  확인했다.
- `compileTestKotlin` PASS.
- targeted 22개, Multi-DB 3개, 전체 315개 테스트가 실패 0·오류 0으로 통과했다.
  전체 suite의 skipped 8개는 기존 모듈 범위이며 실패나 오류로 숨기지 않았다.
- module detekt PASS, `git diff --check` PASS.
- source root 누락·empty inventory·read/scan/write 오류 계약은 Gradle task 구현에
  fail-closed로 반영했고, baseline RED와 synthetic probe 입력은 저장소 source에
  남기지 않았다.

## Evidence and follow-up

설계·계획·review와 이 lesson은 한국어 writer gate를 통과했다
(`audit-korean-terms.mjs`, `findings=0`). 다음 단계는 Lore commit, exact push,
Korean PR 생성과 hosted checks/review 확인이다. PR 생성 후 merge/auto-merge는
수행하지 않고, 다음 순서의 Issue #725로 이동한다.

남은 위험은 hosted CI와 GitHub review가 아직 완료되지 않았다는 점이다. 로컬
verification은 이 외부 gate를 대신하지 않는다.
