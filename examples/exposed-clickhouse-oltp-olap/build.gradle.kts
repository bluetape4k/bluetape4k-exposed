
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Exposed + ClickHouse (OLAP) + PostgreSQL (OLTP)
    testImplementation(project(":bluetape4k-exposed-clickhouse"))
    testImplementation(bt4k.exposed.core)
    testImplementation(bt4k.exposed.jdbc)
    testImplementation(bt4k.exposed.java.time)
    testImplementation(bt4k.postgresql)
    testImplementation(bt4k.hikaricp)

    // ClickHouse (OLAP)
    testImplementation(bt4k.clickhouse.jdbc)

    // Coroutines
    testImplementation(bt4k.bluetape4k.coroutines)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    // Testing
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.clickhouse)
    testImplementation(libs.testcontainers.postgresql)
}
