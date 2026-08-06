
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(platform(bt4k.kotlinx.coroutines.bom))

    // Exposed
    implementation(platform(bt4k.exposed.bom))
    api(project(":bluetape4k-exposed-core"))
    compileOnly(bt4k.exposed.jdbc)
    compileOnly(bt4k.exposed.java.time)

    // Logging
    implementation(bt4k.bluetape4k.logging)

    // PostgreSQL 전용 라이브러리 (사용자가 필요한 것만 런타임에 추가)
    compileOnly(bt4k.postgis.y2025)          // PostGIS 사용 시만
    compileOnly(bt4k.pgvector)              // pgvector 사용 시만

    // Database Drivers
    compileOnly(bt4k.postgresql)

    // Testing
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)

    testRuntimeOnly(bt4k.h2.v2)
    testRuntimeOnly(bt4k.postgresql)
    testRuntimeOnly(bt4k.mysql.connector.j)   // Testcontainers MySQL8 startup verification
    testRuntimeOnly(bt4k.hikaricp)
}
