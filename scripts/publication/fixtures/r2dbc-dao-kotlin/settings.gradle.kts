pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "__KOTLIN_PLUGIN_VERSION__"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "staged-exposed-modules"
                    url = uri(providers.gradleProperty("stagedRepository").get())
                    metadataSources {
                        gradleMetadata()
                    }
                }
            }
            filter {
                includeGroupAndSubgroups("io.github.bluetape4k.exposed")
            }
        }

        maven {
            name = "central-snapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            metadataSources {
                mavenPom()
            }
            content {
                includeGroupAndSubgroups("io.github.bluetape4k")
                excludeGroupAndSubgroups("io.github.bluetape4k.exposed")
            }
        }

        mavenCentral {
            metadataSources {
                mavenPom()
            }
            content {
                excludeGroupAndSubgroups("io.github.bluetape4k.exposed")
            }
        }
    }
}

rootProject.name = "bluetape4k-exposed-r2dbc-dao-consumer"
