# bluetape4k-exposed-ktor-r2dbc

Coroutine-native R2DBC readiness and transaction helpers for Ktor. JDBC and
cache adapters are intentionally separate artifacts.

`exposedR2dbcTransaction` propagates cancellation and `Error` without wrapping;
ordinary exceptions become the cause of `ExposedKtorTransactionException`.
If recording failure metrics also throws, that secondary exception is attached
to the original cause as `suppressed`. This does not recover cleanup exceptions
consumed internally by Exposed.

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-r2dbc")
```

Use this module with the backend-neutral core. See the [module manual](https://bluetape4k.github.io/manual/bluetape4k-exposed/2.0/modules/bluetape4k-exposed-ktor-r2dbc/).
