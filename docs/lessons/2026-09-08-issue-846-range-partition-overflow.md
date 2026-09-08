# #846 범위 파티셔닝에서 Long 경계 산술을 보존한다

## 실패 원인

`ExposedRangePartitioner`는 `max - min + 1`과 파티션 경계를 `Long`으로
계산했습니다. `Long.MIN_VALUE`부터 `Long.MAX_VALUE`까지의 개수는 `2^64`이므로
`Long`에 표현할 수 없고, overflow 결과에 따라 전체 범위가 하나의 파티션으로
퇴화할 수 있었습니다. 생성자의 0 이하 `gridSize`와 `min > max` 조회 결과도
명확히 거부하지 않았습니다.

## 결정

범위 개수와 중간 경계를 JDK의 `BigInteger`로 계산한 뒤 최종 파티션 값만
`Long`으로 변환합니다. 유효한 결과는 항상 원래 `Long` 범위 안에 있으므로
`longValueExact()`로 변환 오류를 즉시 드러냅니다.

생성자 `gridSize`는 양수만 허용합니다. 기존 Spring Batch 호출 계약은 유지하여
`partition(0)` 또는 `partition(음수)`는 생성자에서 설정한 양수 grid size를
사용합니다. 조회 결과에서 `min > max`가 발생하면 빈 파티션으로 숨기지 않고
`IllegalArgumentException`으로 호출자에게 알립니다.

## 검증

`ExposedRangePartitionerTest`에 다음 경계를 고정했습니다.

- `Long.MIN_VALUE..Long.MAX_VALUE`를 4개로 나눌 때 정확한 경계
- `Long.MAX_VALUE` 인근의 3개 값과 grid size 보정
- 음수에서 양수로 이어지는 범위의 연속성·끝점
- `min > max`와 생성자 `gridSize <= 0` 입력 검증

- `partition(0/-1)`이 양수 생성자 grid size를 사용하는 fallback 호환성

H2, PostgreSQL, MySQL_V8에서 targeted test 28개를 포함한 Spring Batch 모듈
전체 115개가 실패·오류·skip 없이 통과했습니다. 모듈 `detekt`와
`checkKotlinAbi`도 통과했습니다.

## 재발 방지

포함 범위 개수를 `Long`으로 계산하는 range partitioner는 경계에서 overflow할
수 있으므로, 차이·증분·곱셈을 포함한 중간 산술은 표현 범위가 충분한 타입으로
수행합니다. 경계값 테스트는 실제 DB aggregate 결과에 의존하지 않고
`selectMinMax` 훅으로 모든 활성 방언에서 동일한 순수 분할 계산을 검증합니다.
