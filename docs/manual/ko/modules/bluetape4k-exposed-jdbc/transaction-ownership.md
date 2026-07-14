---
title: JDBC 트랜잭션 소유권
description: JDBC 트랜잭션을 호출자가 소유하고 연결, 격리 수준, 재시도, DAO 변환 경계를 명확히 정하는 방법을 설명합니다.
manualId: bluetape4k-exposed-jdbc
chapterId: transaction-ownership
---

# JDBC 트랜잭션 소유권

`JdbcRepository`는 현재 Exposed JDBC 트랜잭션에서 쿼리를 실행합니다. 호출자를 대신해 트랜잭션을 열거나 commit하고 닫지 않습니다. 애플리케이션 서비스나 프레임워크 어댑터가 경계를 소유하므로 원자성, 격리 수준, 재시도 정책, 연결을 풀에 돌려주는 시점도 그 계층에서 정해야 합니다.

## 기본 경계

```kotlin
fun updateProduct(id: Long, command: UpdateProduct): ProductRecord =
    transaction(database) {
        val entity = ProductEntity.findById(id) ?: error("Product not found: $id")
        entity.name = command.name
        entity.price = command.price
        entity.toRecord()
    }
```

중요한 것은 중괄호의 위치가 아닙니다. 연관된 읽기와 쓰기, DAO를 레코드로 바꾸는 작업까지 `transaction`이 끝나기 전에 마쳐야 합니다. Exposed DAO 엔티티를 그대로 반환하면 트랜잭션에 묶인 상태가 웹이나 메시징 계층으로 새어 나갑니다.

## 계층별 책임

| 계층 | 책임 |
|---|---|
| Controller/consumer | 입력을 해석하고 완성된 결과를 반환 |
| 애플리케이션 서비스 | 업무 트랜잭션 경계와 재시도 의미 결정 |
| Exposed `transaction` | `JdbcTransaction`, 연결, 격리 수준, read-only 상태 바인딩 |
| 저장소 | 현재 트랜잭션에서 테이블과 DSL 연산 실행 |
| JDBC 드라이버와 풀 | 블로킹 I/O와 물리 연결 생명주기 관리 |

서비스 트랜잭션 하나에서 여러 저장소 메서드를 조합할 수 있습니다. 반대로 저장소 메서드마다 `transaction {}`을 숨겨 두면 서비스가 두 저장소 작업을 하나의 원자적 연산으로 묶기 어렵습니다.

## 중첩 호출과 독립 트랜잭션

`newVirtualThreadJdbcTransaction`은 가상 스레드에서 JDBC 트랜잭션을 실행합니다. `JdbcTransaction.withVirtualThreadJdbcTransaction`은 바깥 연결을 재사용하지 않습니다. 바깥 트랜잭션의 read-only 값만 이어받아 독립된 트랜잭션을 시작하므로 아직 commit하지 않은 변경은 자동으로 보이지 않습니다.

```kotlin
transaction(database) {
    val id = Orders.insertAndGetId { /* values */ }
    commit() // 독립 트랜잭션에서 이 행을 읽어야 한다면 먼저 확정한다

    withVirtualThreadJdbcTransaction {
        Orders.selectAll().where { Orders.id eq id }.single()
    }
}
```

가상 스레드는 블로킹 대기 중 플랫폼 스레드가 묶이는 비용을 줄입니다. JDBC를 R2DBC로 바꾸거나 서로 다른 트랜잭션을 합쳐 주지는 않습니다.

## 격리 수준과 페이징

`JdbcRepository.findPage`는 전체 개수와 현재 페이지 데이터를 차례로 조회합니다. 동시에 다른 트랜잭션이 쓰기를 수행하면 두 결과가 서로 다른 시점을 나타낼 수 있습니다. 이 차이를 허용할 수 없다면 `SERIALIZABLE`이나 DB에 맞는 일관성 전략을 사용하세요. 일반적인 목록 화면은 이 작은 시간 차이를 받아들이는 경우가 많습니다.

## 실패와 재시도

- 예외가 트랜잭션 블록 밖으로 나가야 Exposed가 rollback할 수 있습니다. 오류를 잡은 뒤 성공 값을 반환하면서 rollback을 기대하면 안 됩니다.
- 가능하면 긴 DB 트랜잭션 안에서 외부 HTTP 호출을 기다리지 마세요. JDBC 연결을 쥔 채 네트워크를 기다리면 풀 압력이 커집니다.
- 트랜잭션 블록 전체를 재시도한다면 블록 안의 부수 효과는 멱등하거나 commit 뒤로 미뤄야 합니다.
- read-only는 트랜잭션의 힌트이자 계약이지 DB 권한을 대신하지 않습니다.

## Spring이 경계를 소유하는 경우

Spring 트랜잭션 인프라가 경계를 소유할 수도 있습니다. 그래도 원칙은 같습니다. 한 계층이 소유권을 분명히 가져야 합니다. 바깥 Spring 트랜잭션과 수동으로 연 Exposed 트랜잭션을 섞으려면 같은 연결과 commit 결과를 공유하는지 먼저 확인하세요.

[저장소 패턴](repository-patterns.md), [운영과 테스트](operations-testing.md)로 이어가세요. 드라이버까지 논블로킹이어야 한다면 [R2DBC 매뉴얼](../bluetape4k-exposed-r2dbc.md)과 비교하세요.

## 근거 자료

- [JDBC 저장소 사용 계약](../../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/JdbcRepository.kt)
- [가상 스레드 JDBC 트랜잭션](../../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/VirtualThreadJdbcTransaction.kt)
- [JDBC 예제 controller](../../../../../examples/jdbc-demo/src/main/kotlin/io/bluetape4k/examples/exposed/mvc/controller/ProductController.kt)
- [JDBC 저장소 트랜잭션 테스트](../../../../../exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/repository/MovieJdbcRepositoryTest.kt)
