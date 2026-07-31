# Issue 123 cache health 설계

## 배경

GitHub issue #123은 Caffeine 기반 저장소의 consistency health check를
요구한다. 직접적인 failure mode는 WRITE_BEHIND이다. cache가 write를
수락한 뒤 background DB flush가 실패할 수 있지만, 현재 caller는 이 상태를
관찰할 수 없다.

Claude Code review를 사용할 수 없다는 사용자 지시에 따라 Claude
advisor/review를 사용하지 않는다. 현재 Codex 세션이 구현·검토·검증을
소유하므로 외부 Codex CLI review도 생략한다.

현재 IDE session에서는 이 worktree의 IntelliJ diagnostics를 사용할 수 없다.
따라서 repository search와 Gradle compile/test로 검증한다.

## 설계

`exposed-cache`에 `CacheHealthReport`를 추가한다.

```kotlin
data class CacheHealthReport(
    val mode: CacheWriteMode,
    val queueDepth: Int,
    val isFlushJobRunning: Boolean,
    val lastFlushError: Throwable?,
)
```

report는 다음 값을 제공하는 read-only snapshot이다.

- `mode`: 설정된 cache write mode.
- `queueDepth`: 저장소가 수락했지만 background worker의 flush 완료가 아직
  관찰되지 않은 write-behind entry 수. 현재 in-memory batch로 가져온 entry도 포함한다.
- `isFlushJobRunning`: WRITE_BEHIND mode에서 worker가 시작되었고 현재 job이
  active일 때만 `true`.
- `lastFlushError`: background worker가 관찰한 마지막 non-cancellation flush
  failure. flush가 성공하면 `null`.

Caffeine 전용 저장소 계약에 API를 노출한다.

- `JdbcCaffeineRepository.validateConsistency(): CacheHealthReport`
- `R2dbcCaffeineRepository.validateConsistency(): CacheHealthReport`

다음 구현에 API를 추가한다.

- `AbstractJdbcCaffeineRepository`
- `AbstractR2dbcCaffeineRepository`

issue는 Actuator 통합을 선택 사항으로 명시한다. 핵심 runtime 계약을 집중
테스트와 함께 먼저 제공할 수 있도록 Actuator auto-configuration은 후속 작업으로 남긴다.

## 위험

- `Channel`은 안정적인 queue size를 제공하지 않는다. 성공한 send 시 증가시키고
  flush attempt가 끝난 뒤 감소시키는 방식으로 queue depth를 명시적으로 추적해야 한다.
- health 보고 중 lazy write-behind job을 호출하면 의도치 않게 worker가 시작된다.
  health 보고는 job을 초기화하지 않아야 한다.
- 현재 flush failure는 log 후 suppress된다. 이 동작을 유지하면서 마지막 failure를 노출해야 한다.

## 검증

- `exposed-cache`, `exposed-jdbc-caffeine`, `exposed-r2dbc-caffeine`을 compile한다.
- JDBC에서 idle WRITE_BEHIND, in-flight queue depth, 기록된 flush failure snapshot을 테스트한다.
- R2DBC에서 idle WRITE_BEHIND, in-flight queue depth, 기록된 flush failure snapshot을 테스트한다.
- 현재 세션에서 최종 diff review를 수행한다.
