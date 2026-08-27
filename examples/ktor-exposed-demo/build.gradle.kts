plugins {
    application
    alias(bt4k.plugins.kotlin.serialization)
}

application {
    mainClass.set("io.bluetape4k.examples.exposed.ktor.KtorExposedDemoApplicationKt")
}

val postgresIntegrationTest = sourceSets.create("postgresIntegrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

kotlin.target.compilations.getByName(postgresIntegrationTest.name)
    .associateWith(kotlin.target.compilations.getByName("main"))

configurations.named(postgresIntegrationTest.implementationConfigurationName) {
    extendsFrom(configurations.implementation.get(), configurations.testImplementation.get())
}
configurations.named(postgresIntegrationTest.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.runtimeOnly.get(), configurations.testRuntimeOnly.get())
}

dependencies {
    implementation(platform(bt4k.ktor.bom))
    implementation(platform(bt4k.exposed.bom))

    implementation(project(":bluetape4k-exposed-ktor-core"))
    implementation(project(":bluetape4k-exposed-ktor-jdbc"))
    implementation(project(":bluetape4k-exposed-ktor-r2dbc"))
    implementation(project(":bluetape4k-exposed-ktor-cache"))
    implementation(project(":bluetape4k-exposed-r2dbc-caffeine"))
    implementation(bt4k.bluetape4k.idgenerators)
    implementation(bt4k.bluetape4k.ktor.core)
    implementation(bt4k.bluetape4k.logging)
    implementation(bt4k.exposed.jdbc)
    implementation(bt4k.exposed.r2dbc)
    implementation(bt4k.exposed.java.time)
    implementation(bt4k.hikaricp)
    implementation(bt4k.r2dbc.pool)
    implementation(libs.kotlinx.coroutines.core)
    implementation("io.ktor:ktor-server-netty")

    runtimeOnly(bt4k.h2.v2)
    runtimeOnly(bt4k.r2dbc.postgresql)

    testImplementation(bt4k.bluetape4k.ktor.testing)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(bt4k.bluetape4k.junit5)

    add(postgresIntegrationTest.implementationConfigurationName, libs.testcontainers.postgresql)
}

tasks.register<Test>("postgresIntegrationTest") {
    description = "Runs the sequential PostgreSQL Ktor demo integration tests."
    group = "verification"
    testClassesDirs = postgresIntegrationTest.output.classesDirs
    classpath = postgresIntegrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    maxParallelForks = 1
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    useJUnitPlatform()
}
