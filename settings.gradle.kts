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

val projectName = "bluetape4k"

rootProject.name = "$projectName-exposed-projects"

includeModules("exposed", withBaseDir = false)
includeModules("utils", withBaseDir = false)
includeModules("examples", withBaseDir = true)
includeModules("spring-boot3", withBaseDir = true)
includeModules("spring-boot4", withBaseDir = true)

fun includeModules(baseDir: String, withProjectName: Boolean = true, withBaseDir: Boolean = true) {
    files("$rootDir/$baseDir").files
        .filter { it.isDirectory }
        .forEach { moduleDir ->
            moduleDir.listFiles()
                ?.filter { it.isDirectory }
                ?.forEach { dir ->
                    val basePath = baseDir.replace("/", "-")
                    val projectName = when {
                        !withProjectName && !withBaseDir -> dir.name
                        withProjectName && !withBaseDir  -> projectName + "-" + dir.name
                        withProjectName                  -> projectName + "-" + basePath + "-" + dir.name
                        else                             -> basePath + "-" + dir.name
                    }

                    include(projectName)
                    project(":$projectName").projectDir = dir
                }
        }
}
