# bluetape4k-exposed-ktor-cache

Backend-neutral, caller-owned cache readiness contributors for Ktor. Suppliers
must be O(1), in-memory, side-effect-free, and cancellation-cooperative.

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-cache")
```

Use this module with the core route and choose database adapters independently.
See the [module manual](../../docs/manual/en/modules/bluetape4k-exposed-ktor-cache.md).
