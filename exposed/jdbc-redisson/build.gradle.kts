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
    implementation(bt4k.exposed.java.time)
    implementation(libs.exposed.kotlin.datetime)

    api(project(":bluetape4k-exposed-jdbc"))
    api(project(":bluetape4k-exposed-cache"))
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testImplementation(testFixtures(project(":bluetape4k-exposed-cache")))

    // Redisson
    api(bt4k.bluetape4k.redisson)
    api(bt4k.redisson)


    testImplementation(bt4k.bluetape4k.io)

    // Codecs
    compileOnly(libs.kryo5)
    compileOnly(bt4k.fory.kotlin)  // new Apache Fory

    // Compressor
    compileOnly(libs.snappy.java)
    compileOnly(libs.lz4.java)
    compileOnly(bt4k.zstd.jni)

    // Coroutines
    compileOnly(bt4k.bluetape4k.coroutines)
    compileOnly(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    // Bluetape4k Modules for Testing
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)

    testImplementation(bt4k.bluetape4k.idgenerators)

    // Database Drivers
    testImplementation(bt4k.hikaricp)
    testImplementation(libs.h2.v2)
    testImplementation(libs.mariadb.java.client)
    testImplementation(bt4k.mysql.connector.j)
    testImplementation(bt4k.postgresql)
    testImplementation(libs.pgjdbc.ng)

}
