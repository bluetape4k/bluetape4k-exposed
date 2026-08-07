---
manualId: jdbc-vs-r2dbc
title: JDBC와 R2DBC 선택
locale: ko
releaseRef: 1.12.1
---

# JDBC와 R2DBC 선택

저장소 API 모양보다 driver와 트랜잭션 모델을 먼저 고른다. JDBC와 R2DBC 모두 Exposed DSL과 저장소 helper를 제공하지만 blocking, 문맥 전파, connection 소유권, framework 연동, 장애 테스트 방법이 다르다.

## 운영 관점 비교

| 항목 | JDBC | R2DBC |
| --- | --- | --- |
| Driver API | blocking `DataSource`와 JDBC `Connection` | Reactive Streams 기반 `ConnectionFactory`와 R2DBC connection |
| Kotlin API | 일반 함수, blocking 호출에 맞는 thread 필요 | `suspend` 함수와 `Flow` |
| 트랜잭션 진입점 | Exposed `transaction` 또는 Spring JDBC 연동 | 호출자 소유 `suspendTransaction`, Spring coroutine repository bridge |
| 문맥 | 보통 thread에 묶인 transaction context | coroutine transaction context, 무관한 scope로 작업을 분리하지 않음 |
| 취소 | thread interruption이 statement 취소를 보장하지 않음 | coroutine 취소를 전파하지만 driver와 DB가 관찰하는 시점은 제각각 |
| Connection 소유자 | 애플리케이션 또는 framework가 `DataSource`와 pool 소유 | 애플리케이션 또는 framework가 `ConnectionFactory`와 pool 소유 |
| Spring 조합 | MVC, 명령형 service, Spring Batch, 기존 transaction manager | WebFlux와 coroutine 호출 사슬, R2DBC 기반 infrastructure |
| Testcontainers | JDBC driver와 DB container | 같은 DB용 R2DBC driver와 container, migration tool은 JDBC를 요구할 수 있음 |
| 전환 비용 | 기존 blocking library와 transaction 유지 | 호출 사슬, driver, pool, migration, 관측, 테스트를 함께 바꿔야 함 |

## 언제 JDBC를 고를까

DB driver와 주변 framework가 blocking이고 기존 서비스가 명령형 Spring transaction을 사용한다면 JDBC가 자연스럽다. Spring Batch나 JDBC 기반 도구가 중심인 작업도 마찬가지다. Java virtual thread는 driver를 바꾸지 않고 blocking thread 비용을 줄일 수 있으므로 R2DBC 전환과 별도로 비교한다.

## 언제 R2DBC를 고를까

대상 DB에 쓸 만한 R2DBC driver가 있고 요청 처리부터 repository, transaction, pool, downstream까지 coroutine/reactive 경계를 유지할 수 있을 때 선택한다. blocking 호출 사슬 가운데 R2DBC repository 하나만 넣어도 전체 시스템이 논블로킹으로 바뀌지는 않는다.

## R2DBC가 자동으로 빠른 것은 아니다

R2DBC는 기다리는 동안 thread를 쓰는 방식을 바꾼다. DB 지연, lock contention, query plan, network round trip, pool 한계는 그대로 남는다. 잘 조정한 JDBC pool과 virtual thread 조합이 더 빠르거나 단순할 수도 있다. 실제 workload로 latency, throughput, connection 점유율, CPU, 장애 복구 시간을 비교한 뒤 결정한다.

## 트랜잭션과 문맥 비용

JDBC transaction context는 흔히 thread에 묶인다. R2DBC transaction은 `suspendTransaction`이 연 coroutine context에 묶인다. R2DBC Flow를 transaction 밖에서 수집하면 이 문맥을 잃을 수 있다. 어느 경로든 repository 메서드마다 독립 transaction을 여는 대신 서비스가 업무 transaction을 소유한다.

## Spring과 migration tool

1.12.1 Spring JDBC 모듈은 명령형 Exposed 저장소를 Spring data access와 연결한다. Spring R2DBC 모듈은 suspend와 `Flow` signature를 유지하고 Exposed R2DBC transaction에서 실행한다. 모든 Spring 구성 요소가 같은 방식으로 바뀌는 것은 아니다. migration tool, batch library, 외부 integration은 runtime query가 R2DBC여도 JDBC를 요구할 수 있다.

## 전환하기 전에 증명할 것

1. 현재 JDBC workload의 latency, connection 점유율, timeout, 취소 동작을 기록한다.
2. 선택한 R2DBC driver가 필요한 SQL과 DB 기능을 지원하는지 확인한다.
3. handler, transaction, repository, pool, 테스트를 포함한 세로 경로 하나를 옮긴다.
4. H2 테스트 뒤 운영 DB Testcontainers 테스트를 순차 실행한다.
5. timeout, 취소, pool 고갈, DB 재시작, 종료를 강제로 재현한다.
6. 결과를 비교한다. 운영 이득 없이 복잡도만 늘었다면 JDBC를 유지한다.

## 근거 자료와 다음 단계

- [`exposed/jdbc/build.gradle.kts`](../../../../exposed/jdbc/build.gradle.kts)
- [`exposed/r2dbc/build.gradle.kts`](../../../../exposed/r2dbc/build.gradle.kts)
- [`JdbcRepository.kt`](../../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/repository/JdbcRepository.kt)
- [`R2dbcRepository.kt`](../../../../exposed/r2dbc/src/main/kotlin/io/bluetape4k/exposed/r2dbc/repository/R2dbcRepository.kt)
- [트랜잭션 경계](./transaction-boundaries.md)
- [`exposed-workshop`](https://github.com/bluetape4k/exposed-workshop): JDBC 학습 경로
- [`exposed-r2dbc-workshop`](https://github.com/bluetape4k/exposed-r2dbc-workshop): R2DBC 학습 경로
