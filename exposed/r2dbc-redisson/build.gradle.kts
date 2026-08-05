
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Exposed
    implementation(platform(bt4k.exposed.bom))

    api(bt4k.exposed.core)
    api(bt4k.exposed.r2dbc)
    compileOnly(bt4k.exposed.java.time)
    compileOnly(libs.exposed.kotlin.datetime)

    api(project(":bluetape4k-exposed-r2dbc"))
    api(project(":bluetape4k-exposed-cache"))
    testImplementation(project(":bluetape4k-exposed-r2dbc-tests"))
    testImplementation(testFixtures(project(":bluetape4k-exposed-cache")))

    // Redisson
    api(bt4k.bluetape4k.redisson)
    api(bt4k.redisson)

    // Codecs
    api(bt4k.bluetape4k.io)

    // Serializers
    runtimeOnly(bt4k.kryo5)
    runtimeOnly(bt4k.fory.kotlin)  // new Apache Fory

    // Compressor
    runtimeOnly(bt4k.at.yawk.lz4.java)
    runtimeOnly(bt4k.snappy.java)
    runtimeOnly(bt4k.zstd.jni)

    // Coroutines
    implementation(bt4k.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(bt4k.bluetape4k.idgenerators)

    // R2DBC
    api(bt4k.r2dbc.spi)
    api(bt4k.r2dbc.pool)
    testRuntimeOnly(bt4k.r2dbc.h2)
    testRuntimeOnly(bt4k.r2dbc.mariadb)
    testRuntimeOnly(bt4k.r2dbc.mysql)
    testRuntimeOnly(bt4k.r2dbc.postgresql)

    // Bluetape4k Modules for Testing
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)

    // Database Drivers for Testcontainers Database
    testRuntimeOnly(bt4k.h2.v2)
    testRuntimeOnly(bt4k.mariadb.java.client)
    testRuntimeOnly(bt4k.mysql.connector.j)
    testRuntimeOnly(bt4k.postgresql)

}
