val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

dependencies {
    api(libs.bluetape4k.logging)
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.java.time)
    api(libs.kotlinx.coroutines.core)

    // Trino JDBC 드라이버
    api(libs.trino.jdbc)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.trino)
}
