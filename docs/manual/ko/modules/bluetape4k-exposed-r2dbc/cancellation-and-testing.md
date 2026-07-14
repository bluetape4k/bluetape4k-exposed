---
title: 취소, 실패, 테스트
description: 코루틴 취소를 그대로 전파하고 실제 R2DBC driver로 트랜잭션 동작을 검증합니다.
manualId: bluetape4k-exposed-r2dbc
chapterId: cancellation-and-testing
---

# 취소, 실패, 테스트

취소는 호출자 포기, timeout, 애플리케이션 종료를 알리는 제어 신호다. 이를 일반 저장소 오류처럼 처리하면 이미 끝난 요청을 재시도하거나 fallback write를 실행할 수 있다.

## broad catch에서는 취소를 먼저 돌려보낸다

```kotlin
suspend fun loadActor(id: Long): ActorRecord? = try {
    suspendTransaction(db = database) {
        repository.findByIdOrNull(id)
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    logger.warn(e) { "actor lookup failed: id=$id" }
    null
}
```

가능하면 애플리케이션이 실제로 복구할 수 있는 예외만 좁게 잡는다. broad catch가 꼭 필요하다면 `CancellationException`을 먼저 재전파한 뒤 나머지 실패를 처리한다.

## 취소만으로 알 수 없는 것

호출자 취소는 코루틴 중단을 요청한다. 하지만 driver가 진행 중인 statement를 언제 취소하는지, DB가 취소를 언제 관찰하는지, rollback이 언제 끝나는지, pooled connection이 언제 다시 빌릴 수 있는 상태가 되는지까지 증명하지는 않는다. 이 동작은 Exposed, R2DBC driver, pool, DB 서버에 걸쳐 있다.

그래서 고정된 종료 순서를 문서에 적기보다 관찰 가능한 결과를 테스트한다.

- 호출자가 fallback 결과가 아니라 취소를 받는가
- 중단된 write가 허용되지 않은 부분 상태를 남기지 않는가
- pool의 사용 중 connection 수가 기대한 값으로 돌아오는가
- 다음 트랜잭션이 connection을 얻어 정상 실행되는가
- 애플리케이션이 정한 시간 안에 종료되는가

## 빠른 테스트와 실제 DB 테스트를 나눈다

매핑과 기본 저장소 동작은 H2로 빠르게 확인한다. SQL 문법, 격리 수준, 생성 ID, conflict 처리, 취소, pool lifecycle은 운영 DB의 R2DBC driver와 Testcontainers로 검증한다.

```kotlin
class ActorRepositoryTest : AbstractExposedR2dbcTest() {
    @Test
    fun `배우를 저장하고 다시 읽는다`() = runTest {
        withTables(TestDB.POSTGRESQL, ActorTable) {
            val repository = ActorRepository()
            val ids = repository.saveAll(listOf(ActorRecord(name = "Ada")))
            repository.findById(ids.single()).name shouldBeEqualTo "Ada"
        }
    }
}
```

공용 `withDb` helper는 같은 `TestDB`를 쓰는 테스트를 직렬화하고 `maxAttempts = 1`인 `suspendTransaction`을 연다. 임시 DB 설정을 사용했다면 이전 참조로 되돌린다. `withTables`는 table을 만들고 `finally`에서 정리를 시도한다. 이 테스트 helper의 동작을 운영 애플리케이션 종료 순서로 일반화하면 안 된다.

## 취소 테스트는 barrier로 재현한다

무작위 `delay`에 기대지 말고 취소할 경계에 deterministic barrier를 둔다. statement가 해당 지점에 도착한 것을 확인한 뒤 caller를 취소하고 DB와 pool의 관찰 가능한 상태를 검사한다. DB와 pool을 공유하는 테스트이므로 순차 실행한다.

```kotlin
val job = launch {
    suspendTransaction(db = database) {
        repository.runBlockedOperation(started)
    }
}

started.await()
job.cancelAndJoin()
assertTrue(job.isCancelled)
```

어떤 작업을 block할 수 있는지는 driver마다 다르다. 선택한 driver와 DB가 안정적으로 보여 주는 현상만 assertion으로 사용한다.

## 실패별 대응

| 실패 | 애플리케이션 대응 |
| --- | --- |
| 매핑 또는 constraint 오류 | 그대로 전파하거나 서비스 경계에서 한 번만 변환 |
| pool 획득 timeout | pool 압력을 기록하고 명시한 정책이 있을 때만 재시도 |
| caller 취소 | 취소를 재전파하고 일반 repository error로 세지 않음 |
| connection 단절 | DB 계약으로 확인하기 전까지 commit 결과를 모르는 상태로 취급 |
| 테스트 정리 실패 | fixture를 실패 처리하고 오염된 상태로 다음 테스트를 실행하지 않음 |

## 근거 자료

- [`AbstractExposedR2dbcTest.kt`](../../../../../exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/AbstractExposedR2dbcTest.kt)
- [`withDb.kt`](../../../../../exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/withDb.kt)
- [`withTables.kt`](../../../../../exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/withTables.kt)
- [`TestDB.kt`](../../../../../exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/TestDB.kt)

두 실행 모델을 함께 비교하려면 [JDBC와 R2DBC 선택 가이드](../../guides/jdbc-vs-r2dbc.md)로 이어 간다.
