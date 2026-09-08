# Issue #840 Write-behind retry 항목별 상태 lesson

## 배경

JDBC 동기 `ExposedLettuceLoadedMap`과 suspend
`ExposedLettuceSuspendedLoadedMap`의 write-behind flush는 한 batch에 서로
다른 retry count를 가진 항목이 들어올 수 있습니다. 기존 구현은 batch 첫
항목의 count를 전체 항목에 적용해, fresh 항목을 조기에 dead-letter로
보내거나 이미 실패한 항목의 retry 상태를 잘못 계산했습니다.

## 결정

- retry count는 batch가 아니라 각 `Triple<K, V, Int>` queue entry가 소유합니다.
- flush 실패 시 각 entry의 `retryCount + 1`을 독립적으로 계산합니다.
- 자체 retry 한도에 도달했거나 재시도 queue/channel에 다시 넣지 못한
  항목만 dead-letter로 보냅니다.
- 동기 queue, suspend channel의 기존 I/O 및 backpressure 경계를 유지하고,
  suspend 경로의 `CancellationException` 재전파도 유지합니다.
- R2DBC 구현은 이미 같은 항목별 정책을 따르므로 수정하지 않았습니다.

## 결과

retry count가 다른 항목이 섞인 batch도 queue/channel에서 다시 시도할 항목과
dead-letter로 보낼 항목을 독립적으로 결정합니다. 따라서 한 항목의 실패
횟수가 같은 batch의 fresh 항목을 조기에 폐기하거나 재시도 상태를 덮어쓰지
않습니다.

## 검증

- RED 동기 (`/private/tmp/840-jdbc-red-sync.log`): 기존 구현에서 혼합 retry batch 후 fresh 항목의 다음 writer 호출이
  발생하지 않아 `새 항목이 재시도 후 성공하지 않았습니다.`로 실패했습니다.
- RED suspend (`/private/tmp/840-jdbc-red-suspend.log`): 혼합 retry 테스트가 fresh 항목만 남아야 한다는 기대와 달리
  `[fresh,retried]`가 함께 dead-letter되어 실패했습니다. 같은 클래스의
  queue 포화 및 cancellation 테스트는 통과했습니다.
- GREEN 동기 (`/private/tmp/840-jdbc-green-sync.log`):
  `./gradlew :bluetape4k-exposed-jdbc-lettuce:test --tests "io.bluetape4k.exposed.lettuce.map.ExposedLettuceLoadedMapRetryTest" --no-build-cache`
  결과 2 passing, `BUILD SUCCESSFUL`.
- GREEN suspend (`/private/tmp/840-jdbc-green-suspend.log`):
  `./gradlew :bluetape4k-exposed-jdbc-lettuce:test --tests "io.bluetape4k.exposed.lettuce.map.ExposedLettuceSuspendedLoadedMapRetryTest" --no-build-cache`
  결과 3 passing, `BUILD SUCCESSFUL`.
- suspend 테스트의 writer 시작 신호 대기는 `withTimeout(5_000)`으로 제한해
  writer가 시작되지 않는 회귀가 무기한 대기하지 않도록 했습니다.
- 최종 회귀 재검증 (`/tmp/exposed-847-train.Psbxx1/840-final-retry.log`): sync 2개와
  suspend 3개를 합쳐 5 passing, `detekt`, `checkKotlinAbi`, `BUILD SUCCESSFUL`.
- 영향 범위 검증 (`/tmp/exposed-847-train.Psbxx1/840-final.log`):
  `jdbc-lettuce` 테스트 68 passing, 28 pending, `detekt`, `checkKotlinAbi`,
  `BUILD SUCCESSFUL`.
- JUnit XML `exposed/jdbc-lettuce/build/test-results/test/`에서 sync 2개와
  suspend 3개 모두 `skipped="0"`, `failures="0"`, `errors="0"`을 확인했습니다.
- `git diff --check`: PASS.
- develop 통합 후 전체 모듈 재검증은 682개 통과, 조건부 skip 60개,
  실패·오류 0개였다. 새 재시도 회귀 5개는 모두 skip 없이 통과했고 detekt·ABI도
  통과했다. 근거: `/tmp/exposed-847-856-integration.log` 및 JUnit XML.
  skip에는 MySQL 격리 수준, 캐시 모드·AutoInc 제약, StructuredTaskScope
  런타임 부재 등 기존 assumption이 포함되며 성공으로 집계하지 않았다.

## 향후 지침

develop 통합 시 두 README의 같은 위치에 추가된 재시도 설명과 #841의
NearCache 무효화 설명을 모두 유지했다. #842의 동기 캐시 기능 제한도 보존했다.
독립 아키텍처 리뷰에서 재시도 상태와 캐시 무효화·기능 제한의 소유 경계가
분리되어 있음을 확인했다. 충돌 해결 시 한쪽 설명만 선택하지 않는다.

write-behind retry 또는 dead-letter 정책을 변경할 때는 서로 다른 retry count의
항목을 한 batch에 섞고 queue/channel 순서를 바꾸는 회귀 테스트를 먼저
고정합니다. retry count를 batch-level 변수나 첫 항목에서 추론하지 말고,
retry 한도·재큐잉 포화·dead-letter·cancellation 결과를 항목별로 검토합니다.
동기 JDBC와 suspend 구현을 함께 확인하되, R2DBC의 기존 항목별 invariant를
깨뜨리지 않는지 별도로 확인합니다.
