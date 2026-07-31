---
manualId: "examples-ddd-spring-modulith-demo"
id: "examples-ddd-spring-modulith-demo"
title: "DDD Spring Modulith 데모"
locale: "ko"
kind: "example"
gradlePath: ":examples-ddd-spring-modulith-demo"
sourceDir: "examples/ddd-spring-modulith-demo"
releaseRef: "1.11.0"
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

## 소스 {#sources}

- [`DddSpringModulithDemoApplication`](../../../../examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/DddSpringModulithDemoApplication.kt)
- [`OrderAcceptedEvent`](../../../../examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/orders/events/OrderAcceptedEvent.kt)
- [`ShippingReservationRepository`](../../../../examples/ddd-spring-modulith-demo/src/main/kotlin/io/bluetape4k/exposed/examples/modulith/shipping/internal/ShippingReservationRepository.kt)
