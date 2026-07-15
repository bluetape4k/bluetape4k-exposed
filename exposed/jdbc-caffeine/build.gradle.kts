val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":bluetape4k-exposed-jdbc"))
    api(project(":bluetape4k-exposed-cache"))
    api(libs.caffeine)

    api(bt4k.exposed.core)
    api(bt4k.exposed.jdbc)
    compileOnly(bt4k.exposed.java.time)
    compileOnly(libs.exposed.kotlin.datetime)

    api(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(project(":bluetape4k-exposed-cache")))
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.h2.v2)
    testImplementation(bt4k.hikaricp)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.awaitility.kotlin)

    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.mariadb.java.client)
    testImplementation(bt4k.mysql.connector.j)
    testImplementation(bt4k.postgresql)
    testImplementation(libs.pgjdbc.ng)
}
