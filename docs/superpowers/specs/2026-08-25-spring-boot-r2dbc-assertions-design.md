# `spring-boot/r2dbc` 테스트 assertion 표준화 설계

## 문제

Issue #724의 대상인 `spring-boot/r2dbc` 테스트 10개는
`kotlin.test.assert*`를 직접 사용하거나 bluetape4k assertion과 섞어 쓴다.
모듈의 `testImplementation`에는 `bt4k.bluetape4k.junit5`만 직접 선언되어
있으므로, 테스트가 의도한 bluetape4k assertion 계약을 Gradle 모듈 경계에서
보장하지 못한다. 테스트 코드는 `bluetape4k-assertions`를 직접 사용하고,
새 legacy assertion import가 다시 들어오면 모듈 검사에서 즉시 실패해야 한다.

## 현재 근거

- GitHub Issue #724는 저장소 `bluetape4k/bluetape4k-exposed`의
  `spring-boot/r2dbc` 테스트 10개, 직접 test dependency, nullable 처리,
  compile·targeted multi-DB 테스트·7-Tier 검토를 완료 조건으로 고정한다.
- `spring-boot/r2dbc/build.gradle.kts:31-33`에는
  `libs.exposed.migration.r2dbc`, `bt4k.flyway.core`,
  `bt4k.bluetape4k.junit5`만 test dependency로 선언되어 있다.
- 대상 import는 다음 10개 파일에 남아 있다.
  `R2dbcFluentQueryIntegrationTest.kt`, `R2dbcFluentQueryMultiDbTest.kt`,
  `ExposedR2dbcRepositoryAbiCompatibilityTest.kt`,
  `R2dbcBindValueSnapshotterTest.kt`, `R2dbcDiagnosticSanitizerTest.kt`,
  `R2dbcExamplePredicateCompilerTest.kt`,
  `R2dbcFluentQueryDirectConstructionTest.kt`,
  `R2dbcFluentQueryPlanTest.kt`, `R2dbcPersistentPropertyResolverTest.kt`,
  `R2dbcTransactionLeaseTest.kt`.
- 같은 저장소의 `ktor/exposed/build.gradle.kts`와 `spring-boot/jdbc` 테스트는
  `testImplementation(bt4k.bluetape4k.assertions)`를 직접 선언하고
  `shouldBeEqualTo`, `shouldBeTrue`, `shouldBeFalse`, `shouldNotBeNull`,
  `assertFailsWith`를 사용한다.
- `bluetape4k-assertions`의 현재 API는 값 비교용 `shouldBeEqualTo`, Boolean용
  `shouldBeTrue`/`shouldBeFalse`, null-safety용 `shouldNotBeNull`/`shouldBeNull`,
  참조 비교용 `shouldBeSameInstanceAs`/`shouldNotBeSameInstanceAs`, 배열 내용
  비교용 `shouldBeEqualTo`, 예외 검증용 `assertFailsWith`를 제공한다.

## 목표와 경계

### 포함

1. `spring-boot/r2dbc`의 test source set에
   `testImplementation(bt4k.bluetape4k.assertions)`를 직접 추가한다.
2. 대상 10개 테스트의 `kotlin.test.assert*`와 JUnit assertion 호출을
   bluetape4k matcher 또는 `io.bluetape4k.assertions.assertFailsWith`로
   이관한다.
3. 기존 ABI 테스트의 nullable resource 조회 `!!`를
   `shouldNotBeNull()`로 바꾼다. 대상 파일에서 `!!`가 늘지 않도록 하고,
   이번에 만지는 기존 `!!`도 제거한다.
4. 모듈 `check`에 assertion guard를 연결한다. guard는
   `src/test/kotlin/**/*.kt`에서 `kotlin.test.assert*`와
   `org.junit.jupiter.api.Assertions`의 명시적·wildcard import뿐 아니라
   fully-qualified legacy assertion 호출도 찾으면 logger로 위치를 보고하고
   실패한다. source root가 없거나 읽지 못하거나 scan 중 예외가 발생하면
   빈 결과로 통과시키지 않고 `GradleException`으로 fail-closed 한다.
   `kotlin.test.Test`, `org.junit.jupiter.api.Test`,
   `io.bluetape4k.assertions.*`는 허용한다.

### 제외

- production Kotlin 코드, Spring Boot auto-configuration, Exposed SQL,
  coroutine dispatcher, `runSuspendIO`, `runBlocking`, transaction/connection
  lease 수명주기를 변경하지 않는다.
- 모듈 밖의 테스트, 공통 convention plugin, dependency catalog 구조,
  README/API 문서, CI workflow, 새로운 assertion API는 변경하지 않는다.
- PR을 merge하거나 자동 merge를 활성화하지 않는다.

## 대안 비교

### 대안 A — 모듈 직접 dependency와 모듈 guard (선택)

`bt4k.bluetape4k.assertions`를 해당 test source set에 직접 선언하고,
`check`에 작은 source-scan task를 연결한다. 테스트는 실제 사용 API를
컴파일 시점에 확인하고, 새 legacy import는 같은 모듈의 검증 단계에서
차단한다. 변경 범위는 `spring-boot/r2dbc`와 대상 테스트로 한정된다.

### 대안 B — `bt4k.bluetape4k.junit5`의 전이 dependency에 의존 (거부)

현재 또는 향후 JUnit5 artifact의 전이 그래프가 assertion artifact를
포함한다는 보장이 없다. 모듈의 직접 계약이 Gradle metadata에 드러나지
않고, dependency 제거·전이 변경이 테스트 컴파일을 조용히 깨뜨릴 수 있다.

### 대안 C — 저장소 공통 convention에 전역 assertion dependency/guard 추가 (거부)

모든 모듈에 새 dependency와 정책을 강제하면 이번 issue의 10개 파일보다
넓은 변경과 unrelated migration을 만든다. 공통 정책이 필요한 경우 별도
이슈에서 모듈별 기존 assertion dialect와 예외를 조사한 뒤 추진한다.

## Assertion 이관 계약

| 기존 의미 | bluetape4k-assertions 표현 | 적용 규칙 |
| --- | --- | --- |
| 구조적 동등성 | `actual shouldBeEqualTo expected` | 인자 순서를 receiver/expected로 뒤집고 중위 표기법을 사용한다. |
| Boolean true/false | `actual.shouldBeTrue()` / `actual.shouldBeFalse()` | `assertEquals(true, value)`도 Boolean 전용 matcher로 바꾼다. |
| non-null | `value.shouldNotBeNull()` | 반환값을 사용해 smart-cast하고 `!!`를 추가하지 않는다. |
| null | `value.shouldBeNull()` | `assertEquals(null, value)`의 의미를 보존한다. |
| 참조 동일성 | `actual shouldBeSameInstanceAs expected` / `actual shouldNotBeSameInstanceAs expected` | `assertSame`/`assertNotSame`에 구조적 비교를 사용하지 않는다. |
| 배열 내용 | `actual shouldBeEqualTo expected` | primitive array overload의 `contentEquals` 의미를 사용한다. |
| 예외 타입 | `assertFailsWith<T> { ... }` | 이미 bluetape4k import인 호출은 유지하고 `kotlin.test`/JUnit 변형만 제거한다. |

`R2dbcTransactionLeaseTest`의 cancellation assertion은
`CancellationException` 인스턴스 동일성을 보존해야 한다. `R2dbcBindValueSnapshotterTest`
의 ByteArray는 참조가 아니라 내용 비교를 유지해야 한다. 두 사례는 대체
matcher 선택을 임의의 `shouldBeEqualTo`로 단순화하지 않는 경계 사례다.

## 실패 모드와 방어

| 실패 모드 | 조기 신호 | 방어와 검증 |
| --- | --- | --- |
| 직접 dependency가 빠져 compile classpath가 전이 graph에 의존함 | `compileTestKotlin`에서 `io.bluetape4k.assertions` unresolved | build script의 직접 `testImplementation`과 compile gate를 함께 검증한다. |
| identity/content 의미가 바뀜 | lease 예외 인스턴스 비교 또는 ByteArray 비교가 컴파일은 되지만 실패 | `shouldBeSameInstanceAs`, `shouldNotBeSameInstanceAs`, primitive-array `shouldBeEqualTo`를 사용하고 해당 테스트를 targeted 실행한다. |
| nullable smart-cast가 사라짐 | ABI resource 또는 compiler 결과가 nullable로 남거나 새 `!!` 발생 | `shouldNotBeNull()` 반환값을 사용하고 대상 파일의 `!!` scan을 0으로 고정한다. |
| coroutine·transaction 경계가 assertion 정리 중 변형됨 | `runSuspendIO`, `runBlocking`, `withTables`, parameterized dialect test 변화 | assertion/import/build 파일만 diff로 확인하고 H2 및 multi-DB 테스트를 순차 실행한다. |
| legacy assertion이 다시 유입됨 | 새 `kotlin.test.assert*` 또는 JUnit Assertions import | `checkSpringBootR2dbcAssertionStyle`를 `check`에 연결하고 guard 자체를 PASS시킨다. |
| import 없이 fully-qualified legacy 호출로 우회함 | `kotlin.test.assertEquals(...)`, `org.junit.jupiter.api.Assertions.assertEquals(...)`, wildcard import가 scan에 걸리지 않음 | import·호출·wildcard 규칙을 같은 guard에서 검사하고 synthetic RED probe로 우회 경로를 고정한다. |
| source scan이 예외를 삼키거나 root 누락을 빈 결과로 처리함 | source root 없음·읽기 실패인데 check가 성공함 | root/file read를 명시적으로 검증하고 모든 `IOException`/scan 예외를 `GradleException`으로 전환한다. |
| 진단 guard가 `println`으로 로그를 흘림 | Gradle output에 직접 stdout 사용 | guard는 Gradle `logger.error`/`logger.lifecycle`만 사용하고 `println`, `System.out`, `System.err` scan을 수행한다. |

## 호환성과 롤백

production API와 binary ABI는 변경하지 않는다. test dependency와 test-only
assertion 호출만 바뀌므로 published artifact의 runtime classpath에는 영향을
주지 않는다. compile 또는 targeted test가 실패하면 이관 commit을 되돌리고
direct dependency만 남겨 source scan과 matcher 선택을 다시 조정할 수 있다.
Guard가 기존 module test의 합법적인 `kotlin.test.Test`를 잘못 잡으면
`assert` 접두사 import만 대상으로 범위를 좁히고, `Test` annotation은 계속
허용한다.

## 수용 기준과 DoD

- [ ] `spring-boot/r2dbc`가 `bt4k.bluetape4k.assertions`를 직접 test dependency로 선언한다.
- [ ] 지정한 10개 파일의 legacy assertion import·wildcard·fully-qualified 호출이 0개이고,
      `bluetape4k-assertions` matcher/`assertFailsWith`를 사용한다.
- [ ] 대상 10개 파일의 `!!`, `println`, `System.out`, `System.err`가 0개다.
- [ ] `checkSpringBootR2dbcAssertionStyle`와
      `:bluetape4k-exposed-spring-boot-r2dbc:compileTestKotlin`가 통과한다.
- [ ] guard synthetic RED probe가 legacy import, wildcard, fully-qualified 호출,
      missing/unreadable source root를 각각 거부하고, logger-only 진단을 확인한다.
- [ ] 대상 unit 테스트와 multi-DB 테스트를 Testcontainers/실제 DB 경계를
      고려해 순차 검증하고, 실패·cancellation·ABI·content equality 사례를
      포함한다.
- [ ] T1–T6 및 main integration 7-Tier 검토에서 P0/P1이 0이고,
      Spring coroutine/Exposed transaction 경계 보존을 확인한다.
- [ ] PR 생성 전 Korean review/lesson artifact, Lore commit, exact pushed
      head, Korean PR body의 마지막 `## DoD Status`를 확인한다. merge는
      fresh explicit approval 전까지 PENDING이다.

## 설계 승인 근거

이 설계의 범위와 완료 조건은 Issue #724 본문에 직접 고정되어 있고,
사용자는 이슈를 순서대로 commit·PR까지 진행하도록 명시했다. 새 직접
dependency가 필요한 이유로 Type B 계획을 취소하고 Type A full-feature
workflow를 승인한 receipt를 함께 기록했다. 따라서 구현 전에 추가적인
제품 선택을 요구하는 미해결 질문은 없다.
