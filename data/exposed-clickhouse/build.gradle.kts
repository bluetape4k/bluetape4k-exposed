val bluetape4kVersion: String by project

dependencies {
    api("io.github.bluetape4k:bluetape4k-logging:${bluetape4kVersion}")
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.java.time)
    api(libs.kotlinx.coroutines.core)
    api(libs.clickhouse.jdbc)

    testImplementation("io.github.bluetape4k:bluetape4k-junit5:${bluetape4kVersion}")
    testImplementation("io.github.bluetape4k:bluetape4k-testcontainers:${bluetape4kVersion}")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.clickhouse)
}
