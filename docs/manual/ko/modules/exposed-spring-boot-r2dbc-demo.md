---
manualId: "exposed-spring-boot-r2dbc-demo"
id: "exposed-spring-boot-r2dbc-demo"
title: "Spring Boot R2DBC 데모"
locale: "ko"
kind: "example"
gradlePath: ":exposed-spring-boot-r2dbc-demo"
sourceDir: "examples/r2dbc-demo"
releaseRef: "1.12.1"
artifact: null
---

# Spring Boot R2DBC 데모

> WebFlux `suspend` 엔드포인트에서 애플리케이션이 소유한 Exposed R2DBC 데이터베이스까지 따라갑니다.

## 학습 내용 {#problem}

Spring WebFlux, `ExposedR2dbcRepository`, 풀을 사용하는 H2 R2DBC 연결, 코루틴 엔드포인트, 통합 테스트를 하나로 연결한 데모입니다. 소유권도 분명하게 드러납니다. `ConnectionPool`, `R2dbcDatabase`, 데이터베이스 디스패처는 애플리케이션이 만듭니다. 연동 모듈은 저장소 인터페이스를 구현하지만 Spring reactive 트랜잭션 관리자를 만들지 않습니다.

## 사전 조건 {#prerequisites}

- JDK 21 이상
- 저장소의 Gradle Wrapper
- 기본 실행에는 Docker가 필요하지 않습니다. 메모리 H2를 R2DBC로 사용합니다.

## 실행 {#run}

```bash
./gradlew :exposed-spring-boot-r2dbc-demo:test
./gradlew :exposed-spring-boot-r2dbc-demo:bootRun
```

HTTP 애플리케이션의 기본 포트는 `8080`입니다.

## 확인할 결과 {#expected-result}

`DataInitializer`는 `ApplicationReadyEvent` 뒤에 실행됩니다. 테이블이 없으면 만들고 코루틴에서 상품 3개를 비동기로 넣습니다. 첫 목록 요청과 초기화가 겹칠 수 있으므로 테스트는 상품 3개가 보일 때까지 잠시 반복해서 조회합니다.

```bash
curl http://localhost:8080/products
curl -i http://localhost:8080/products/999999
```

초기화가 끝나면 목록에 상품 3개가 있고, 없는 ID는 `404`를 반환해야 합니다. 컨트롤러 테스트는 생성·수정·삭제와 삭제 뒤 `404`도 검증합니다. 저장소 테스트에서는 CRUD, `Flow`, 목록 수집, bulk 작업, count/existence, `streamAll()`을 확인합니다.

## 실패 진단 {#failures}

- `R2dbcDatabase` 빈이 없음: `spring.r2dbc.*`, `ConnectionFactoryOptions`, `ConnectionPool` 구성을 `ExposedR2dbcConfig`에서 확인합니다.
- 첫 목록이 비어 있음: 초기화는 비동기입니다. 저장소 오류로 단정하기 전에 초기화 로그와 스키마 생성을 살펴봅니다.
- 수정·삭제 작업의 절반만 반영됨: 읽기와 쓰기를 명시적인 `suspendTransaction` 하나로 묶습니다. 저장소 호출을 따로 실행하면 각 호출이 별도 트랜잭션을 소유할 수 있습니다.
- 요청이 멈춤: 요청 경로에 JDBC, `runBlocking`, 스레드 sleep이 있는지 찾습니다. 반복 조회의 sleep은 테스트 도우미에만 있습니다.
- 애플리케이션 종료 후 프로세스가 끝나지 않음: `ConnectionPool`을 Spring 빈으로 유지해 종료 책임과 상태를 애플리케이션에서 관찰할 수 있게 합니다.

## 다음 학습 경로 {#next}

[Spring Boot R2DBC 연동](bluetape4k-exposed-spring-boot-r2dbc.md)에서 저장소 트랜잭션 의미를 확인하고, [R2DBC 저장소 패턴](bluetape4k-exposed-r2dbc/repository-patterns.md)으로 이어 가세요. [Exposed R2DBC 워크숍](https://github.com/bluetape4k/exposed-r2dbc-workshop)에서는 트랜잭션, Flow, WebFlux, Ktor, 캐시, 라우팅을 단계별로 실습할 수 있습니다.

## 사용하기 좋은 경우 {#when-to-use}

Exposed R2DBC를 사용하는 코루틴 기반 WebFlux 서비스를 만들기 전에 실행해 보기 좋습니다. 주변 스택이 블로킹이라면 JDBC 데모가 더 알맞습니다. 이 예제는 R2DBC가 더 빠르다는 증거가 아니라 연결, 트랜잭션, 취소 책임을 일관되게 두는 방법을 보여 줍니다.

## 의존성 좌표 {#coordinates}

이 데모는 라이브러리를 배포하지 않습니다. 사용자 애플리케이션에서는 중앙 BOM과 R2DBC 연동 모듈을 버전 없이 선언합니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-r2dbc")
}
```

## 핵심 개념 {#concepts}

`ExposedR2dbcConfig`가 `ConnectionFactoryOptions`, `ConnectionPool`, `R2dbcDatabase`를 만들며, 현재 데이터베이스 디스패처는 `Dispatchers.IO`입니다. `ProductR2dbcRepository`는 `ResultRow`를 불변 `ProductRecord`로 매핑합니다. 컨트롤러 엔드포인트는 모두 `suspend`입니다.

필요한 저장소 메서드는 자체 Exposed R2DBC 트랜잭션을 엽니다. 수정과 삭제는 저장소 메서드를 둘 이상 호출하므로 컨트롤러에서 바깥쪽 `suspendTransaction`을 추가해 읽기-확인-쓰기 전체를 원자적으로 묶습니다. 이 경로에는 Spring reactive 트랜잭션 관리자가 없습니다.

## 빠르게 시작하기 {#quick-start}

1. 테스트를 실행하고 컨트롤러·저장소 테스트를 읽습니다.
2. `bootRun`으로 시작한 뒤 초기화를 기다리고 `GET /products`를 호출합니다.
3. `ExposedR2dbcConfig`에서 URL이 풀과 `R2dbcDatabase`로 이어지는 과정을 따라갑니다.
4. `save()` 한 번의 호출과 `update()`의 명시적인 다중 호출 트랜잭션을 비교합니다.
5. 애플리케이션 테스트에서 요청을 취소하고, 취소가 재시도나 일반 서버 오류로 바뀌지 않는지 확인합니다.

## 작업별 API {#api-by-task}

| 확인할 작업 | 배포본에서 볼 소스 |
| --- | --- |
| 풀과 데이터베이스 소유권 | `ExposedR2dbcConfig` |
| 테이블과 레코드 매핑 | `Products`, `ProductRecord` |
| 저장소 매핑 | `ProductR2dbcRepository` |
| 단일·다중 호출 트랜잭션 | `ProductController` |
| 비동기 스키마·시드 생명주기 | `DataInitializer` |
| WebFlux 동작 | `ProductControllerTest` |
| 저장소와 Flow 동작 | `ProductR2dbcRepositoryTest` |

## 권장 패턴 {#patterns}

- 한 애플리케이션 구성 요소가 커넥션 풀과 `R2dbcDatabase`를 만들고 닫게 합니다.
- 여러 저장소 호출이 한 작업 단위라면 바깥쪽 `suspendTransaction`으로 묶습니다.
- `CancellationException`을 보존합니다. 취소는 일시적인 데이터베이스 오류가 아닙니다.
- 단순히 `Flow`로 감싼 수집 결과와 커서 기반 스트림을 구분합니다. 스트리밍이 필요하면 `streamAll()`을 사용하고 테스트하세요.
- 운영에 적용하기 전에는 시작 시 스키마 생성 대신 검토한 마이그레이션을 사용합니다.

## 연동 {#integrations}

Spring Boot WebFlux, bluetape4k Exposed Spring Boot R2DBC 모듈, Exposed R2DBC, `r2dbc-pool`, H2 R2DBC 드라이버, 코루틴, Jackson 3를 사용합니다. JDBC H2 런타임 의존성은 저장소의 마이그레이션 도구를 지원하며, HTTP 영속화 경로는 R2DBC입니다.

## 설정 {#configuration}

`application.yml`은 `r2dbc:h2:mem:///webfluxdb`를 지정합니다. `R2dbcPoolProperties`는 `bluetape4k.r2dbc.pool.*` 아래에서 idle/lifetime 제한, 생성·획득 timeout, 최대·초기·최소 크기, 재시도, eviction 주기를 조정합니다. 기본값은 데모용이지 운영 크기 산정 기준이 아닙니다. 동시 요청 수와 데이터베이스 한도를 측정한 뒤 바꾸세요.

## 운영 {#operations}

풀 획득 시간, active/idle 연결 수, 질의 시간, 트랜잭션 롤백, 취소, 초기화 완료를 따로 관찰합니다. 첫 조회가 비어 있는 현상은 데이터 유실이 아니라 시작 시점 경쟁일 수 있습니다. H2 실행 성공은 구성과 저장소 동작을 검증할 뿐 운영 데이터베이스 호환성이나 처리량을 보장하지 않습니다.

## 테스트 {#testing}

`ProductControllerTest`는 임의 포트의 WebFlux 애플리케이션을 시작하고 `WebTestClient`로 호출합니다. `ProductR2dbcRepositoryTest`는 구성된 `R2dbcDatabase`에서 단일·bulk 작업, `Flow`, `streamAll()`을 검증합니다. 실제 서비스 경계에는 취소 테스트와 다중 호출 롤백 테스트를 추가하세요.

## 학습 경로와 예제 {#workshops}

`ExposedR2dbcConfig` → `DataInitializer` → `ProductR2dbcRepository` → `ProductController` → 테스트 순서로 읽으면 소유권을 따라가기 쉽습니다. 연동 매뉴얼은 저장소 동작을 설명하고, R2DBC 워크숍은 더 깊은 실습을 제공합니다. 이 데모로 돌아와 두 자료의 개념이 하나의 애플리케이션에서 이어지는지 확인하세요.

## 제약 사항 {#limitations}

메모리 H2, 비동기 시작 스키마 생성, 단일 프로세스를 사용하며 인증은 없습니다. Spring reactive 트랜잭션 관리자, 운영 마이그레이션, 풀 크기 산정, 재시도 정책, 고가용성을 제공하지 않으며 특정 작업에서 R2DBC가 JDBC보다 빠르다는 증거도 아닙니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Spring Boot R2DBC 예제 구조도

[![Spring Boot R2DBC 예제 구조도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-r2dbc-demo-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-r2dbc-demo-diagram-01.svg)

_배포본 README: [`examples/r2dbc-demo/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/examples/r2dbc-demo/README.ko.md)_

### WebFlux suspend request 처리 흐름

[![WebFlux suspend request 처리 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-r2dbc-demo-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-r2dbc-demo-diagram-02.svg)

_배포본 README: [`examples/r2dbc-demo/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/examples/r2dbc-demo/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [데모 개요](../../../../examples/r2dbc-demo/README.ko.md)
- [풀과 데이터베이스 설정](../../../../examples/r2dbc-demo/src/main/kotlin/io/bluetape4k/examples/exposed/webflux/config/ExposedR2dbcConfig.kt)
- [코루틴 컨트롤러와 트랜잭션 경계](../../../../examples/r2dbc-demo/src/main/kotlin/io/bluetape4k/examples/exposed/webflux/controller/ProductController.kt)
- [저장소 매핑](../../../../examples/r2dbc-demo/src/main/kotlin/io/bluetape4k/examples/exposed/webflux/repository/ProductR2dbcRepository.kt)
- [저장소 테스트](../../../../examples/r2dbc-demo/src/test/kotlin/io/bluetape4k/examples/exposed/webflux/ProductR2dbcRepositoryTest.kt)
