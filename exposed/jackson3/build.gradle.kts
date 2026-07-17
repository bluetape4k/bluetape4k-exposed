val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Exposed
    implementation(platform(bt4k.exposed.bom))
    api(bt4k.exposed.core)
    compileOnly(bt4k.exposed.jdbc)
    compileOnly(libs.exposed.dao)
    api(project(":bluetape4k-exposed-core"))
    compileOnly(project(":bluetape4k-exposed-dao"))
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))

    /* Jackson */
    api(bt4k.bluetape4k.jackson3)
    api(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    // R2DBC - ReadableExtensions
    compileOnly(libs.r2dbc.spi)

    // Database Drivers
    testRuntimeOnly(bt4k.hikaricp)
    testRuntimeOnly(libs.h2.v2)
    testRuntimeOnly(libs.mariadb.java.client)
    testRuntimeOnly(bt4k.mysql.connector.j)
    testRuntimeOnly(bt4k.postgresql)
    testRuntimeOnly(libs.pgjdbc.ng)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)
}
