# Issue #902 Java 역직렬화에서 snapshot 불변식 복원

## Context

Issue [#902](https://github.com/bluetape4k/bluetape4k-exposed/issues/902)는 공개 snapshot 모델을 Java serialization 경계에서 다시 읽을 때 생성자 불변식이 유지되는지 조사했다. 대상은 `CacheSnapshot`, `SnapshotCacheConfig`, `CaffeineSnapshotCacheConfig`, `SnapshotStoreId`, `SnapshotCacheLimits`, `MeasuredInvalidation`, `SnapshotCacheApplyReport`, `SnapshotCacheOperationResult` 여덟 모델이다.

현재 구현은 `6965bc68300d712f21f0c19c5b457d19363687b0`에 있다. 기존 생성자는 namespace 형식, 식별자 길이, count/weight/시간 한계, SHA-256 형식, operation 결과의 count와 예외 메타데이터를 검사한다. `SnapshotCacheApplyReport`는 결과 목록을 보관하고, runtime-only capability인 `SnapshotCacheLookup`, `SnapshotCacheMiss`, `SnapshotLocalFence`, `SnapshotMissCapabilityRegistry`는 직렬화 대상이 아니다.

기존 [#681 lesson](2026-08-23-issue-681-serializable-uid.md)은 public DTO에 `Serializable`과 `serialVersionUID`를 추가하고 정상 round-trip을 확인한 사례다. 이번 lesson은 그 기본 계약을 반복하지 않고, Java 역직렬화가 생성자 검증을 우회하는 문제와 손상된 stream의 불변식 복원을 다룬다.

## Root Cause

`Serializable` 객체를 `ObjectInputStream.readObject()`로 복원할 때 Java는 일반 생성자와 Kotlin의 `init` 블록을 호출하지 않고 객체를 할당한 뒤 stream의 field를 채운다. 따라서 정상 생성자로 만든 객체는 검증을 통과해도, producer가 만든 손상된 stream은 `backend = ""`, 음수 count, 잘못된 duration 또는 digest를 가진 객체를 만들 수 있었다. Issue 본문의 probe는 reflection으로 field를 바꾼 테스트 stream을 만든 뒤 이 동작을 재현했다. reflection은 손상된 producer stream을 만드는 테스트 장치이며 일반 생성자의 우회 가능성을 주장하는 경로가 아니다.

## Decision or Finding

- 역직렬화가 field를 복원한 뒤 호출하는 `readResolve()`에서 public 생성자를 다시 호출한다. 생성자는 복원된 값을 새 객체로 재구성하므로, `init`의 `require` 검증이 다시 실행되고 잘못된 stream은 `IllegalArgumentException`으로 거부된다.
- 여덟 `Serializable` snapshot 모델에 `readResolve()`를 적용한다. `init` 불변식이 있는 모델은 생성자 검증을 다시 수행하고, `CacheSnapshot`과 `SnapshotCacheApplyReport`처럼 별도 불변식이 없는 모델도 동일한 복원 경계를 사용한다.
- 모든 모델의 `serialVersionUID = 1L`을 유지한다. `legacy-snapshot-models.base64`에는 clean `develop`에서 만든 여덟 모델의 실제 legacy stream 항목이 있으며, 이를 계속 읽어야 한다. `legacy-snapshot-report-alias.base64`는 report와 원본 결과 목록이 같은 객체를 가리키던 legacy graph를 검증한다.
- `SnapshotCacheApplyReport.readResolve()`는 `results.toList()`로 먼저 방어적으로 복사한 뒤 `Collections.unmodifiableList(...)`로 감싼다. 역직렬화 후 호출자가 원본 목록 alias를 통해 report를 바꾸거나 report의 결과 목록을 직접 수정할 수 없어야 한다.
- runtime-only capability는 계속 non-`Serializable`로 둔다. 실행 중 상태와 capability를 저장 가능한 public snapshot 모델로 확장하지 않는다.

## Outcome

구현은 `CacheSnapshot.kt`, `SnapshotCacheConfig.kt`, `SnapshotCacheStore.kt`에서 생성자 재구성 경계를 추가했다. 손상된 field를 reflection으로 만든 stream에 대해서는 store identifier, cache limit/configuration, Caffeine limit, invalidation metadata, operation result count와 exception metadata 검증이 역직렬화 시점에 다시 적용된다.

회귀 테스트는 정상 Java stream round-trip과 `serialVersionUID == 1L`을 함께 확인한다. clean `develop` 기반 legacy fixture의 여덟 모델 항목을 모두 읽고, report alias fixture에서는 원본 목록을 변경해도 report의 결과가 바뀌지 않으며 복원된 결과 목록의 변경 시도가 `UnsupportedOperationException`을 내는지 확인한다. 이 범위는 #681의 `Serializable`/UID/round-trip 기본 확인을 보완한다.

## Verification

| 검증 | 결과 |
| --- | --- |
| focused serialization XML | `exposed/cache/build/test-results/test/TEST-io.bluetape4k.exposed.cache.snapshot.SnapshotSerializationTest.xml`: `tests="11"`, `failures="0"`, `errors="0"`, `skipped="0"` |
| cache module test | `./gradlew :bluetape4k-exposed-cache:test --no-daemon --no-configuration-cache --rerun-tasks`: `BUILD SUCCESSFUL` |
| cache module check | `./gradlew :bluetape4k-exposed-cache:check --no-daemon --no-configuration-cache --rerun-tasks`: `BUILD SUCCESSFUL` |
| cache module Detekt | `./gradlew :bluetape4k-exposed-cache:detekt --no-daemon --no-configuration-cache --rerun-tasks`: `BUILD SUCCESSFUL` |

이 worktree에서는 JDBC/R2DBC 또는 기타 container integration suite를 실행하지 않았다. PR, hosted CI, review, merge는 아직 pending이며, canonical GNO indexing은 merge 후 canonical checkout 동기화가 끝난 다음으로 deferred 상태다.

## Future Guidance

1. 새 public `Serializable` snapshot 모델에 생성자 `init` 검증이 있으면 `serialVersionUID`와 함께 `readResolve()` 생성자 재구성을 추가한다. 생성자 검증만으로 역직렬화 경계를 보호한다고 가정하지 않는다.
2. 호환성을 주장할 때는 현재 모델의 정상 round-trip만 실행하지 말고 clean `develop` 또는 직전 release에서 만든 실제 legacy stream fixture를 읽는다. UID를 바꿀 이유가 없다면 `1L`을 유지한다.
3. field를 손상시킨 producer stream, nested object graph, collection alias를 각각 회귀 범위에 포함한다. collection field는 방어적 복사와 unmodifiable view를 모두 확인한다.
4. runtime-only 상태는 `Serializable` 모델과 분리하고, merge 이후에는 container integration과 hosted CI를 별도 evidence로 확인한다. lesson은 canonical checkout 동기화와 authorized cleanup 뒤에 GNO에 색인한다.

## Writer DoD (SPW-01~05)

- [x] **SPW-01** — Issue #902, 현재 HEAD, 세 source 파일, serialization test/fixture, targeted XML, 기존 #681 lesson을 source ledger로 고정하고 container/PR/CI/merge/GNO를 미검증 범위로 기록했다.
- [x] **SPW-02** — Context, Root Cause, Decision or Finding, Outcome, Verification, Future Guidance로 문제·원인·결정·결과·증거·재발 방지 규칙을 완성했다.
- [x] **SPW-03** — Korean technical register를 적용하고 `Serializable`, `serialVersionUID`, `readResolve()`, `ObjectInputStream`, `IllegalArgumentException`, `UnsupportedOperationException`과 명령·수치를 보존했다. KO-01~KO-07 자연성 검사를 완료했다. terminology audit의 `snapshot-loanword` 6건은 public API/도메인 용어에 대한 context-specific 예외로 기록했으며 전역 치환하지 않았다.
- [x] **SPW-04** — 구현·테스트·fixture·XML과 세 Gradle 결과를 대조해 여덟 모델, 11개 focused test, 실패/오류/skip 수, 검증 공백을 확인했다.
- [x] **SPW-05** — 최종 Markdown read-back으로 heading, 표, code token, link, 수치와 외부 경계를 확인했다.
