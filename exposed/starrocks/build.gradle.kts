dependencies {
    api(platform(bt4k.kotlinx.coroutines.bom))

    api(bt4k.bluetape4k.core)

    api(bt4k.exposed.core)
    api(bt4k.exposed.jdbc)
    api(bt4k.exposed.java.time)
    api(bt4k.starrocks.connector.j)

    // Coroutines
    api(bt4k.bluetape4k.coroutines)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)

    testImplementation(bt4k.hikaricp)
}
