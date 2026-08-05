
plugins {
    kotlin("plugin.allopen")
    alias(bt4k.plugins.kotlinx.benchmark)
}

allOpen {
    // https://github.com/Kotlin/kotlinx-benchmark
    annotation("org.openjdk.jmh.annotations.State")
}

// https://github.com/Kotlin/kotlinx-benchmark
benchmark {
    targets {
        register("test") {
            this as kotlinx.benchmark.gradle.JvmBenchmarkTarget
            jmhVersion = bt4k.versions.managed.jmh.core.h350a653f63e5.get()
        }
    }
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(bt4k.spring.boot4.dependencies))
    // Exposed
    implementation(platform(bt4k.exposed.bom))
    api(project(":bluetape4k-exposed-core"))
    api(project(":bluetape4k-exposed-dao"))
    api(bt4k.exposed.jdbc)
    compileOnly(bt4k.exposed.migration.jdbc)
    compileOnly(bt4k.exposed.spring.boot4.starter)
    compileOnly(bt4k.exposed.java.time)
    compileOnly(libs.exposed.kotlin.datetime)

    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))

    // Entity ID generators
    implementation(bt4k.bluetape4k.idgenerators)
    implementation(bt4k.java.uuid.generator)

    // JDBC
    api(bt4k.bluetape4k.jdbc)
    compileOnly(bt4k.hikaricp)

    // Coroutines
    compileOnly(bt4k.bluetape4k.coroutines)
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)

    // Spring Boot (테스트용)
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }

    // Database Drivers
    testRuntimeOnly(bt4k.h2.v2)
    testRuntimeOnly(bt4k.mariadb.java.client)
    testRuntimeOnly(bt4k.mysql.connector.j)
    testRuntimeOnly(bt4k.postgresql)
    testRuntimeOnly(bt4k.pgjdbc.ng)

    // Benchmark (JMH for exposed-jdbc CRUD/pool 측정)
    testImplementation(bt4k.kotlinx.benchmark.runtime)
    testImplementation(bt4k.kotlinx.benchmark.runtime.jvm)
    testImplementation(bt4k.jmh.core)
}
