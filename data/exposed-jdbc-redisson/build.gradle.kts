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
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.kotlin.datetime)

    api(project(":bluetape4k-exposed-jdbc"))
    api(project(":bluetape4k-exposed-cache"))
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testImplementation(testFixtures(project(":bluetape4k-exposed-cache")))

    // Redisson
    api("io.github.bluetape4k:bluetape4k-redisson:${bluetape4kVersion}")
    api(libs.redisson)


    testImplementation("io.github.bluetape4k:bluetape4k-io:${bluetape4kVersion}")

    // Codecs
    compileOnly(libs.kryo5)
    compileOnly(libs.fory.kotlin)  // new Apache Fory

    // Compressor
    compileOnly(libs.snappy.java)
    compileOnly(libs.lz4.java)
    compileOnly(libs.zstd.jni)

    // Coroutines
    compileOnly("io.github.bluetape4k:bluetape4k-coroutines:${bluetape4kVersion}")
    compileOnly(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    // Bluetape4k Modules for Testing
    testImplementation("io.github.bluetape4k:bluetape4k-junit5:${bluetape4kVersion}")
    testImplementation("io.github.bluetape4k:bluetape4k-testcontainers:${bluetape4kVersion}")
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)

    testImplementation("io.github.bluetape4k:bluetape4k-idgenerators:${bluetape4kVersion}")

    // Database Drivers
    testImplementation(libs.hikaricp)
    testImplementation(libs.h2.v2)
    testImplementation(libs.mariadb.java.client)
    testImplementation(libs.mysql.connector.j)
    testImplementation(libs.postgresql.driver)
    testImplementation(libs.pgjdbc.ng)

}
