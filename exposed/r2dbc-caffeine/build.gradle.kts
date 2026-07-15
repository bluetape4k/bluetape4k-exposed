val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":bluetape4k-exposed-r2dbc"))
    api(project(":bluetape4k-exposed-cache"))
    api(bt4k.bluetape4k.coroutines)
    api(libs.caffeine)

    api(bt4k.exposed.core)
    api(bt4k.exposed.r2dbc)
    compileOnly(bt4k.exposed.java.time)
    compileOnly(libs.exposed.kotlin.datetime)

    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.reactive)

    testRuntimeOnly(bt4k.r2dbc.h2)
    testRuntimeOnly(bt4k.postgresql)     // Testcontainers PostgreSQL startup verification
    testRuntimeOnly(bt4k.mysql.connector.j)     // Testcontainers MySQL8 startup verification
    testImplementation(testFixtures(project(":bluetape4k-exposed-cache")))
    testImplementation(project(":bluetape4k-exposed-r2dbc-tests"))
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(libs.h2.v2)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.awaitility.kotlin)
}
