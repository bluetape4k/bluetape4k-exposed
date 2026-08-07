---
title: "Hibernate와 Exposed 비교"
locale: "ko"
releaseRef: "1.12.1"
---

# Hibernate와 Exposed 비교

Hibernate와 Exposed는 문법만 다른 같은 계층이 아닙니다. 두 라이브러리가 드러내는 영속성 모델이 다릅니다. 프레임워크의 우열을 정하려 하지 말고, 애플리케이션의 소유권과 쿼리 작성 방식에 맞는 모델을 고르세요.

[![JPA와 Exposed 아키텍처 비교](../../assets/persistence/jpa-exposed-comparison.ko.png)](../../assets/persistence/jpa-exposed-comparison.ko.svg)

도표는 위에서 아래로 읽습니다. 애플리케이션이 업무 트랜잭션을 소유합니다. JPA는 영속성 컨텍스트와 `EntityManager` 또는 `Session`을 거쳐 동작하고, Exposed는 JDBC 또는 R2DBC 트랜잭션 경로를 명시적으로 드러냅니다. `bluetape4k-hibernate`는 JPA 실행 계층 옆에서 선택적으로 사용하는 Kotlin 확장입니다. 트랜잭션을 소유하거나 영속성 공급자를 대체하지 않습니다.

## 모델 비교

| 관점 | Hibernate | Exposed |
| --- | --- | --- |
| 중심 모델 | 영속성 컨텍스트가 관리하는 엔티티 그래프 | 명시적인 테이블, SQL DSL, DAO, 트랜잭션 블록 |
| 변경 감지 | 영속성 컨텍스트 안에서 dirty checking | Exposed 트랜잭션 안에서 명시적으로 statement나 DAO 변경 |
| 관계 | 객체 연관, proxy, fetch plan | 필요한 join과 참조 엔티티를 명시적으로 선택 |
| 쿼리 방식 | JPQL·HQL, Criteria, native SQL | 타입 안전 SQL DSL, DAO 쿼리, 필요할 때 사용자 SQL |
| 작업 단위 | 보통 session·entity manager가 소유 | 트랜잭션 블록이나 바깥 프레임워크 트랜잭션이 소유 |
| 스키마 | JPA 매핑과 migration 또는 schema 도구 | Exposed 테이블 정의와 migration 또는 schema 도구 |
| 리액티브 경로 | Hibernate Reactive의 별도 session 모델 | Exposed R2DBC의 Coroutine 트랜잭션과 R2DBC 드라이버 |

## Hibernate가 자연스러운 경우

풍부한 도메인 모델과 객체 탐색, 표준 JPA 연동, 영속성 컨텍스트의 변경 감지가 핵심 요구라면 Hibernate가 잘 맞습니다. Aggregate 중심 CRUD에서 명시적인 영속성 코드를 줄일 수 있습니다. 대신 proxy 로딩과 flush 시점, cascade, fetch plan을 정확성과 성능의 일부로 관리해야 합니다.

## Exposed가 자연스러운 경우

SQL 모양과 테이블 소유권, join, batch, 트랜잭션 경계를 Kotlin 코드에 드러내고 싶다면 Exposed가 잘 맞습니다. SQL DSL은 조회가 많은 서비스와 데이터베이스 고유 기능에 유리합니다. 엔티티와 비슷한 인터페이스가 필요하면 DAO를 사용할 수 있지만, JPA의 영속성 컨텍스트 모델까지 받아들일 필요는 없습니다.

## 전환과 공존

문법을 통일하려는 이유만으로 안정된 영속성 계층을 다시 쓰지 마세요. 지원되지 않는 SQL, 불분명한 트랜잭션, 리액티브 요구, 운영 복잡성, 유지보수 비용처럼 구체적인 이유가 있을 때 한 bounded context나 저장소부터 옮깁니다. 두 기술이 공존하는 동안에는 각 테이블과 트랜잭션의 소유자를 하나로 정하세요. 같은 `DataSource`를 쓴다고 해서 서로 다른 영속성 컨텍스트가 자동으로 안전한 한 작업 단위가 되지는 않습니다.

전환 전에 저장소의 외부 동작을 계약 테스트로 고정합니다. 정렬, null 처리, 낙관적 동시성, cascade 기대값, 트랜잭션 롤백, 생성 식별자를 포함하세요. 쿼리 실행 계획은 운영 데이터베이스에서 다시 확인해야 합니다.

## 선택 질문

1. 팀은 Aggregate 객체 그래프와 SQL·테이블 중 무엇을 중심으로 사고합니까?
2. 이 애플리케이션에서 lazy loading은 장점입니까, 운영 위험입니까?
3. 어떤 데이터베이스 고유 기능을 일급 기능으로 다뤄야 합니까?
4. 여러 저장소가 참여할 때 누가 트랜잭션을 소유합니까?
5. 논블로킹 경로에 다른 session이나 저장소 모델이 필요합니까?

[JDBC와 R2DBC 선택](jdbc-vs-r2dbc.md), [트랜잭션 경계](transaction-boundaries.md)를 먼저 읽고 대표 저장소 하나를 만들어 본 뒤 전체 전환 여부를 결정하세요.

## 더 읽을 자료

- [JetBrains Exposed](https://www.jetbrains.com/help/exposed/home.html)
- [Hibernate ORM 문서](https://hibernate.org/orm/documentation/)
- [Exposed 워크숍](https://github.com/bluetape4k/exposed-workshop)
- [Bluetape4k 워크숍](https://github.com/bluetape4k/bluetape4k-workshop)
