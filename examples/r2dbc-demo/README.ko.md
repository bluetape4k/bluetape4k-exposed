# exposed-spring-boot-r2dbc-demo

[English](./README.md) | 한국어

Exposed R2DBC + suspend Repository + Spring WebFlux 통합 데모 (Spring Boot 4)

## 개요

이 모듈은 **Exposed R2DBC**와 Kotlin coroutine `suspend` 함수로 Spring WebFlux 상품 API를 구성하는 예제입니다.
Repository는 `ProductRecord`를 `Products` 테이블과 매핑하고, Spring Boot는 WebFlux 런타임, R2DBC pool 설정,
그리고 **Spring Boot BOM** 기반 플랫폼 의존성 관리를 맡습니다.

## 데모 구조

![Spring Boot R2DBC demo structure diagram](../../docs/images/readme-diagrams/spring-boot-exposed-r2dbc-demo-diagram-01.png)

## WebFlux suspend 요청 흐름

![WebFlux suspend request flow diagram](../../docs/images/readme-diagrams/spring-boot-exposed-r2dbc-demo-diagram-02.png)

## 주요 특징

- **Exposed R2DBC 기반**: `ProductRecord`, `Products` 테이블 정의
- **suspend 함수**: 모든 Repository와 Controller 메서드가 Kotlin 코루틴 `suspend` 함수
- **ExposedR2dbcRepository**: record 중심의 row 매핑 구현
- **Spring WebFlux**: 비동기 논블로킹 REST API
- **코루틴**: `suspendTransaction`으로 R2DBC 데이터베이스 액세스
- **자동 스키마 생성**: 애플리케이션 준비 완료 후 비동기 초기화
- **Spring Boot 호환**: Spring Boot 4+ 플랫폼 의존성 관리

## 프로젝트 구조

```
src/main/kotlin/io/bluetape4k/examples/exposed/webflux/
├── WebfluxDemoApplication.kt       # Spring Boot 애플리케이션
├── domain/
│   └── ProductEntity.kt            # ProductRecord + Products 테이블
├── repository/
│   └── ProductR2dbcRepository.kt    # suspend CRUD Repository
├── controller/
│   └── ProductController.kt         # 비동기 REST API
└── config/
    ├── ExposedR2dbcConfig.kt        # R2DBC 데이터베이스 설정
    └── DataInitializer.kt           # 비동기 초기 데이터 로더
```

## 도메인 모델

### Products (Exposed R2DBC 테이블)

```kotlin
object Products : LongIdTable("webflux_products") {
    val name = varchar("name", 255)
    val price = decimal("price", 10, 2)
    val stock = integer("stock").default(0)
}
```

### ProductRecord (HTTP record)

```kotlin
data class ProductRecord(
    val id: Long? = null,
    val name: String,
    val price: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    val stock: Int = 0,
) : java.io.Serializable
```

Repository는 `extractId(entity)`를 통해 ID를 추출합니다.

## Repository

### ExposedR2dbcRepository 구현

```kotlin
interface ProductR2dbcRepository: ExposedR2dbcRepository<ProductRecord, Long> {
    override val table: IdTable<Long> get() = Products

    override fun extractId(entity: ProductRecord): Long? = entity.id

    override fun toDomain(row: ResultRow): ProductRecord =
        ProductRecord(
            id = row[Products.id].value,
            name = row[Products.name],
            price = row[Products.price],
            stock = row[Products.stock],
        )

    override fun toPersistValues(domain: ProductRecord): Map<Column<*>, Any?> =
        mapOf(
            Products.name to domain.name,
            Products.price to domain.price,
            Products.stock to domain.stock,
        )
}
```

모든 Repository 메서드는 `suspend` 함수입니다:

```kotlin
suspend fun findAll(): List<ProductRecord>
suspend fun findByIdOrNull(id: Long): ProductRecord?
suspend fun save(entity: ProductRecord): ProductRecord
suspend fun deleteById(id: Long)
```

## REST API

### 기본 CRUD

| 메서드    | 경로               | 설명             |
|--------|------------------|----------------|
| GET    | `/products`      | 모든 상품 조회 (비동기) |
| GET    | `/products/{id}` | 특정 상품 조회 (비동기) |
| POST   | `/products`      | 상품 생성 (비동기)    |
| PUT    | `/products/{id}` | 상품 수정 (비동기)    |
| DELETE | `/products/{id}` | 상품 삭제 (비동기)    |

모든 엔드포인트는 `suspend` 함수이며, Spring WebFlux가 자동으로 코루틴을 처리합니다.

### 요청/응답 예시

**모든 상품 조회 (비동기)**

```bash
curl http://localhost:8080/products
```

응답:

```json
[
  {
    "id": 1,
    "name": "Kotlin Coroutines Book",
    "price": 39.99,
    "stock": 100
  },
  {
    "id": 2,
    "name": "Spring WebFlux Guide",
    "price": 49.99,
    "stock": 50
  }
]
```

**상품 생성 (비동기)**

```bash
curl -X POST http://localhost:8080/products \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Reactive Programming",
    "price": 29.99,
    "stock": 200
  }'
```

응답 (201 Created):

```json
{
  "id": 3,
  "name": "Reactive Programming",
  "price": 29.99,
  "stock": 200
}
```

**상품 수정 (비동기)**

```bash
curl -X PUT http://localhost:8080/products/1 \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Advanced Kotlin Coroutines",
    "price": 49.99,
    "stock": 150
  }'
```

**상품 삭제 (비동기)**

```bash
curl -X DELETE http://localhost:8080/products/1
```

## 실행 방법

### 필수 사항

- Java 21+
- Gradle 8.x+
- Spring Boot 4+

### 빌드

```bash
./gradlew :exposed-spring-boot-r2dbc-demo:build
```

### 애플리케이션 실행

```bash
./gradlew :exposed-spring-boot-r2dbc-demo:bootRun
```

또는 JAR로 실행:

```bash
./gradlew :exposed-spring-boot-r2dbc-demo:assemble
java -jar examples/r2dbc-demo/build/libs/exposed-r2dbc-spring-data-webflux-demo-*.jar
```

### 기본 포트

애플리케이션은 기본 포트 `8080`에서 시작됩니다.

### 초기 데이터

애플리케이션이 준비 완료(`ApplicationReadyEvent`)한 후 비동기로 다음 3개의 샘플 상품이 생성됩니다.

```
1. Kotlin Coroutines Book - $39.99 (100개 재고)
2. Spring WebFlux Guide - $49.99 (50개 재고)
3. Reactive Programming - $29.99 (200개 재고)
```

## 데이터베이스

기본적으로 **H2 R2DBC 인메모리 데이터베이스**를 사용합니다. `application.yml`에서 변경할 수 있습니다.

### application.yml

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:webfluxdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    driver-class-name: org.h2.Driver
  r2dbc:
    url: r2dbc:h2:mem:///webfluxdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=LEGACY
    username: sa
    password:
```

### PostgreSQL로 변경

```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/exposed_demo
    username: postgres
    password: password
  datasource:
    url: jdbc:postgresql://localhost:5432/exposed_demo
    driver-class-name: org.postgresql.Driver
    username: postgres
    password: password
```

그리고 `build.gradle.kts`에서:

```kotlin
implementation("org.postgresql:r2dbc-postgresql")
runtimeOnly("org.postgresql:postgresql")
```

## 테스트

### 단위 테스트 실행

```bash
./gradlew :exposed-spring-boot-r2dbc-demo:test
```

### 코루틴 테스트

모든 테스트는 `runTest { ... }` 블록 내에서 실행되어 코루틴을 지원합니다.

```bash
./gradlew :exposed-spring-boot-r2dbc-demo:test --tests "ProductControllerTest"
```

## 핵심 패턴

### suspend 함수 기반

모든 Repository와 Controller 메서드는 `suspend` 함수입니다.

```kotlin
@GetMapping("/{id}")
suspend fun findById(@PathVariable id: Long): ProductRecord =
    productRepository.findByIdOrNull(id)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: $id")
```

Spring WebFlux가 자동으로 코루틴을 처리합니다.

### suspendTransaction

R2DBC 데이터베이스 액세스는 `suspendTransaction`으로 감싸집니다.

```kotlin
@PutMapping("/{id}")
suspend fun update(@PathVariable id: Long, @RequestBody dto: ProductRecord): ProductRecord =
    suspendTransaction {
        val existing = productRepository.findByIdOrNull(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: $id")
        productRepository.save(dto.copy(id = existing.id ?: id))
    }
```

### 비동기 초기화

데이터 초기화는 `ApplicationReadyEvent`에서 별도 코루틴으로 실행되어 시작 스레드를 막지 않습니다.

```kotlin
@Component
class DataInitializer(private val r2dbcDatabase: R2dbcDatabase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady(event: ApplicationReadyEvent) {
        scope.launch {
            initializeData()
        }
    }
}
```

## Record 매핑

Repository 메서드는 record 중심이므로 Row -> `ProductRecord` 변환을 구현해야 합니다.

```kotlin
override fun toDomain(row: ResultRow): ProductRecord =
    ProductRecord(
        id = row[Products.id].value,
        name = row[Products.name],
        price = row[Products.price],
        stock = row[Products.stock],
    )

override fun toPersistValues(domain: ProductRecord): Map<Column<*>, Any?> =
    mapOf(
        Products.name to domain.name,
        Products.price to domain.price,
        Products.stock to domain.stock,
    )
```

## Spring Boot 4 참고

Spring Boot 4 기반으로 예제를 실행할 때 확인할 내용입니다.

### BOM 변경

`build.gradle.kts`:

```kotlin
dependencies {
    // Spring Boot BOM 사용
    implementation(platform("org.springframework.boot:spring-boot-dependencies:<spring-boot-version>"))
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<bluetape4k-version>"))

    // 나머지 의존성은 동일
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
}
```

### 플랫폼 의존성 관리

Spring Boot BOM이 WebFlux, R2DBC, 테스트, Spring Framework 버전을 함께 맞춥니다:

- Spring Boot 4+
- Java 21+
- Spring WebFlux
- Spring R2DBC
- Spring Boot Test

## 주의사항

1. **runBlocking 금지**: suspend 함수에서 `runBlocking`을 사용하면 안 됩니다. Spring WebFlux가 자동으로 처리합니다.

2. **R2DBC 드라이버**: 선택한 데이터베이스의 R2DBC 드라이버가 클래스패스에 있어야 합니다.

3. **suspendTransaction 필수**: 트랜잭션이 필요한 경우 `suspendTransaction`을 사용합니다.

4. **Spring Boot 플랫폼**: `dependencyManagement { imports }` 대신 `implementation(platform(...))` 사용합니다.

## 의존성

```kotlin
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:<spring-boot-version>"))
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<bluetape4k-version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.jetbrains.exposed:exposed-spring-boot4-starter")
    implementation("org.jetbrains.exposed:exposed-r2dbc")
    implementation("org.jetbrains.exposed:exposed-java-time")
    implementation("io.r2dbc:r2dbc-pool")
    runtimeOnly("io.r2dbc:r2dbc-h2")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

## 참고 자료

- [Exposed R2DBC 문서](https://github.com/JetBrains/Exposed)
- [Spring Boot 마이그레이션 가이드](https://spring.io/blog/2023/09/06/spring-boot-4-0-m1-released)
- [Spring WebFlux 가이드](https://spring.io/projects/spring-webflux)
- [Kotlin 코루틴 공식 문서](https://kotlinlang.org/docs/coroutines-overview.html)
- [R2DBC 사양](https://r2dbc.io/)
