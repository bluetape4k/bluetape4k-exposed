# Issue #5 Spring Modulith Exposed 이벤트 발행 가능성

날짜: 2026-05-16
이슈: https://github.com/bluetape4k/bluetape4k-exposed/issues/5
원본 이슈: https://github.com/bluetape4k/bluetape4k-projects/issues/25

## 결론

JDBC 지원은 기술적으로 가능하지만, 프로젝트가 이벤트 발행 테이블과 쿼리에 대한 Exposed DSL 소유권을 특별히 원하지 않는 한 전체 `spring-modulith-exposed` 모듈의 제품 가치는 낮다.

현재 Spring Modulith SPI에서는 R2DBC 지원을 일급 Spring Modulith 이벤트 저장소로 제공할 수 없다. 공개 `EventPublicationRepository` 계약은 동기식 명령형 방식이다. suspend/R2DBC 구현은 SPI 내부에서 블로킹하거나, 상위 Spring Modulith의 변경이 필요하다.

권장 범위:

- 원래 이슈를 작성된 그대로 구현하지 않는다.
- 런타임 범위는 JDBC 전용으로 유지한다.
- 런타임을 `exposed-spring-modulith`로 구현하고,
  `exposed-spring-modulith`로 게시하여 아티팩트가 공식 Spring Modulith 저장소 모듈처럼 보이지 않도록 한다.
- 애플리케이션과 동일한 `DataSource` 및 Exposed `springTransactionManager`를 사용한다.
- Spring Modulith가 반응형/suspend 이벤트 발행 저장소 SPI를 제공할 때까지 R2DBC를 보류한다.

## 승인된 런타임 범위

승인된 구현 범위는 다음과 같다.

- 모듈 경로: `spring-boot/exposed-spring-modulith`
- Gradle 모듈/아티팩트: `exposed-spring-modulith`
- 공개 통합: Spring Boot 자동 구성
- 런타임 저장소: JDBC 전용
- 트랜잭션 경계: Exposed `springTransactionManager`
- 스키마 모델: Spring Modulith 2.x JDBC V2 컬럼 구조
- 필수 데이터베이스 지원: H2, PostgreSQL, MySQL 8
- 명시적 비목표: R2DBC 저장소, suspend 저장소, 이벤트 리스너 DSL, 모든 공식 Spring Modulith 영속성 저장소를 대체하는 기능

아티팩트 이름은 모듈이 공식 Spring Modulith 아티팩트임을 암시하지 않으면서도 통합 대상을 명확히 드러내기 위해 `spring-modulith-` 대신 의도적으로 `exposed-`로 시작한다.

MySQL 8 통합 테스트에서 `LISTENER_ID` 및 `SERIALIZED_EVENT`와 같은 `TEXT` 컬럼에 대해 이식 가능한 일반 복합 인덱스를 생성할 수 없다는 사실이 확인되었다. 따라서 구현은 완료 날짜 인덱스는 유지하지만 Exposed가 생성하는 스키마에서는 리스너/직렬화 이벤트 인덱스를 사용하지 않는다. 방언별 인덱싱이 필요한 프로덕션 애플리케이션은 Flyway 또는 Liquibase를 통해 추가해야 한다.

아카이브 완료 테스트에서는 Exposed `insertIgnore`가 기본 H2 모드에서 이식 가능하지 않다는 사실도 확인되었다. 따라서 아카이브 복사는 방언별 무시/upsert 구문 대신 존재 여부 확인 후 일반 insert를 사용한다.

현재 검증 명령:

`./gradlew :exposed-spring-modulith:test --no-daemon --rerun-tasks`

범위/결과:

- H2, PostgreSQL, MySQL 8
- `CompletionMode.UPDATE`, `DELETE`, `ARCHIVE`
- 조회 실패, 재제출 시도, 식별자별 삭제
- 12개 통과

## 근거

Spring Modulith 2.0은 Spring Boot 4와 관련된 세대다. 2.0 GA 발표에 따르면 기준 버전이 Spring Boot 4 및 Spring Framework 7로 변경되었다:
https://spring.io/blog/2025/11/21/spring-modulith-2-0-ga-1-4-5-and-1-3-11-released

현재 참조 문서에는 Spring Modulith 2.0.6과 해당 BOM이 나열되어 있다:
https://docs.spring.io/spring-modulith/reference/index.html

이 저장소는 이미 Spring Boot 4.0.6 및 Exposed 1.2.0을 대상으로 한다.

- `gradle/libs.versions.toml`: `spring-boot = "4.0.6"`
- `gradle/libs.versions.toml`: `exposed = "1.2.0"`
- `gradle/libs.versions.toml`: R2DBC 드라이버 버전이 이미 존재한다.

Spring Modulith의 이벤트 레지스트리는 원래 비즈니스 트랜잭션 안에 발행 로그 항목을 기록하고, 이후 리스너 실행을 전후하여 완료 상태로 표시한다:
https://github.com/spring-projects/spring-modulith/blob/main/src/docs/antora/modules/ROOT/pages/events.adoc

Spring Modulith는 이미 공식 영속성 스타터를 제공한다.

- `spring-modulith-starter-jpa`
- `spring-modulith-starter-jdbc`
- `spring-modulith-starter-mongodb`
- `spring-modulith-starter-neo4j`

참조:
https://github.com/spring-projects/spring-modulith/blob/main/src/docs/antora/modules/ROOT/pages/events.adoc

Spring Modulith 공식 코드 검색에서는 R2DBC 이벤트 저장소 지원을 찾을 수 없었다. JDBC/JPA/MongoDB/Neo4j 저장소 모듈만 존재한다.

## SPI 표면

공개 저장소 SPI는 다음과 같다.
`org.springframework.modulith.events.core.EventPublicationRepository`.

현재 main 브랜치의 메서드는 다음을 포함한다.

- `TargetEventPublication create(TargetEventPublication publication)`
- `markProcessing(UUID identifier)`
- `markCompleted(Object event, PublicationTargetIdentifier identifier, Instant completionDate)`
- `markCompleted(UUID identifier, Instant completionDate)`
- `markFailed(UUID identifier)`
- `findIncompletePublications()`
- `findIncompletePublicationsPublishedBefore(Instant instant)`
- `findIncompletePublicationsByEventAndTargetIdentifier(...)`
- `findCompletedPublications()`
- `findFailedPublications(FailedCriteria criteria)`
- `findByStatus(Status status)`
- `deletePublications(List<UUID> identifiers)`
- `deleteCompletedPublications()`
- `deleteCompletedPublicationsBefore(Instant instant)`

소스:
https://github.com/spring-projects/spring-modulith/blob/main/spring-modulith-events/spring-modulith-events-core/src/main/java/org/springframework/modulith/events/core/EventPublicationRepository.java

이는 동기식 Java 인터페이스다. `Publisher`, `Mono`, `Flux`, `CompletionStage` 또는 Kotlin `suspend` 경계가 없다. 따라서 네이티브 R2DBC 통합은 구조적으로 맞지 않는다.

## 공식 JDBC 구현 형태

Spring Modulith의 현재 JDBC V2 구현은 패키지 전용이며 `@Transactional`이 지정되어 있다.

`JdbcEventPublicationRepositoryV2 implements EventPublicationRepository`

다음 컬럼을 저장한다.

- `ID`
- `COMPLETION_DATE`
- `EVENT_TYPE`
- `LISTENER_ID`
- `PUBLICATION_DATE`
- `SERIALIZED_EVENT`
- `STATUS`
- `COMPLETION_ATTEMPTS`
- `LAST_RESUBMISSION_DATE`

소스:
https://github.com/spring-projects/spring-modulith/blob/main/spring-modulith-events/spring-modulith-events-jdbc/src/main/java/org/springframework/modulith/events/jdbc/JdbcEventPublicationRepositoryV2.java

PostgreSQL v2 스키마는 다음을 사용한다.

- `event_publication`
- `id UUID`
- `listener_id TEXT`
- `event_type TEXT`
- `serialized_event TEXT`
- `publication_date TIMESTAMP WITH TIME ZONE`
- `completion_date TIMESTAMP WITH TIME ZONE`
- `status TEXT`
- `completion_attempts INT`
- `last_resubmission_date TIMESTAMP WITH TIME ZONE`
- `serialized_event`에 대한 해시 인덱스
- `completion_date`에 대한 인덱스

소스:
https://github.com/spring-projects/spring-modulith/blob/main/spring-modulith-events/spring-modulith-events-jdbc/src/main/resources/org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql

Issue #5에서 제안한 스키마는 `status`, `completion_attempts`, `last_resubmission_date`를 생략했기 때문에 Spring Modulith 2.x 기준으로 오래되었다.

## 가능성 매트릭스

| 범위 | 가능성 | 이유 |
| --- | --- | --- |
| Exposed 애플리케이션에서 공식 JDBC 사용 | 높음 | Exposed와 Spring Modulith는 동일한 `DataSource`를 공유할 수 있으며, 공식 JDBC는 이미 JPA 없이 이벤트를 영속화한다. |
| Exposed JDBC `EventPublicationRepository` | 중간 | SPI는 공개되어 구현할 수 있지만, 공식 JDBC 동작을 재현해야 하며 업스트림 생명주기 변경을 추적해야 한다. |
| Exposed Table DSL만 사용 | 높음 | 모델링은 쉽지만 공식 SQL 스키마가 이미 제공되므로 가치는 낮다. |
| 자동 구성 | 높음 | 저장소에는 이미 `spring-boot/exposed-jdbc` 및 `spring-boot/exposed-r2dbc` 아래에 Spring Boot 4 자동 구성 패턴이 있다. |
| H2 + PostgreSQL 테스트 | JDBC 기준 높음 | 공식 스키마가 둘 다 지원하며, 저장소에 H2/PostgreSQL/Testcontainers 의존성이 있다. |
| 네이티브 R2DBC 저장소 | 낮음 | Spring Modulith 저장소 SPI는 동기식이며, 공식 R2DBC 저장소 모듈은 존재하지 않는다. |
| Suspend API | Modulith SPI로서는 낮음 | suspend facade는 Spring Modulith 외부에 존재할 수 있지만 `EventPublicationRepository`를 충족하지는 못한다. |
| 모듈 이벤트 DSL | 가치 낮음 | Spring Modulith가 이미 `@ApplicationModuleListener`를 제공하므로 추가 DSL은 추상화 잡음이 될 가능성이 높다. |

## 구현 위험

JDBC 구현 위험:

- Spring Modulith 2.x 생명주기를 정확히 일치시켜야 한다:
  `PUBLISHED`, `PROCESSING`, `COMPLETED`, `FAILED`, `RESUBMITTED`.
- 완료 시도 횟수와 마지막 재제출 날짜의 의미를 보존해야 한다.
- 공식 JDBC 동작과 일치시키려면 완료 아카이브/삭제 동작을 지원해야 한다.
- 직렬화가 공식 모듈과 호환되도록 Spring Modulith `EventSerializer`를 사용해야 한다.
- `spring-modulith-starter-jdbc`와의 빈 충돌을 방지하고 `EventPublicationRepository` 빈이 없을 때만 등록해야 한다.
- 비즈니스 트랜잭션이 롤백될 때 이벤트 발행 insert도 롤백되는지 트랜잭션 롤백 테스트가 필요하다.
- 리스너 실패 시 Spring Modulith 2.x 생명주기에 따라 발행이 재개 가능/실패 상태로 유지되는지 테스트해야 한다.

R2DBC 위험:

- 현재 SPI는 블로킹 메서드 시그니처를 강제한다.
- `@TransactionalEventListener` 및 Spring Modulith 이벤트 발행 레지스트리는 명령형 트랜잭션 의미론을 기반으로 구성되어 있다.
- `runBlocking` 브리지는 R2DBC의 목적을 훼손하고 트랜잭션 컨텍스트를 깨뜨릴 수 있다.
- 현재 Exposed R2DBC는 `suspendTransaction`을 사용하지만, `EventPublicationRepository`는 suspend 함수를 호출할 수 없다.

## 권장 이슈 업데이트

원래 이슈를 다음과 같이 더 좁은 결정으로 대체한다.

1. 업스트림에서 반응형/suspend SPI를 추가할 때까지 Spring Modulith 이벤트 발행에 R2DBC를 구현하지 않는다.
2. 프로젝트가 Exposed를 통해 발행 테이블과 동일한 Exposed 트랜잭션 매니저에 대한 소유권을 원하므로, JDBC 전용 Exposed 기반 저장소를 구현한다.
3. 공식 아티팩트처럼 보이는 이름을 피하기 위해 `exposed-spring-modulith`를 사용한다.
4. 테스트는 자동 등록, 생성/완료 생명주기, 실패한 발행 조회, H2/PostgreSQL/MySQL 8 스키마 호환성에 집중한다.

## 권장 최종 처리

JDBC 전용 런타임 구현을 검토하는 동안 Issue #5를 열어 둔다. 모듈, 문서 및 대상 테스트가 반영된 후에만 닫는다. Spring Modulith가 반응형/suspend SPI를 추가하지 않는 한 동일한 이슈에서 R2DBC를 다시 열지 않는다.
