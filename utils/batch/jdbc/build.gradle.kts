plugins {
    alias(bt4k.plugins.kover)
}

kover {
    reports {
        filters {
            excludes {
                packages("io.bluetape4k.batch.benchmark")
            }
        }
    }
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
    testRuntimeClasspath {
        exclude(group = "io.github.bluetape4k", module = "bluetape4k-virtualthread-jdk21")
    }
}

dependencies {
    api(platform(bt4k.kotlinx.coroutines.bom))
    api(project(":bluetape4k-exposed-batch-core"))

    implementation(platform(bt4k.exposed.bom))
    api(bt4k.exposed.jdbc)
    compileOnly(bt4k.exposed.java.time)
    api(bt4k.bluetape4k.jdbc)
    compileOnly(bt4k.bluetape4k.coroutines)
    compileOnly(libs.kotlinx.coroutines.core)

    implementation(bt4k.bluetape4k.virtualthread.api)
    runtimeOnly(bt4k.bluetape4k.virtualthread.jdk21)
    compileOnly(bt4k.bluetape4k.jackson3)

    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(bt4k.bluetape4k.jackson3)
    testImplementation(bt4k.bluetape4k.virtualthread.jdk25)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)

    testRuntimeOnly(bt4k.h2.v2)
    testRuntimeOnly(bt4k.hikaricp)
    testRuntimeOnly(bt4k.postgresql)
    testRuntimeOnly(bt4k.mysql.connector.j)
}
