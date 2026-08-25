plugins {
    kotlin("plugin.spring")
    id("org.jetbrains.kotlinx.kover")
    application
}

application {
    mainClass.set("io.bluetape4k.exposed.examples.modulith.DddSpringModulithDemoApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(bt4k.spring.boot4.dependencies))
    implementation(platform(bt4k.spring.modulith.bom))

    implementation(project(":bluetape4k-exposed-core"))
    implementation(project(":bluetape4k-exposed-spring-boot-jdbc"))
    implementation(project(":bluetape4k-exposed-spring-modulith"))
    implementation(bt4k.bluetape4k.idgenerators)

    implementation(bt4k.exposed.core)
    implementation(bt4k.exposed.jdbc)
    implementation(bt4k.exposed.java.time)
    implementation(bt4k.exposed.spring7.transaction)
    implementation(bt4k.hikaricp)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation(libs.spring.modulith.events.jackson)

    runtimeOnly(bt4k.h2.v2)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(libs.awaitility.kotlin)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
}
