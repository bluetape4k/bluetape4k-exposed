# #803 multi-row VALUES 구현 계획

> 실행자는 `executing-plans`로 이 계획을 순서대로 수행한다. DB 테스트는 단일 실행자가 순차 실행한다.

**목표:** 기존 호출 호환성을 유지하면서 네 batch 경로에 명시적 multi-row 옵션과 행 수 사전 거부를 추가한다.

**구조:** 기존 overload/생성자를 보존한다. 새 overload의 false는 기존 경로로 위임하고 true만 bounded 수집 후 Exposed 1.5.0에 전달한다. writer는 기존 트랜잭션 경계를 유지한다.

**기술:** Kotlin/JVM, Exposed 1.5.0, JUnit 5, bluetape4k-assertions, H2/PostgreSQL, 기존 TestDB fixture.

승인된 설계의 제한: 한도는 upstream의 행 수 추정치이지 모든 DB의 실제 bind 한도가 아니다.
SQL Server 등 더 작은 driver 한도와 다중-bind 표현식은 호출자가 청크를 줄여 관리하고,
DB 오류는 그대로 전파한다. 이번 작업은 새로운 dialect 정책 라이브러리를 만들지 않는다.
신규 Boolean은 필수이며, 생략 시 기존 overload/생성자가 선택되어 false 동작을 유지한다.
기존 API의 기본 동작과 신규 인자의 Kotlin 기본값을 혼동하지 않는다.

## 1. repository 계약 테스트

2026-09-05 변경 승인: 아래 PostgreSQL 부분 충돌 반환 수용 조건은 repository의
`useMultiRowValues=true + ignore=true` 조기 거부로 대체한다. Iterable/Sequence,
빈 입력, 생성 값 요청 true/false 모두 입력 순회·바인더·INSERT가 0인지 검증한다.
기존 false 경로와 writer의 ignore 계약은 그대로 유지한다.
native 결함 재현 테스트는 원인 확인용으로 별도 유지한다.

생성 파일:

- `exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/repository/JdbcRepositoryMultiRowValuesTest.kt`
- `exposed/r2dbc/src/test/kotlin/io/bluetape4k/exposed/r2dbc/repository/R2dbcRepositoryMultiRowValuesTest.kt`

각 파일은 private LongIdTable(id, 고유 name, nullable note), Record, LongJdbcRepository 또는 LongR2dbcRepository 구현을 포함한다. 기존 `withTables`/`TestDB`를 사용한다.

- [ ] 기존 positional 호출과 신규 named 호출을 아래 형태로 추가한다. R2DBC는 `runSuspendIO`에서 실행한다.

```kotlin
val rows = listOf(Record(name = "first", note = null), Record(name = "second", note = "note"))
val saved = repository.batchInsert(rows, useMultiRowValues = true) { row ->
    this[Rows.name] = row.name
    this[Rows.note] = row.note
}
saved.map { it.name } shouldBeEqualTo rows.map { it.name }
saved.map { it.note } shouldBeEqualTo rows.map { it.note }
saved.all { it.id > 0 }.shouldBeTrue()
```

- [ ] parameter-set 수와 SQL tuple 수를 관찰한다. `SqlLogger.log`의 `context.args.count()`와 `context.sql(transaction)`을 함께 기록한다. 기본 경로는 행별 context, multi-row는 한 context에 전체 인자를 담는지 검사한다. 로그 수를 네트워크 실행 수로 부르지 않는다.
- [ ] empty, single, Sequence.constrainOnce, false 명시, 큰 입력을 추가한다. 3컬럼 테이블 기준 `65535 / 3` 행이 허용되고 다음 행은 바인더 호출 전에 거부되는지 검사한다.
- [ ] 무한 Sequence 소비 수가 최대 행 수 + 1인지 검사한다. 사전 거부 이후 같은 외부 트랜잭션의 선행 삽입은 유지되는지 확인한다.
- [ ] PostgreSQL에서 기존 충돌 행과 신규 행을 섞고 반환 ID로 재조회하여 실제 삽입 행만 정확히 반환하는지 검사한다. H2 ignore는 별도 제한 테스트로 분리한다.
- [ ] 생성 값 요청=false는 명시 ID를 바인딩한 경우를 테스트한다. mapper가 생성 값을 요구하지 않도록 한다. 반환 리스트와 입력의 일치를 전제하지 않고 별도 SELECT로 저장된 행을 확인한다. ignore도 ID별 재조회로 확인하며 입력과 결과를 zip하지 않는다.

RED 명령:

```bash
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-jdbc:test --tests '*RepositoryMultiRowValuesTest' :bluetape4k-exposed-r2dbc:test --tests '*RepositoryMultiRowValuesTest' --no-configuration-cache --no-build-cache --console=plain
```

예상: 신규 overload 부재로 compileTestKotlin 실패. 오류 원인이 정확히 `useMultiRowValues` 부재인지 확인한다.

## 2. repository 구현

신규 true overload는 `require(!ignore)`를 입력 iterator 생성 전에 실행한다.
기존 false 위임은 이 검사보다 먼저 유지한다. 이 변경과 README/KDoc/수용 기준을
함께 검증한 뒤 보류된 writer·전체 테스트·ABI·리뷰·PR 단계를 재개한다.

수정 파일:

- `exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/JdbcRepository.kt`
- `exposed/r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/repository/R2dbcRepository.kt`

- [ ] 기존 메서드 아래에 Iterable/Sequence overload를 추가한다. Boolean 위치는 `(entities, ignore=false, shouldReturnGeneratedValues=true, useMultiRowValues, insertStatement)`다.
- [ ] false는 기존 메서드 호출로 즉시 반환한다. true는 입력 iterator를 한 번 얻고 empty를 먼저 반환한다. `currentDialect`와 `table.columns.size`로 허용 행 수를 계산한다.

```kotlin
val limit = if (currentDialect is SQLiteDialect) 32_766 else 65_535
val rows = entities.take(limit / table.columns.size.coerceAtLeast(1) + 1).toList()
require(rows.size.toLong() * table.columns.size.toLong() <= limit) {
    "Multi-row VALUES limit exceeded: rows=${rows.size}, columns=${table.columns.size}, parameterLimit=$limit"
}
return table.batchInsert(rows, useMultiRowValues = true, ignore = ignore,
    shouldReturnGeneratedValues = shouldReturnGeneratedValues, body = insertStatement).map { it.toEntity() }
```

위 검증은 private helper에서 Iterable/Sequence 간 재사용한다. 입력을 두 번 순회하지 않는다. R2DBC는 suspend mapper와 cancellation 전파를 유지한다. helper를 위한 새 의존성은 추가하지 않는다.

- [ ] 1번 명령을 재실행하고 GREEN을 확인한다. H2의 미지원 ignore는 제한 사례로만 기록한다.

## 3. writer 테스트와 구현

생성 테스트:

- `utils/batch/jdbc/src/test/kotlin/io/bluetape4k/batch/jdbc/JdbcBatchWriterMultiRowValuesTest.kt`
- `utils/batch/r2dbc/src/test/kotlin/io/bluetape4k/batch/r2dbc/R2dbcBatchWriterMultiRowValuesTest.kt`

수정 구현:

- `utils/batch/jdbc/src/main/kotlin/io/bluetape4k/batch/jdbc/ExposedJdbcBatchWriter.kt`
- `utils/batch/r2dbc/src/main/kotlin/io/bluetape4k/batch/r2dbc/ExposedR2dbcBatchWriter.kt`

- [ ] 기존 생성자 호출과 새 `useMultiRowValues=true` 생성자 호출 테스트를 먼저 추가한다. `withTables(configure = { sqlLogger = observer })`로 writer의 새 트랜잭션에서도 SQL과 argument context를 관찰한다.
- [ ] empty/single/nullable/큰 입력/한도 경계, 기존 성공 write 이후 초과 write를 테스트한다. 중복 오류는 기존 dup 행을 commit한 뒤 `[new, dup, another-new]`를 한 write로 전달하고 예외 뒤 count=1 및 두 신규 키 부재를 확인한다. JDBC/R2DBC 모두 같은 데이터로 검증한다.
- [ ] R2DBC 취소는 바인더 진입 callback에서 실제 child Job을 취소하여 SQL 실행 전이라는 지점을 고정한다. 바인더 진입 횟수, child 취소, 미삽입을 확인한다. 서버 실행 중 취소·커넥션 cleanup 전체 검증은 #808에 남기며 이번 테스트를 그 증거로 주장하지 않는다.
- [ ] 아래 RED 명령으로 신규 생성자 부재를 확인한다.

```bash
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-batch-jdbc:test --tests '*WriterMultiRowValuesTest' :bluetape4k-exposed-batch-r2dbc:test --tests '*WriterMultiRowValuesTest' --no-configuration-cache --no-build-cache --console=plain
```

- [ ] 기존 생성자를 secondary constructor로 유지하고 새 필수 Boolean 생성자로 위임한다. JDBC 기존 descriptor `(Database, Table, Boolean, Function2)` 및 default bridge를 유지하고 R2DBC `(R2dbcDatabase, Table, Function2)`도 유지한다.
- [ ] empty 반환 후 `database.dialect`로 한도를 선택하고 `items.size.toLong() * table.columns.size.toLong()`을 검사한다. 이 검사는 트랜잭션과 바인더 이전이다.
- [ ] JDBC는 기존 `ignore`와 생성 값 요청=true, R2DBC는 생성 값 요청=false를 전달한다. 기존 dispatcher/transaction 블록을 유지하고 새 catch/retry를 추가하지 않는다.
- [ ] 같은 명령으로 GREEN 확인 후 기존 writer 테스트도 함께 실행한다.

## 4. PostgreSQL과 모듈 검증

- [ ] 건강한 Colima/Docker 상태와 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` 상속을 확인한다. VM을 재시작하지 않는다.
- [ ] 위 두 명령을 `EXPOSED_TEST_DB=POSTGRESQL --max-workers=1` 조건으로 순차 실행한다(환경변수와 Gradle 옵션 위치는 각각 셸 prefix와 Gradle 인자로 둔다). 저장소의 TestMutexService와 단일 Gradle 프로세스로 DB task 중첩을 차단한다. JDBC/R2DBC 부분 ignore 결과를 조회 결과와 비교한다. 오류나 skip을 성공으로 보고하지 않는다.
- [ ] 네 모듈의 전체 test를 H2로 순차 실행한다. 필요한 compile, detekt, ABI dump/check를 실제 Gradle task 목록에서 확인한 뒤 실행한다.
- [ ] API dump는 기존 줄 삭제가 없는지 검사한다. 새 overload/default bridge만 추가되었는지 확인하고 기존 호출부가 컴파일되는지 확인한다.
- [ ] `javap -classpath <생성 jar> io.bluetape4k.batch.jdbc.ExposedJdbcBatchWriter`로 기존 생성자와 `(Database, Table, boolean, Function2, int, DefaultConstructorMarker)` bridge가 남았는지 확인한다. R2DBC 기존 `(R2dbcDatabase, Table, Function2)` 생성자도 같은 방식으로 검사한다. 기준 descriptor는 기존 `api/bluetape4k-exposed-batch-{jdbc,r2dbc}.api`에서 확인했다.
- [ ] `saveAll`은 비목표다. 해당 구현과 API를 변경하지 않았음을 diff로 검사하고 기존 repository 전체 테스트로 생성 ID 순서 회귀를 확인한다.
- [ ] 오류 발생 시 targeted 테스트 → 전체 affected module 검증 순으로 다시 실행한다. `batch-core:jar` configuration cache 문제는 이슈 범위 밖으로 보존하고 검증 옵션을 기록한다.

## 5. 문서·리뷰·전달

- [ ] `utils/batch/README.md`, `utils/batch/README.ko.md`, `exposed/jdbc/README.md`, `exposed/jdbc/README.ko.md`, `exposed/r2dbc/README.md`, `exposed/r2dbc/README.ko.md`에 opt-in 예시와 설계의 방언/반환값/한도/rollback 계약을 반영한다. 코드 KDoc은 한국어다.
- [ ] 생성 키가 필요한 MySQL/Oracle 미검증 조합은 `false` 권고, PostgreSQL ignore 결과와 입력의 zip 금지, O(허용 행 수) 참조 수집 비용을 명시한다.
- [ ] `git diff --check`와 한국어 terminology audit를 실행하고 문서의 식별자·수치·명령을 소스와 대조한다.
- [ ] 독립 6관점 리뷰와 통합 리뷰에서 P0/P1을 해결한다. spec/plan/실제 테스트 결과의 수용 기준을 대조한다.
- [ ] 공식 소스 조사 결과를 wiki research에 보존하고 reusable lesson을 이 저장소에 기록한다. scope 밖 수정은 하지 않는다.
- [ ] 한국어 Lore commit 후 `bluetape4k/bluetape4k-exposed`, base `develop`, head `feat/issue-803-multi-row-values` PR을 생성한다. assignee debop, milestone 2.1.0, #803 metadata, 마지막 `## DoD Status`를 확인한다.
- [ ] exact-head CI와 리뷰 상태를 확인하고 머지 준비 DoD를 보고한다. 추가 인간 reviewer 부재는 1인 개발 저장소의 blocker가 아니다. 머지는 새 exact-head 승인 후에만 수행한다.

## 복구와 완료 기준

### 2026-09-05 구현 중간 검증

1. 완료: repository 신규 overload의 RED를 `No parameter with name 'useMultiRowValues' found`로 확인했다.
2. 완료: 기존 overload를 보존한 구현 후 H2 JDBC 7개/R2DBC 7개 계약 테스트가 통과했다.
3. 실패: H2+PostgreSQL 재실행은 28개 중 26개 통과, 부분 ignore 계약 2개 실패였다.
   JDBC/R2DBC 모두 `id is not in record set`으로 실패했다. 실패 테스트는 그대로 보존한다.
4. 완료: mapper를 제외한 native 호출도 PostgreSQL에서 반환 3행 중 ID가 있는 행이
   2행임을 재현했다. H2 미지원 사례와 합친 진단 4개가 통과했다(기능 수용 성공 아님).
5. 대기: writer 테스트 초안을 추가하고 두 모듈의 compileTestKotlin 실패를 확인했다.
   신규 생성자의 `useMultiRowValues` 부재 오류가 있다. production writer는 아직 수정하지
   않았다. 테스트 초안의 SQL 정규식/nullable 커버리지도 구현 재개 시 검토해야 한다.
6. 대기: 부분 ignore 처리 계약 결정, 전체 모듈/ABI/리뷰/PR. 사전 거부 또는 upstream
   결과 처리 보강은 승인된 '실제 삽입 행만 반환' 계약의 변경이므로 임의 적용하지 않는다.

공식 `InsertBlockingExecutable.processResults`와 `InsertSuspendExecutable.processResults`는
행별 영향 개수를 얻지 못하면 원래 입력을 유지한 뒤 반환 값을 인덱스로 결합한다.
`MultiRowValuesInsertBlockingExecutable.isAlwaysBatch=false` 경로에서는 부분 충돌의
입력 개수와 실제 반환 개수가 달라질 수 있다. 자세한 출처와 판단은 wiki의
`research/2026-09-05-exposed-150-multi-row-ignore.md`에 기록했다.
조사 기록은 wiki `908c4bb`로 commit/push했고 원격 일치를 확인했다.
`git diff --check`는 통과했다. 노출된 실패를 유지하며 feature branch는 아직 push하지 않았다.

모든 변경은 해당 worktree에 제한한다. 기존 #771 작업은 보존한다. 신규 옵션을 끄면 이전 동작으로 복귀하며 스키마 복구 작업은 없다. CI·테스트 미완료는 PENDING으로 남긴다. 구현 완료는 테스트/ABI/문서/독립 리뷰 증거가 모두 필요하고, 전체 DONE은 별도 머지 승인·머지·정리 이후다.
