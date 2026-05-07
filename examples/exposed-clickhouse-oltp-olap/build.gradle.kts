val bluetape4kVersion: String by project

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
    testImplementation("io.github.bluetape4k:bluetape4k-coroutines:${bluetape4kVersion}")
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    // Testing
    testImplementation("io.github.bluetape4k:bluetape4k-junit5:${bluetape4kVersion}")
    testImplementation("io.github.bluetape4k:bluetape4k-testcontainers:${bluetape4kVersion}")
    testImplementation(libs.testcontainers.clickhouse)
    testImplementation(libs.testcontainers.postgresql)
}
