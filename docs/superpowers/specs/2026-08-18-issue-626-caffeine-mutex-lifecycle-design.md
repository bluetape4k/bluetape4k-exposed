# Issue #626 Suspended JDBC Caffeine Mutex 수명주기 설계

## 문서 상태

- 대상 이슈: [#626](https://github.com/bluetape4k/bluetape4k-exposed/issues/626)
- Epic/stack: [#659](https://github.com/bluetape4k/bluetape4k-exposed/issues/659) Slot 1
- 대상 모듈: `:bluetape4k-exposed-jdbc-caffeine`
- 대상 릴리스: `1.13.0` 개발선
- 선행 조건: `develop` `f81e01bf7da2a92354a4c9f4083e75eeb71db1d1`
- 설계 상태: RALPLAN Architect/Critic `APPROVE`, P0/P1/P2 `0`

## 문제 정의

`AbstractSuspendedJdbcCaffeineRepository`는 캐시 miss를 같은 직렬화 키별
`Mutex`로 직렬화한다. 현재 private `loadMutexes`는 키가 처음 관찰될 때
`computeIfAbsent`로 entry를 만들고 완료 후 제거하지 않는다. 키 churn이 계속되면
캐시가 만료되거나 무효화된 뒤에도 조정 상태가 남아 registry cardinality가
증가한다.

단순히 `finally`에서 `loadMutexes.remove(key)`를 호출하면 holder가 해제하는
순간에 이미 대기 중인 waiter 또는 새 호출자가 기존 Mutex를 잃는다. 그 결과
같은 키에 두 Mutex가 살아서 동시에 DB loader가 실행될 수 있다.

## 목표

1. 성공적인 동일 키 cache miss는 기존처럼 동시에 하나의 DB loader만 실행하고,
   성공 결과를 Caffeine에 저장해 겹친 호출이 cache hit를 관찰하게 한다.
2. 성공·예외·`CancellationException`·`null` 결과가 끝난 뒤 private coordination
   entry를 회수한다.
3. waiter가 `Mutex`를 기다리는 동안 holder가 entry를 조기 제거하지 않도록 한다.
4. 호출자 취소는 호출자 소유로 유지하고 원래 `CancellationException`을 다시
   던진다. 실패·취소·`null` 결과 자체는 deferred outcome으로 공유하지 않으며,
   queued/next caller가 이전 시도 뒤 순차적으로 재시도할 수 있다.
5. 공개 API, ABI, cache data model, write-behind 동작과 `docs/manual/**`를
   변경하지 않는다.

## 채택한 설계

private registry의 value를 `Mutex`에서 private entry로 바꾼다. entry는 `Mutex`와
active user count를 가진다. count의 생성·증가·감소·0 도달 시 제거는 모두 같은
키의 `ConcurrentHashMap.compute` 또는 `computeIfPresent` 내부에서 수행한다.
count를 별도 `AtomicInteger`로 map 연산 밖에서 변경하지 않는다.

```kotlin
private fun acquireLoadMutex(key: String): LoadMutexEntry =
    loadMutexes.compute(key) { _, current ->
        (current ?: LoadMutexEntry()).also { it.users += 1 }
    } ?: error("load mutex entry was not created")

private fun releaseLoadMutex(key: String, entry: LoadMutexEntry) {
    loadMutexes.computeIfPresent(key) { _, current ->
        if (current !== entry) current
        else {
            current.users -= 1
            current.takeUnless { it.users == 0 }
        }
    }
}
```

실제 구현에서는 `!!`를 production code에 추가하지 않고 기존 Kotlin null-safety
패턴에 맞게 반환값을 안전하게 확정한다. `get`은 entry를 획득한 뒤 기존
`entry.mutex.withLock { cache 재확인 -> findByIdFromDb -> cache 저장 }`을 수행하고
`finally`에서 release한다. `withLock` 대기 중인 호출도 entry count에 포함되므로
holder release가 entry를 제거할 수 없다. 마지막 release와 새 acquire는 map의
동일 키 연산으로 선형화된다.

### 결과별 계약

| 결과 | 겹친 호출의 의미 | 수명주기 결과 |
| --- | --- | --- |
| entity | 첫 성공 loader가 cache를 채우고 waiter는 cache hit | 마지막 user 후 entry 제거 |
| 예외 | 예외를 deferred outcome으로 공유하지 않으며 다음 호출은 순차 retry 가능 | `finally`에서 entry 제거 또는 waiter 보존 |
| `CancellationException` | 호출자 취소를 재전파하고 다른 waiter가 있으면 이후 시도 가능 | 취소한 user만 release, holder/waiter entry 보존 |
| `null` | null을 cache에 저장하지 않으며 다음 호출은 순차 retry 가능 | 마지막 user 후 entry 제거 |

### 검토한 대안

- `Deferred` registry: 실패·취소·`null`까지 한 miss wave에서 공유할 수 있지만
  loader owner, caller cancellation, `close()` scope를 새로 정의해야 한다. 현재
  호환 계약은 성공 값 coalescing만 요구하므로 범위를 넓히지 않는다.
- striped Mutex: registry를 고정 크기로 만들지만 서로 다른 키가 불필요하게
  직렬화되고 기존 key granularity가 바뀐다. 사용자 정책 없이 채택하지 않는다.
- plain `remove(key)`: waiter/new-acquire 경계에서 두 Mutex가 생길 수 있어
  거부한다.

## 검증 계약

- 기존 same-key 8-way 테스트는 DB loader 1회와 동일 결과를 유지한다.
- unique-key churn 뒤 reflection으로 private registry size가 0인지 확인한다.
- 첫 loader 예외/`null` 뒤 다음 호출이 성공하고 registry가 0인지 확인한다.
- 실제 `Job.cancelAndJoin`으로 loader를 취소하고 원래 `CancellationException`과
  entry cleanup, 후속 retry를 확인한다.
- holder + queued waiter에서 waiter 취소가 holder entry를 제거하지 않는지,
  holder 종료 후 registry가 0인지 확인한다. 두 번째 loader를 gate한 단일
  release/new-acquire 경계에서 세 번째 caller를 시작해 concurrently active
  duplicate loader가 없는지 확인한다.
- 기존 read-through, `getAll`, clear/invalidate, write-behind 회귀를 실행한다.
- 공개 subclass를 재컴파일하고 baseline/candidate의 `javap -public -s` 결과를
  비교한다. EN/KO README 계약 토큰과 `git diff --check`도 검증한다.

## 비목표와 경계

- public metrics, lifecycle hook, striped-lock policy, cache size policy를 추가하지
  않는다.
- `getAll`을 병렬화하거나 write-behind 수명주기를 재설계하지 않는다.
- `docs/manual/**`는 안정 릴리스 `1.12.1` 기준을 유지한다. `1.13.0` manual
  승격은 릴리스 이슈 #651의 별도 gate다.
- 외부 Redis/Testcontainers 또는 비-H2 드라이버 fault semantics는 이 private
  local-cache contract의 검증 범위가 아니다.

## 승인 기준

1. success/exception/cancellation/null 모든 경로에서 registry entry 수명이
   leak 없이 끝난다.
2. waiter가 있는 동안 premature removal이나 concurrently active duplicate
   loader가 발생하지 않는다.
3. 실패·취소·`null`의 순차 retry와 성공 cache hit 의미가 KDoc/README EN/KO에
   동일하게 기록된다.
4. public ABI와 기존 cache/write-behind 계약이 변하지 않는다.
5. H2 targeted/full test, 필요한 representative smoke, detekt/compile, ABI,
   README parity, diff check가 모두 통과한다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — Issue/Epic, source path, current `develop`, stable manual
  boundary, Architect/Critic evidence를 고정했다.
- [x] SPW-02 — 문제, 선택지, 결과별 계약, race 선형화, acceptance와 비목표를
  포함했다.
- [x] SPW-03 — 한국어 technical register와 `Mutex`, `entry`, `waiter`, `retry`
  용어를 일관되게 사용하고 code token은 보존했다.
- [x] SPW-04 — 현재 `get`/`loadMutexes` 구현, existing concurrency tests,
  RALPLAN PRD/test-spec와 설계 결정을 대조했다.
- [x] SPW-05 — Markdown read-back으로 코드 블록, 표, 링크, 경계 문구를
  확인했다.
