# Issue 123 cache health 계획

## 목표

WRITE_BEHIND queue, worker, flush error 상태를 노출하는 Caffeine 저장소
health snapshot을 추가하여 milestone 1.8.1의 issue #123을 구현한다.

## 단계

1. 공유 model을 추가한다.
   - `exposed-cache`에 `CacheHealthReport`를 생성한다.
   - `serialVersionUID`를 포함한 `Serializable`로 만든다.

2. Caffeine 계약을 노출한다.
   - `JdbcCaffeineRepository`에 `validateConsistency()`를 추가한다.
   - `R2dbcCaffeineRepository`에 `suspend validateConsistency()`를 추가한다.

3. runtime 상태 추적을 구현한다.
   - 수락한 write-behind entry 수를 `AtomicInteger`로 추적한다.
   - 마지막 non-cancellation flush failure를 `AtomicReference`로 추적한다.
   - health 보고가 lazy write-behind job을 강제로 초기화하지 않도록, job 시작 여부를 별도로 추적한다.

4. 테스트를 추가한다.
   - JDBC: idle health snapshot, 막힌 in-flight flush의 queue depth, flush failure report.
   - R2DBC: idle health snapshot, 막힌 in-flight flush의 queue depth, flush failure report.

5. 검증하고 전달한다.
   - 대상 compile/test를 실행한다.
   - `git diff --check`를 실행한다.
   - 현재 세션에서 로컬 review를 수행한다.
   - commit과 push 후 #123을 닫는 PR을 생성한다.

## 제약

- issue에서 선택 사항인 Actuator `HealthIndicator` 통합은 이번 범위에서 제외한다.
- 사용자 지시에 따라 Claude advisor/review를 생략한다.
- 사용자 지시에 따라 외부 Codex CLI review를 생략한다.
- 이 worktree가 IntelliJ의 활성 project가 아니면 IntelliJ diagnostics를 사용할 수 없으므로 Gradle compile/test로 대체한다.
