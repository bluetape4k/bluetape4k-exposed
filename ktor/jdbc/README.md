# bluetape4k-exposed-ktor-jdbc

Caller-owned JDBC `Database` readiness and transaction helpers for Ktor.
Blocking work runs on the dispatcher supplied by the application.

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-jdbc")
```

Use this module with `bluetape4k-exposed-ktor-core`; R2DBC and cache adapters are
separate. See the [module manual](../../docs/manual/en/modules/bluetape4k-exposed-ktor-jdbc.md).
