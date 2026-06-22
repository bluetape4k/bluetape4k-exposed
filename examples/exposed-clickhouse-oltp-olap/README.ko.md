# examples-exposed-clickhouse-oltp-olap

한국어 | [English](./README.md)

PostgreSQL **OLTP**와 ClickHouse **OLAP**를 함께 사용하는 end-to-end 예제입니다.
통합 테스트는 PostgreSQL에 트랜잭션성 주문 행을 저장한 뒤, 생성된 레코드를 ClickHouse
`MergeTree` 테이블로 전달하고, ClickHouse 전용 집계 함수로 리전별 분석 결과를 조회합니다.

## 예제 구성

![PostgreSQL OLTP and ClickHouse OLAP example topology](../../docs/images/readme-diagrams/examples-exposed-clickhouse-oltp-olap-diagram-01.png)

## 테스트 흐름

![OLTP to OLAP integration test flow](../../docs/images/readme-diagrams/examples-exposed-clickhouse-oltp-olap-flow-02.png)

## 구성 요소

| 구성 요소                 | 역할                                                              |
|-----------------------|-----------------------------------------------------------------|
| `Orders` 테이블          | 트랜잭션 주문 행을 저장하는 PostgreSQL OLTP 테이블                         |
| `OrdersRepository`    | JDBC 트랜잭션 안에서 주문을 한 건씩 삽입하는 동기 repository                 |
| `OrderEvents` 테이블     | ID 기준 정렬과 `region` 파티셔닝을 사용하는 ClickHouse OLAP `MergeTree` |
| `AnalyticsRepository` | 배치 forwarding과 집계 쿼리(`uniqExact`, `quantile`, `argMax`) 담당     |

## 실행

통합 테스트는 **Testcontainers**로 PostgreSQL과 ClickHouse를 모두 기동합니다:

```bash
./gradlew :examples-exposed-clickhouse-oltp-olap:test
```

## 주의사항

- 이 예제의 ClickHouse forwarding은 PostgreSQL commit과 **원자적으로 묶이지 않습니다**.
  OLTP 트랜잭션 이후 전달 단계에서 실패하면 OLAP 이벤트가 일부만 남을 수 있습니다. 실제
  파이프라인에서는 멱등 처리, replay, outbox 경계 중 하나를 설계해야 합니다.
- 집계 함수(`uniqExact`, `quantile`, `argMax`)는 Exposed 표현식 API가 ClickHouse 전용
  함수를 모두 모델링하지 않아 raw SQL로 실행합니다.

## 관련 모듈

- [`exposed-clickhouse`](../../exposed/clickhouse/README.ko.md) — 본 예제의 ClickHouse 어댑터
