plugins {
    kotlin("plugin.spring")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
    // JDK 25 테스트 런타임에는 legacy JDK 21 provider를 섞지 않습니다.
    testRuntimeClasspath {
        exclude(group = "io.github.bluetape4k", module = "bluetape4k-virtualthread-jdk21")
    }
}

dependencies {
    // Spring Boot와 Exposed BOM은 API dependency의 버전을 소비자에게 전달합니다.
    api(platform(bt4k.spring.boot4.dependencies))
    api(platform(bt4k.kotlinx.coroutines.bom))
    api(platform(bt4k.exposed.bom))

    // Spring Data 공통 SPI만 소유하며 JDBC/R2DBC adapter에는 의존하지 않습니다.
    api("org.springframework.data:spring-data-commons")
    api(libs.kotlin.reflect)
    api(bt4k.bluetape4k.logging)
    api(bt4k.exposed.core)
    api(libs.exposed.dao)
    compileOnly("org.springframework:spring-context")

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.assertions)
    testImplementation(project(":bluetape4k-exposed-dao"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(bt4k.mockk)
    testImplementation(bt4k.h2.v2)
}

val commonPublicationDirectory = layout.buildDirectory.dir("publications/BluetapeExposed")
val checkCommonPublicationDependencyBoundary = tasks.register("checkCommonPublicationDependencyBoundary") {
    group = "verification"
    description = "Checks that the common publication does not expose bluetape4k-core."
    dependsOn(
        "generateMetadataFileForBluetapeExposedPublication",
        "generatePomFileForBluetapeExposedPublication",
    )

    doLast {
        val publicationDirectory = commonPublicationDirectory.get().asFile
        val metadataFile = publicationDirectory.resolve("module.json")
        val pomFile = publicationDirectory.resolve("pom-default.xml")
        check(metadataFile.isFile && pomFile.isFile) {
            "Common publication metadata is missing: ${metadataFile.absolutePath}, ${pomFile.absolutePath}"
        }

        val metadataRoot = groovy.json.JsonSlurper().parse(metadataFile) as? Map<*, *>
            ?: error("Common Gradle Module Metadata root must be an object")
        val variants = metadataRoot["variants"] as? List<*>
            ?: error("Common Gradle Module Metadata variants are missing")
        val metadataCoordinates = variants.flatMap { rawVariant ->
            val variant = rawVariant as? Map<*, *>
                ?: error("Common Gradle Module Metadata variant must be an object")
            listOf("dependencies", "dependencyConstraints").flatMap { dependencyKey ->
                val rawDependencies = variant[dependencyKey] ?: return@flatMap emptyList()
                val dependencies = rawDependencies as? List<*>
                    ?: error("Common Gradle Module Metadata $dependencyKey must be an array")
                dependencies.map { rawDependency ->
                    val dependency = rawDependency as? Map<*, *>
                        ?: error("Common Gradle Module Metadata dependency must be an object")
                    val group = dependency["group"]?.toString()?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: error("Common Gradle Module Metadata dependency group is missing")
                    val module = dependency["module"]?.toString()?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: error("Common Gradle Module Metadata dependency module is missing")
                    "$group:$module"
                }
            }
        }.toSet()

        val pomDocument = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(pomFile)
        val dependencyNodes = pomDocument.getElementsByTagNameNS("*", "dependency")
        val pomCoordinates = (0 until dependencyNodes.length).mapNotNull { index ->
            val dependency = dependencyNodes.item(index) as? org.w3c.dom.Element ?: return@mapNotNull null
            val group = dependency.getElementsByTagNameNS("*", "groupId").item(0)?.textContent?.trim()
            val module = dependency.getElementsByTagNameNS("*", "artifactId").item(0)?.textContent?.trim()
            if (group.isNullOrBlank() || module.isNullOrBlank()) null else "$group:$module"
        }.toSet()

        val forbiddenCoordinate = "io.github.bluetape4k:bluetape4k-core"
        val metadataViolations = metadataCoordinates.filter { it == forbiddenCoordinate }
        val pomViolations = pomCoordinates.filter { it == forbiddenCoordinate }
        check(metadataViolations.isEmpty() && pomViolations.isEmpty()) {
            "Common publication exposes $forbiddenCoordinate: " +
                    "module.json=$metadataViolations, pom.xml=$pomViolations"
        }
        logger.lifecycle(
            "Common publication dependency boundary passed: " +
                    "module.json and pom-default.xml omit $forbiddenCoordinate",
        )
    }
}

tasks.named("check") {
    dependsOn(checkCommonPublicationDependencyBoundary)
}
