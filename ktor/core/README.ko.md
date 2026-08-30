# bluetape4k-exposed-ktor-core

선택형 Exposed 아티팩트가 공유하는 backend-neutral Ktor health/readiness 계약입니다.
JDBC, R2DBC, cache backend 의존성을 포함하지 않습니다.

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-core")
```

호출자가 소유한 cooperative probe와 `Route.bluetape4kExposedHealthRoutes`를
사용하세요. [모듈 매뉴얼](https://bluetape4k.github.io/ko/manual/bluetape4k-exposed/1.12/modules/bluetape4k-exposed-ktor-core/)을
참고하세요.
