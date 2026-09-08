# #841: 패턴 무효화는 backing 이후 NearCache namespace를 비운다

## 배경

Suspend JDBC·R2DBC 레포지토리의 `invalidateByPattern`은 loaded-map의
`keyPrefix` namespace만 SCAN/UNLINK하고 `nearCacheName` namespace와 local front를
남겼다. NearCache에 이미 적재된 값은 다음 조회에서 Redis나 DB보다 먼저 반환되므로,
DB 값을 갱신한 뒤 패턴 무효화를 호출해도 오래된 값이 노출됐다.

## 결정

- `count.requirePositiveNumber("count")`를 Redis에 접근하기 전에 호출한다.
- loaded-map backing 키를 먼저 삭제하고, 성공한 경우에만 활성화된 NearCache의
  `clearAll()`을 호출한다. 반환값은 backing에서 실제 삭제된 키 수로 유지한다.
- `clearAll()`은 이 레포지토리의 `nearCacheName` namespace에 한정되므로 local front와
  해당 Redis back을 함께 비운다. 요청한 패턴 밖의 같은 namespace 항목은 보수적으로
  제거될 수 있지만, 다른 레포지토리의 namespace는 건드리지 않는다.
- backing 캐시의 일반 예외와 코루틴 취소는 잡아 삼키지 않고 호출자에게 전파한다.
  backing 삭제가 실패하거나 취소되면 후속 NearCache 정리는 실행되지 않는다.

처음 작성한 회귀 fixture는 존재하지 않는 `findById` API를 사용했다. 실제 suspend
레포지토리 계약의 `get`으로 교정했으며, 이후에는 targeted `test` 실행을 compile probe로
사용해 fixture API 불일치를 런타임 재현과 분리한다.

## 결과

NearCache를 사용하는 경우에도 패턴 무효화가 backing 삭제 뒤 해당 NearCache namespace를
비우므로 다음 조회가 DB 갱신값을 관찰한다. NearCache를 끈 경로와 다른 namespace의 값은
기존처럼 보존된다.

## 검증

- 수정 전 RED: JDBC/R2DBC 신규 회귀 XML이 각각 `tests=3, skipped=0, failures=2, errors=0`.
  `nearCacheEnabled=true`에서 stale 값이 남았고, 양수 검증 부재로 `count <= 0`가 먼저
  Redis 경로에 도달했다.
- 수정 후 targeted GREEN: JDBC/R2DBC 각각 `5 passing`, XML `skipped=0, failures=0,
  errors=0`. 두 backend의 near on/off, ID·ID 목록·패턴·전체 정리, 다른 namespace 보존,
  잘못된 count, backing failure/cancellation 전파를 확인했다.
- `:bluetape4k-exposed-jdbc-lettuce:detekt`와 `:bluetape4k-exposed-r2dbc-lettuce:detekt`
  통과.
- `checkProductionAbi --no-parallel --no-build-cache --no-configuration-cache` 통과:
  `modules=45/45`, `baselines=45/45`, `actualDumps=45/45`, `emptyBaselines=0`.

공유 Gradle 슬롯을 반환하기 위해 이 lane에서는 수정 후 두 모듈의 full test를 다시
실행하지 않았다. 따라서 전체 모듈 테스트와 hosted CI 결과는 이 lesson의 검증 범위가
아니며, targeted 결과와 ABI·Detekt 결과를 별도로 기록해야 한다.

## 향후 지침

패턴 무효화처럼 두 namespace를 가진 캐시 어댑터를 추가할 때는 backing과 near-cache의
정리 순서, namespace 경계, 삭제 건수의 의미, 예외·취소 전파를 하나의 공통 회귀 corpus로
검증한다. 테스트 fixture는 먼저 실제 인터페이스의 compile probe를 통과시킨 뒤 Redis/H2
재현을 실행하고, `findById` 같은 유사하지만 존재하지 않는 API 호출을 남기지 않는다.
