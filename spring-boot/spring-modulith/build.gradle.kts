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

    api(bt4k.exposed.core)
    api(bt4k.exposed.jdbc)
    api(bt4k.exposed.java.time)
    api(bt4k.exposed.spring7.transaction)
    api(libs.spring.modulith.events.api)
    api(libs.spring.modulith.events.core)
    api("org.springframework:spring-tx")

    api(project(":bluetape4k-exposed-spring-boot-jdbc"))
    api(project(":bluetape4k-exposed-jdbc-caffeine"))

    implementation(libs.spring.modulith.events.jackson)

    compileOnly(libs.micrometer.core)
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-starter-jdbc")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.micrometer.core)
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(libs.h2.v2)
    testImplementation(bt4k.hikaricp)
    testImplementation(bt4k.mysql.connector.j)
    testImplementation(bt4k.postgresql)
}
