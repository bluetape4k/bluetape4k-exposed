# 이슈 317 미완료 발행 재처리 리뷰

## 범위

- 이슈: #317 `feat(spring-modulith): verify outstanding event republication with Exposed store`
- 리뷰한 파일:
  - `spring-boot/spring-modulith/src/test/kotlin/io/bluetape4k/spring/modulith/exposed/ExposedEventPublicationRepositoryTest.kt`
  - `spring-boot/spring-modulith/README.md`
  - `spring-boot/spring-modulith/README.ko.md`

## Tier 4 - 구현 리뷰

- 결과: PASS
- P0/P1 지적 사항: 0
- 증거:
  - 테스트는 `context.publishEvent(...)`로 실제 Spring Modulith 발행을 생성하므로, 저장된 리스너 ID는 수작업 픽스처가 아니라 Spring Modulith가 할당한다.
  - 재시작 경로는 `spring.modulith.events.republish-outstanding-events-on-restart=true`로 Spring 애플리케이션 컨텍스트를 다시 생성한다.
  - 단언은 미완료 이벤트가 정확히 한 번 다시 처리되고 수동으로 완료한 발행은 다시 처리되지 않음을 입증한다.
  - 검증은 `TestDB.enabledDialects()`와 `CompletionMode.entries` 전체에서 실행된다.

## Tier 5 - 테스트 리뷰

- 결과: PASS
- P0/P1 지적 사항: 0
- 증거:
  - `repo-test-summary -- ./gradlew :bluetape4k-exposed-spring-modulith:test --no-configuration-cache --no-build-cache --no-parallel --rerun-tasks --console=plain`
  - 결과: `SUCCESS: Executed 54 tests in 15.2s`
  - 결과: `BUILD SUCCESSFUL in 19s`
- 동시성 헬퍼 게이트:
  - 스트레스, 경합, 스레드 안전성 동작을 새로 도입하지 않았다.
  - 제한된 폴링 헬퍼는 Spring Modulith의 비동기 시작 시점 재제출 리스너가 완료되기만을 기다린다. `MultithreadingTester`, `SuspendedJobTester`, `StructuredTaskScopeTester`는 이 이벤트 전달 대기에는 적합하지 않다.

## Tier 7 - 문서 및 다이어그램 리뷰

- 결과: PASS
- P0/P1 지적 사항: 0
- 증거:
  - `README.md`와 `README.ko.md`에 이제 `spring.modulith.events.republish-outstanding-events-on-restart`가 명시되어 있다.
  - 두 README는 완료된 발행을 건너뛰는 동작, 멱등 리스너 요구 사항, 안정적인 리스너 ID, 중복 부수 효과 방지, 재처리 후 완료 모드 동작, 로드할 수 없는 이벤트에 대한 주의 사항을 설명한다.
  - 다이어그램 평가: 새 다이어그램 자산은 추가하지 않았다. 기존 수명주기 시퀀스 다이어그램은 이미 발행 생성, 완료 모드, 재시도/재제출 상태 추적을 다룬다. 이슈 #317은 새로운 컴포넌트 토폴로지나 상태 머신 대신 운영자용 시작 속성과 멱등성 주의 사항을 추가하므로, 텍스트 하위 섹션이 더 명확하고 유지보수 비용도 낮다.

## 정합성 검사

- `git diff --check`: PASS
- IDE 진단: 이 CLI 세션에서는 Kotlin/IntelliJ 진단 백엔드를 사용할 수 없었지만, Gradle `compileTestKotlin`과 모듈 테스트는 통과했다.
