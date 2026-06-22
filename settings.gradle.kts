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
    .orElse("catalog/2026-06-09-01")
    .get()

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

    val catalogFile = file(".gradle/bluetape4k-dependencies/libs.versions.toml")
    if (!catalogFile.isFile) {
        catalogFile.parentFile.mkdirs()
        val catalogUrl =
            "https://raw.githubusercontent.com/bluetape4k/bluetape4k-dependencies/$bluetape4kDependenciesCatalogRef/gradle/libs.versions.toml"
        uri(catalogUrl).toURL().openStream().use { input ->
            catalogFile.outputStream().use { output -> input.copyTo(output) }
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
includeModules("examples", withBaseDir = true)

includeMappedModule("utils/batch", "bluetape4k-exposed-batch")

includeMappedModule("spring-boot/jdbc", "bluetape4k-exposed-spring-boot-jdbc")
includeMappedModule("spring-boot/r2dbc", "bluetape4k-exposed-spring-boot-r2dbc")
includeMappedModule("spring-boot/batch-exposed", "bluetape4k-exposed-spring-boot-batch")
includeMappedModule("spring-boot/spring-modulith", "bluetape4k-exposed-spring-modulith")
includeMappedModule("spring-boot/jdbc-demo", "exposed-spring-boot-jdbc-demo")
includeMappedModule("spring-boot/r2dbc-demo", "exposed-spring-boot-r2dbc-demo")

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
