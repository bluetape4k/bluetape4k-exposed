val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Exposed + ClickHouse (OLAP) + PostgreSQL (OLTP)
    testImplementation(project(":bluetape4k-exposed-clickhouse"))
    testImplementation(libs.exposed.core)
    testImplementation(libs.exposed.jdbc)
    testImplementation(libs.exposed.java.time)
    testImplementation(libs.postgresql.driver)
    testImplementation(libs.hikaricp)

    // ClickHouse (OLAP)
    testImplementation(libs.clickhouse.jdbc)

    // Coroutines
    testImplementation(libs.bluetape4k.coroutines)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    // Testing
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.clickhouse)
    testImplementation(libs.testcontainers.postgresql)
}
