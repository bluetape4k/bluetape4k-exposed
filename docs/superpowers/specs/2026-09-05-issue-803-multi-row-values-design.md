# #803 multi-row VALUES 설계

## 목적과 범위

Exposed 1.5.0의 multi-row VALUES를 JDBC/R2DBC repository의 `batchInsert`와
`utils/batch` writer에서 명시적으로 선택한다. 기본 동작, 기존 JVM 시그니처,
트랜잭션 소유권을 유지한다. `saveAll`, Spring Batch writer(#805), catalog,
새 의존성, 스키마, job 실행 계약은 변경하지 않는다.

사용자는 2026-09-05 이 범위의 구현·검증·PR 계획을 승인했다.
기준은 `origin/develop`의 `81ab8b0052734b18ece5e3673ffcc0fc285cc10b`이다.

## 근거와 대안

- [1.5.0 batchInsert](https://github.com/JetBrains/Exposed/blob/1.5.0/exposed-jdbc/src/main/kotlin/org/jetbrains/exposed/v1/jdbc/Queries.kt)는 기존 overload와 명시적 `useMultiRowValues` overload를 제공한다.
- [MultiRowValuesInsertStatement](https://github.com/JetBrains/Exposed/blob/1.5.0/exposed-core/src/main/kotlin/org/jetbrains/exposed/v1/core/statements/MultiRowValuesInsertStatement.kt)는 `행 수 × table.columns.size`를 65,535(SQLite 32,766)와 비교한다. 실제 SQL의 bind 수가 아닌 추정치다.
- upstream은 한도를 넘으면 이전 청크를 실행한다. 따라서 단순 플래그 전달은 전체 입력의 사전 거부 계약을 충족하지 않는다.
- 자동 청크 분할은 큰 입력을 수용하지만 중간 실패와 부분 실행 경계를 늘린다. 이번에는 선택하지 않는다.
- 선택: 기존 overload를 유지하고 필수 `useMultiRowValues` 인자를 추가한 overload에서만 입력을 제한 크기로 수집·검증한다. 한도 초과는 바인더와 SQL 실행 전에 `IllegalArgumentException`으로 거부한다.

## API와 실행 계약

1. repository는 기존 `(entities, ignore, shouldReturnGeneratedValues, insertStatement)`를 유지한다. 새 overload는 람다 앞에 필수 `useMultiRowValues`를 추가한다. 기존 positional Boolean의 의미를 바꾸지 않는다.
2. `useMultiRowValues=false`는 기존 overload에 위임한다. 기존 Sequence의 평가 동작도 유지한다.
3. `true`는 먼저 `ignore=true`를 입력 순회·바인더·DB 접근 전에 거부한다(빈 입력도 포함). 허용된 조합은 최대 허용 행 수 + 1개까지만 수집한다. 빈 입력은 DB 접근 없이 빈 결과를 반환한다. 한도는 현재 트랜잭션의 방언을 따른다.
4. writer는 기존 생성자를 보존하는 추가 생성자를 제공한다. JDBC의 `ignore=false`, 생성 값 요청 `true`; R2DBC의 생성 값 요청 `false`를 유지한다.
5. JDBC의 `Dispatchers.VT`와 `transaction`, R2DBC의 `suspendTransaction`을 유지한다. 새 재시도나 예외 래핑을 추가하지 않는다. 바인더는 재실행될 수 있으므로 외부 부작용을 두지 않는다.
6. 한도 초과 사전 거부는 행 수 기반 추정치에 한정한다. 여러 bind를 만드는 SQL 표현식과 드라이버 고유의 더 작은 한도는 호출자가 더 작은 청크로 관리한다. SQL 오류는 트랜잭션을 rollback해야 하며 호출자가 트랜잭션 내부에서 잡고 commit하면 부분 실행을 보존할 수 있다.
7. 반환값은 Exposed가 반환한 `ResultRow`만 매핑한다. repository의 multi-row와 ignore 조합은 방언과 생성 값 요청 여부에 관계없이 `IllegalArgumentException`으로 거부한다. 일반 삽입의 결과·순서를 검증하고 Oracle/MySQL의 generated-key 제한을 문서화한다. `saveAll`은 생성 ID 계약을 그대로 유지한다.
8. 네 모듈 사이에 새 의존성을 추가하지 않기 위해 작은 사전검증은 각 실행 경계에 둔다. 큰 공통 추상화나 새로운 공개 정책 타입은 도입하지 않는다.
9. 사전 거부는 해당 repository 호출에서 INSERT를 실행하지 않는다는 뜻이다. 동일 외부 트랜잭션의 이전 쓰기는 자동 취소하지 않는다. writer는 자체 트랜잭션을 소유하므로 실패한 write의 쓰기는 rollback 대상이며 이전에 성공한 write는 유지한다. 두 경우를 각각 테스트한다.
10. 재시도 횟수와 대상은 기존 Exposed 트랜잭션 설정을 그대로 따른다. 이 API는 별도 재시도를 추가하지 않는다. R2DBC는 실제 Job 취소의 `CancellationException`을 삼키거나 재시도하지 않고 rollback 경로로 전파한다. JDBC blocking 작업의 즉시 중단을 보장하지 않는다. 기존 취소 처리 자체의 광범위한 검증은 #808 범위다.
11. PostgreSQL 부분 충돌은 native 진단 테스트로 upstream의 ID 누락을 고정하고 repository는 SQL 실행 전 거부를 검증한다. 기존 `false` 경로의 ignore 의미는 변경하지 않는다. 일반 입력의 순서를 테스트하되 모든 dialect의 순서를 일반화하지 않는다.
12. writer는 빈 입력 확인 후 `database.dialect`로 한도를 계산하고 자체 트랜잭션 시작 전에 크기를 검증한다. repository는 호출자가 연 현재 트랜잭션에서 검증하되 바인더·INSERT보다 먼저 수행한다.
13. 사전검증 오류는 행 수, 컬럼 수, 한도만 포함한다. 엔티티 값, SQL, bind 값은 오류에 넣지 않는다. 곱셈은 `Long`으로 계산한다.

## 수용 기준과 검증

### 시그니처와 호출 호환성

repository의 기존 JVM descriptor는 Iterable/Sequence, Boolean, Boolean,
Function2이며 R2DBC는 뒤에 Continuation이 추가된다. 이를 삭제하지 않는다.
신규 메서드는 Function2 앞에 Boolean 하나를 추가한다.

```kotlin
fun <D> batchInsert(entities: Iterable<D>, ignore: Boolean = false,
    shouldReturnGeneratedValues: Boolean = true, useMultiRowValues: Boolean,
    insertStatement: BatchInsertStatement.(D) -> Unit): List<E>
// Sequence overload와 suspend R2DBC overload도 같은 인자 순서다.

ExposedJdbcBatchWriter(database, table, ignore = false, bind = bind)
ExposedJdbcBatchWriter(database, table, ignore = false, useMultiRowValues = true, bind = bind)
ExposedR2dbcBatchWriter(database, table, bind = bind)
ExposedR2dbcBatchWriter(database, table, useMultiRowValues = true, bind = bind)

repository.batchInsert(items, false, true, bind) // 기존 의미 그대로
repository.batchInsert(items, useMultiRowValues = true, insertStatement = bind)
```

신규 writer 생성자의 `useMultiRowValues`는 필수 인자다. 기존 생성자와 default-argument
JVM bridge를 보존하고 새 생성자에 `false` 기본값을 추가하지 않는다.

### 방언과 반환값

| 공개 진입점 | ignore 선택 | 생성 값 요청 | 반환 |
|---|---|---|---|
| JDBC repository | 가능, 기본 false | 선택 가능, 기본 true | `List<E>` |
| R2DBC repository | 가능, 기본 false | 선택 가능, 기본 true | `List<E>` |
| JDBC writer | 가능, 기본 false | 기존 true 유지, 선택 API 없음 | `Unit` |
| R2DBC writer | 선택 API 없음, false | 기존 false 유지, 선택 API 없음 | `Unit` |

아래 결과 매핑 계약은 repository의 `List<E>`에 적용한다. writer는 엔티티나 ID를 반환하지 않으며 DB 쓰기 성공/실패만 노출한다.

| 경로 | 계약 |
|---|---|
| 모든 `false` | 기존 Exposed batch 경로에 위임 |
| H2/PostgreSQL, ignore=false, 생성 값 요청=true | 입력별 생성 ID와 데이터 일치 검증 |
| 모든 방언, multi-row=true, ignore=true | repository에서 입력 순회 전에 거부. 충돌 무시가 필요하면 기존 false 경로 사용 |
| Oracle/MySQL 등 미검증 generated-key 조합 | upstream 제한을 그대로 따름; multi-row에서 정확한 생성 ID 대응을 보장하지 않으므로 ID가 필요하면 false 사용 |
| 생성 값 요청=false | ID 등 DB 생성 값을 mapper가 요구하면 실패할 수 있음; 기존 계약 유지 |

한도는 SQLite에만 32,766을 쓰고 다른 모든 방언에는 upstream과 같은 65,535를 쓴다.
`rowCount.toLong() * columnCount.toLong() > limit`이면 `IllegalArgumentException`을
던진다. 오류 메시지는 `rows`, `columns`, `parameterLimit`만 포함한다.
이 조건을 통과해도 SQL 표현식의 추가 bind 또는 더 작은 driver 한도 때문에 DB 오류가
발생할 수 있다. 그 오류는 그대로 전파하며 호출자가 트랜잭션을 rollback하고 청크를 줄인다.

- 기존 positional/trailing lambda 호출 및 JVM ABI 보존.
- JDBC/R2DBC repository와 writer 모두 기본 SQL과 multi-row SQL 비교. SQL의 `VALUES` tuple 수와 실행 관찰 시 parameter-set 수를 별도로 검사한다. 기본 batch 로그 N줄을 N회 DB 실행으로 해석하지 않는다. 실제 네트워크 round-trip이나 성능 배수는 주장하지 않는다.
- empty, single, nullable, 큰 입력, 한도 직전/초과, 1회용 Sequence를 검증한다. 초과 또는 무한 Sequence는 최대 허용 행 수 + 1개만 소비하고 바인더를 호출하지 않아야 한다.
- generated ID, 입력 순서, repository 조합 사전 거부 및 writer 중복 오류 후 rollback을 검증한다.
- H2와 PostgreSQL을 순차 실행한다. DB 실패를 skip으로 숨기지 않는다.
- README 양 언어와 한국어 KDoc에 기본값·한도 추정·generated-key·rollback 경계를 명시한다.

## 위험과 복구

신규 옵션의 입력 수집은 O(허용 행 수) 메모리를 사용한다. 기본 경로에는 수집을 추가하지 않는다.
기존 생성자/메서드 삭제와 Boolean 재해석은 금지한다. 문제가 발견되면 호출자가 옵션을
`false`로 바꾸어 기존 경로로 돌아갈 수 있다. 데이터 마이그레이션은 없다.

## 검증 상태

2026-09-05 계약 변경 승인: PostgreSQL에서 native multi-row 부분 충돌 시 반환 3행 중
ID가 있는 행은 2행임을 JDBC/R2DBC 모두 재현했다. 반환 행 필터링이나 upstream 내부
복제를 도입하지 않고 repository 조합을 조기 거부한다. writer는 엔티티를 반환하지
않으므로 이 조합 제한을 확대하지 않는다. 오류는 고정된 옵션 설명만 포함한다.

기준 writer 테스트: H2 JDBC 4 PASS/1 기존 skip, R2DBC 4 PASS.
`batch-core:jar`의 configuration cache 직렬화 오류 때문에 기준 검증은
`--no-configuration-cache`로 실행했다. #803 코드 수정과 무관한 기존 문제다.
구현 검증과 독립 리뷰는 아직 진행 전이다.
