val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

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
    implementation(platform(bt4k.spring.boot4.dependencies))

    api("org.springframework.data:spring-data-commons")

    api(libs.kotlin.reflect)
    api(bt4k.bluetape4k.logging)
    api(bt4k.exposed.core)
    api(libs.exposed.dao)
    api(bt4k.exposed.jdbc)
    api(bt4k.exposed.java.time)
    api(bt4k.exposed.spring7.transaction)

    testImplementation(bt4k.exposed.migration.jdbc)
    testImplementation(bt4k.flyway.core)
    testImplementation(bt4k.bluetape4k.junit5)

    testImplementation(bt4k.bluetape4k.virtualthread.jdk21)

    api(project(":bluetape4k-exposed-jdbc"))
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))

    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-health")
    compileOnly("org.springframework.boot:spring-boot-starter-data-jdbc")
    compileOnly(project(":bluetape4k-exposed-jdbc-caffeine"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.mockk)
    testImplementation(libs.h2.v2)
    testImplementation(bt4k.hikaricp)

    // Multi-DB 테스트용 JDBC 드라이버
    testImplementation(bt4k.mysql.connector.j)
    testImplementation(libs.mariadb.java.client)
    testImplementation(bt4k.postgresql)
}
