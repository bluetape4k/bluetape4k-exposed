# bluetape4k-exposed-ktor-r2dbc

Ktor를 위한 coroutine-native R2DBC readiness와 transaction helper입니다. JDBC와
cache 어댑터는 별도 아티팩트입니다.

`exposedR2dbcTransaction`은 취소와 `Error`를 감싸지 않고 전달하며,
일반 예외는 `ExposedKtorTransactionException`의 원인으로 보존합니다.
실패 메트릭 기록도 실패하면 해당 예외를 주원인의 `suppressed`에 추가합니다.
Exposed 내부에서 소비한 정리 예외까지 복원하는 계약은 아닙니다.

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-r2dbc")
```

backend-neutral core와 함께 사용하세요. [모듈 매뉴얼](https://bluetape4k.github.io/ko/manual/bluetape4k-exposed/2.0/modules/bluetape4k-exposed-ktor-r2dbc/)을
참고하세요.
