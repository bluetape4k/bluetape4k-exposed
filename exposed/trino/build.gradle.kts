
dependencies {
    api(bt4k.bluetape4k.logging)
    api(bt4k.exposed.core)
    api(bt4k.exposed.jdbc)
    api(bt4k.exposed.java.time)
    api(libs.kotlinx.coroutines.core)

    // Trino JDBC 드라이버
    api(bt4k.trino.jdbc)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.trino)
}
