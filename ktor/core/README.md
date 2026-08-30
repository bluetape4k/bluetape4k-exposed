# bluetape4k-exposed-ktor-core

Backend-neutral Ktor health and readiness contracts for selective Exposed
artifacts. It has no JDBC, R2DBC, or cache backend dependency.

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-core")
```

Use `Route.bluetape4kExposedHealthRoutes` with caller-owned cooperative probes.
See the [module manual](https://bluetape4k.github.io/manual/bluetape4k-exposed/1.12/modules/bluetape4k-exposed-ktor-core/).
