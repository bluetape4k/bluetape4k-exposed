# Issue 213 Exposed Gradle Plugin

## 배경

Issue #213은 repository가 JetBrains official Exposed Gradle plugin을 채택하고
downstream repository의 plugin usage를 문서화해 달라고 요청했습니다.

## 결정

central `bt4k` version catalog의 shared JetBrains Exposed version line에서
`org.jetbrains.exposed.plugin`을 소비하고 root build에서 `apply false`로 노출합니다.
concrete table package와 H2 migration target이 있는 demo application module에만
apply/configure합니다.

## 결과

JDBC와 R2DBC demo module은 executable `generateMigrations` task를 노출하고 plugin이
생성한 deterministic initial migration script를 포함합니다. root README file은 plugin을
문서화하고 JetBrains documentation 및 Gradle Plugin Portal로 연결합니다.

## 검증

- `./gradlew help --configuration-cache`
- `./gradlew :exposed-spring-boot-jdbc-demo:generateMigrations --filename=V1__create_products.sql --configuration-cache`
- `./gradlew :exposed-spring-boot-r2dbc-demo:generateMigrations --filename=V1__create_webflux_products.sql --configuration-cache`
- `./gradlew :exposed-spring-boot-jdbc-demo:test :exposed-spring-boot-r2dbc-demo:test --configuration-cache`

## 향후 guard

concrete `exposed.migrations` configuration이 없으면 Exposed Gradle plugin을 library module에
apply하지 않습니다. 그렇지 않으면 task는 보이지만 migration generation에는 유용하지
않습니다.
