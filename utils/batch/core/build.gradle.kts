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
    api(bt4k.bluetape4k.core)
    api(bt4k.bluetape4k.coroutines)
    api(bt4k.bluetape4k.logging)
    api(bt4k.bluetape4k.workflow)

    implementation(bt4k.bluetape4k.virtualthread.api)
    runtimeOnly(bt4k.bluetape4k.virtualthread.jdk21)
    implementation(libs.kotlinx.coroutines.core)
    compileOnly(bt4k.bluetape4k.jackson3)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(bt4k.bluetape4k.jackson3)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(bt4k.bluetape4k.virtualthread.jdk25)
    // Cross-adapter failure-lifecycle tests live with the core runner contract.
    testImplementation(project(":bluetape4k-exposed-batch-jdbc"))
    testImplementation(project(":bluetape4k-exposed-batch-r2dbc"))
    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testImplementation(project(":bluetape4k-exposed-r2dbc-tests"))
}
