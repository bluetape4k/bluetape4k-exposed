# Issue 342 동시성 테스터 검토

## 범위

- 이슈: #342 `test(cache): replace ad hoc concurrency probes with bluetape4k junit5 testers`
- 모듈: `bluetape4k-exposed-core`, `bluetape4k-exposed-jdbc-caffeine`, `bluetape4k-exposed-r2dbc-caffeine`
- 변경한 테스트:
  - `UserContextTest`
  - `JdbcCaffeineRepositoryExtraTest`
  - `SuspendedJdbcCaffeineRepositoryExtraTest`
  - `CacheManagementTest`

## 도우미 적용 범위

- `MultithreadingTester`
  - `UserContextTest`: 이제 명시적인 executor, latch, sleep 대신 테스터 워커 2개로 스레드 로컬 격리를 검증한다.
  - `JdbcCaffeineRepositoryExtraTest`: 이제 `get`과 `getAll`의 동기 캐시 미스 경합에 테스터 워커 8개를 사용한다.
- `SuspendedJobTester`
  - `SuspendedJdbcCaffeineRepositoryExtraTest`: 이제 `get`과 `getAll`의 일시 중단 캐시 미스 경합에 테스터 작업 8개를 사용한다.
  - `CacheManagementTest`: 이제 `get`과 `getAll`의 R2DBC 캐시 미스 경합에 테스터 작업 8개를 사용한다.

## 의도적으로 유지한 경계 동기화

남아 있는 직접적인 `CountDownLatch` 사용은 write-behind 경계를 확인하기 위한 것이다. 이는 결정적인 프로덕션 플러시/실패 지점(`flushStarted`, `releaseFlush`, `flushFailed`, `flushSucceeded`)을 조정하며, 일반적인 경합/스트레스 실행기가 아니다. 테스터를 사용하면 이 테스트가 검증하는 정확한 프로덕션 경계가 드러나지 않는다.

## 간소화한 7단계 검토

| 단계 | 결과 | 근거 |
| --- | --- | --- |
| 1 정확성 | PASS | 도우미 워커가 기존과 같은 리포지토리 호출을 실행하고 단일 로드 횟수/결과를 검증한다. |
| 2 동시성 | PASS | 적용하기 적절한 곳에서 임시 executor/async 경합 실행기를 bluetape4k-junit5 도우미로 교체했다. |
| 3 테스트 신뢰성 | PASS | `UserContextTest`에서 sleep/latch 중첩 검사를 제거했고, write-behind latch는 결정적인 게이트로 유지했다. |
| 4 유지보수성 | PASS | 이제 테스트 이름과 표준 도우미 API에 테스트 의도가 드러난다. |
| 5 범위 | PASS | 이슈에 명시된 대표적인 테스트 집중 지점만 변경했으며 프로덕션 코드는 변경하지 않았다. |
| 6 호환성 | PASS | 의존성이나 공개 API 변경이 없다. |
| 7 근거 | PASS | 기준선 및 최종 대상 Gradle 작업이 `--no-parallel`로 통과했다. |

## 검증

- 기준선: `./gradlew --no-parallel :bluetape4k-exposed-core:test :bluetape4k-exposed-jdbc-caffeine:test :bluetape4k-exposed-r2dbc-caffeine:test` — 1m 36s 만에 BUILD SUCCESSFUL.
- 편집 후 컴파일: `./gradlew --no-parallel :bluetape4k-exposed-core:compileTestKotlin :bluetape4k-exposed-jdbc-caffeine:compileTestKotlin :bluetape4k-exposed-r2dbc-caffeine:compileTestKotlin` — 1s 만에 BUILD SUCCESSFUL.
- 최종 대상 테스트: `./gradlew --no-parallel :bluetape4k-exposed-core:test :bluetape4k-exposed-jdbc-caffeine:test :bluetape4k-exposed-r2dbc-caffeine:test` — 1m 23s 만에 BUILD SUCCESSFUL.
- 변경 후 검사 요약: 대표 파일에서 `newFixedThreadPool=0`, `async(Dispatchers.Default)=0`이며, `UserContextTest`에서는 `CountDownLatch=0`, `Thread.sleep=0`이다.

## 판정

P0/P1: 0. `git diff --check`와 문서 인덱싱 후 PR을 생성할 수 있다.
