val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

plugins {
    kotlin("plugin.spring")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(platform(libs.spring.modulith.bom))

    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.java.time)
    api(libs.exposed.spring7.transaction)
    api(libs.spring.modulith.events.api)
    api(libs.spring.modulith.events.core)
    api("org.springframework:spring-tx")

    api(project(":bluetape4k-exposed-spring-boot-jdbc"))
    api(project(":bluetape4k-exposed-jdbc-caffeine"))

    implementation(libs.spring.modulith.events.jackson)

    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-starter-jdbc")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.h2.v2)
    testImplementation(libs.hikaricp)
    testImplementation(libs.mysql.connector.j)
    testImplementation(libs.postgresql.driver)
}
