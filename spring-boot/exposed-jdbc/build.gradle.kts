val bluetape4kVersion: String by project

plugins {
    kotlin("plugin.spring")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Spring Boot BOM: platform()을 사용하면 compileClasspath/runtimeClasspath에만 적용되고
    // kotlinBuildToolsApiClasspath 같은 내부 Gradle 설정에는 영향을 주지 않음
    // (dependencyManagement 플러그인은 ALL configurations에 적용되어 kotlin-stdlib 버전 충돌 유발)
    implementation(platform(libs.spring.boot.dependencies))

    api("org.springframework.data:spring-data-commons")

    api(libs.kotlin.reflect)
    api("io.github.bluetape4k:bluetape4k-logging:${bluetape4kVersion}")
    api(libs.exposed.core)
    api(libs.exposed.dao)
    api(libs.exposed.jdbc)
    api(libs.exposed.java.time)
    api(libs.exposed.spring7.transaction)

    testImplementation(libs.exposed.migration.jdbc)
    testImplementation(libs.flyway.core)
    testImplementation("io.github.bluetape4k:bluetape4k-junit5:${bluetape4kVersion}")

    testImplementation("io.github.bluetape4k:bluetape4k-virtualthread-jdk21:${bluetape4kVersion}")

    api(project(":bluetape4k-exposed-jdbc"))
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))

    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-starter-data-jdbc")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.h2.v2)
    testImplementation(libs.hikaricp)

    // Multi-DB 테스트용 JDBC 드라이버
    testImplementation(libs.mysql.connector.j)
    testImplementation(libs.mariadb.java.client)
    testImplementation(libs.postgresql.driver)
}
