# exposed-spring-boot-common

[English](./README.md) | 한국어

bluetape4k Exposed JDBC 및 R2DBC adapter가 공유하는 Spring Data Commons SPI 모듈입니다.
backend-neutral annotation, mapping metadata, 파생 쿼리 계획, `Sort` 변환을 제공합니다.
데이터베이스 연결·트랜잭션을 시작하지 않으며 JDBC/R2DBC adapter에 의존하지 않습니다.

## 설치

```kotlin
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:<spring-boot-version>"))
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<bluetape4k-version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-common")
}
```

## 공개 패키지

- `io.bluetape4k.spring.data.exposed.common.annotation` — `@ExposedEntity`, `@Query`.
- `io.bluetape4k.spring.data.exposed.common.mapping` — Exposed entity용 Spring Data mapping metadata.
- `io.bluetape4k.spring.data.exposed.common.repository.query` — PartTree 쿼리 계획과 parameter metadata.
- `io.bluetape4k.spring.data.exposed.common.repository.support` — `Sort.toExposedOrderBy` 변환.

JDBC 실행·트랜잭션·`ExposedEntityInformation`은
`bluetape4k-exposed-spring-boot-jdbc`가 계속 소유합니다. suspend 실행과 coroutine lifecycle은
`bluetape4k-exposed-spring-boot-r2dbc`가 소유합니다.

## 의존성 경계

R2DBC만 사용하는 애플리케이션은 common 모듈과 R2DBC adapter에만 의존할 수 있으며,
runtime graph에 JDBC adapter나 `spring-jdbc`를 포함하지 않습니다. 기존 JDBC package symbol은
JDBC artifact가 deprecated compatibility facade로 유지하므로 새 코드는 위 common package를
import해야 합니다.
