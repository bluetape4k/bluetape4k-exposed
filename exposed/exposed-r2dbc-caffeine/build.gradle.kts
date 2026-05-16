val bluetape4kVersion: String by project

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":exposed-r2dbc"))
    api(project(":exposed-cache"))
    api(libs.bluetape4k.coroutines)
    api(libs.caffeine)

    api(libs.exposed.core)
    api(libs.exposed.r2dbc)
    compileOnly(libs.exposed.java.time)
    compileOnly(libs.exposed.kotlin.datetime)

    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.reactive)

    testRuntimeOnly(libs.r2dbc.h2)
    testRuntimeOnly(libs.postgresql.driver)     // Testcontainers PostgreSQL startup verification
    testRuntimeOnly(libs.mysql.connector.j)     // Testcontainers MySQL8 startup verification
    testImplementation(testFixtures(project(":exposed-cache")))
    testImplementation(project(":exposed-r2dbc-tests"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.h2.v2)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.awaitility.kotlin)
}
