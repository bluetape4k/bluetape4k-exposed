# #823: keyset cursor는 정렬 순서를 모두 저장해야 한다

## 실패 원인

`ExposedKeysetItemReader`의 generic 생성자는 고유하지 않은 `Long` 컬럼도 받지만,
기존 조회와 checkpoint는 마지막 컬럼 값 하나만 사용했다. 정렬 key가 `[1, 1, 1, 2]`이고
`pageSize=2`이면 첫 page 뒤의 조건이 `column > 1`이 되어 세 번째 `1`을 건너뛴다.
restart해도 checkpoint에는 `1`만 남으므로 누락된 행을 복구할 수 없다.

## 결정

- 중복 가능한 컬럼은 `(column, LongIdTable.id)`를 total order로 사용한다.
- 다음 조회 조건은 `column > lastKey OR (column = lastKey AND id > lastId)`로 만든다.
- 조회 순서와 같은 두 값을 `ExecutionContext`에 저장한다.
- 기존 single-column 생성자는 JVM ABI를 유지하되 lookahead에서 중복을 발견하면 실패한다.
- 복합 cursor factory는 tie-breaker가 없는 기존 checkpoint를 거부한다. 불완전한 cursor로
  재개해서 누락을 숨기는 것보다 job operator가 restart 정책을 선택하게 하는 편이 안전하다.

`OFFSET` pagination은 제외했다. 큰 page 번호에서 선행 행을 다시 훑고, 동시에 변경되는 데이터에
대해 cursor 의미도 약해진다. `(column, id)` 복합 인덱스를 정의하고 같은 순서로 정렬하면 기존
keyset 접근의 인덱스 친화적인 query shape을 유지할 수 있다.

## 재발 방지

keyset reader를 추가하거나 확장할 때 다음 항목을 함께 검증한다.

- 정렬식이 중복 없는 total order인지 확인한다.
- `WHERE`, `ORDER BY`, checkpoint가 같은 cursor 구성 요소와 순서를 사용하는지 확인한다.
- 같은 첫 번째 key가 page와 chunk 경계를 넘는 fixture로 전체 행과 restart 결과를 검증한다.
- 생성 SQL이 복합 인덱스 순서로 정렬되며 `OFFSET`을 사용하지 않는지 주요 dialect에서 확인한다.
- cursor 구성 요소의 값을 순회 중 변경할 수 있다면 별도의 consistency 정책을 먼저 정한다.

## 검증 범위

H2, PostgreSQL, MySQL에서 duplicate page, checkpoint restart, legacy checkpoint 거부,
single-column fail-fast, 복합 `ORDER BY`, `OFFSET` 부재를 검증했다. Detekt와 Kotlin ABI 검사도
통과했다. 동시 update 중 일관된 읽기는 이번 변경의 범위가 아니며, 호출자는 cursor 값을
순회 중 변경하지 않아야 한다.
