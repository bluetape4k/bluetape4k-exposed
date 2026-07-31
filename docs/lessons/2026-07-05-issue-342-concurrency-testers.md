# 교훈: 테스트 경합 실행기에는 bluetape4k 동시성 테스터를 우선 사용한다

## 배경

이슈 #342에서 경합 및 경쟁 상황을 구성하기 위해 직접 만든 실행기, 래치, `async(Dispatchers.Default)`,
대기 기반 중첩 검사 방식을 사용하는 캐시/UserContext 테스트가 발견되었다.

## 교훈

블로킹/스레드 경쟁 테스트에는 `MultithreadingTester`를 사용하고 일시 중단 캐시 경쟁 테스트에는
`SuspendedJobTester`를 사용한다. 단언이 write-behind flush의 시작/해제/실패 체크포인트처럼
결정적인 프로덕션 경계에 의존할 때만 원시 래치를 유지한다.

## 가드레일

임시방편으로 작성한 동시성 검사 방식을 교체할 때는 판단 기준을 다음 두 범주로 나눈다.

1. 경합/스트레스 실행기: `MultithreadingTester`, `SuspendedJobTester`, `StructuredTaskScopeTester` 중 하나로 교체한다.
2. 프로덕션 경계 동기화 도구: 래치를 유지하되, 테스터를 사용하면 테스트 대상 경계가 가려지는 이유를 문서화한다.

## 검증 결과

- 이제 `UserContextTest`, `JdbcCaffeineRepositoryExtraTest`, `SuspendedJdbcCaffeineRepositoryExtraTest`,
  `CacheManagementTest`는 대표적인 경쟁 상황 검사에 저장소에서 제공하는 헬퍼를 사용한다.
- 최종 대상 검증이 통과했다: `./gradlew --no-parallel :bluetape4k-exposed-core:test :bluetape4k-exposed-jdbc-caffeine:test :bluetape4k-exposed-r2dbc-caffeine:test`.
