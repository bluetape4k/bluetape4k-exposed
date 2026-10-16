# 이슈 #913 — R2DBC Caffeine 시간 기반 테스트를 결정적으로 검증한다

## 배경

[이슈 #913](https://github.com/bluetape4k/bluetape4k-exposed/issues/913)은 병합 후 CI의 직렬 write-behind 검증에서 `R2dbcCaffeineSnapshotCacheTest.maximum size and expiry settings are enforced by Caffeine`이 실패한 문제를 다룬다. [CI 실행 38788902610](https://github.com/bluetape4k/bluetape4k-exposed/actions/runs/38788902610)의 직렬 행렬은 R2DBC Caffeine H2 결과를 `121 tests, 1 failed, 2 skipped`로 기록했다. 같은 실행의 전용 R2DBC Caffeine H2 job은 통과했다.

실패 테스트는 `maximumSize = 1`과 `expireAfterWrite = 2ms`를 함께 설정한 뒤 두 항목을 쓰고 즉시 조회했다. 로컬에서도 같은 테스트가 1회 실행 중 `Expected <0> to equal to <1>`로 실패했다. 조회 전에 두 항목이 만료되어 개수 검증이 0을 반환할 수 있으므로, 이 증거는 제품 회귀보다 용량과 시간을 한 테스트에서 동시에 검증한 구조를 가리킨다.

## 결정

공개 `r2dbcCaffeineSnapshotCache(...)` 팩터리와 운영 기본 시계는 유지한다. 내부 생성 경로에만 `Ticker`를 받는 오버로드를 추가하고, 기존 내부 생성 경로는 `Ticker.systemTicker()`를 전달한다. Caffeine은 [공식 테스트 지침](https://github.com/ben-manes/caffeine/wiki/Testing)에서 시간 만료 테스트에 사용자 정의 `Ticker`를 사용하고 만료 정리를 확인할 때 `Cache.cleanUp()`을 호출하도록 안내한다.

최대 크기 테스트는 시간이 진행되지 않는 `ManualTicker`를 사용한다. write 만료와 access 만료는 각각 별도 테스트에서 `ManualTicker`를 3ms 전진시키고 `cleanUp()` 후 캐시 miss를 확인한다. 이 파일의 만료 검증에서 `Thread.sleep`은 사용하지 않는다.

## 결과

용량 제한은 시간 만료와 독립적으로 검증하고, 만료 경계는 가상 시계로 제어한다. 공개 팩터리의 시그니처와 기본 운영 동작은 바꾸지 않으며 새 의존성도 추가하지 않는다.

## 검증

- 수정 전 타깃 테스트: 1개 실행, 동일 용량 assertion에서 실패했다.
- 수정 후 `R2dbcCaffeineSnapshotCacheTest`: 15개 통과, `BUILD SUCCESSFUL`.
- `detekt`는 통과했다. `detektTest`는 기존 테스트 코드의 7건(`WriteBehindCacheTest.kt`의 `UseCheckOrError` 4건, 이 테스트 파일에서 수정하지 않은 `config(...)` 헬퍼의 `LongParameterList` 1건, `R2dbcSnapshotTransactionTest.kt`의 `InjectDispatcher` 2건)으로 실패했다. 변경한 테스트 줄에서는 새 Detekt finding이 나오지 않았다.

## 다음 변경에서 확인할 점

Caffeine의 시간 정책과 용량 정책을 한 테스트에서 함께 주장하지 않는다. 만료 테스트에는 내부 테스트 시계나 동등한 제어 가능한 시간원을 주입하고, 시계를 전진한 뒤 `Cache.cleanUp()`으로 유지 관리를 명시한다. 운영 팩터리의 기본값은 system ticker로 유지하고, 테스트 seam을 위해 공개 API를 넓히지 않는다.
