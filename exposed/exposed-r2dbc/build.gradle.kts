val bluetape4kVersion: String by project

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Exposed
    implementation(platform(libs.exposed.bom))

    api(libs.exposed.core)
    api(libs.exposed.r2dbc)
    compileOnly(libs.exposed.java.time)
    compileOnly(libs.exposed.migration.r2dbc)
    testImplementation(libs.exposed.java.time)

    api(project(":bluetape4k-exposed-core"))
    api(project(":bluetape4k-exposed-dao"))
    testImplementation(project(":bluetape4k-exposed-r2dbc-tests"))

    api("io.github.bluetape4k:bluetape4k-r2dbc:${bluetape4kVersion}")
    api(libs.r2dbc.spi)
    testRuntimeOnly(libs.r2dbc.h2)
    testRuntimeOnly(libs.r2dbc.mariadb)
    testRuntimeOnly(libs.r2dbc.mysql)
    testRuntimeOnly(libs.r2dbc.postgresql)

    // Coroutines
    api("io.github.bluetape4k:bluetape4k-coroutines:${bluetape4kVersion}")
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    compileOnly("io.github.bluetape4k:bluetape4k-io:${bluetape4kVersion}")
    compileOnly("io.github.bluetape4k:bluetape4k-idgenerators:${bluetape4kVersion}")

    // Bluetape4k Modules for Testing
    testImplementation("io.github.bluetape4k:bluetape4k-junit5:${bluetape4kVersion}")
    testImplementation("io.github.bluetape4k:bluetape4k-testcontainers:${bluetape4kVersion}")
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)

    // Database Drivers for Testcontainers Databases
    testRuntimeOnly(libs.h2.v2)
    testRuntimeOnly(libs.mariadb.java.client)
    testRuntimeOnly(libs.mysql.connector.j)
    testRuntimeOnly(libs.postgresql.driver)
}
