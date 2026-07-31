# 리뷰 - 이슈 #276 UserContext 코루틴 전파

## 범위

- 이슈: #276 `fix(core): preserve UserContext across coroutine dispatcher hops`
- 모듈: `:bluetape4k-exposed-core`, `:bluetape4k-exposed-r2dbc`
- 검토한 파일:
  - `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/auditable/UserContext.kt`
  - `exposed/core/build.gradle.kts`
  - `exposed/core/src/test/kotlin/io/bluetape4k/exposed/core/auditable/UserContextTest.kt`
  - `exposed/r2dbc/src/test/kotlin/io/bluetape4k/exposed/r2dbc/repository/AuditableR2dbcRepositoryTest.kt`

## 발견 사항

- P0: 없음.
- P1: 없음.
- P2: 없음.
- P3: 오래된 비공개 `THREAD_LOCAL_USER` KDoc 때문에 원시 `InheritableThreadLocal`이 코루틴에서 안전하다고 오해할 수 있었다. 코루틴 전파에는 `asContextElement`가 필요하다고 문서화하여 수정했다.

## 근거

- 실패 단계(RED): 새로운 코루틴 안전 API 테스트는 구현 전에 `Unresolved reference 'withCoroutineUser'` 오류로 실패했다.
- 대상 테스트 성공(GREEN): `UserContextTest.withCoroutineUser 는 coroutine dispatcher hop 이후에도 사용자명을 유지한다`가 통과했다.
- 감사 테스트 성공(GREEN): `AuditableR2dbcRepositoryTest.withCoroutineUser 는 virtual thread transaction 의 감사 사용자명에 전파된다`가 H2, PostgreSQL, MySQL_V8에서 통과했다.
- 모듈 테스트 성공(GREEN): `./gradlew :bluetape4k-exposed-core:test :bluetape4k-exposed-r2dbc:test --no-parallel --rerun-tasks`
  - `exposed/core`: 테스트 277개, 실패 0개, 오류 0개, 건너뜀 13개.
  - `exposed/r2dbc`: 테스트 365개, 실패 0개, 오류 0개, 건너뜀 15개.
- `git diff --check`: 통과.

## 검토 의견

- `withCoroutineUser`는 `withContext(asContextElement(username))`를 사용하므로, 호출자의 `Job`을 교체하거나 외부 스코프를 생성하지 않고 `ThreadContextElement`를 추가한다.
- `withThreadLocalUser`는 동기식 스레드 API로 유지되며, KDoc에서는 더 이상 코루틴 디스패처 전환에 이 API를 권장하지 않는다.
- 공개 코어 API가 `ThreadContextElement`를 노출하므로 `kotlinx-coroutines-core`는 `api` 의존성이다.
