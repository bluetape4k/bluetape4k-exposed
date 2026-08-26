
import java.nio.file.Files
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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

val bluetapeAssertionSourceRoots = listOf(
    layout.projectDirectory.dir("src/main/kotlin").asFile,
    layout.projectDirectory.dir("src/test/kotlin").asFile,
)

val verifyBluetapeAssertionImports = tasks.register("verifyBluetapeAssertionImports") {
    group = "verification"
    description = "Rejects raw assertion imports in jdbc-tests Kotlin sources."
    inputs.files(bluetapeAssertionSourceRoots)

    doLast {
        fun regularKotlinFiles(root: java.io.File): List<java.io.File> {
            val rootPath = root.toPath().toAbsolutePath().normalize()
            check(Files.isDirectory(rootPath)) { "Missing Kotlin source root: $root" }
            check(!Files.isSymbolicLink(rootPath)) { "Kotlin source root must not be a symlink: $root" }
            val projectPath = projectDir.toPath().toRealPath()
            val realRoot = rootPath.toRealPath()
            check(realRoot.startsWith(projectPath)) {
                "Kotlin source root must stay inside project directory: $root"
            }
            var ancestor = rootPath
            while (ancestor != projectPath) {
                check(!Files.isSymbolicLink(ancestor)) {
                    "Kotlin source root ancestor must not be a symlink: $ancestor"
                }
                ancestor = checkNotNull(ancestor.parent) { "Source root escaped project directory: $root" }
            }
            return Files.walk(rootPath).use { paths ->
                val allPaths = paths.toList()
                check(allPaths.none { path -> Files.isSymbolicLink(path) }) {
                    "Symlink path is forbidden below Kotlin source root: $root"
                }
                allPaths
                    .filter { path ->
                        Files.isRegularFile(path) &&
                            path.toRealPath().startsWith(realRoot) &&
                            path.fileName.toString().endsWith(".kt")
                    }
                    .map { it.toFile() }
            }
        }

        fun isForbiddenAssertionImport(line: String): Boolean {
            val importLine = line.trim()
            val importIndex = line.indexOf("import")
            val leading = if (importIndex < 0) "" else line.substring(0, importIndex).trim()
            check(importIndex < 0 || leading.isEmpty() || leading.endsWith("*/")) {
                "Import hidden behind a leading comment is rejected by the guard: $line"
            }
            check(!importLine.endsWith(".") && !importLine.endsWith(" as")) {
                "Incomplete or continued import declaration is rejected by the guard: $line"
            }
            check(';' !in importLine) {
                "Semicolon-terminated import declarations are rejected by the guard: $line"
            }
            check("/*" !in importLine && "*/" !in importLine && "//" !in importLine) {
                "Comments in import declarations are rejected by the guard: $line"
            }
            val imported = importLine
                .removePrefix("import")
                .trim()
                .replace(Regex("\\s+as\\s+[A-Za-z_][A-Za-z0-9_]*\\s*$"), "")
                .replace("`", "")
                .replace(Regex("\\s*\\.\\s*"), ".")
            check(imported.isNotBlank() && !Regex("\\s").containsMatchIn(imported)) {
                "Unparseable import declaration is rejected by the guard: $line"
            }
            return imported == "org.junit.jupiter.api.Assertions" ||
                imported.startsWith("org.junit.jupiter.api.Assertions.") ||
                imported == "org.junit.jupiter.api.assertThrows" ||
                imported.startsWith("kotlin.test.assert") ||
                imported.startsWith("org.assertj.") ||
                imported.startsWith("org.kluent.")
        }

        val expectedKotlinRoots = bluetapeAssertionSourceRoots
            .map { it.toPath().toAbsolutePath().normalize().toRealPath() }
            .toSet()
        val unexpectedKotlinSources = tasks
            .withType<KotlinCompile>()
            .flatMap { it.inputs.sourceFiles.files }
            .filter { it.extension == "kt" }
            .filter { file ->
                val sourcePath = file.toPath().toAbsolutePath().normalize().toRealPath()
                expectedKotlinRoots.none { root -> sourcePath.startsWith(root) }
            }
        check(unexpectedKotlinSources.isEmpty()) {
            "Kotlin compile source is outside the fixed guard roots: $unexpectedKotlinSources"
        }

        val violations = bluetapeAssertionSourceRoots
            .flatMap(::regularKotlinFiles)
            .flatMap { file ->
                var blockCommentOpen = false
                file.readLines().mapIndexedNotNull { index, line ->
                    var remainder = line.trimStart()
                    var strippedComment = false
                    if (blockCommentOpen) {
                        val closes = remainder.indexOf("*/")
                        if (closes < 0) return@mapIndexedNotNull null
                        blockCommentOpen = false
                        remainder = remainder.substring(closes + 2).trimStart()
                        strippedComment = true
                    }
                    while (remainder.startsWith("/*")) {
                        val closes = remainder.indexOf("*/", 2)
                        if (closes < 0) {
                            blockCommentOpen = true
                            return@mapIndexedNotNull null
                        }
                        remainder = remainder.substring(closes + 2).trimStart()
                        strippedComment = true
                    }
                    if (strippedComment && (remainder.startsWith("import") || remainder.startsWith("."))) {
                        check(false) {
                            "Import hidden after a block comment is rejected by the guard: " +
                                "${file.relativeTo(projectDir)}:${index + 1}"
                        }
                    }
                    if (remainder.startsWith("//") && "import" in remainder) {
                        check(false) {
                            "Import continuation/comment bypass is rejected by the guard: " +
                                "${file.relativeTo(projectDir)}:${index + 1}"
                        }
                    }
                    if (remainder.startsWith("import") && isForbiddenAssertionImport(remainder)) {
                        "${file.relativeTo(projectDir)}:${index + 1}: $remainder"
                    } else {
                        null
                    }
                }
            }

        check(violations.isEmpty()) {
            "Raw assertion imports are forbidden in jdbc-tests:\n${violations.joinToString("\n")}"
        }
    }
}

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

tasks.named("test") {
    dependsOn(verifyBluetapeAssertionImports)
}
tasks.named("migrationDriftTest") {
    dependsOn(verifyBluetapeAssertionImports)
}
tasks.named("check") {
    dependsOn(verifyBluetapeAssertionImports)
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

    api(bt4k.bluetape4k.assertions)
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
