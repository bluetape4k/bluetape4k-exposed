val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(bt4k.bluetape4k.cache.lettuce)
    api(bt4k.bluetape4k.lettuce)
    api(project(":bluetape4k-exposed-jdbc"))
    api(project(":bluetape4k-exposed-cache"))
    api(bt4k.bluetape4k.jackson3)
    api(bt4k.bluetape4k.resilience4j)
    api(libs.resilience4j.retry)

    // Exposed
    api(bt4k.exposed.core)
    api(libs.exposed.dao)
    api(bt4k.exposed.jdbc)
    api(bt4k.exposed.java.time)

    // Lettuce
    api(libs.lettuce.core)

    // Serializer (LettuceLoadedMap에서 사용하는 codec용)
    compileOnly(bt4k.fory.kotlin)
    compileOnly(libs.kryo5)

    // Compressor
    compileOnly(libs.snappy.java)
    compileOnly(libs.lz4.java)
    compileOnly(bt4k.zstd.jni)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testImplementation(testFixtures(project(":bluetape4k-exposed-cache")))

    testImplementation(libs.h2.v2)
    testImplementation(bt4k.hikaricp)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.mariadb.java.client)
    testImplementation(bt4k.mysql.connector.j)
    testImplementation(bt4k.postgresql)
    testImplementation(libs.pgjdbc.ng)
}
