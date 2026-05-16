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

rootProject.name = "bluetape4k-exposed"

includeModules("exposed", withBaseDir = false)
includeModules("examples", withBaseDir = true)

includeMappedModule("utils/batch", "exposed-batch")

includeMappedModule("spring-boot/exposed-jdbc", "exposed-spring-boot-jdbc")
includeMappedModule("spring-boot/exposed-r2dbc", "exposed-spring-boot-r2dbc")
includeMappedModule("spring-boot/batch-exposed", "exposed-spring-boot-batch")
includeMappedModule("spring-boot/exposed-spring-modulith", "exposed-spring-modulith")
includeMappedModule("spring-boot/exposed-jdbc-demo", "exposed-spring-boot-jdbc-demo")
includeMappedModule("spring-boot/exposed-r2dbc-demo", "exposed-spring-boot-r2dbc-demo")

fun includeModules(baseDir: String, withBaseDir: Boolean = true) {
    files("$rootDir/$baseDir").files
        .filter { it.isDirectory }
        .forEach { moduleDir ->
            moduleDir.listFiles()
                ?.filter { it.isDirectory }
                ?.filter { it.resolve("build.gradle.kts").isFile }
                ?.forEach { dir ->
                    val basePath = baseDir.replace("/", "-")
                    val projectName = if (withBaseDir) {
                        "$basePath-${dir.name}"
                    } else {
                        dir.name
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
