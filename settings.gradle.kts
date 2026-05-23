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

val bluetape4kDependenciesVersion = providers.gradleProperty("bluetape4kDependenciesVersion").get()

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
    versionCatalogs {
        create("bt4k") {
            from("io.github.bluetape4k:bluetape4k-version-catalog:$bluetape4kDependenciesVersion")
        }
    }
}

rootProject.name = "bluetape4k-exposed"

// BOM — explicitly included (excluded from exposed/ auto-discovery below)
includeProject("bluetape4k-exposed-bom", file("exposed/bluetape4k-exposed-bom"))

includeModules("exposed", withBaseDir = false, prefix = "bluetape4k-", excludeDirNames = setOf("bluetape4k-exposed-bom"))
includeModules("examples", withBaseDir = true)

includeMappedModule("utils/batch", "bluetape4k-exposed-batch")

includeMappedModule("spring-boot/exposed-jdbc", "bluetape4k-exposed-spring-boot-jdbc")
includeMappedModule("spring-boot/exposed-r2dbc", "bluetape4k-exposed-spring-boot-r2dbc")
includeMappedModule("spring-boot/batch-exposed", "bluetape4k-exposed-spring-boot-batch")
includeMappedModule("spring-boot/exposed-spring-modulith", "bluetape4k-exposed-spring-modulith")
includeMappedModule("spring-boot/exposed-jdbc-demo", "exposed-spring-boot-jdbc-demo")
includeMappedModule("spring-boot/exposed-r2dbc-demo", "exposed-spring-boot-r2dbc-demo")

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
