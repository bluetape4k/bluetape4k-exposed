val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

dependencies {
    api(libs.bluetape4k.logging)
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.java.time)
    api(libs.kotlinx.coroutines.core)
    api(libs.clickhouse.jdbc)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.clickhouse)
}
