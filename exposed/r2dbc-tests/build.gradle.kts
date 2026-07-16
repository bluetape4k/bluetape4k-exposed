val bluetape4kVersion: String = providers.gradleProperty("bluetape4kVersion").get()

val migrationDriftDatabase = providers.environmentVariable("EXPOSED_TEST_DB")
    .map { it.trim().uppercase() }
    .orElse("H2")

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("migration-drift")
    }
}

tasks.register<Test>("migrationDriftTest") {
    group = "verification"
    description = "Runs live R2DBC schema migration drift tests."

    val testSourceSet = sourceSets.named("test").get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath

    inputs.property("exposedTestDb", migrationDriftDatabase)
    environment("EXPOSED_TEST_DB", migrationDriftDatabase.get())
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }

    maxParallelForks = 1
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    useJUnitPlatform {
        includeTags("migration-drift")
    }
    jvmArgs(
        "-Xshare:off",
        "-Xms2M",
        "-Xmx4G",
        "-XX:+UseG1GC",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+EnableDynamicAgentLoading",
        "--enable-preview",
        "-Didea.io.use.nio2=true",
    )

    binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/migrationDriftTest/binary"))
    reports.junitXml.required.set(true)
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/migrationDriftTest"))
    reports.junitXml.includeSystemOutLog.set(false)
    reports.junitXml.includeSystemErrLog.set(false)
    reports.html.required.set(false)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    // Exposed
    implementation(platform(libs.exposed.bom))

    api(bt4k.exposed.core)
    api(bt4k.exposed.r2dbc)
    implementation(libs.exposed.migration.r2dbc)
    implementation(bt4k.exposed.java.time)

    // Id Generators
    implementation(bt4k.bluetape4k.idgenerators)
    implementation(libs.java.uuid.generator)

    // Coroutines
    compileOnly(bt4k.bluetape4k.coroutines)
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test)

    // R2DBC
    api(libs.r2dbc.spi)
    api(libs.r2dbc.pool)
    implementation(bt4k.r2dbc.h2)
    implementation(libs.r2dbc.mariadb)
    implementation(libs.r2dbc.mysql)
    implementation(libs.r2dbc.postgresql)

    // Bluetape4k Modules for Testing
    api(bt4k.bluetape4k.junit5)
    api(bt4k.bluetape4k.testcontainers)
    api(libs.testcontainers.mariadb)
    api(libs.testcontainers.mysql)
    api(libs.testcontainers.postgresql)

    // Database Drivers for Testcontainers Database
    compileOnly(libs.h2.v2)
    compileOnly(libs.mariadb.java.client)
    compileOnly(bt4k.mysql.connector.j)
    compileOnly(bt4k.postgresql)



    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
