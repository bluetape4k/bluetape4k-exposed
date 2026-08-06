dependencies {
    api(platform(bt4k.kotlinx.coroutines.bom))
    implementation(bt4k.bluetape4k.core)

    api(bt4k.bluetape4k.logging)
    api(bt4k.exposed.core)
    api(bt4k.exposed.jdbc)
    api(bt4k.exposed.java.time)
    api(libs.kotlinx.coroutines.core)
    api(bt4k.starrocks.connector.j)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
}
