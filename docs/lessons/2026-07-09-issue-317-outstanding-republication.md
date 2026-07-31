# Issue 317 미완료 발행 재처리

## 배경

Spring Modulith는 애플리케이션 시작 시 미완료 발행을 다시 제출할 수 있다. Exposed 기반 저장소에는 저장된 미완료 행이 컨텍스트 종료 후에도 유지되고, 재시작할 때 실제 리스너 레지스트리를 통해 재처리된다는 검증이 필요했다.

## 결정

추정한 리스너 id로 행을 직접 구성하는 대신 `context.publishEvent(...)`를 사용하여 저장될 발행 데이터를 생성한다. 그런 다음 `spring.modulith.events.republish-outstanding-events-on-restart=true`로 애플리케이션 컨텍스트를 재시작하고 미완료 이벤트만 전달되는지 검증한다.

## 결과

이제 테스트는 `TestDB.enabledDialects()`를 통해 H2, PostgreSQL, MySQL 8과 Spring Modulith의 모든 완료 모드를 커버한다. 영문 및 한글 README 문서에는 재시작 속성과 리스너 멱등성 주의 사항을 반영했다.

## 향후 지침

- 리스너 id 불일치를 포착할 수 있도록 재시작 후 재발행 테스트는 실제 Spring 이벤트 경로를 사용한다.
- 시작 시 재제출을 비동기 이벤트 전달로 취급하고, 상태 검증에는 제한 시간이 있는 폴링을 사용한다.
- 재시작 동작이 기존 수명 주기 다이어그램으로 설명할 수 없는 새로운 토폴로지나 상태 전이를 도입할 때만 새 다이어그램을 추가한다.

## 검증

- `repo-test-summary -- ./gradlew :bluetape4k-exposed-spring-modulith:test --no-configuration-cache --no-build-cache --no-parallel --rerun-tasks --console=plain`
- `SUCCESS: Executed 54 tests in 15.2s`
- `BUILD SUCCESSFUL in 19s`
- `git diff --check`
