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
    api(bt4k.exposed.r2dbc)
    compileOnly(bt4k.exposed.java.time)
    api(bt4k.bluetape4k.r2dbc)
    api(bt4k.r2dbc.spi)
    api(bt4k.bluetape4k.coroutines)
    api(libs.kotlinx.coroutines.core)
    compileOnly(bt4k.bluetape4k.jackson3)

    testImplementation(project(":bluetape4k-exposed-r2dbc-tests"))
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(bt4k.bluetape4k.jackson3)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)

    testRuntimeOnly(bt4k.r2dbc.h2)
    testRuntimeOnly(bt4k.r2dbc.postgresql)
    testRuntimeOnly(bt4k.r2dbc.mysql)
    testRuntimeOnly(bt4k.h2.v2)
    testRuntimeOnly(bt4k.postgresql)
    testRuntimeOnly(bt4k.mysql.connector.j)
}
