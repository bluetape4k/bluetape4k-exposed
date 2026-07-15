val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(bt4k.bluetape4k.lettuce)
    api(bt4k.bluetape4k.cache.lettuce)
    api(project(":bluetape4k-exposed-r2dbc"))
    api(project(":bluetape4k-exposed-cache"))
    api(bt4k.bluetape4k.jackson3)
    api(bt4k.bluetape4k.resilience4j)
    api(libs.resilience4j.retry)

    // Exposed R2DBC
    api(bt4k.exposed.core)
    api(bt4k.exposed.r2dbc)
    compileOnly(bt4k.exposed.java.time)
    compileOnly(libs.exposed.kotlin.datetime)

    // Lettuce
    api(libs.lettuce.core)

    // Serializer (LettuceLoadedMap 코덱용)
    compileOnly(bt4k.fory.kotlin)
    compileOnly(libs.kryo5)

    // Compressor
    compileOnly(libs.snappy.java)
    compileOnly(libs.lz4.java)
    compileOnly(bt4k.zstd.jni)

    // Coroutines (R2DBC suspend 브리징)
    implementation(bt4k.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)

    // R2DBC drivers (test)
    testRuntimeOnly(bt4k.r2dbc.h2)
    testRuntimeOnly(bt4k.postgresql)     // Testcontainers PostgreSQL startup verification
    testRuntimeOnly(bt4k.mysql.connector.j)     // Testcontainers MySQL8 startup verification

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(project(":bluetape4k-exposed-r2dbc-tests"))
    testImplementation(testFixtures(project(":bluetape4k-exposed-cache")))
    testImplementation(libs.h2.v2)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(bt4k.bluetape4k.idgenerators)
}
