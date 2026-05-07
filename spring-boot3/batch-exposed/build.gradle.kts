val bluetape4kVersion: String by project

plugins {
    kotlin("plugin.spring")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(libs.spring.boot3.dependencies))
    // Core
    api(libs.kotlin.reflect)
    api(project(":bluetape4k-exposed-jdbc"))
    api(project(":bluetape4k-exposed-core"))
    api("io.github.bluetape4k:bluetape4k-virtualthread-api:${bluetape4kVersion}")

    // Exposed
    api(libs.exposed.spring.transaction)
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.java.time)

    // Spring Batch (Spring Boot BOM 버전 관리)
    api("org.springframework.boot:spring-boot-starter-batch")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")

    // Test
    testImplementation("io.github.bluetape4k:bluetape4k-junit5:${bluetape4kVersion}")
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testImplementation("io.github.bluetape4k:bluetape4k-virtualthread-jdk21:${bluetape4kVersion}")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.spring.batch.test)
    testImplementation(libs.h2.v2)
    testImplementation(libs.hikaricp)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql.driver)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.mysql.connector.j)
}
