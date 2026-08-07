---
manualId: "learning-path"
title: "Exposed 학습 경로"
locale: "ko"
releaseRef: "1.12.1"
---

# Exposed 학습 경로

모듈 이름을 차례로 읽는 것보다 하나의 데이터 접근 경로를 끝까지 완성하는 편이 빠르다. 아래 순서는 개념을 확인하고, 가장 작은 저장소를 만들고, 프레임워크와 운영 조건을 붙이는 흐름으로 구성했다. 각 단계에는 상세 설명과 실행 가능한 예제가 이어지므로 현재 필요한 지점부터 들어가도 된다.

## 1. 공통 모델 익히기

[`core`](../modules/bluetape4k-exposed-core.md)와 [`dao`](../modules/bluetape4k-exposed-dao.md)에서 엔티티, ID, 열 매핑, 저장소 개념을 먼저 정리한다. 이 공통 모델을 알아야 JDBC와 R2DBC 구현의 차이를 API 모양이 아니라 트랜잭션과 자원 소유권 관점에서 비교할 수 있다.

## 2. 주 데이터 접근 경로 완성하기

- **JDBC 경로** — 블로킹 드라이버와 트랜잭션 관리자가 경계를 소유한다면 [`jdbc`](../modules/bluetape4k-exposed-jdbc.md)를 선택한다. 일반적인 Spring MVC 서비스와 배치 작업의 기본 경로다. 단계별 실습은 [`exposed-workshop`](https://github.com/bluetape4k/exposed-workshop)에서 이어간다.
- **R2DBC 경로** — 드라이버, 프레임워크, 호출 사슬이 모두 논블로킹이라면 [`r2dbc`](../modules/bluetape4k-exposed-r2dbc.md)를 선택한다. 코루틴 트랜잭션과 취소 처리를 함께 익혀야 하며, [`exposed-r2dbc-workshop`](https://github.com/bluetape4k/exposed-r2dbc-workshop)이 연습 순서를 제공한다.

저장소 안의 [`Spring Boot JDBC 예제`](../modules/exposed-spring-boot-jdbc-demo.md), [`Spring Boot R2DBC 예제`](../modules/exposed-spring-boot-r2dbc-demo.md), [`Ktor 예제`](../modules/examples-ktor-exposed-demo.md)는 설정부터 테스트까지 한 애플리케이션 안에서 확인하는 다음 단계다.

## 3. 캐시와 데이터베이스 어댑터 붙이기

캐시는 저장소와 트랜잭션의 동작을 이해한 뒤 선택한다. Caffeine은 프로세스 내부 캐시가 맞을 때, Lettuce와 Redisson은 Redis를 사용해 여러 인스턴스가 상태를 공유해야 할 때 검토한다. 캐시 무효화와 종료 책임까지 정하지 못했다면 아직 캐시를 붙일 때가 아니다.

PostgreSQL, MySQL 8, DuckDB, ClickHouse, Trino, BigQuery, StarRocks, CockroachDB 어댑터는 데이터베이스별 기능을 보강한다. JDBC/R2DBC 기반 계층을 대체하지 않으므로 먼저 주 경로를 만든 뒤 실제 데이터베이스 요구에 맞는 어댑터로 좁힌다.

## 4. 더 큰 예제로 확장하기

여러 라이브러리를 함께 쓰는 애플리케이션 예제는 [`bluetape4k-workshop`](https://github.com/bluetape4k/bluetape4k-workshop)에서 찾을 수 있다. 저장소 안의 ClickHouse OLTP/OLAP와 BigQuery 드라이런 예제는 분석용 데이터 경로를 테스트하는 구체적인 출발점이다.

감사 이력, 객체 변경 비교, 커밋 메타데이터가 목표라면 이 단계에서 [`bluetape4k-javers`](https://github.com/bluetape4k/bluetape4k-javers)로 이동한다. Exposed 저장소는 현재 상태의 읽기·쓰기를 맡고, JaVers는 그 상태가 어떻게 바뀌었는지를 기록한다. 두 책임을 분리해야 저장소 API와 감사 모델이 서로 끌려가지 않는다.
