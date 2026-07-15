val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Exposed
    implementation(platform(libs.exposed.bom))
    api(bt4k.exposed.core)
    api(bt4k.exposed.jdbc)
    api(libs.exposed.dao)
    implementation(libs.exposed.crypt)
    implementation(libs.exposed.kotlin.datetime)
    implementation(bt4k.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.exposed.money)
    implementation(bt4k.exposed.migration.jdbc)
    implementation(bt4k.exposed.spring.boot4.starter)

    // Bluetape4k
    compileOnly(bt4k.bluetape4k.jdbc)
    compileOnly(bt4k.bluetape4k.io)
    

    api(bt4k.bluetape4k.junit5)
    api(bt4k.bluetape4k.testcontainers)
    api(libs.testcontainers)
    api(libs.testcontainers.mariadb)
    api(libs.testcontainers.mysql)
    api(libs.testcontainers.postgresql)
    // compileOnly(libs.testcontainers.cockroachdb)

    // Database Drivers
    compileOnly(bt4k.hikaricp)

    // Database Drivers
    compileOnly(libs.h2.v2)
    compileOnly(libs.mariadb.java.client)
    compileOnly(bt4k.mysql.connector.j)
    compileOnly(bt4k.postgresql)
    compileOnly(libs.pgjdbc.ng)

    // Coroutines
    implementation(bt4k.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.debug)
    implementation(libs.kotlinx.coroutines.test)

    // Id Generators
    implementation(bt4k.bluetape4k.idgenerators)
    implementation(libs.java.uuid.generator)
}
