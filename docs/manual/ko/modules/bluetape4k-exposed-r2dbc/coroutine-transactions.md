---
title: 코루틴 트랜잭션
description: R2DBC 작업을 호출자가 소유한 suspendTransaction 안에서 실행하고 트랜잭션 문맥을 명확히 유지합니다.
manualId: bluetape4k-exposed-r2dbc
chapterId: coroutine-transactions
---

# 코루틴 트랜잭션

저장소 메서드가 `suspend`라고 해서 애플리케이션 트랜잭션이 저절로 생기지는 않는다. 호출자가 `R2dbcDatabase`를 고르고 `suspendTransaction`을 연 뒤, 어느 작업까지 한꺼번에 성공하거나 실패해야 하는지 정해야 한다.

## 트랜잭션은 호출자가 소유한다

```kotlin
suspend fun renameActor(
    database: R2dbcDatabase,
    repository: ActorRepository,
    id: Long,
    newName: String,
) = suspendTransaction(db = database) {
    val actor = repository.findById(id)
    repository.updateById(id) { it[ActorTable.name] = newName }
    actor.copy(name = newName)
}
```

`R2dbcRepository`는 Exposed statement를 실행하지만 데이터베이스를 숨기거나 메서드마다 새 트랜잭션을 열지 않는다. 여러 읽기와 쓰기를 하나로 묶어야 하는 이유는 서비스 계층이 가장 잘 안다. 그래서 업무 규칙을 소유한 서비스가 트랜잭션 경계도 소유하는 편이 안전하다.

## Flow는 트랜잭션 안에서 끝까지 수집한다

`findAll`, `findBy`, `findByField` 같은 조회 함수는 `Flow<E>`를 반환한다. Flow를 만드는 시점과 쿼리 결과를 소비하는 시점은 다를 수 있으므로 `collect`, `toList`, terminal operator를 트랜잭션 안에서 실행한다.

```kotlin
suspend fun activeActors(database: R2dbcDatabase): List<ActorRecord> =
    suspendTransaction(db = database) {
        ActorRepository()
            .findBy { ActorTable.active eq true }
            .toList()
    }
```

트랜잭션 밖으로 Flow를 반환한 뒤 나중에 수집하면 쿼리를 만들 때 사용한 트랜잭션과 connection 문맥이 이미 끝났을 수 있다.

## 코루틴 문맥을 벗어나지 않는다

DB 작업은 트랜잭션을 받은 structured scope 안에 둔다. 별도의 application scope로 작업을 날리면 호출자 취소와 트랜잭션 소유권이 분리된다. 병렬 DB 작업도 connection pool 용량과 트랜잭션 모델을 먼저 정해야 한다. 트랜잭션 하나를 여러 작업이 자유롭게 공유하는 실행 큐처럼 다루지 않는다.

`virtualThreadTransactionAsync`는 현재 coroutine context에 child `async`를 붙이고 선택한 dispatcher에서 `suspendTransaction`을 실행한다. 호출자가 넘긴 `ExecutorService`는 계속 호출자 소유이며 helper가 닫지 않는다.

## 격리 수준과 재시도

기본값과 다른 계약이 필요하면 `transactionIsolation`과 `readOnly`를 명시한다. 트랜잭션 재시도는 insert, 외부 호출, 이벤트 발행을 중복시킬 수 있다. 1.12.1 테스트 helper는 원인을 분명하게 드러내기 위해 `maxAttempts = 1`로 실행한다.

## 장애를 찾는 순서

| 증상 | 먼저 확인할 것 |
| --- | --- |
| 반환한 Flow를 수집할 때 쿼리 실패 | 수집이 `suspendTransaction` 밖으로 나갔는지 |
| 업무 데이터가 일부만 반영됨 | 필요한 statement가 같은 호출자 소유 트랜잭션에 있는지 |
| connection 획득 timeout | 동시 요청 수, pool 크기, connection 점유 시간, 중첩 트랜잭션 |
| 취소가 업무 오류로 기록됨 | broad catch와 `CancellationException` 재전파 여부 |
| 종료 뒤 executor thread가 남음 | executor와 pool을 만든 component가 닫는지 |

이 모듈만 보고 rollback이나 connection 반환 순서를 단정하면 안 된다. JetBrains Exposed, R2DBC driver, connection pool, framework lifecycle이 함께 결정하는 동작이다. 운영에서 쓸 조합으로 취소와 종료 경로를 직접 검증한다.

## 근거 자료

- [`R2dbcRepository.kt`](../../../../../exposed/r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/repository/R2dbcRepository.kt)
- [`VirtualThreadTransaction.kt`](../../../../../exposed/r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/VirtualThreadTransaction.kt)
- [`withDb.kt`](../../../../../exposed/r2dbc-tests/src/main/kotlin/io/bluetape4k/exposed/r2dbc/tests/withDb.kt)
- [`exposed/r2dbc/README.ko.md`](../../../../../exposed/r2dbc/README.ko.md)

다음은 [R2DBC 저장소 패턴](./repository-patterns.md)과 [취소·실패·테스트](./cancellation-and-testing.md)다.
