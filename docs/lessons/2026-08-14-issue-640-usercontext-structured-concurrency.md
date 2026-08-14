# 이슈 #640 UserContext Structured Concurrency 회귀 증거

**관련 이슈**: #640
**영향 모듈**: `:bluetape4k-exposed-core`

## 상황

`UserContext.withUser`의 KDoc는 `ScopedValue`와 `ThreadLocal`을 함께 설정해
Structured Concurrency child task까지 사용자명을 전파한다고 설명한다. 기존
`UserContextTest`는 직접 scope, 예외 복원, coroutine dispatcher hop을 검증했지만
실제 JDK child task 경계는 실행하지 않았다.

## 결정

JDK 25 preview API인 `StructuredTaskScope.open()`을 사용하는 회귀 테스트를
`exposed/core` 테스트 소스에 추가했다. `@EnabledForJreRange(min = JRE.JAVA_25)`로
지원하지 않는 JDK에서는 테스트를 실행하지 않으며, production API와 KDoc는
변경하지 않는다.

## 결과와 검증

- `withUser` 안에서 fork한 child task가 사용자명을 읽는지 확인했다.
- child task가 끝난 뒤 `SCOPED_USER`가 unbound이고 바깥 `ThreadLocal` 값이
  복원되는지 확인했다.
- child 예외와 부모 예외 뒤에도 사용자 context cleanup이 유지되는지 확인했다.
- 대상 테스트: 2 tests passed.
- `:bluetape4k-exposed-core:test`: 293 tests executed, 13 skipped, 0 failures.
- `detekt`: `BUILD SUCCESSFUL`.
- `git diff --check`: 통과.

## 놓치기 쉬운 점

`ScopedValue` 전파 주장은 직접 `ScopedValue.where`를 호출하는 단위 테스트만으로
충분하지 않다. `StructuredTaskScope`가 생성된 시점의 binding을 child task에
전달하는 실제 경계를 실행해야 하며, preview API를 사용하는 테스트에는 target JDK와
`--enable-preview` 실행 조건이 함께 있어야 한다.

## 다음 guard

JDK preview 동시성 API를 사용하는 `UserContext` 회귀 테스트를 추가할 때는
child-task 전파, child/parent 예외, scope 종료 후 `ScopedValue`와 이전
`ThreadLocal` 복원을 한 묶음으로 검증한다. `withCoroutineUser`의 dispatcher-hop
계약은 별도 이슈 #276 범위로 유지한다.

## 참고 자료

- [Oracle JDK 25 `ScopedValue` API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ScopedValue.html)
- [Oracle JDK 25 `StructuredTaskScope` API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/StructuredTaskScope.html)

## Writer gate

- `SPW-01`: PASS — 이슈 #640, `UserContext` KDoc/기존 테스트, JDK 25 API를
  근거로 범위와 독자를 고정했다.
- `SPW-02`: PASS — 상황, 결정, 결과, 검증, 놓치기 쉬운 점, 다음 guard를
  포함했다.
- `SPW-03`: PASS — 한국어 기술 문체와 API·명령·식별자 보존을 확인했다.
- `SPW-04`: PASS — 구현·테스트 결과와 JDK 25 공식 API 설명을 대조했다.
- `SPW-05`: PASS — Markdown 전체를 read-back하고 링크·코드 토큰·검증 수치를
  확인했다.
