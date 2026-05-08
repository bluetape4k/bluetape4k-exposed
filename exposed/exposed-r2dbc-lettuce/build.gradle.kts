val bluetape4kVersion: String by project

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api("io.github.bluetape4k:bluetape4k-lettuce:${bluetape4kVersion}")
    api("io.github.bluetape4k:bluetape4k-cache-lettuce:${bluetape4kVersion}")
    api(project(":bluetape4k-exposed-r2dbc"))
    api(project(":bluetape4k-exposed-cache"))
    api("io.github.bluetape4k:bluetape4k-resilience4j:${bluetape4kVersion}")
    api(libs.resilience4j.retry)

    // Exposed R2DBC
    api(libs.exposed.core)
    api(libs.exposed.r2dbc)
    compileOnly(libs.exposed.java.time)
    compileOnly(libs.exposed.kotlin.datetime)

    // Lettuce
    api(libs.lettuce.core)

    // Serializer (LettuceLoadedMap 코덱용)
    compileOnly(libs.fory.kotlin)
    compileOnly(libs.kryo5)

    // Compressor
    compileOnly(libs.snappy.java)
    compileOnly(libs.lz4.java)
    compileOnly(libs.zstd.jni)

    // Coroutines (R2DBC suspend 브리징)
    implementation("io.github.bluetape4k:bluetape4k-coroutines:${bluetape4kVersion}")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)

    // R2DBC drivers (test)
    testRuntimeOnly(libs.r2dbc.h2)
    testRuntimeOnly(libs.postgresql.driver)     // Testcontainers PostgreSQL startup verification
    testRuntimeOnly(libs.mysql.connector.j)     // Testcontainers MySQL8 startup verification

    testImplementation("io.github.bluetape4k:bluetape4k-junit5:${bluetape4kVersion}")
    testImplementation("io.github.bluetape4k:bluetape4k-testcontainers:${bluetape4kVersion}")
    testImplementation(project(":bluetape4k-exposed-r2dbc-tests"))
    testImplementation(testFixtures(project(":bluetape4k-exposed-cache")))
    testImplementation(libs.h2.v2)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("io.github.bluetape4k:bluetape4k-idgenerators:${bluetape4kVersion}")
}
