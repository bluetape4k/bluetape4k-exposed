dependencies {
    api(platform(bt4k.kotlinx.coroutines.bom))
    api(bt4k.bluetape4k.core)
    api(bt4k.exposed.core)
    api(bt4k.exposed.jdbc)
    api(bt4k.exposed.java.time)
    api(libs.kotlinx.coroutines.core)
    api(bt4k.clickhouse.jdbc)

    testImplementation(bt4k.hikaricp)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.clickhouse)
    testImplementation(libs.kotlinx.coroutines.test)
}
