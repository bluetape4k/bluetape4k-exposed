# bluetape4k-exposed-ktor-jdbc

호출자가 소유한 JDBC `Database`를 위한 Ktor readiness와 transaction helper입니다.
Blocking 작업은 애플리케이션이 제공한 dispatcher에서 실행됩니다.

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-jdbc")
```

이 모듈은 `bluetape4k-exposed-ktor-core`와 함께 사용하며 R2DBC와 cache 어댑터는
분리되어 있습니다. [모듈 매뉴얼](https://bluetape4k.github.io/ko/manual/bluetape4k-exposed/1.12/modules/bluetape4k-exposed-ktor-jdbc/)을
참고하세요.
