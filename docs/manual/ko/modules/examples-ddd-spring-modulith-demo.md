---
manualId: "examples-ddd-spring-modulith-demo"
id: "examples-ddd-spring-modulith-demo"
title: "DDD Spring Modulith 데모"
locale: "ko"
kind: "example"
gradlePath: ":examples-ddd-spring-modulith-demo"
sourceDir: "examples/ddd-spring-modulith-demo"
releaseRef: "1.12.1"
artifact: null
---

# DDD Spring Modulith 데모

이 실행 가능한 Spring Boot 예제는 주문 접수, 이벤트 발행, 배송 예약을 이름 있는 애플리케이션 모듈로 나눕니다. Exposed 영속성을 사용하면서도 공유 계약이 Spring이나 JaVers 타입에 의존하지 않도록 DDD 경계를 보여 줍니다.

## 살펴볼 구성 {#what-to-inspect}

`orders` 모듈은 주문을 접수하고 이름 있는 events interface를 통해 `OrderAcceptedEvent`를 발행합니다. `shipping` 모듈은 안정적인 listener로 이 공개 이벤트를 받아 예약을 저장합니다. `modulithinvalid`에는 Spring Modulith 구조 검증이 잡아내야 하는 잘못된 의존성을 의도적으로 넣었습니다.

실제 테이블은 `DDD_MODULITH_ORDERS`, `EVENT_PUBLICATION`, `DDD_MODULITH_SHIPPING_RESERVATIONS`입니다.

## 검증 실행 {#run}

```bash
./gradlew :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain
```

테스트는 모듈 구조와 주문에서 배송으로 이어지는 흐름을 함께 확인합니다. 유효한 module interface와 `modulithinvalid` 패키지를 나란히 읽으면 검증기가 지키는 의존 방향을 알 수 있습니다.

## 경계 규칙 {#boundary-rules}

- 안정적인 event DTO는 명시적으로 이름 붙인 interface package에서 발행합니다.
- 이벤트는 안정적인 listener id를 가진 `@ApplicationModuleListener`로 소비합니다.
- 재실행될 수 있는 쓰기는 `orderId` 같은 business key로 idempotent하게 만듭니다. 배송은 같은 주문의 예약이 이미 있는지 확인합니다.
- aggregate와 event 계약은 Spring-neutral하게 유지합니다. 이 예제에서 Spring Modulith는 domain type system이 아니라 lifecycle 통합을 제공합니다.
- JDBC 영속성은 소유한 transaction boundary 안에서 수행하고, 다른 모듈이 internal repository에 접근하지 않게 합니다.

## 학습 순서 {#learning-path}

`orders`부터 시작해 `OrderAcceptedEvent`를 따라 `shipping`으로 이동하고, `ShippingReservationRepository`와 schema initializer를 확인하세요. 마지막으로 구조 테스트를 실행한 뒤 의도적으로 거부되는 `modulithinvalid` 구성을 살펴봅니다.

## 문제 {#problem}

이 예제는 영속성과 애플리케이션 orchestration이 독립적으로 변할 때 domain event와 모듈 경계를 명시적으로 유지하는 방법을 보여 줍니다.

## 사용하기 좋은 경우 {#when-to-use}

작은 Spring Modulith 검증 fixture나 주문에서 배송으로 이어지는 event 흐름의 참고 자료로 사용합니다. 운영 배포 템플릿은 아닙니다.

## 의존성 좌표 {#coordinates}

이 프로젝트는 예제 애플리케이션이며 library artifact를 배포하지 않습니다. repository 루트에서 Gradle test task를 실행합니다.

## 핵심 개념 {#concepts}

이름 있는 interface가 안정적인 event를 공개하고, internal repository와 잘못된 module dependency는 소유 모듈 안에 둡니다.

## 빠르게 시작하기 {#quick-start}

`./gradlew :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain`을 실행하고 구조·흐름 테스트 보고서를 확인합니다.

## 작업별 API {#api-by-task}

- 이름 있는 events interface로 `OrderAcceptedEvent`를 발행합니다.
- `@ApplicationModuleListener`로 이벤트를 소비합니다.
- internal repository를 통해 shipping reservation을 저장합니다.

## 권장 패턴 {#patterns}

event DTO를 안정적으로 유지하고 replay 가능한 쓰기는 business key로 idempotent하게 만들며 aggregate 계약을 framework type과 분리합니다.

## 연동 {#integrations}

Spring Modulith는 module 검증과 event lifecycle 통합을 제공하고, Exposed는 애플리케이션 transaction 경계 안에서 JDBC 영속성을 담당합니다.

## 설정 {#configuration}

test database와 Spring application context는 예제의 Gradle·test-resource 설정으로 구성합니다. 운영 credential은 fixture 밖에 둡니다.

## 실패 유형과 해결 방법 {#failures}

구조 테스트는 `modulithinvalid`를 거부해야 합니다. 중복 event delivery와 persistence 충돌은 business key와 transaction 경계로 처리합니다.

## 운영 {#operations}

event 발행, listener 완료, reservation 쓰기, 검증 실패를 분리해 관찰합니다. 서비스에 적용할 때 correlation identifier를 기록합니다.

## 테스트 {#testing}

Gradle test task를 실행하고 module 구조 실패와 주문·배송 흐름 assertion을 모두 확인합니다.

## 학습 경로와 예제 {#workshops}

`orders`에서 시작해 `OrderAcceptedEvent`를 `shipping`까지 따라간 뒤 유효한 모듈과 `modulithinvalid`를 비교합니다.

## 제약 사항 {#limitations}

이 예제는 운영 messaging topology, retry 정책, 배포 모델 또는 서비스 간 consistency를 정의하지 않습니다.

## 소스 {#sources}

- [`DddSpringModulithDemoApplication`](../../../../examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/DddSpringModulithDemoApplication.kt)
- [`OrderAcceptedEvent`](../../../../examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/events/OrderAcceptedEvent.kt)
- [`ShippingReservationRepository`](../../../../examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/shipping/internal/ShippingReservationRepository.kt)
