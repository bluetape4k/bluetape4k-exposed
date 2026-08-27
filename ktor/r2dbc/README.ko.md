# bluetape4k-exposed-ktor-r2dbc

Ktor를 위한 coroutine-native R2DBC readiness와 transaction helper입니다. JDBC와
cache 어댑터는 별도 아티팩트입니다.

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-r2dbc")
```

backend-neutral core와 함께 사용하세요. [모듈 매뉴얼](../../docs/manual/ko/modules/bluetape4k-exposed-ktor-r2dbc.md)을
참고하세요.
