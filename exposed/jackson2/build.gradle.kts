val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Exposed
    implementation(platform(libs.exposed.bom))
    api(libs.exposed.core)
    compileOnly(libs.exposed.jdbc)
    compileOnly(libs.exposed.dao)
    api(project(":bluetape4k-exposed-core"))
    compileOnly(project(":bluetape4k-exposed-dao"))
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))

    /* Jackson */
    api(libs.bluetape4k.jackson2)
    api(libs.jackson.module.kotlin)
    implementation(libs.jackson.module.blackbird)

    // R2DBC - ReadableExtensions
    compileOnly(libs.r2dbc.spi)

    // Database Drivers
    testRuntimeOnly(libs.hikaricp)
    testRuntimeOnly(libs.h2.v2)
    testRuntimeOnly(libs.mariadb.java.client)
    testRuntimeOnly(libs.mysql.connector.j)
    testRuntimeOnly(libs.postgresql.driver)
    testRuntimeOnly(libs.pgjdbc.ng)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)
}
