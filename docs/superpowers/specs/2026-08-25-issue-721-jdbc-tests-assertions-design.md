# Issue #721 `jdbc-tests` assertion 계약 설계

## 문제와 목표

`exposed/jdbc-tests`의 published test-support main source인
`src/main/kotlin/io/bluetape4k/exposed/tests/Assertions.kt`는
`io.bluetape4k.assertions`를 직접 import하지만
`exposed/jdbc-tests/build.gradle.kts`에는 `bt4k.bluetape4k.junit5`만 직접
선언되어 있다. migration drift fixture도 `org.junit.jupiter.api.Assertions`
정적 메서드를 직접 사용한다.

이 변경의 목표는 다음과 같다.

- main source 소비자가 필요한 assertion API를 안정적으로 해석하도록
  `bluetape4k-assertions`를 직접 `api`로 선언한다.
- `JdbcMigrationDriftTest`의 raw JUnit assertion을
  `assertFailsWith`, `shouldBeEqualTo`, `shouldBeTrue`, `shouldBeFalse`,
  `shouldBeSameInstanceAs`로 바꾼다.
- `exposed/jdbc-tests`의 `src/main/kotlin`과 `src/test/kotlin`에서
  `org.junit.jupiter.api.Assertions`, `kotlin.test.assert*`, AssertJ,
  Kluent import가 다시 들어오지 않도록 module-local Gradle guard를
  `check`에 연결한다. guard는 `sourceSets.main`과 `sourceSets.test`의
  고정 Kotlin source root만 대상으로 하며 regular `.kt` 파일만 검사한다.

## 현재 근거와 책임 경계

- 현재 `Assertions.kt`는 이미 `assertFailsWith`,
  `shouldBeEqualTo`, `shouldBeTrue`, `shouldBeFalse`를 사용한다.
- `JdbcMigrationDriftTest`의 `assertEquals`, `assertTrue`, `assertFalse`,
  `assertSame`, `assertThrows`는 migration SQL 검증과
  `preservingFailure`의 identity/suppressed 예외 계약에만 사용된다.
- migration 실행, dialect 선택, cleanup `rollback`/drop 순서, SQL 실패
  메시지와 테스트 DB 생명주기는 변경하지 않는다.
- 실제 DB cleanup 실패를 새로 주입하는 시나리오는 assertion API 정규화와
  무관한 별도 안정성 범위다. 이번 변경은 기존 `preservingFailure`의
  synthetic primary/cleanup/suppressed 테스트를 보존해 helper 계약만 잠근다.
- `ENABLE_DIALECTS_METHOD`가 제공하는 H2·PostgreSQL·MySQL_V8 선택 경로는
  동일한 fixture assertion을 순차 실행해 dialect별 진단과 cleanup 계약을
  회귀 검증한다.
- 새 assertion artifact나 새 모듈을 만들지 않는다. 중앙
  `bluetape4k-dependencies` catalog의 기존 `bt4k.bluetape4k.assertions`
  alias만 직접 노출한다.

## 선택지와 결정

### 선택지 1 — 기존 artifact 직접 `api` + fixture 정규화 + module-local guard

`api(bt4k.bluetape4k.assertions)`를 `bluetape4k-junit5`와 같은
Bluetape4k API 그룹에 선언한다. migration fixture의 모든 assertion을
intent-specific matcher로 바꾸고, source roots를 읽어 금지 import를
검사하는 Gradle verification task를 `check`에 연결한다. 검사 root와
제외 목록은 환경 변수나 Gradle property로 바꿀 수 없고, symlink는
따라가지 않는다.

이 안은 published main source의 ABI/classpath 요구와 테스트 스타일 계약을
한 번에 고정하며, 변경 파일이 모듈 build script와 단일 fixture로 제한된다.

### 선택지 2 — `implementation` 또는 test-only configuration

compile classpath에서 우연히 통과하는 현재 상태를 재현할 수 있지만,
published test-support 소비자가 `Assertions.kt`를 컴파일할 때 직접 API가
노출된다는 계약을 보장하지 못한다. 선택하지 않는다.

### 선택지 3 — repo-wide assertion import 정책으로 확장

모든 모듈을 한 번에 정규화하면 정책 일관성은 높아지지만 #721의 단일
`jdbc-tests` 범위와 독립적인 실패 지점이 늘어난다. 후속 이슈로 분리한다.

## 계약과 실패 모드

1. **의존성 계약**: `Assertions.kt`가 참조하는 `io.bluetape4k.assertions`
   클래스는 `:bluetape4k-exposed-jdbc-tests`의 published API classpath에서
   해석되어야 한다. 직접 alias가 누락되면 compile 검사가 실패해야 한다.
2. **assertion 의미 계약**: equality는 `actual shouldBeEqualTo expected`,
   identity는 `actual shouldBeSameInstanceAs expected`, boolean은
   `shouldBeTrue/False`, 예외는 `assertFailsWith<T>`로 표현한다. dialect
   접두사와 `preservingFailure`의 primary/suppressed 관계는 그대로다.
3. **진단 계약**: 거부된 migration statement는
   `assertFailsWith<IllegalArgumentException>(message = statement)`로
   원문 SQL을 failure message에 보존한다. dialect 접두사와 helper의
   primary/suppressed 예외 문맥도 유지한다.
4. **정책 계약**: 금지 raw import를 source root에 추가하면
   `verifyBluetapeAssertionImports`가 실패하고 `check`가 통과하지 않는다.
   `org.junit.jupiter.api.Assertions`의 단일 클래스·wildcard·alias import,
   `kotlin.test.assert*`, AssertJ·Kluent assertion package를 모두 금지하며,
   JUnit lifecycle/parameterized/extension annotation import는 허용한다.

주요 실패 모드는 다음과 같다.

- 직접 API 선언을 `implementation`으로 잘못 두어 published consumer compile이
  깨지는 경우: `compileTestKotlin`과 API dependency inspection에서 검출한다.
- `assertSame`를 equality matcher로 바꿔 서로 다른 동일값 객체를 허용하는
  경우: `shouldBeSameInstanceAs`를 사용하고 identity 테스트를 유지한다.
- `assertFailsWith` 호출에서 migration statement 진단을 잃는 경우:
  `message = statement` 인자와 거부 목록의 failure message를 검증한다.
- `preservingFailure` 예외가 cleanup 예외를 잃거나 rollback 검증이 바뀌는
  경우: `assertFailsWith` 반환 예외의 identity와 `suppressed` 내용을
  검증한다.
- 금지 import guard가 source root 밖만 검사하거나 자기 자신을 allowlist하는
  경우: main/test 두 고정 root의 regular `.kt` raw import scan을
  RED/GREEN으로 확인한다. canonical path가 root 밖으로 나가는 symlink와
  환경 변수·Gradle property를 통한 root/exclude 변경은 guard 대상에서
  제외한다.

## 호환성 및 범위

- production Exposed 실행 로직과 migration SQL 생성/실행은 바꾸지 않는다.
- assertion artifact는 기존 catalog/BOM 좌표를 사용하므로 별도 버전 축을
  만들지 않는다.
- assertion failure의 구현체가 JUnit `AssertionFailedError`에서
  `AssertionError`로 달라질 수 있으나 테스트 의미, dialect 문맥, rollback
  순서는 보존한다.
- README, public KDoc, module registration, workflow YAML은 변경하지
  않는다. 변경된 build script와 test source가 유일한 실행 surface다.

## 수용 기준과 DoD

- `exposed/jdbc-tests`에 raw `org.junit.jupiter.api.Assertions`,
  `kotlin.test.assert*`, AssertJ/Kluent import가 0건이다.
- `api(bt4k.bluetape4k.assertions)`가 직접 선언되고
  `Assertions.kt`의 published main source compile이 유지된다.
- `:bluetape4k-exposed-jdbc-tests:compileTestKotlin` 및
  `migrationDriftTest`의 H2 targeted 실행이 통과한다. Docker가 준비된
  환경에서는 `EXPOSED_TEST_DB=POSTGRESQL`과 `EXPOSED_TEST_DB=MYSQL_V8`도
  각각 순차 실행해 H2+선택 dialect 결과를 기록한다.
- migration dialect 실패 메시지, `preservingFailure` primary/suppressed
  identity, cleanup/rollback semantics가 기존과 동일하다.
- 이번 범위에서 실제 DB cleanup failure injection은 추가하지 않으며,
  helper의 synthetic failure 계약과 기존 fixture의 cleanup/rollback
  경로를 변경하지 않는다.
- module-local import guard가 `check`에서 통과하고, diff에 `println`,
  `System.out`, `System.err`를 추가하지 않는다.
- Kotlin testing checklist와 7-Tier 최종 검토에서 P0/P1이 0건이다.

## 검증 중단점과 롤백

RED 단계에서 기존 raw import 검색 결과와 guard의 의도적 실패를 기록하고,
금지 import probe로 wildcard/alias 경계를 확인한다.
GREEN 단계에서 직접 dependency, matcher 변환, guard를 순서대로 적용하고
compile/targeted/full module 및 가능한 교차-dialect 검증을 다시 실행한다.
어떤 단계에서든
published API compile 또는 rollback/identity 증거가 깨지면 해당 단계의
변경만 되돌리고 raw source와 baseline 테스트로 원인을 분리한다.
