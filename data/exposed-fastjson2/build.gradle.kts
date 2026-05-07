val bluetape4kVersion: String by project

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

    /* Fastjson2 */
    api("io.github.bluetape4k:bluetape4k-fastjson2:${bluetape4kVersion}")
    api(libs.fastjson2.kotlin)
    api(libs.fastjson2.extension)
    compileOnly(libs.r2dbc.spi)

    // Database Drivers
    testImplementation(libs.hikaricp)
    testRuntimeOnly(libs.h2.v2)
    testRuntimeOnly(libs.mariadb.java.client)
    testRuntimeOnly(libs.mysql.connector.j)
    testRuntimeOnly(libs.postgresql.driver)
    testRuntimeOnly(libs.pgjdbc.ng)

    testImplementation("io.github.bluetape4k:bluetape4k-junit5:${bluetape4kVersion}")
    testImplementation("io.github.bluetape4k:bluetape4k-testcontainers:${bluetape4kVersion}")
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)
}
