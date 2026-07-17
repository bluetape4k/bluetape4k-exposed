val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Exposed
    implementation(platform(bt4k.exposed.bom))
    api(project(":bluetape4k-exposed-core"))
    compileOnly(bt4k.exposed.jdbc)
    compileOnly(bt4k.exposed.java.time)  // 현재 미사용, exposed-postgresql 패턴과 일관성 위해 포함

    // Logging
    implementation(bt4k.bluetape4k.logging)

    // MySQL 8 GIS 전용 라이브러리 (사용자가 필요한 것만 런타임에 추가)
    api(libs.jts.core)                   // JTS Core (Geometry 타입)

    // Database Drivers
    compileOnly(bt4k.mysql.connector.j)

    // Testing
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mysql)

    testRuntimeOnly(bt4k.mysql.connector.j)
    testRuntimeOnly(bt4k.hikaricp)
}
