val bluetape4kVersion: String by project

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":exposed-jdbc"))
    api(project(":exposed-cache"))
    api(libs.caffeine)

    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    compileOnly(libs.exposed.java.time)
    compileOnly(libs.exposed.kotlin.datetime)

    api(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(project(":exposed-cache")))
    testImplementation(project(":exposed-jdbc-tests"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.h2.v2)
    testImplementation(libs.hikaricp)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.awaitility.kotlin)

    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.mariadb.java.client)
    testImplementation(libs.mysql.connector.j)
    testImplementation(libs.postgresql.driver)
    testImplementation(libs.pgjdbc.ng)
}
