val bluetape4kVersion: String by project

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Exposed
    implementation(platform(libs.exposed.bom))
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.dao)
    implementation(libs.exposed.crypt)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.exposed.money)
    implementation(libs.exposed.migration.jdbc)
    implementation(libs.exposed.spring.boot.starter)

    // Bluetape4k
    compileOnly("io.github.bluetape4k:bluetape4k-jdbc:${bluetape4kVersion}")
    compileOnly("io.github.bluetape4k:bluetape4k-io:${bluetape4kVersion}")
    

    api("io.github.bluetape4k:bluetape4k-junit5:${bluetape4kVersion}")
    api("io.github.bluetape4k:bluetape4k-testcontainers:${bluetape4kVersion}")
    api(libs.testcontainers)
    api(libs.testcontainers.mariadb)
    api(libs.testcontainers.mysql)
    api(libs.testcontainers.postgresql)
    // compileOnly(libs.testcontainers.cockroachdb)

    // Database Drivers
    compileOnly(libs.hikaricp)

    // Database Drivers
    compileOnly(libs.h2.v2)
    compileOnly(libs.mariadb.java.client)
    compileOnly(libs.mysql.connector.j)
    compileOnly(libs.postgresql.driver)
    compileOnly(libs.pgjdbc.ng)

    // Coroutines
    implementation("io.github.bluetape4k:bluetape4k-coroutines:${bluetape4kVersion}")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.debug)
    implementation(libs.kotlinx.coroutines.test)

    // Id Generators
    implementation("io.github.bluetape4k:bluetape4k-idgenerators:${bluetape4kVersion}")
    implementation(libs.java.uuid.generator)
}
