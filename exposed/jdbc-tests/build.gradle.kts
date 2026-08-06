
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
    description = "Runs live JDBC schema migration drift tests."

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
    // Exposed
    api(platform(bt4k.exposed.bom))
    api(platform(bt4k.kotlinx.coroutines.bom))
    api(platform(bt4k.testcontainers.bom))
    api(bt4k.exposed.core)
    api(bt4k.exposed.jdbc)
    api(libs.exposed.dao)
    implementation(libs.exposed.crypt)
    implementation(libs.exposed.kotlin.datetime)
    implementation(bt4k.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.exposed.money)
    implementation(bt4k.exposed.migration.jdbc)
    implementation(bt4k.exposed.spring.boot4.starter)

    // Bluetape4k
    compileOnly(bt4k.bluetape4k.jdbc)
    compileOnly(bt4k.bluetape4k.io)
    

    api(bt4k.bluetape4k.junit5)
    api(bt4k.bluetape4k.testcontainers)
    api(libs.testcontainers)
    api(libs.testcontainers.mariadb)
    api(libs.testcontainers.mysql)
    api(libs.testcontainers.postgresql)
    // compileOnly(libs.testcontainers.cockroachdb)

    // Database Drivers
    compileOnly(bt4k.hikaricp)

    // Database Drivers
    compileOnly(bt4k.h2.v2)
    compileOnly(bt4k.mariadb.java.client)
    compileOnly(bt4k.mysql.connector.j)
    compileOnly(bt4k.postgresql)
    compileOnly(bt4k.pgjdbc.ng)

    // Coroutines
    implementation(bt4k.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.debug)
    implementation(libs.kotlinx.coroutines.test)

    // Id Generators
    implementation(bt4k.bluetape4k.idgenerators)
    implementation(bt4k.java.uuid.generator)
}
