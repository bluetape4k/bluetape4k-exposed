
plugins {
    kotlin("plugin.spring")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
    // junit5의 legacy JDK 21 provider가 JDK 25 테스트 runtime에 섞이지 않도록 한다.
    testRuntimeClasspath {
        exclude(group = "io.github.bluetape4k", module = "bluetape4k-virtualthread-jdk21")
    }
}

dependencies {
    // Spring Boot BOM: platform()을 사용하면 compileClasspath/runtimeClasspath에만 적용되고
    // kotlinBuildToolsApiClasspath 같은 내부 Gradle 설정에는 영향을 주지 않음
    // (dependencyManagement 플러그인은 ALL configurations에 적용되어 kotlin-stdlib 버전 충돌 유발)
    api(platform(bt4k.spring.boot4.dependencies))
    api(platform(bt4k.kotlinx.coroutines.bom))

    api("org.springframework.data:spring-data-commons")
    api("org.springframework:spring-tx")

    // JDBC 어댑터와 분리된 공통 Spring Data SPI만 재사용한다.
    api(project(":bluetape4k-exposed-spring-boot-common"))

    api(libs.kotlin.reflect)
    api(bt4k.exposed.core)
    api(bt4k.exposed.r2dbc)
    api(bt4k.exposed.java.time)

    testImplementation(libs.exposed.migration.r2dbc)
    testImplementation(bt4k.flyway.core)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.assertions)

    api(project(":bluetape4k-exposed-r2dbc"))
    testImplementation(project(":bluetape4k-exposed-r2dbc-tests"))

    // JDK 25 테스트 런타임과 StructuredTaskScope provider의 classfile/preview 호환성을 맞춘다.
    testImplementation(bt4k.bluetape4k.virtualthread.jdk25)

    api(bt4k.bluetape4k.coroutines)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.reactor)  // Spring Data 코루틴 지원 요구사항
    testImplementation(libs.kotlinx.coroutines.test)

    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-health")
    compileOnly(project(":bluetape4k-exposed-r2dbc-caffeine"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(bt4k.mockk)

    testImplementation(bt4k.h2.v2)
    testImplementation(bt4k.r2dbc.h2)
    testImplementation(bt4k.hikaricp)

    // Multi-DB 테스트용 R2DBC 드라이버
    testImplementation(bt4k.r2dbc.mysql)
    testImplementation(bt4k.r2dbc.mariadb)
    testImplementation(bt4k.r2dbc.postgresql)

    // Multi-DB 테스트용 JDBC 드라이버 (Testcontainers 컨테이너 연결용)
    testImplementation(bt4k.mysql.connector.j)
    testImplementation(bt4k.mariadb.java.client)
    testImplementation(bt4k.postgresql)
}

val r2dbcCompileClasspath = configurations.named("compileClasspath")
val r2dbcRuntimeClasspath = configurations.named("runtimeClasspath")

tasks.register("checkR2dbcDependencyBoundary") {
    group = "verification"
    description = "R2DBC compile/runtime classpaths must not contain JDBC Spring Data adapters."
    notCompatibleWithConfigurationCache("The check resolves dependency configurations at execution time.")
    doLast {
        val forbiddenModules = setOf(
            "bluetape4k-exposed-spring-boot-jdbc",
            "spring-jdbc",
        )
        val resolvedModules = listOf(r2dbcCompileClasspath.get(), r2dbcRuntimeClasspath.get())
            .flatMap { configuration ->
                configuration.resolvedConfiguration.resolvedArtifacts.map { artifact -> artifact.name }
            }
            .toSet()
        val forbidden = resolvedModules.intersect(forbiddenModules)
        check(forbidden.isEmpty()) {
            "R2DBC dependency boundary violated; forbidden modules: ${forbidden.sorted()}"
        }
    }
}

val checkSpringBootR2dbcAssertionStyle = tasks.register("checkSpringBootR2dbcAssertionStyle") {
    group = "verification"
    description = "Rejects legacy assertions in Spring Boot R2DBC tests."

    val sourceRoot = layout.projectDirectory.dir("src/test/kotlin")
    val reportFile = layout.buildDirectory.file("reports/spring-boot-r2dbc/assertion-style.txt")

    inputs.dir(sourceRoot)
    outputs.file(reportFile)

    doLast {
        val root = sourceRoot.asFile
        if (!root.isDirectory) {
            throw GradleException("Assertion style scan source root is missing: ${root.path}")
        }

        val sourceFiles = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
            .sortedBy { it.path }
        if (sourceFiles.isEmpty()) {
            throw GradleException("Assertion style scan found no Kotlin test sources: ${root.path}")
        }

        val legacyImport = Regex(
            "^\\s*import\\s+(?:kotlin\\.test\\.(?:assert[A-Za-z0-9_]*|\\*)|org\\.junit\\.jupiter\\.api\\.Assertions(?:\\.\\*)?)"
        )
        val fullyQualifiedCall = Regex(
            "\\b(?:kotlin\\.test\\.|org\\.junit\\.jupiter\\.api\\.Assertions\\.)assert[A-Za-z0-9_]*\\s*\\("
        )
        val legacyCall = Regex(
            "\\bassert(?:Equals|True|False|NotNull|ContentEquals|Same|NotSame|Throws)\\s*\\("
        )
        val findings = mutableListOf<String>()

        sourceFiles.forEach { sourceFile ->
            val lines = try {
                sourceFile.readLines()
            } catch (cause: Exception) {
                throw GradleException("Assertion style scan could not read ${sourceFile.path}", cause)
            }
            lines.forEachIndexed { index, line ->
                val rule = when {
                    legacyImport.containsMatchIn(line) -> "legacy assertion import"
                    fullyQualifiedCall.containsMatchIn(line) -> "fully-qualified legacy assertion call"
                    legacyCall.containsMatchIn(line) -> "legacy assertion call"
                    else -> null
                }
                if (rule != null) {
                    val relativePath = root.toPath().relativize(sourceFile.toPath())
                    findings += "src/test/kotlin/$relativePath:${index + 1}: $rule"
                }
            }
        }

        val report = reportFile.get().asFile
        try {
            report.parentFile.mkdirs()
            report.writeText(
                if (findings.isEmpty()) {
                    "PASS: no legacy assertions found in ${sourceFiles.size} Kotlin test sources.\n"
                } else {
                    findings.joinToString(separator = "\n", postfix = "\n")
                }
            )
        } catch (cause: Exception) {
            throw GradleException("Assertion style scan could not write ${report.path}", cause)
        }

        if (findings.isNotEmpty()) {
            findings.forEach { finding -> logger.error(finding) }
            throw GradleException("Legacy assertions found in ${findings.size} locations")
        }

        logger.lifecycle("Assertion style scan passed for ${sourceFiles.size} Kotlin test sources")
    }
}

tasks.named("check") {
    dependsOn(checkSpringBootR2dbcAssertionStyle)
}
