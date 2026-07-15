pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version ("1.0.0")
    }
}

val bluetape4kDependenciesCatalogRef = providers.gradleProperty("bluetape4kDependenciesCatalogRef")
    .orElse(providers.environmentVariable("BLUETAPE4K_DEPENDENCIES_CATALOG_REF"))
    .orElse("644f4f3a2cbc6e5cb396bbb3f88b6a23aab7e383")
    .get()
val bluetape4kDependenciesCatalogCacheKey = bluetape4kDependenciesCatalogRef.replace(Regex("[^A-Za-z0-9._-]"), "_")

fun catalogSha256(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

fun expectedCatalogSha256(checksumFile: File): String? =
    checksumFile.takeIf(File::isFile)
        ?.readText()
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.firstOrNull()
        ?.lowercase()
        ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

fun catalogChecksumMatches(catalogFile: File, checksumFile: File): Boolean =
    catalogFile.isFile && expectedCatalogSha256(checksumFile)?.let { it == catalogSha256(catalogFile) } == true

fun downloadCatalogFile(url: String, target: File) {
    uri(url).toURL().openStream().use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    }
}

fun resolveBluetape4kDependenciesCatalogFile(): File {
    providers.gradleProperty("bluetape4kDependenciesCatalogPath")
        .orElse(providers.environmentVariable("BLUETAPE4K_DEPENDENCIES_CATALOG_PATH"))
        .orNull
        ?.let(::file)
        ?.let { return it }

    listOf(
        "../bluetape4k-dependencies/gradle/libs.versions.toml",
        "bluetape4k-dependencies/gradle/libs.versions.toml",
    ).map(::file).firstOrNull { it.isFile }?.let { return it }

    val catalogFile = file(".gradle/bluetape4k-dependencies/$bluetape4kDependenciesCatalogCacheKey/libs.versions.toml")
    val checksumFile = file(".gradle/bluetape4k-dependencies/$bluetape4kDependenciesCatalogCacheKey/libs.versions.toml.sha256")
    if (!catalogChecksumMatches(catalogFile, checksumFile)) {
        require(catalogFile.parentFile.mkdirs() || catalogFile.parentFile.isDirectory) {
            "Cannot create bluetape4k-dependencies catalog cache: ${catalogFile.parentFile}"
        }
        val catalogBaseUrl =
            "https://raw.githubusercontent.com/bluetape4k/bluetape4k-dependencies/$bluetape4kDependenciesCatalogRef/gradle"
        val catalogTempFile = File.createTempFile("libs.versions-", ".toml.tmp", catalogFile.parentFile)
        val checksumTempFile = File.createTempFile("libs.versions-", ".sha256.tmp", catalogFile.parentFile)
        try {
            downloadCatalogFile("$catalogBaseUrl/libs.versions.toml", catalogTempFile)
            downloadCatalogFile("$catalogBaseUrl/libs.versions.toml.sha256", checksumTempFile)
            val expectedChecksum = requireNotNull(expectedCatalogSha256(checksumTempFile)) {
                "Invalid bluetape4k-dependencies catalog checksum: $checksumTempFile"
            }
            require(catalogSha256(catalogTempFile) == expectedChecksum) {
                "bluetape4k-dependencies catalog checksum mismatch for ref $bluetape4kDependenciesCatalogRef"
            }
            java.nio.file.Files.move(
                checksumTempFile.toPath(),
                checksumFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            java.nio.file.Files.move(
                catalogTempFile.toPath(),
                catalogFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            catalogTempFile.delete()
            checksumTempFile.delete()
        }
    }
    return catalogFile
}

val bluetape4kDependenciesCatalogFile = resolveBluetape4kDependenciesCatalogFile()

require(bluetape4kDependenciesCatalogFile.isFile) {
    "bluetape4k-dependencies catalog not found: $bluetape4kDependenciesCatalogFile. " +
        "Checkout bluetape4k-dependencies at the release-train tag or set bluetape4kDependenciesCatalogPath."
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
    versionCatalogs {
        create("bt4k") {
            from(files(bluetape4kDependenciesCatalogFile))
        }
    }
}

rootProject.name = "bluetape4k-exposed"

// BOM — explicitly included (excluded from exposed/ auto-discovery below)
includeProject("bluetape4k-exposed-bom", file("exposed/bom"))

includeModules("exposed", withBaseDir = false, prefix = "bluetape4k-exposed-", excludeDirNames = setOf("bom"))
includeModules("examples", withBaseDir = true, excludeDirNames = setOf("jdbc-demo", "r2dbc-demo"))

includeMappedModule("utils/batch", "bluetape4k-exposed-batch")
includeModules("benchmark", withBaseDir = true)

includeMappedModule("ktor/exposed", "bluetape4k-exposed-ktor")

includeMappedModule("spring-boot/jdbc", "bluetape4k-exposed-spring-boot-jdbc")
includeMappedModule("spring-boot/r2dbc", "bluetape4k-exposed-spring-boot-r2dbc")
includeMappedModule("spring-boot/batch-exposed", "bluetape4k-exposed-spring-boot-batch")
includeMappedModule("spring-boot/spring-modulith", "bluetape4k-exposed-spring-modulith")
includeMappedModule("examples/jdbc-demo", "exposed-spring-boot-jdbc-demo")
includeMappedModule("examples/r2dbc-demo", "exposed-spring-boot-r2dbc-demo")
includeMappedModule("examples/ddd-spring-modulith-demo", "examples-ddd-spring-modulith-demo")

fun includeModules(
    baseDir: String,
    withBaseDir: Boolean = true,
    prefix: String = "",
    excludeDirNames: Set<String> = emptySet(),
) {
    files("$rootDir/$baseDir").files
        .filter { it.isDirectory }
        .forEach { moduleDir ->
            moduleDir.listFiles()
                ?.filter { it.isDirectory && it.name !in excludeDirNames }
                ?.filter { it.resolve("build.gradle.kts").isFile }
                ?.forEach { dir ->
                    val basePath = baseDir.replace("/", "-")
                    val projectName = if (withBaseDir) {
                        "$prefix$basePath-${dir.name}"
                    } else {
                        "$prefix${dir.name}"
                    }
                    includeProject(projectName, dir)
                }
        }
}

fun includeMappedModule(modulePath: String, projectName: String) {
    includeProject(projectName, file("$rootDir/$modulePath"))
}

fun includeProject(projectName: String, projectDir: File) {
    include(projectName)
    project(":$projectName").projectDir = projectDir
}
