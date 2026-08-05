
val supportedMigrationDriftDatabases = setOf("H2", "POSTGRESQL", "MYSQL_V8")
val migrationDriftDatabase = providers.environmentVariable("EXPOSED_TEST_DB")
    .map {
        val selected = it.trim().uppercase().ifEmpty { "H2" }
        require(selected in supportedMigrationDriftDatabases) {
            "EXPOSED_TEST_DB must be one of ${supportedMigrationDriftDatabases.joinToString()}"
        }
        selected
    }
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
        "-Xmx2G",
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
    implementation(platform(bt4k.spring.boot4.dependencies))
    // Exposed
    implementation(platform(bt4k.exposed.bom))

    api(bt4k.exposed.core)
    api(bt4k.exposed.r2dbc)
    implementation(libs.exposed.migration.r2dbc)
    implementation(bt4k.exposed.java.time)

    // Id Generators
    implementation(bt4k.bluetape4k.idgenerators)
    implementation(bt4k.java.uuid.generator)

    // Coroutines
    compileOnly(bt4k.bluetape4k.coroutines)
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test)

    // R2DBC
    api(bt4k.r2dbc.spi)
    api(bt4k.r2dbc.pool)
    implementation(bt4k.r2dbc.h2)
    implementation(bt4k.r2dbc.mariadb)
    implementation(bt4k.r2dbc.mysql)
    implementation(bt4k.r2dbc.postgresql)

    // Bluetape4k Modules for Testing
    api(bt4k.bluetape4k.junit5)
    api(bt4k.bluetape4k.testcontainers)
    api(libs.testcontainers.mariadb)
    api(libs.testcontainers.mysql)
    api(libs.testcontainers.postgresql)

    // Database Drivers for Testcontainers Database
    compileOnly(bt4k.h2.v2)
    compileOnly(bt4k.mariadb.java.client)
    compileOnly(bt4k.mysql.connector.j)
    compileOnly(bt4k.postgresql)



    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
