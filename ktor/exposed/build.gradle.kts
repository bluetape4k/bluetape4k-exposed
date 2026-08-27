
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("driver-timeout")
    }
}

tasks.register<Test>("driverTimeoutTest") {
    group = "verification"
    description = "Runs sequential driver statement-timeout and Toxiproxy compatibility tests."

    val testSourceSet = sourceSets.named("test").get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }

    maxParallelForks = 1
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    useJUnitPlatform {
        includeTags("driver-timeout")
    }
    jvmArgs(
        "-Xshare:off",
        "-Xms2M",
        "-Xmx2G",
        "-XX:+UseG1GC",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+EnableDynamicAgentLoading",
        "--enable-preview",
        "-Didea.io.use.nio2=true",
    )

    binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/driverTimeoutTest/binary"))
    reports.junitXml.required.set(true)
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/driverTimeoutTest"))
    reports.junitXml.includeSystemOutLog.set(false)
    reports.junitXml.includeSystemErrLog.set(false)
    reports.html.required.set(false)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(platform(bt4k.ktor.bom))
    api(platform(bt4k.exposed.bom))
    api(platform(bt4k.micrometer.bom))
    api(platform(bt4k.kotlinx.coroutines.bom))

    api(bt4k.bluetape4k.ktor.core)

    // Compatibility aggregator: expose the selective artifacts while retaining
    // the legacy package and descriptor surface implemented in this module.
    api(project(":bluetape4k-exposed-ktor-core"))
    api(project(":bluetape4k-exposed-ktor-jdbc"))
    api(project(":bluetape4k-exposed-ktor-r2dbc"))
    api(project(":bluetape4k-exposed-ktor-cache"))

    api(project(":bluetape4k-exposed-cache"))
    api(project(":bluetape4k-exposed-jdbc"))
    api(project(":bluetape4k-exposed-r2dbc"))

    api(libs.kotlinx.coroutines.core)
    api(libs.micrometer.core)

    testImplementation(project(":bluetape4k-exposed-jdbc-tests"))
    testImplementation(project(":bluetape4k-exposed-r2dbc-tests"))
    testImplementation(libs.testcontainers.cockroachdb)
    testImplementation(libs.testcontainers.toxiproxy)

    testRuntimeOnly(bt4k.mariadb.java.client)
    testRuntimeOnly(bt4k.mysql.connector.j)
    testRuntimeOnly(bt4k.postgresql)
    testRuntimeOnly(bt4k.r2dbc.mariadb)
    testRuntimeOnly(bt4k.r2dbc.mysql)
    testRuntimeOnly(bt4k.r2dbc.postgresql)

    testImplementation(bt4k.bluetape4k.ktor.testing)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation("io.ktor:ktor-server-auth")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(bt4k.mockk)

    testImplementation(bt4k.exposed.java.time)
    testImplementation(bt4k.h2.v2)
    testImplementation(bt4k.r2dbc.h2)
    testImplementation(libs.micrometer.test)
}
