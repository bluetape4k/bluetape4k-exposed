---
manualId: "examples-exposed-clickhouse-oltp-olap"
id: "examples-exposed-clickhouse-oltp-olap"
title: "ClickHouse OLTP/OLAP 예제"
locale: "ko"
kind: "example"
gradlePath: ":examples-exposed-clickhouse-oltp-olap"
sourceDir: "examples/exposed-clickhouse-oltp-olap"
releaseRef: "1.12.1"
artifact: null
---

# ClickHouse OLTP/OLAP 예제

> 트랜잭션 처리와 분석 작업에 서로 다른 데이터 경로가 필요한 이유를 확인합니다.

## 학습 내용 {#problem}

OLTP 중심의 쓰기·읽기 흐름과 ClickHouse 분석 흐름을 비교합니다. 작업 성격에 맞는 저장소 배치를 보여 주는 예제이며 한 데이터베이스가 다른 데이터베이스를 항상 대체한다고 주장하지 않습니다.

## 사전 조건 {#prerequisites}

- JDK와 저장소의 Gradle Wrapper
- 테스트가 사용하는 데이터베이스 컨테이너를 실행할 Docker
- 두 작업 경로를 띄울 수 있는 로컬 메모리와 빈 포트

## 실행 {#run}

```bash
./gradlew :examples-exposed-clickhouse-oltp-olap:test
```

## 확인할 결과 {#expected-result}

Testcontainers가 PostgreSQL과 ClickHouse를 띄웁니다. 테스트는 PostgreSQL `Orders` 테이블에 주문을 커밋하고 레코드를 ClickHouse `OrderEvents` `MergeTree`로 전달한 뒤 `uniqExact`, `quantile`, `argMax`를 사용한 지역별 분석 결과를 검증합니다.

## 실패 진단 {#failures}

- 컨테이너가 시작되지 않음: Docker, 메모리, 포트 충돌을 확인합니다.
- ClickHouse 연결 실패: 준비 상태를 기다리고 컨테이너 로그를 봅니다.
- 분석 행이 보이지 않음: 질의 의미를 바꾸기 전에 적재와 flush 시점을 확인합니다.
- SQL 방언 오류: 생성 SQL과 ClickHouse 어댑터의 제약을 비교합니다.

## 다음 학습 경로 {#next}

[ClickHouse 어댑터](bluetape4k-exposed-clickhouse.md), [OLTP와 OLAP](../guides/oltp-vs-olap.md), [bluetape4k 워크숍](https://github.com/bluetape4k/bluetape4k-workshop) 순서로 이어 가세요.

## 사용하기 좋은 경우 {#when-to-use}

트랜잭션 원본과 분석 sink를 함께 검토하면서 전달 경계를 직접 확인하고 싶을 때 사용하세요. outbox, 재실행 원장, 멱등 적재 키 중 무엇이 필요한지 결정하기 전의 실행 가능한 출발점입니다.

## 의존성 좌표 {#coordinates}

이 예제는 라이브러리를 배포하지 않습니다. 애플리케이션에서는 `io.github.bluetape4k:bluetape4k-dependencies:<version>`를 가져오고 개별 라이브러리 버전은 생략하세요.

## 핵심 개념 {#concepts}

OLTP commit은 PostgreSQL이 소유하고 ClickHouse 쓰기는 별도의 분석 작업입니다. 두 단계는 원자적으로 묶이지 않으므로 전달 중 실패하면 sink에 일부만 들어갈 수 있습니다. Exposed expression API가 모델링하지 않는 ClickHouse 전용 집계 함수는 raw SQL로 실행합니다.

## 빠르게 시작하기 {#quick-start}

Docker를 실행하고 위의 Gradle 명령을 그대로 실행한 뒤 `OltpOlapIntegrationTest`를 확인합니다. 두 컨테이너가 준비되고, OLTP row가 커밋되며, 전달과 집계 단언이 모두 통과해야 성공입니다.

## 작업별 API {#api-by-task}

PostgreSQL insert는 `OrdersRepository`, 커밋된 주문을 `OrderEvents`로 바꾸는 부분은 전달 단계, batch 적재와 집계 질의는 `AnalyticsRepository`에서 확인합니다. 실제 애플리케이션으로 옮길 때도 이 세 단계를 숨기지 마세요.

## 권장 패턴 {#patterns}

ClickHouse에 안정적인 이벤트 식별자를 두고 전달을 멱등하게 만드세요. 마지막으로 전달한 위치를 기록하고 batch 중간 실패 뒤 재실행을 시험합니다. 분석 이벤트 유실을 허용할 수 없다면 영속 outbox를 사용합니다.

## 연동 {#integrations}

Exposed JDBC PostgreSQL 경로, ClickHouse 어댑터, PostgreSQL Testcontainers, ClickHouse Testcontainers를 조합합니다. 두 서비스 모두 폐기 가능한 테스트 기반 객체입니다.

## 설정 {#configuration}

PostgreSQL과 ClickHouse 연결 설정을 분리합니다. 운영에서는 실제 질의와 적재량을 기준으로 ClickHouse engine, 정렬 키, partition, retention, batch, 재시도 정책을 정하세요.

## 운영 {#operations}

원본 commit 위치, 전달 지연, 성공·거부 row 수, 재시도 횟수, ClickHouse insert 지연, 집계 지연을 관찰합니다. 커밋된 OLTP row와 분석 sink가 받아들인 이벤트의 차이가 계속 커지면 알려야 합니다.

## 테스트 {#testing}

깨끗한 end-to-end 테스트를 유지하고 PostgreSQL commit과 ClickHouse 완료 사이에서 실패하는 사례를 추가하세요. 애플리케이션이 선택한 멱등 키로 재실행했을 때 이벤트가 빠지거나 중복 집계되지 않는지 증명합니다.

## 학습 경로와 예제 {#workshops}

[ClickHouse 어댑터](bluetape4k-exposed-clickhouse.md)에서 지원 DDL과 트랜잭션 경계를 확인하고 [OLTP와 OLAP](../guides/oltp-vs-olap.md)에서 운영 전달 구조를 설계하세요.

## 제약 사항 {#limitations}

이 예제는 로컬의 두 컨테이너를 연결한 한 경로만 증명합니다. exactly-once pipeline, 분산 트랜잭션, 운영 schema migration, 용량, replication, retention, 재해 복구 정책은 제공하지 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### PostgreSQL OLTP and ClickHouse OLAP example topology

[![PostgreSQL OLTP and ClickHouse OLAP example topology](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/examples-exposed-clickhouse-oltp-olap-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/examples-exposed-clickhouse-oltp-olap-diagram-01.svg)

_배포본 README: [`examples/exposed-clickhouse-oltp-olap/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/examples/exposed-clickhouse-oltp-olap/README.ko.md)_

### OLTP to OLAP integration test 흐름

[![OLTP to OLAP integration test 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/examples-exposed-clickhouse-oltp-olap-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/examples-exposed-clickhouse-oltp-olap-flow-02.svg)

_배포본 README: [`examples/exposed-clickhouse-oltp-olap/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/examples/exposed-clickhouse-oltp-olap/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [예제 소스](../../../../examples/exposed-clickhouse-oltp-olap/README.ko.md)
- [Gradle 빌드](../../../../examples/exposed-clickhouse-oltp-olap/build.gradle.kts)
