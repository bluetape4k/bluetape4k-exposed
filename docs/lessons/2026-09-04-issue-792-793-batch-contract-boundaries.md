# Issue #792·#793 배치 계약 경계 lesson

## Context

배치 in-memory 저장소의 재시작 매칭은 `Map.equals`에 의존하고 있어
`Array`·primitive array와 중첩 typed value를 내용 기준으로 비교하지 못했다.
또한 Spring Modulith 실패 게시 조회의 `maxItemsToRead`는 `Long` 계약을
Exposed JDBC의 `Int` limit으로 변환하면서 `Int.MAX_VALUE` 초과 값을 overflow시켰다.

## Decision or Finding

- in-memory `JobExecution`은 public `JobExecution` API를 확장하지 않고 private
  ID-to-hash 인덱스를 둔다. 해시는 공용 `BatchParameterHash` v2 typed-value
  canonical 규칙을 사용하므로 배열·중첩 구조는 내용 기준, map은 순서 무관으로
  매칭하며 caller-owned parameter를 변경하지 않는다.
- `maxItemsToRead`에서 `-1L`은 무제한으로 유지하고, JDBC `limit(Int)`로
  안전하게 표현 가능한 `1..Int.MAX_VALUE`만 변환한다. 그 밖의 양수는 DB에
  도달하기 전에 명시적인 `IllegalArgumentException`으로 거부한다.
- `0`과 `-1` 이외의 음수는 Spring Modulith `FailedCriteria` 생성자 계약에서
  이미 거부되므로 repository 경계와 혼동하지 않는다. 새로운 public signature나
  ABI를 추가하지 않는다.

## Verification

| 검증 | 결과 |
| --- | --- |
| #792 RED 회귀 | 수정 전 새 배열·중첩 값 재사용 기대가 `Expected <2> to equal to <1>`로 실패 |
| #793 RED 회귀 | 수정 전 `Int.MAX_VALUE+1`·`Long.MAX_VALUE`가 overflow된 DB limit으로 `ExposedSQLException` 발생 |
| #792 focused test | H2 in-memory repository 24/24 통과 |
| #793 focused test | H2/PostgreSQL/MySQL_V8 boundary matrix 64/64 통과 |
| affected batch module | 242/242 테스트 통과 (`--rerun-tasks --no-build-cache`) |
| affected modulith module | 76/76 테스트 통과 (`--rerun-tasks --no-build-cache`) |
| Kotlin compile | batch·modulith `compileKotlin`·`compileTestKotlin` fresh compile 통과 |
| Kotlin ABI | batch·modulith `checkKotlinAbi` 통과 |
| static analysis | batch `detektMain`, modulith `detektTest` 통과. batch `detektTest` 19건과 modulith `detektMain` 1건은 수정 전 HEAD의 기존 위반 |
| formatting | `git diff --check` 통과 |

## Future Guidance

1. in-memory persistence에서 구조화된 caller input을 매칭할 때 Kotlin 표준
   `Map.equals`가 배열을 reference equality로 처리하는지 먼저 확인하고,
   public model 확장 대신 공용 canonical hash 또는 별도 private index를 검토한다.
2. 외부 `Long` 조회 상한을 JDBC `Int` API로 내릴 때 sentinel·최솟값·최댓값·
   overflow 경계를 DB 호출 전에 검증하고, 실패 메시지에 원래 값을 보존한다.
3. coroutine cancellation을 도입하는 후속 repository API에서는 조회 상한 검증과
   cancellation 전파를 분리해, 검증 로직이 cancellation을 삼키지 않는지 별도 회귀로
   고정한다.

## Writer DoD

- [x] SPW-01 — #792 배열·중첩 typed-value restart equality/hash 계약을 고정했다.
- [x] SPW-02 — #793 `-1L`, `Int` 경계, overflow 거부 계약을 고정했다.
- [x] SPW-03 — public API/ABI와 caller-owned input 불변 경계를 보존했다.
- [x] SPW-04 — 이슈별 RED와 H2/PostgreSQL/MySQL_V8 회귀 테스트를 남겼다.
- [x] SPW-05 — 동일한 canonical hash·numeric boundary 규칙을 후속 batch 작업의
  설계 입력으로 남겼다.
