# bluetape4k-exposed-ktor-cache

Ktor를 위한 backend-neutral 호출자 소유 cache readiness contributor입니다.
Supplier는 O(1) 메모리 조회이며 side-effect-free, cancellation-cooperative여야 합니다.

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-cache")
```

core route와 함께 사용하고 database 어댑터는 필요한 것만 선택하세요. [모듈 매뉴얼](https://bluetape4k.github.io/ko/manual/bluetape4k-exposed/2.0/modules/bluetape4k-exposed-ktor-cache/)을
참고하세요.
