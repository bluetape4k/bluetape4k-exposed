---
title: "Spring과 Ktor 연동"
locale: "ko"
releaseRef: "1.12.1"
---

# Spring과 Ktor 연동

어떤 프레임워크가 더 익숙한지만 보지 말고, 기반 객체와 트랜잭션, 종료 절차를 누가 맡을지 먼저 정하세요. Spring 모듈은 컨테이너가 관리하는 애플리케이션에 참여하고, Ktor 모듈은 명시적인 도우미만 제공하므로 데이터베이스 생명주기는 애플리케이션이 관리합니다.

> [Spring Boot Exposed 활성화 시각화 자료](https://bluetape4k.github.io/ko/visual-companions/bluetape4k-exposed/spring-boot-exposed-activation/)에서 활성화 조건과 소유권을 직접 확인할 수 있습니다.

## 소유권 비교

| 경로 | 기반 객체 소유자 | 트랜잭션 경계 | 저장소 구성 | 잘 맞는 환경 |
| --- | --- | --- | --- | --- |
| Spring Boot JDBC | Spring이 `DataSource`를 만들고, 이 모듈은 Exposed를 연결하며 필요한 경우 이름 있는 트랜잭션 관리자를 제공 | Spring `@Transactional` 서비스 | `@EnableExposedJdbcRepositories`와 저장소 팩터리 | 블로킹 Spring MVC, 스케줄 작업, 일반적인 서비스 트랜잭션 |
| Spring Boot R2DBC | 애플리케이션이 `ConnectionPool`과 `R2dbcDatabase` 생성 | 명시적인 Exposed `suspendTransaction`; 저장소 메서드는 각각 트랜잭션을 엶 | `@EnableExposedR2dbcRepositories`; 얇은 매핑 자동 설정 | 끝까지 논블로킹인 Coroutine·WebFlux 호출 경로 |
| Ktor JDBC | 애플리케이션이 `Database`, 풀, 디스패처, 레지스트리 생성 | 호출자가 제공한 블로킹 디스패처에서 Exposed JDBC 트랜잭션 실행 | 라우트와 서비스를 명시적으로 조립 | 블로킹 드라이버를 이벤트 루프와 분리한 Ktor 서비스 |
| Ktor R2DBC | 애플리케이션이 풀과 `R2dbcDatabase` 생성 | Exposed `suspendTransaction` | 라우트와 서비스를 명시적으로 조립 | 데이터베이스까지 논블로킹인 Ktor 서비스 |

## Spring JDBC: 컨테이너가 관리하는 트랜잭션 참여

JDBC 자동 설정은 Exposed 엔티티가 있을 때 활성화됩니다. 애플리케이션의 `DataSource`를 Exposed에 연결하고, 필요한 빈을 애플리케이션이 제공하지 않은 경우에만 `springTransactionManager`를 만듭니다. 여러 저장소 호출을 함께 커밋하거나 롤백하려면 Spring 관리 서비스에 비즈니스 트랜잭션을 두세요. 트랜잭션 관리자가 여러 개라면 사용할 관리자를 명시하고 실제 데이터베이스에서 롤백을 검증해야 합니다.

![Spring JDBC 자동 설정](../../assets/spring/jdbc-auto-configuration.png)

저장소에 포함된 JDBC 데모는 트랜잭션 관리자를 직접 선언합니다. 이는 유효한 사용자 설정 사례이지, 모든 애플리케이션이 자동 설정만으로 구성된다는 근거는 아닙니다.

## Spring R2DBC: 의도적으로 얇은 연동

R2DBC 자동 설정은 JDBC 쪽 설정이 준비된 뒤 매핑 지원만 제공합니다. 커넥션 풀이나 `R2dbcDatabase`, Spring 리액티브 트랜잭션 관리자를 만들지 않습니다. 저장소 구현은 내부에서 Exposed Coroutine 트랜잭션을 사용합니다. Flow 반환 메서드는 스트리밍할 수 있지만 다른 메서드는 결과를 모두 만든 뒤 반환할 수 있습니다.

![Spring R2DBC 연동 경계](../../assets/spring/r2dbc-auto-configuration.png)

Spring 애노테이션만 보고 여러 호출이 한 트랜잭션에 묶인다고 판단하면 안 됩니다. 관련 Exposed 작업을 한 번에 커밋해야 한다면 하나의 명시적인 `suspendTransaction` 안에서 실행하세요.

## Ktor: 드러나는 생명주기와 실패 처리

Ktor 플러그인은 의도적으로 작습니다. 애플리케이션이 데이터베이스 객체와 블로킹 디스패처를 제공하고, 공용 `StatusPages` 처리를 한 번만 설치하며, 준비 상태와 종료 절차를 자체 생명주기에 연결합니다. JDBC 도우미는 Coroutine 취소를 다시 던지고 블로킹 작업을 이벤트 루프 밖에서 실행합니다. R2DBC 도우미는 Exposed suspend 트랜잭션을 직접 호출합니다.

숨은 프레임워크 소유권 때문에 시작·준비·종료 동작을 이해하기 어려운 경우 이 명시적인 방식이 유리합니다. 대신 풀과 레지스트리를 닫는 책임도 애플리케이션에 있습니다.

## 선택 순서

1. 웹 프레임워크 이름이 아니라 요청 전체 경로를 보고 JDBC와 R2DBC 중 하나를 고릅니다.
2. 컨테이너 기반 구성과 트랜잭션 가로채기가 설계에 포함되면 Spring을 선택합니다.
3. 객체 생성과 생명주기를 코드에 명확히 드러내고 싶다면 Ktor를 선택합니다.
4. 캐시나 라우팅을 더하기 전에 커밋, 롤백, 취소, 준비 상태, 종료를 각각 한 번씩 검증합니다.

## 다음 학습 경로

- [Spring JDBC 데모](../modules/exposed-spring-boot-jdbc-demo.md), [Spring R2DBC 데모](../modules/exposed-spring-boot-r2dbc-demo.md), [Ktor 데모](../modules/examples-ktor-exposed-demo.md) 중 하나를 실행합니다.
- JDBC는 [exposed-workshop](https://github.com/bluetape4k/exposed-workshop), Coroutine과 리액티브 경로는 [exposed-r2dbc-workshop](https://github.com/bluetape4k/exposed-r2dbc-workshop)에서 단계별로 연습할 수 있습니다.
- 여러 생태계 라이브러리를 함께 쓰는 예제는 [bluetape4k-workshop](https://github.com/bluetape4k/bluetape4k-workshop)에서 확인하세요.

## 근거 자료

- [Spring Boot JDBC 모듈](../../../../spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/config/ExposedSpringDataAutoConfiguration.kt)
- [Spring Boot R2DBC 모듈](../../../../spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/config/ExposedR2dbcSpringDataAutoConfiguration.kt)
- [Ktor 모듈](../../../../ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtor.kt)
